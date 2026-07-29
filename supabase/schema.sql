-- Supabase Postgres Database Schema for Voice-First Shop Ledger
-- Multi-Tenant Row-Level Security (RLS) Enabled

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Shops Table
CREATE TABLE IF NOT EXISTS public.shops (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    vertical TEXT NOT NULL DEFAULT 'vegetable',
    language TEXT NOT NULL DEFAULT 'hinglish',
    tier TEXT NOT NULL DEFAULT 'pilot',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.shops ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage their own shop"
    ON public.shops FOR ALL
    USING (auth.uid() = user_id);

-- Sentinel "default shop" -- this deployment runs single-tenant today; requests that
-- carry no real shop_id are coalesced onto this row (see DEFAULT_LEARNED_PARSE_SHOP_ID
-- in process-voice-job/index.ts) so the Learned Parse Memory below (which requires a
-- non-null shop_id FK) still activates for real traffic. See ISSUE-031.
INSERT INTO public.shops (id, name, vertical, language, tier)
VALUES ('00000000-0000-0000-0000-000000000001', 'Unattributed (default)', 'vegetable', 'hinglish', 'pilot')
ON CONFLICT (id) DO NOTHING;

-- 2. Item Units Table (Master Reference)
CREATE TABLE IF NOT EXISTS public.item_units (
    id TEXT PRIMARY KEY,
    colloquial_term TEXT NOT NULL,
    multiplier DOUBLE PRECISION NOT NULL,
    base_unit TEXT NOT NULL
);

ALTER TABLE public.item_units ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public item units read access"
    ON public.item_units FOR SELECT
    USING (true);

-- 3. Catalog Items Table
CREATE TABLE IF NOT EXISTS public.catalog_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    unit_id TEXT NOT NULL REFERENCES public.item_units(id),
    price DOUBLE PRECISION NOT NULL,
    active BOOLEAN DEFAULT true,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.catalog_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for catalog_items"
    ON public.catalog_items FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- 4. Transactions Table (Append-Only Event Stream)
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    price_at_sale DOUBLE PRECISION NOT NULL,
    total DOUBLE PRECISION NOT NULL,
    payment_mode TEXT NOT NULL DEFAULT 'CASH',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source TEXT NOT NULL DEFAULT 'VOICE',
    device_id TEXT,
    job_id TEXT,
    audio_cloud_url TEXT,
    raw_transcript TEXT,
    -- line_no/line_count: one voice recording (one job_id) can now produce multiple
    -- transaction rows (multi-item capture) -- see ISSUE-029, migration 20260728000000.
    line_no INT NOT NULL DEFAULT 0,
    line_count INT NOT NULL DEFAULT 1,
    -- Correction signal for the Learned Parse Memory (ISSUE-031, migration
    -- 20260728010000) -- a shopkeeper voiding a wrongly-booked sale is the only ground
    -- truth that can contradict a self-consistent-but-wrong memoized parse. Soft
    -- delete only: the ledger stays append-only.
    voided BOOLEAN NOT NULL DEFAULT false,
    voided_at TIMESTAMPTZ
);

ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public transactions access"
    ON public.transactions FOR ALL
    USING (true)
    WITH CHECK (true);

-- 5. Credits Table (Udhaar)
CREATE TABLE IF NOT EXISTS public.credits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    customer_name TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    due_date TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'PENDING',
    linked_transaction_id UUID REFERENCES public.transactions(id),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.credits ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public credits access"
    ON public.credits FOR ALL
    USING (true)
    WITH CHECK (true);

-- 5b. Suppliers Table (Supplier Balances Owed)
CREATE TABLE IF NOT EXISTS public.suppliers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    phone TEXT,
    balance_owed DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.suppliers ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public suppliers access"
    ON public.suppliers FOR ALL
    USING (true)
    WITH CHECK (true);

