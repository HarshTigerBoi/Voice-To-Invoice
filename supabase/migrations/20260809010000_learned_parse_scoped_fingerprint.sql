-- ISSUE-114: Learned Parse Memory had 0 lifetime hits. Two causes, both fixed here.
--
-- 1. Fingerprint fragmentation. catalog_fingerprint hashed the WHOLE catalog, so any catalog
--    edit invalidated every memo. Verified 2026-08-09: 108 rows across 8 fingerprints, only 6
--    promoted, avg observations 0.9. The edge function now stores a SCOPED hash covering only
--    the items a memo names, so legacy values can never match and must be cleared. NULL is
--    read as "revalidate on next use" by the lookup; the corroboration belt still guards it.
--
-- 2. Shop-ID split. Rows exist under both the real shop and the legacy sentinel
--    00000000-0000-0000-0000-000000000001 (used before ensure_shop provisioning landed).
--    Verified counts at time of writing: 51+12+7+6 real vs 18+5+5+2+1+1 sentinel.

-- Merge sentinel rows into the real shop, but only where the real shop has no row for that
-- memo_key. Observation counts are summed so a memo split across both IDs keeps its history.
UPDATE learned_parses r
SET observations             = r.observations + s.observations,
    segmenter_corroborations = r.segmenter_corroborations + s.segmenter_corroborations,
    corrections              = r.corrections + s.corrections,
    distinct_days            = GREATEST(r.distinct_days, s.distinct_days),
    last_seen_at             = GREATEST(r.last_seen_at, s.last_seen_at),
    last_seen_date           = GREATEST(r.last_seen_date, s.last_seen_date)
FROM learned_parses s
WHERE s.shop_id = '00000000-0000-0000-0000-000000000001'
  AND r.shop_id <> '00000000-0000-0000-0000-000000000001'
  AND r.memo_key = s.memo_key;

-- Re-point sentinel rows that have no counterpart in a real shop. If more than one real shop
-- ever exists this is ambiguous, so it is restricted to the single-tenant case that is true
-- today (verified: exactly 2 distinct shop_ids, one of them the sentinel).
UPDATE learned_parses
SET shop_id = (
      SELECT shop_id FROM learned_parses
      WHERE shop_id <> '00000000-0000-0000-0000-000000000001'
      GROUP BY shop_id ORDER BY count(*) DESC LIMIT 1
    )
WHERE shop_id = '00000000-0000-0000-0000-000000000001'
  AND (SELECT count(DISTINCT shop_id) FROM learned_parses
       WHERE shop_id <> '00000000-0000-0000-0000-000000000001') = 1
  AND memo_key NOT IN (
      SELECT memo_key FROM learned_parses
      WHERE shop_id <> '00000000-0000-0000-0000-000000000001'
  );

-- Drop any sentinel rows left over (they were duplicates merged above).
DELETE FROM learned_parses WHERE shop_id = '00000000-0000-0000-0000-000000000001';

-- catalog_fingerprint was declared NOT NULL. The scoped-fingerprint design needs a third
-- state -- "legacy value, revalidate on next use" -- which the edge function reads as
-- `stored === null`. NOT NULL makes that state unrepresentable, so drop it.
-- (Found the hard way: the first attempt at this migration aborted on 23502 and rolled back.)
ALTER TABLE learned_parses ALTER COLUMN catalog_fingerprint DROP NOT NULL;

-- Every surviving fingerprint is a legacy whole-catalog hash. Clear them.
UPDATE learned_parses SET catalog_fingerprint = NULL;
