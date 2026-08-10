-- ISSUE-114 follow-up. Migration 20260809010000 set catalog_fingerprint = NULL on all 87
-- legacy rows so the scoped-fingerprint scheme could take over. But this RPC only ever WROTE
-- catalog_fingerprint on the INSERT path and the reset path -- the increment path left it
-- untouched, so NULL was STICKY: those rows would never receive a scoped fingerprint and the
-- staleness check would stay permanently disabled for them (guarded only by the segmenter
-- corroboration belt). The edge function's C1.2 comment claims "the next observation write
-- backfills the scoped value" -- this migration is what makes that claim true.
--
--   1. v_items_match treats a NULL stored fingerprint as "adopt the incoming one" rather than
--      as a mismatch. Without it, `existing.catalog_fingerprint = p_catalog_fingerprint` is
--      NULL (not false) for those rows, `IF NOT NULL` is false, and the reset branch is
--      skipped -- right behaviour, wrong reason, and fragile.
--   2. The increment UPDATE now backfills catalog_fingerprint.
--
-- Applied to production 2026-08-09 via apply_migration; this file is the repo's record of it.
CREATE OR REPLACE FUNCTION public.record_learned_parse_observation(
    p_shop_id uuid, p_memo_key text, p_canonical_items jsonb,
    p_catalog_fingerprint text, p_job_id text, p_corroborated boolean)
 RETURNS TABLE(promoted boolean, permanently_blocked boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
AS $function$
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

    -- Change 1: NULL stored fingerprint == "legacy row, adopt whatever comes in next".
    v_items_match := (existing.catalog_fingerprint IS NULL
                      OR existing.catalog_fingerprint = p_catalog_fingerprint)
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
        -- Change 2: backfill, so NULL is transient rather than permanent.
        catalog_fingerprint = p_catalog_fingerprint,
        promoted = v_promoted,
        demoted_reason = CASE WHEN v_promoted THEN NULL ELSE demoted_reason END
    WHERE id = existing.id;

    RETURN QUERY SELECT v_promoted, existing.permanently_blocked;
END;
$function$;
