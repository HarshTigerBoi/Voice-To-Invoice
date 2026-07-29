-- Multi-item voice capture: allow N transactions / N unmatched rows per job.
--
-- The live idx_transactions_unique_job_id (job_id UNIQUE) made a second transaction
-- row for the same job impossible, and index.ts's `upsert(txInserts, {onConflict:'job_id'})`
-- was silently failing (42P10: no unique constraint matching ON CONFLICT) for every
-- multi-item AUTO_CONFIRMED job since 2026-07-23 -- the sale was logged as confirmed
-- but never reached the ledger. See ISSUE-029 in Docs/audit.md.

-- Step 1: add columns (kept in a separate step from the unique indexes below --
-- existing multi-item jobs written by the Android client already have >1 row per
-- job_id, all defaulting to line_no=0, which collides with a same-transaction unique
-- index create; backfill must run in between on a fresh database with existing data).
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS line_no INT NOT NULL DEFAULT 0;
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS line_count INT NOT NULL DEFAULT 1;

ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS line_no INT NOT NULL DEFAULT 0;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS item_name TEXT;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS quantity DOUBLE PRECISION;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS unit TEXT;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS price_at_sale DOUBLE PRECISION;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS total DOUBLE PRECISION;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS price_intent TEXT;
ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS implausibility_reason TEXT;

ALTER TABLE public.stt_job_logs ADD COLUMN IF NOT EXISTS line_count INT NOT NULL DEFAULT 0;

-- Step 2: backfill line_no/line_count for any rows that already have >1 per job_id
-- (harmless no-op on a fresh database with no existing multi-item rows).
WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY timestamp, id) - 1 AS rn,
         COUNT(*) OVER (PARTITION BY job_id) AS cnt
  FROM public.transactions WHERE job_id IS NOT NULL
)
UPDATE public.transactions t SET line_no = ranked.rn, line_count = ranked.cnt
FROM ranked WHERE ranked.id = t.id;

WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY timestamp, id) - 1 AS rn
  FROM public.unmatched_queue WHERE job_id IS NOT NULL
)
UPDATE public.unmatched_queue u SET line_no = ranked.rn
FROM ranked WHERE ranked.id = u.id;

-- Step 3: replace the per-job unique constraints with per-line ones.
--
-- NOT partial (no WHERE job_id IS NOT NULL): Postgres cannot use a partial index as an
-- ON CONFLICT (columns) target unless the statement restates the exact predicate, and
-- Supabase's .upsert(rows, {onConflict:'job_id,line_no'}) does not -- an earlier version
-- of this migration made this index partial (copying idx_transactions_unique_job_id's
-- shape unthinkingly) and every multi-item transaction write failed with 42P10 silently
-- from deploy 59 until this was caught by a live replay test. See ISSUE-030. Dropping the
-- predicate is safe: Postgres already treats each NULL as distinct in a unique index, so
-- unlimited rows with job_id IS NULL (manual/UPI transactions) remain unaffected.
DROP INDEX IF EXISTS public.idx_transactions_unique_job_id;
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_job_line
    ON public.transactions(job_id, line_no);

ALTER TABLE public.unmatched_queue DROP CONSTRAINT IF EXISTS unmatched_queue_job_id_key;
DROP INDEX IF EXISTS public.unmatched_queue_job_id_key;
DROP INDEX IF EXISTS public.idx_unmatched_queue_job_id_unique;
CREATE UNIQUE INDEX IF NOT EXISTS idx_unmatched_queue_job_line
    ON public.unmatched_queue(job_id, line_no);

-- stt_job_logs live DB had two duplicate unique indexes on job_id
-- (idx_stt_job_logs_job_id_unique AND idx_stt_job_logs_unique_job_id) -- drop the
-- redundant one, keep idx_stt_job_logs_unique_job_id which matches schema.sql naming.
DROP INDEX IF EXISTS public.idx_stt_job_logs_job_id_unique;
