-- Learned Parse Memory + transaction void/correction signal.
--
-- Context: Grok-4.5 chat (step 4 interpretation) is called on essentially every voice
-- job with no quality gate (index.ts, the `if ((rawGrokTranscript || rawSarvamTranscript
-- || transcript) && xaiApiKey)` block). This migration adds the storage this needs:
--
--   1. transactions.voided / voided_at -- the correction signal that was previously
--      completely absent (a wrongly auto-confirmed sale could never be contradicted).
--   2. learned_parses -- per-shop memoization of a PROVEN parse (item_name/quantity/
--      unit/price_intent only -- never price, which is recomputed fresh every time from
--      catalog_items regardless of cache hit or miss).
--   3. A trigger that demotes/blacklists a memo the moment a transaction it produced is
--      voided, so a self-consistent-but-wrong memo cannot survive a human correction.
--
-- Promotion is intentionally faster than the original "3 observations across 2 distinct
-- days" design: it requires only 2 independent recordings (2 distinct job_ids), but
-- EVERY one of them must have been independently corroborated by the deterministic
-- phonetic segmenter (see index.ts step 3) -- i.e. two different engines agreeing on
-- two different recordings, not one engine repeating itself. That corroboration
-- requirement is what makes the shorter warm-up safe: a systematic phonetic misread
-- would have to fool BOTH engines identically, twice, to ever promote.
--
-- Three independent kinds of evidence can kill a promoted memo, and any one of them
-- demotes it immediately back to observations=0 (must re-earn promotion from scratch):
--   - a canary re-verification call (a background Grok call on a sample of hits)
--       disagreeing with the memo
--   - a contributing transaction being voided by the shopkeeper
--   - a catalog_fingerprint mismatch (handled in application code, not here)
-- Two independent demotions of the same memo permanently blacklist it
-- (`permanently_blocked`) -- it will keep being observed but never promoted again,
-- even if it later looks consistent, since a repeat offender is no longer trustworthy
-- self-consistency evidence.

-- 1. Correction / void signal -----------------------------------------------------
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS voided BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS voided_at TIMESTAMPTZ;

-- 1b. Sentinel "default shop" row -- verified against live data that public.shops is
-- completely empty and every existing catalog_items/transactions row has shop_id NULL
-- (this deployment runs single-tenant; shop_id is not actually populated end-to-end
-- despite schema.sql declaring it NOT NULL). learned_parses.shop_id below has a NOT
-- NULL FK to shops, so without this row the memory feature would have a valid schema
-- but silently never activate for any real traffic. index.ts coalesces onto this id
-- whenever the request carries no shop_id -- see DEFAULT_LEARNED_PARSE_SHOP_ID.
INSERT INTO public.shops (id, name, vertical, language, tier)
VALUES ('00000000-0000-0000-0000-000000000001', 'Unattributed (default)', 'vegetable', 'hinglish', 'pilot')
ON CONFLICT (id) DO NOTHING;

-- 2. Learned parse memory ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.learned_parses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    memo_key TEXT NOT NULL,
    -- The pre-catalog-match shape ONLY: [{item_name, quantity, unit, price_intent}].
    -- Never price_at_sale/total/confidence -- those are recomputed from live
    -- catalog_items on every hit, cache or not.
    canonical_items JSONB NOT NULL,
    catalog_fingerprint TEXT NOT NULL,
    observations INT NOT NULL DEFAULT 1,
    contributing_job_ids TEXT[] NOT NULL DEFAULT '{}',
    distinct_days INT NOT NULL DEFAULT 1,
    last_seen_date DATE NOT NULL DEFAULT CURRENT_DATE,
    segmenter_corroborations INT NOT NULL DEFAULT 0,
    corrections INT NOT NULL DEFAULT 0,
    promoted BOOLEAN NOT NULL DEFAULT false,
    permanently_blocked BOOLEAN NOT NULL DEFAULT false,
    demoted_reason TEXT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(shop_id, memo_key)
);

ALTER TABLE public.learned_parses ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Service role full access on learned_parses"
    ON public.learned_parses FOR ALL TO service_role USING (true);

CREATE INDEX IF NOT EXISTS idx_learned_parses_shop_memo ON public.learned_parses(shop_id, memo_key);

-- 3. Reset/demote helper -- shared by canary mismatch (app code) and the void trigger
-- below. p_increment_corrections=true counts as one strike against the memo; two
-- strikes (from any source) permanently blocks it.
CREATE OR REPLACE FUNCTION reset_learned_parse(
    p_shop_id UUID,
    p_memo_key TEXT,
    p_reason TEXT,
    p_increment_corrections BOOLEAN DEFAULT true
) RETURNS void AS $$
BEGIN
    UPDATE public.learned_parses
    SET promoted = false,
        demoted_reason = p_reason,
        observations = 0,
        contributing_job_ids = '{}',
        distinct_days = 0,
        segmenter_corroborations = 0,
        corrections = corrections + (CASE WHEN p_increment_corrections THEN 1 ELSE 0 END),
        permanently_blocked = permanently_blocked
            OR (corrections + (CASE WHEN p_increment_corrections THEN 1 ELSE 0 END) >= 2)
    WHERE shop_id = p_shop_id AND memo_key = p_memo_key;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. Demote every learned_parses row a voided job contributed to.
