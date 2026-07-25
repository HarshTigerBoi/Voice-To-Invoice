-- Migration: Create stt_job_logs diagnostic table
-- This table stores the complete trace of every voice recording session

CREATE TABLE IF NOT EXISTS public.stt_job_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id TEXT NOT NULL,
    recorded_at_ms BIGINT,
    hold_duration_ms BIGINT,
    status TEXT,
    raw_transcript TEXT,
    parsed_item_name TEXT,
    parsed_qty DOUBLE PRECISION,
    parsed_unit TEXT,
    parsed_total DOUBLE PRECISION,
    is_sanity_flagged BOOLEAN DEFAULT false,
    error_message TEXT,
    diagnostic_trace_json TEXT,
    audio_cloud_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.stt_job_logs ENABLE ROW LEVEL SECURITY;

-- Allow public access for diagnostic logging (anon key can insert/read)
CREATE POLICY "Public stt_job_logs access"
    ON public.stt_job_logs FOR ALL
    USING (true)
    WITH CHECK (true);

-- Index for chronological queries
CREATE INDEX IF NOT EXISTS idx_stt_job_logs_time ON public.stt_job_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stt_job_logs_job_id ON public.stt_job_logs(job_id);
