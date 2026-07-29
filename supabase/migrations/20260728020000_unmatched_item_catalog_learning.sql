-- Catalog-Learning-From-History (ISSUE-033)
--
-- Problem: a genuinely unmatched item (not in catalog_items) only ever entered the
-- catalog when a shopkeeper set a price for it during a Pending Confirmation review. If
-- that review was skipped -- e.g. no price was known yet -- the item left NO trace, and
-- the next time it was spoken it started from zero again: "not in your catalog yet" on
-- every single occurrence, forever. Real-world case: "Amchur" recurring unmatched with no
-- path to ever being learned, because the shopkeeper had never been prompted with a price
-- to enter.
--
-- Fix: track how many distinct recordings (jobs) have produced the SAME item name (by
-- phonetic key, so STT-spelling variants collapse together) while genuinely unmatched.
-- Once that recurrence crosses a threshold, auto-add the item to catalog_items at price 0
-- -- it does not book any money by itself (price stays 0 until a rate is set), but it DOES
-- start matching immediately, so unpricedLineReason (item_resolution.ts) explains the line
-- correctly as "has no price -- set a rate" instead of "not in your catalog yet", and the
-- shopkeeper only has to set a rate ONCE for it to stick for every future mention.
--
-- Mirrors the learned_parses promotion pattern (ISSUE-031, migration
-- 20260728010000_learned_parses_and_void.sql) but for catalog MEMBERSHIP rather than parse
-- interpretation -- same atomic upsert-and-decide shape, same per-job idempotency guard.
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
    promoted_catalog_item_id UUID REFERENCES public.catalog_items(id),
    UNIQUE(shop_id, phonetic_key)
);

ALTER TABLE public.unmatched_item_observations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Service role full access on unmatched_item_observations" ON public.unmatched_item_observations;
CREATE POLICY "Service role full access on unmatched_item_observations"
    ON public.unmatched_item_observations FOR ALL TO service_role USING (true);

CREATE INDEX IF NOT EXISTS idx_unmatched_item_observations_shop_key
    ON public.unmatched_item_observations(shop_id, phonetic_key);

-- Records one unmatched observation and decides catalog promotion atomically. Called from
-- process-voice-job/index.ts for every line that parsed to a real item name but did not
-- match any existing catalog row. FOR UPDATE row lock on the (shop_id, phonetic_key) row
-- serializes concurrent jobs for the same item, so two recordings crossing the threshold
-- in the same instant can never create two duplicate catalog rows.
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

    SELECT * INTO existing FROM public.unmatched_item_observations
        WHERE shop_id = p_shop_id AND phonetic_key = p_phonetic_key FOR UPDATE;

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
        INSERT INTO public.catalog_items (shop_id, name, unit_id, price, active)
        VALUES (p_shop_id, p_item_name, v_unit_id, 0, true)
        RETURNING id INTO v_new_item_id;

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