-- 6. Stock-In Table (Append-Only Stock Arrivals)
CREATE TABLE IF NOT EXISTS public.stock_in (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    cost_price DOUBLE PRECISION NOT NULL,
    supplier TEXT,
    supplier_id UUID REFERENCES public.suppliers(id),
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.stock_in ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public stock_in access"
    ON public.stock_in FOR ALL
    USING (true)
    WITH CHECK (true);

-- 7. Unmatched Queue Table
CREATE TABLE IF NOT EXISTS public.unmatched_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id TEXT,
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    audio_ref TEXT,
    raw_transcript TEXT NOT NULL,
    resolved_item_id UUID REFERENCES public.catalog_items(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
    timestamp TIMESTAMPTZ DEFAULT NOW(),
    -- One review ROW per pending line, not per job -- see ISSUE-029.
    line_no INT NOT NULL DEFAULT 0,
    item_name TEXT,
    quantity DOUBLE PRECISION,
    unit TEXT,
    price_at_sale DOUBLE PRECISION,
    total DOUBLE PRECISION,
    price_intent TEXT,
    implausibility_reason TEXT
);

ALTER TABLE public.unmatched_queue ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public unmatched_queue access"
    ON public.unmatched_queue FOR ALL
    USING (true)
    WITH CHECK (true);

-- 8. STT Job Logs Table (Diagnostic Trace & Voice Recording Logs)
CREATE TABLE IF NOT EXISTS public.stt_job_logs (
    created_at TIMESTAMPTZ DEFAULT NOW(),
    raw_transcript TEXT,
    hold_duration_ms BIGINT,
    parsed_item_name TEXT,
    parsed_qty DOUBLE PRECISION,
    parsed_unit TEXT,
    parsed_total DOUBLE PRECISION,
    status TEXT,
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id TEXT NOT NULL,
    recorded_at_ms BIGINT,
    is_sanity_flagged BOOLEAN DEFAULT false,
    error_message TEXT,
    audio_cloud_url TEXT,
    diagnostic_trace_json TEXT,
    line_count INT NOT NULL DEFAULT 0
);

ALTER TABLE public.stt_job_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public stt_job_logs access"
    ON public.stt_job_logs FOR ALL
    USING (true)
    WITH CHECK (true);

-- 9. Learned Parses Table (Learned Parse Memory -- ISSUE-031, migration 20260728010000)
--
-- Per-shop memoization of a PROVEN Grok interpretation, keyed on the phonetic signature
-- of the transcript. Stores ONLY the pre-catalog-match interpretation (item_name/
-- quantity/unit/price_intent) -- price/total/confidence are always recomputed live from
-- catalog_items regardless of cache hit or miss, since prices change daily.
--
-- Promotion requires >=2 distinct recordings where the deterministic phonetic segmenter
-- independently corroborated Grok's item identity EVERY time, and zero corrections.
-- Any one of {canary re-verification mismatch, a contributing transaction being voided,
-- a catalog_fingerprint mismatch} demotes a promoted row back to observations=0; two
-- such strikes permanently blacklist it (permanently_blocked).
CREATE TABLE IF NOT EXISTS public.learned_parses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    memo_key TEXT NOT NULL,
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

-- Shared reset/demote helper -- called on canary mismatch (app code) and by the void
-- trigger below. Two strikes (from any source) permanently block the memo.
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

-- Records one observation and decides promotion atomically. Called from
-- process-voice-job/index.ts after every fresh (non-cached) Grok success.
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

CREATE INDEX IF NOT EXISTS idx_learned_parses_shop_memo ON public.learned_parses(shop_id, memo_key);

-- 10. Unmatched Item Observations Table (Catalog-Learning-From-History -- ISSUE-033,
-- migration 20260728020000)
--
-- A genuinely unmatched item (not in catalog_items) previously only entered the catalog
-- when a shopkeeper set a price for it during a Pending Confirmation review -- skip that
-- review once and the item started from zero again on every future mention. This table
-- tracks how many distinct recordings have produced the SAME item name (by phonetic key,
-- so STT-spelling variants collapse together) while genuinely unmatched; once that
-- recurrence crosses a threshold, record_unmatched_item_observation auto-adds the item to
-- catalog_items at price 0 so it starts matching immediately -- it books no money by
-- itself, but unpricedLineReason (item_resolution.ts) then explains the line correctly as
-- "has no price -- set a rate" instead of "not in your catalog yet". Mirrors the
-- learned_parses promotion pattern above but for catalog MEMBERSHIP rather than parse
-- interpretation.
-- NOTE: deliberately NO UNIQUE(shop_id, phonetic_key). phoneticKey is lossy and genuinely
-- different items collide on it ('Kela'/'Kheera' both -> KILA at distance 0.000), so one
-- phonetic bucket may legitimately hold several rows -- one per distinct literal item. See
-- ISSUE-034 / migration 20260728030000.
CREATE TABLE IF NOT EXISTS public.unmatched_item_observations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    phonetic_key TEXT NOT NULL,
    sample_name TEXT NOT NULL,
    unit_id TEXT NOT NULL,
    occurrences INT NOT NULL DEFAULT 1,
    contributing_job_ids TEXT[] NOT NULL DEFAULT '{}',
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    promoted_catalog_item_id UUID REFERENCES public.catalog_items(id)
);

