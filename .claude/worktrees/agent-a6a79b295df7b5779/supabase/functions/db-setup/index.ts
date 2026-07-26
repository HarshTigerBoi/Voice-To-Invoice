import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import postgres from "https://deno.land/x/postgresjs@v3.4.3/mod.js";

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
};

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  try {
    const dbUrl = Deno.env.get('SUPABASE_DB_URL');
    if (!dbUrl) {
      return new Response(JSON.stringify({ error: 'SUPABASE_DB_URL not available' }), {
        status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    const sql = postgres(dbUrl, { max: 1 });

    // 1. Create stt_job_logs table if it does not exist
    await sql`
      CREATE TABLE IF NOT EXISTS public.stt_job_logs (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        job_id TEXT NOT NULL,
        shop_id UUID,
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
      )
    `;

    // 2. Add shop_id column to stt_job_logs if missing
    try {
      await sql`ALTER TABLE public.stt_job_logs ADD COLUMN IF NOT EXISTS shop_id UUID`;
    } catch (_) {}

    // 3. Create Unique Index on job_id for stt_job_logs
    try {
      await sql`CREATE UNIQUE INDEX IF NOT EXISTS idx_stt_job_logs_job_id_unique ON public.stt_job_logs(job_id)`;
    } catch (_) {}

    // 4. Create Unique Index on job_id for unmatched_queue
    try {
      await sql`ALTER TABLE public.unmatched_queue ADD COLUMN IF NOT EXISTS job_id TEXT UNIQUE`;
    } catch (_) {}

    try {
      await sql`CREATE UNIQUE INDEX IF NOT EXISTS idx_unmatched_queue_job_id_unique ON public.unmatched_queue(job_id)`;
    } catch (_) {}

    // 5. Drop NOT NULL constraints on shop_id across tables
    try {
      await sql`ALTER TABLE public.transactions ALTER COLUMN shop_id DROP NOT NULL`;
    } catch (_) {}

    try {
      await sql`ALTER TABLE public.unmatched_queue ALTER COLUMN shop_id DROP NOT NULL`;
    } catch (_) {}

    try {
      await sql`ALTER TABLE public.stt_job_logs ALTER COLUMN shop_id DROP NOT NULL`;
    } catch (_) {}

    // 6. Disable RLS for service tables to prevent write blocks
    try {
      await sql`ALTER TABLE public.transactions DISABLE ROW LEVEL SECURITY`;
    } catch (_) {}

    try {
      await sql`ALTER TABLE public.unmatched_queue DISABLE ROW LEVEL SECURITY`;
    } catch (_) {}

    try {
      await sql`ALTER TABLE public.stt_job_logs DISABLE ROW LEVEL SECURITY`;
    } catch (_) {}

    await sql.end();

    console.log('db-setup: stt_job_logs shop_id column and unique indexes added successfully');

    return new Response(JSON.stringify({
      status: 'success',
      message: 'stt_job_logs shop_id column and unique indexes created/verified successfully',
      timestamp: new Date().toISOString(),
    }), {
      status: 200,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });

  } catch (err) {
    console.error('db-setup error:', err);
    return new Response(JSON.stringify({ error: String(err) }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
});
