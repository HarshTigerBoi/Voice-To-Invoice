import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.0";

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
};

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  try {
    // Use service_role to bypass RLS and read ALL data
    const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? '';
    const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';

    if (!serviceRoleKey) {
      return new Response(JSON.stringify({ error: 'SUPABASE_SERVICE_ROLE_KEY secret not set on Edge Function' }), {
        status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' }
      });
    }

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false }
    });

    console.log('diagnostic-reader: Fetching all app data with service_role...');

    // 1. STT Job Logs (voice processing trace - newest first)
    const { data: sttLogs, error: sttError } = await supabase
      .from('stt_job_logs')
      .select('*')
      .order('created_at', { ascending: false })
      .limit(200);

    // 2. Transactions (summary / ledger)
    const { data: transactions, error: txError } = await supabase
      .from('transactions')
      .select('*')
      .order('timestamp', { ascending: false })
      .limit(500);

    // 3. Catalog items
    const { data: catalog, error: catError } = await supabase
      .from('catalog_items')
      .select('*')
      .eq('active', true);

    // 4. Unmatched queue (review tray)
    const { data: reviewQueue, error: reviewError } = await supabase
      .from('unmatched_queue')
      .select('*')
      .order('timestamp', { ascending: false })
      .limit(200);

    // 5. Shops
    const { data: shops, error: shopError } = await supabase
      .from('shops')
      .select('*');

    // 6. Credits (udhaar)
    const { data: credits, error: creditError } = await supabase
      .from('credits')
      .select('*')
      .order('updated_at', { ascending: false })
      .limit(200);

    // 7. Stock-in
    const { data: stockIn, error: stockError } = await supabase
      .from('stock_in')
      .select('*')
      .order('timestamp', { ascending: false })
      .limit(200);

    // 8. Audio files in Supabase Storage
    const { data: audioFiles, error: audioError } = await supabase
      .storage
      .from('voice-recordings')
      .list('', { limit: 200, sortBy: { column: 'created_at', order: 'desc' } });

    // Build signed URLs for audio files (valid for 1 hour)
    const audioUrls: { name: string; url: string; created_at: string }[] = [];
    if (audioFiles && audioFiles.length > 0) {
      const { data: signedUrls } = await supabase.storage
        .from('voice-recordings')
        .createSignedUrls(audioFiles.map(f => f.name), 3600);
      if (signedUrls) {
        for (let i = 0; i < audioFiles.length; i++) {
          audioUrls.push({
            name: audioFiles[i].name,
            url: signedUrls[i]?.signedUrl ?? '',
            created_at: audioFiles[i].created_at ?? '',
          });
        }
      }
    }

    const report = {
      fetched_at: new Date().toISOString(),
      summary: {
        total_stt_jobs: sttLogs?.length ?? 0,
        total_transactions: transactions?.length ?? 0,
        total_catalog_items: catalog?.length ?? 0,
        total_review_queue: reviewQueue?.length ?? 0,
        total_audio_files: audioFiles?.length ?? 0,
        total_credits: credits?.length ?? 0,
        total_stock_in: stockIn?.length ?? 0,
      },
      errors: {
        stt_logs: sttError?.message ?? null,
        transactions: txError?.message ?? null,
        catalog: catError?.message ?? null,
        review_queue: reviewError?.message ?? null,
        shops: shopError?.message ?? null,
        credits: creditError?.message ?? null,
        stock_in: stockError?.message ?? null,
        audio: audioError?.message ?? null,
      },
      shops: shops ?? [],
      stt_job_logs: sttLogs ?? [],
      transactions: transactions ?? [],
      catalog_items: catalog ?? [],
      review_queue: reviewQueue ?? [],
      credits: credits ?? [],
      stock_in: stockIn ?? [],
      audio_recordings: audioUrls,
    };

    console.log(`diagnostic-reader: Fetched ${report.summary.total_stt_jobs} STT logs, ${report.summary.total_transactions} transactions, ${report.summary.total_audio_files} audio files`);

    return new Response(JSON.stringify(report, null, 2), {
      status: 200,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });

  } catch (err) {
    console.error('diagnostic-reader error:', err);
    return new Response(JSON.stringify({ error: String(err) }), {
      status: 500,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' }
    });
  }
});