ALTER TABLE public.unmatched_item_observations ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Service role full access on unmatched_item_observations"
    ON public.unmatched_item_observations FOR ALL TO service_role USING (true);

CREATE INDEX IF NOT EXISTS idx_unmatched_item_observations_shop_key
    ON public.unmatched_item_observations(shop_id, phonetic_key);

CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

-- Normalized literal edit distance, 0.0 (identical) .. 1.0 (nothing in common).
CREATE OR REPLACE FUNCTION normalized_name_distance(a TEXT, b TEXT)
RETURNS DOUBLE PRECISION AS $$
DECLARE
    v_a TEXT := lower(trim(COALESCE(a, '')));
    v_b TEXT := lower(trim(COALESCE(b, '')));
    v_len INT;
BEGIN
    v_len := GREATEST(length(v_a), length(v_b));
    IF v_len = 0 THEN RETURN 0.0; END IF;
    IF length(v_a) > 255 OR length(v_b) > 255 THEN
        RETURN CASE WHEN v_a = v_b THEN 0.0 ELSE 1.0 END;
    END IF;
    RETURN levenshtein(v_a, v_b)::DOUBLE PRECISION / v_len;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Max normalized LITERAL distance at which two spellings count as the same item (0.15).
-- Measured, not guessed -- see migration 20260728030000 for the full table of worked
-- values and why no looser cutoff is safe. NOT the same metric as item_resolution.ts's
-- NAME_AGREEMENT_MAX_NORM (which is over phonetic keys); the shared 0.15 is coincidence.
CREATE OR REPLACE FUNCTION catalog_learning_name_agreement_max() RETURNS DOUBLE PRECISION AS $$
    SELECT 0.15::DOUBLE PRECISION
$$ LANGUAGE sql IMMUTABLE;

-- Records one unmatched observation and decides catalog promotion atomically. Called from
-- process-voice-job/index.ts for every line that parsed to a real item name but did not
-- match any existing catalog row.
--
-- Two-stage identity check (ISSUE-034): the phonetic key selects a cheap indexable bucket,
-- then normalized_name_distance on the LITERAL names decides whether the incoming sighting
-- is really the same item as an existing row in that bucket. Without the second stage,
-- 'Kela' and 'Kheera' (identical phonetic key) shared one counter and could auto-create a
-- banana in the catalog under the name of a cucumber.
--
-- An advisory xact lock on (shop_id, phonetic_key) serializes the find-or-create, which a
-- unique constraint can no longer do now that one bucket may hold several rows.
CREATE OR REPLACE FUNCTION record_unmatched_item_observation(
    p_shop_id UUID,
    p_phonetic_key TEXT,
    p_item_name TEXT,
    p_unit_id TEXT,
    p_job_id TEXT,
    p_threshold INT DEFAULT 3
) RETURNS TABLE(is_in_catalog BOOLEAN, catalog_item_id UUID, newly_promoted BOOLEAN) AS $$
DECLARE
    existing RECORD;
    v_unit_id TEXT;
    v_new_obs INT;
    v_job_ids TEXT[];
    v_new_item_id UUID;
