CREATE OR REPLACE FUNCTION record_unmatched_item_observation(
    p_shop_id UUID,
    p_phonetic_key TEXT,
    p_item_name TEXT,
    p_unit_id TEXT,
    p_job_id TEXT,
    p_threshold INT DEFAULT 3,
    p_canonical_key TEXT DEFAULT NULL,
    p_base_unit TEXT DEFAULT NULL
) RETURNS TABLE(is_in_catalog BOOLEAN, catalog_item_id UUID, newly_promoted BOOLEAN) AS $$
DECLARE
    existing RECORD;
    v_unit_id TEXT;
    v_base_unit TEXT;
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

    v_base_unit := COALESCE(
        p_base_unit,
        (SELECT base_unit FROM public.item_units WHERE id = v_unit_id),
        v_unit_id
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
        -- Adopt an existing row for the SAME ITEM before creating a near-duplicate. The
        -- canonical key comes from the lexicon (lexicon.ts / ItemLexicon.kt) because PL/pgSQL
        -- cannot compute it: the literal distance guard below is blind across scripts
        -- (normalized_name_distance('अदरक','Adrak') ~ 1.0), which is what let one item become
        -- two rows. ISSUE-107 / ISSUE-109.
        IF p_canonical_key IS NOT NULL AND p_canonical_key <> '' THEN
            SELECT id INTO v_new_item_id
                FROM public.catalog_items
                WHERE shop_id = p_shop_id
                  AND active = true
                  AND canonical_key = p_canonical_key
                  AND (p_base_unit IS NULL OR base_unit = p_base_unit)
                ORDER BY price DESC
                LIMIT 1;
        END IF;

        IF v_new_item_id IS NULL THEN
            -- existing literal-distance guard, unchanged
            SELECT id INTO v_new_item_id
                FROM public.catalog_items
                WHERE shop_id = p_shop_id
                  AND active = true
                  AND normalized_name_distance(name, p_item_name) <= catalog_learning_name_agreement_max()
                ORDER BY normalized_name_distance(name, p_item_name) ASC
                LIMIT 1;
        END IF;

        IF v_new_item_id IS NULL THEN
            INSERT INTO public.catalog_items (shop_id, name, unit_id, price, active, canonical_key, base_unit)
            VALUES (p_shop_id, p_item_name, v_unit_id, 0, true,
                    NULLIF(p_canonical_key, ''), v_base_unit)
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