CREATE OR REPLACE FUNCTION demote_learned_parses_for_job(p_job_id TEXT, p_shop_id UUID)
RETURNS void AS $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT shop_id, memo_key FROM public.learned_parses
        WHERE p_job_id = ANY(contributing_job_ids)
          AND (p_shop_id IS NULL OR shop_id = p_shop_id)
    LOOP
        PERFORM reset_learned_parse(r.shop_id, r.memo_key, 'transaction_voided:' || p_job_id, true);
    END LOOP;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION trg_demote_learned_parses_on_void() RETURNS trigger AS $$
BEGIN
    IF NEW.voided = true AND (OLD.voided IS DISTINCT FROM true) AND NEW.job_id IS NOT NULL THEN
        PERFORM demote_learned_parses_for_job(NEW.job_id, NEW.shop_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS transactions_voided_demote_learned_parses ON public.transactions;
CREATE TRIGGER transactions_voided_demote_learned_parses
    AFTER UPDATE OF voided ON public.transactions
    FOR EACH ROW EXECUTE FUNCTION trg_demote_learned_parses_on_void();

-- 5. Record one observation and decide promotion atomically (called from index.ts
-- after every fresh Grok success). p_corroborated is true when the deterministic
-- segmenter (step 3) independently agreed with Grok's item identity on THIS job.
CREATE OR REPLACE FUNCTION record_learned_parse_observation(
    p_shop_id UUID,
    p_memo_key TEXT,
    p_canonical_items JSONB,
    p_catalog_fingerprint TEXT,
    p_job_id TEXT,
    p_corroborated BOOLEAN
) RETURNS TABLE(promoted BOOLEAN, permanently_blocked BOOLEAN) AS $$
DECLARE
    existing RECORD;
    v_today DATE := CURRENT_DATE;
    v_items_match BOOLEAN;
    v_new_obs INT;
    v_new_days INT;
    v_new_corrob INT;
    v_job_ids TEXT[];
    v_promoted BOOLEAN;
BEGIN
    SELECT * INTO existing FROM public.learned_parses
        WHERE shop_id = p_shop_id AND memo_key = p_memo_key FOR UPDATE;

    IF NOT FOUND THEN
        INSERT INTO public.learned_parses (
            shop_id, memo_key, canonical_items, catalog_fingerprint,
            observations, contributing_job_ids, distinct_days, last_seen_date,
            segmenter_corroborations, corrections, promoted, permanently_blocked, last_seen_at
        ) VALUES (
            p_shop_id, p_memo_key, p_canonical_items, p_catalog_fingerprint,
            1, ARRAY[p_job_id], 1, v_today,
            CASE WHEN p_corroborated THEN 1 ELSE 0 END, 0, false, false, now()
        );
        RETURN QUERY SELECT false, false;
        RETURN;
    END IF;

    IF p_job_id = ANY(existing.contributing_job_ids) THEN
        -- Idempotent retry of an already-recorded job (e.g. a WorkManager retry landing
        -- after the job already finished) -- do not double-count.
        RETURN QUERY SELECT existing.promoted, existing.permanently_blocked;
        RETURN;
    END IF;

    IF existing.permanently_blocked THEN
        UPDATE public.learned_parses SET
            contributing_job_ids = array_append(contributing_job_ids, p_job_id),
            last_seen_at = now()
        WHERE id = existing.id;
        RETURN QUERY SELECT false, true;
        RETURN;
    END IF;

    v_items_match := (existing.catalog_fingerprint = p_catalog_fingerprint)
                      AND (existing.canonical_items::text = p_canonical_items::text);

    IF NOT v_items_match THEN
        -- Either the catalog changed or this fresh Grok answer disagrees with the
        -- previously stored one -- never silently average two different answers.
        -- Restart the count with this observation as the new baseline.
        UPDATE public.learned_parses SET
            canonical_items = p_canonical_items,
            catalog_fingerprint = p_catalog_fingerprint,
            observations = 1,
            contributing_job_ids = ARRAY[p_job_id],
            distinct_days = 1,
            last_seen_date = v_today,
            segmenter_corroborations = CASE WHEN p_corroborated THEN 1 ELSE 0 END,
            promoted = false,
            demoted_reason = 'reset_on_disagreement_or_catalog_change',
            last_seen_at = now()
        WHERE id = existing.id;
        RETURN QUERY SELECT false, existing.permanently_blocked;
        RETURN;
    END IF;

    v_new_obs := existing.observations + 1;
    v_new_days := existing.distinct_days + (CASE WHEN existing.last_seen_date <> v_today THEN 1 ELSE 0 END);
    v_new_corrob := existing.segmenter_corroborations + (CASE WHEN p_corroborated THEN 1 ELSE 0 END);
    v_job_ids := array_append(existing.contributing_job_ids, p_job_id);

    -- Promotion rule: >=2 distinct recordings, EVERY one of them corroborated by the
    -- segmenter, and zero corrections against this memo ever.
    v_promoted := v_new_obs >= 2
        AND array_length(v_job_ids, 1) >= 2
        AND v_new_corrob = v_new_obs
        AND existing.corrections = 0;

    UPDATE public.learned_parses SET
        observations = v_new_obs,
        contributing_job_ids = v_job_ids,
        distinct_days = v_new_days,
        last_seen_date = v_today,
        segmenter_corroborations = v_new_corrob,
        last_seen_at = now(),
        promoted = v_promoted,
        demoted_reason = CASE WHEN v_promoted THEN NULL ELSE demoted_reason END
    WHERE id = existing.id;

    RETURN QUERY SELECT v_promoted, existing.permanently_blocked;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