BEGIN
    -- p_unit_id comes from the parsed line and is not guaranteed to be a valid
    -- item_units id; catalog_items.unit_id is a NOT NULL FK, so fall back to PACKET
    -- rather than let an invalid unit ever abort this best-effort learning step.
    v_unit_id := COALESCE(
        (SELECT id FROM public.item_units WHERE id = p_unit_id),
        'PACKET'
    );

    PERFORM pg_advisory_xact_lock(hashtext(p_shop_id::TEXT || '|' || p_phonetic_key));

    SELECT * INTO existing
        FROM public.unmatched_item_observations
        WHERE shop_id = p_shop_id
          AND phonetic_key = p_phonetic_key
          AND normalized_name_distance(sample_name, p_item_name) <= catalog_learning_name_agreement_max()
        ORDER BY normalized_name_distance(sample_name, p_item_name) ASC, occurrences DESC
        LIMIT 1
        FOR UPDATE;

    IF NOT FOUND THEN
        INSERT INTO public.unmatched_item_observations (
            shop_id, phonetic_key, sample_name, unit_id, occurrences,
            contributing_job_ids, first_seen_at, last_seen_at
        ) VALUES (
            p_shop_id, p_phonetic_key, p_item_name, v_unit_id, 1,
            ARRAY[p_job_id], now(), now()
        );
        RETURN QUERY SELECT false, NULL::UUID, false;
        RETURN;
    END IF;

    -- Idempotency: the same job/line can be reprocessed (WorkManager retry, "already
    -- processed" cache hit) -- never double-count it.
    IF p_job_id = ANY(existing.contributing_job_ids) THEN
        RETURN QUERY SELECT existing.promoted_catalog_item_id IS NOT NULL, existing.promoted_catalog_item_id, false;
        RETURN;
    END IF;

    IF existing.promoted_catalog_item_id IS NOT NULL THEN
        UPDATE public.unmatched_item_observations SET
            contributing_job_ids = array_append(contributing_job_ids, p_job_id),
            last_seen_at = now()
        WHERE id = existing.id;
        RETURN QUERY SELECT true, existing.promoted_catalog_item_id, false;
        RETURN;
    END IF;

    v_new_obs := existing.occurrences + 1;
    v_job_ids := array_append(existing.contributing_job_ids, p_job_id);

    IF v_new_obs >= p_threshold THEN
        -- Adopt an existing catalog row if the shop already stocks this item under a
        -- near-identical spelling, rather than creating a near-duplicate beside it.
        SELECT id INTO v_new_item_id
            FROM public.catalog_items
            WHERE shop_id = p_shop_id
              AND active = true
              AND normalized_name_distance(name, p_item_name) <= catalog_learning_name_agreement_max()
            ORDER BY normalized_name_distance(name, p_item_name) ASC
            LIMIT 1;

        IF v_new_item_id IS NULL THEN
            INSERT INTO public.catalog_items (shop_id, name, unit_id, price, active)
            VALUES (p_shop_id, p_item_name, v_unit_id, 0, true)
            RETURNING id INTO v_new_item_id;
        END IF;

        UPDATE public.unmatched_item_observations SET
            occurrences = v_new_obs,
            contributing_job_ids = v_job_ids,
            sample_name = p_item_name,
            last_seen_at = now(),
            promoted_catalog_item_id = v_new_item_id
        WHERE id = existing.id;

        RETURN QUERY SELECT true, v_new_item_id, true;
        RETURN;
    END IF;

    UPDATE public.unmatched_item_observations SET
        occurrences = v_new_obs,
        contributing_job_ids = v_job_ids,
        sample_name = p_item_name,
        last_seen_at = now()
    WHERE id = existing.id;

    RETURN QUERY SELECT false, NULL::UUID, false;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE INDEX IF NOT EXISTS idx_unmatched_item_observations_promoted ON public.unmatched_item_observations(promoted_catalog_item_id);

-- Performance & Idempotency Indexes
CREATE INDEX IF NOT EXISTS idx_transactions_shop_time ON public.transactions(shop_id, timestamp DESC);
-- (job_id, line_no) not just job_id -- one job/recording can produce multiple lines. See ISSUE-029.
-- NOT partial: a partial index cannot serve as an ON CONFLICT (job_id,line_no) target for
-- Supabase's .upsert(), which silently broke every multi-item transaction write until
-- caught by a live replay test (ISSUE-030).
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_job_line ON public.transactions(job_id, line_no);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unmatched_queue_job_line ON public.unmatched_queue(job_id, line_no);
CREATE INDEX IF NOT EXISTS idx_catalog_shop_active ON public.catalog_items(shop_id, active);
CREATE INDEX IF NOT EXISTS idx_credits_shop_status ON public.credits(shop_id, status);
CREATE INDEX IF NOT EXISTS idx_stt_job_logs_time ON public.stt_job_logs(created_at DESC);
