-- ISSUE-034: literal-name guard on Catalog-Learning-From-History (fixes ISSUE-033)
--
-- ISSUE-033 grouped unmatched-item observations on `phoneticKey(item_name)` alone, on the
-- assumption that a shared phonetic key means "the same word, spelled differently by STT".
-- That assumption is false. phoneticKey is deliberately lossy (it collapses vowels and
-- voiced/unvoiced consonant pairs to survive Indian-accent STT noise), so genuinely
-- DIFFERENT items collide at distance 0.000. Measured directly against phonetic.ts:
--
--     Kela  (banana)   -> KILA  |  Kheera (cucumber)      -> KILA   distance 0.000
--     Mooli (radish)   -> NOLI  |  Mouli  (sacred thread) -> NOLI   distance 0.000
--     Aam   (mango)    -> AN    |  (2-char key, wide open to future collisions)
--
-- Consequence of the un-guarded version: two "Kela" recordings plus one "Kheera" recording
-- would together cross the threshold on ONE shared counter and auto-create a single catalog
-- row named whichever spelling arrived last -- i.e. a banana silently entering the shop's
-- permanent catalog as a cucumber. Exactly the failure class ISSUE-030 called out ("the
-- dangerous case is precisely when the AI's wrong word DOES resolve to a catalog row").
--
-- Fix: keep the phonetic key as the GROUP key (so it still does the job it is good at --
-- a cheap, indexable bucket), but require the raw LITERAL names to ALSO agree, within a
-- deliberately tight normalized Levenshtein distance, before two observations may share a
-- counter.
--
-- The threshold (0.15, `catalog_learning_name_agreement_max()`) is measured, not guessed.
-- Every value below was computed with this migration's own normalized_name_distance():
--
--     WANT MERGE (same item, STT/AI spelling variant)
--       'tamatar' vs 'tamaatar'  0.125   <= 0.15  MERGES   correct
--       'baingan' vs 'bhaingan'  0.125   <= 0.15  MERGES   correct
--       'amchur'  vs 'aamchur'   0.143   <= 0.15  MERGES   correct
--       'dhaniya' vs 'dhania'    0.143   <= 0.15  MERGES   correct
--       'gobhi'   vs 'gobi'      0.200   >  0.15  splits   (accepted, see below)
--       'amchur'  vs 'amchoor'   0.286   >  0.15  splits   (accepted, see below)
--       'amrud'   vs 'amrood'    0.333   >  0.15  splits   (accepted, see below)
--       'aaloo'   vs 'alu'       0.600   >  0.15  splits   (accepted, see below)
--
--     WANT SEPARATE (genuinely different items)
--       'mooli'   vs 'mouli'     0.200   >  0.15  splits   correct
--       'aam'     vs 'aan'       0.333   >  0.15  splits   correct
--       'kela'    vs 'kheera'    0.500   >  0.15  splits   correct
--
-- Why not a looser threshold that would also catch 'amchoor'/'amrood'? Because the two
-- classes OVERLAP and cannot be separated by this metric at any cutoff: 'amrud'/'amrood'
-- (want merge) and 'aam'/'aan' (want separate) both sit at exactly 0.333, and
-- 'aaloo'/'alu' (want merge, 0.600) scores WORSE than 'kela'/'kheera' (want separate,
-- 0.500). There is no value of this threshold that merges every real variant without also
-- merging at least one genuinely different item.
--
-- Given that, the cutoff is set to the strictest useful value and the asymmetry of the two
-- error types decides the rest: a missed merge costs only SPEED (the item needs one more
-- recording before it is learned -- it still gets learned), while a false merge writes a
-- WRONG, persistent, user-visible catalog row. Speed is recoverable; a banana permanently
-- filed as a cucumber is not.
--
-- NOTE: this 0.15 is a normalized LITERAL Levenshtein distance and is NOT the same metric
-- as item_resolution.ts's NAME_AGREEMENT_MAX_NORM (also 0.15), which is a normalized
-- distance over PHONETIC keys. The numeric coincidence is not a shared constant -- do not
-- refactor them into one.
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

-- One phonetic bucket may now legitimately hold MORE THAN ONE row (Kela and Kheera are two
-- distinct learned items that share key KILA), so the old uniqueness rule no longer holds.
ALTER TABLE public.unmatched_item_observations
    DROP CONSTRAINT IF EXISTS unmatched_item_observations_shop_id_phonetic_key_key;

-- Normalized literal edit distance, 0.0 (identical) .. 1.0 (nothing in common). Divided by
-- the LONGER string so it means "fraction of the word that differs" regardless of order.
CREATE OR REPLACE FUNCTION normalized_name_distance(a TEXT, b TEXT)
RETURNS DOUBLE PRECISION AS $$
DECLARE
    v_a TEXT := lower(trim(COALESCE(a, '')));
    v_b TEXT := lower(trim(COALESCE(b, '')));
    v_len INT;
BEGIN
    v_len := GREATEST(length(v_a), length(v_b));
    IF v_len = 0 THEN RETURN 0.0; END IF;
    -- fuzzystrmatch's levenshtein() hard-errors above 255 chars; item names are far
    -- shorter, but guard anyway so a garbage transcript can never abort the learning step.
    IF length(v_a) > 255 OR length(v_b) > 255 THEN
        RETURN CASE WHEN v_a = v_b THEN 0.0 ELSE 1.0 END;
    END IF;
    RETURN levenshtein(v_a, v_b)::DOUBLE PRECISION / v_len;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Max normalized literal distance at which two spellings still count as the same item.
-- See the measured table in the header comment for why 0.15 is the dividing line, and why
-- no looser value is safe. Deliberately a function, not an inlined literal, so the cutoff
-- has exactly one definition shared by both call sites below.
CREATE OR REPLACE FUNCTION catalog_learning_name_agreement_max() RETURNS DOUBLE PRECISION AS $$
    SELECT 0.15::DOUBLE PRECISION
$$ LANGUAGE sql IMMUTABLE;

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
    v_unit_id := COALESCE(
        (SELECT id FROM public.item_units WHERE id = p_unit_id),
        'PACKET'
    );

    -- The find-or-create below is no longer protected by a unique constraint (one phonetic
    -- bucket can hold several literal items), so serialize concurrent jobs for the same
    -- bucket explicitly. Without this, two simultaneous first-sightings of the same item
    -- would each find nothing and insert their own row, splitting the counter.
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
        -- Guard against promoting a name the shop ALREADY stocks under a different
        -- spelling (e.g. the catalog gained the item by another path between
        -- observations) -- adopt that row instead of creating a near-duplicate.
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
