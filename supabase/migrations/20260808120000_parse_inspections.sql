-- Step 1a: Price Aam and Chawal for shop 2f992a33-fa26-4be2-9006-3e6eafd41e2c
UPDATE catalog_items SET price = 120, updated_at = now()
WHERE id = '9b0ad3c5-33dc-46bc-8447-2865409f5bc7';  -- Aam, shop-scoped, 0 -> 120

UPDATE catalog_items SET price = 60, updated_at = now()
WHERE id = 'b178b448-e157-4063-b4d4-88ded86125a8';  -- चावल, shop-scoped, 0 -> 60

-- Step 1b: Deactivate learned garbage rows
UPDATE catalog_items SET active = false, updated_at = now()
WHERE shop_id = '2f992a33-fa26-4be2-9006-3e6eafd41e2c'
  AND price = 0
  AND name IN ('March','अठारह के लोग','पंद्रह','बचा रहा','सत्ताईस','सत्रह की','सिंगर');

-- Shadow verification of jobs that shipped WITHOUT an AI second opinion (fast path,
-- STT-race shortcut, learned-parse memory). Observe-only: nothing in the pipeline reads
-- this table, and no code path may branch on its contents. It exists to answer one
-- question from data instead of from argument -- "how often is the deterministic path
-- wrong?" -- and to be deletable the day that question is settled.
create table if not exists public.parse_inspections (
  id             uuid primary key default gen_random_uuid(),
  job_id         text not null,
  shop_id        uuid,
  created_at     timestamptz not null default now(),
  parse_source   text not null,          -- segmenter_fast_path | memory
  transcript     text,
  shipped_items  jsonb not null,
  grok_items     jsonb,
  agrees         boolean,
  mismatch_kind  text,                   -- item_count|item_name|quantity|unit|price_intent|grok_error
  grok_model     text,
  grok_latency_ms integer,
  grok_error     text
);
create index if not exists parse_inspections_created_idx on public.parse_inspections (created_at desc);
create index if not exists parse_inspections_mismatch_idx on public.parse_inspections (mismatch_kind) where mismatch_kind is not null;
alter table public.parse_inspections enable row level security;
-- service-role only; the client never reads this.
