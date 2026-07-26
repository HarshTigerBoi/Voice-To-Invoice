-- Supabase Postgres Database Schema for Voice-First Shop Ledger
-- Multi-Tenant Row-Level Security (RLS) Enabled

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Shops Table
CREATE TABLE IF NOT EXISTS public.shops (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    vertical TEXT NOT NULL DEFAULT 'vegetable',
    language TEXT NOT NULL DEFAULT 'hinglish',
    tier TEXT NOT NULL DEFAULT 'pilot',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.shops ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can manage their own shop"
    ON public.shops FOR ALL
    USING (auth.uid() = user_id);

-- 2. Item Units Table (Master Reference)
CREATE TABLE IF NOT EXISTS public.item_units (
    id TEXT PRIMARY KEY,
    colloquial_term TEXT NOT NULL,
    multiplier DOUBLE PRECISION NOT NULL,
    base_unit TEXT NOT NULL
);

ALTER TABLE public.item_units ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public item units read access"
    ON public.item_units FOR SELECT
    USING (true);

-- 3. Catalog Items Table
CREATE TABLE IF NOT EXISTS public.catalog_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    unit_id TEXT NOT NULL REFERENCES public.item_units(id),
    price DOUBLE PRECISION NOT NULL,
    active BOOLEAN DEFAULT true,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.catalog_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for catalog_items"
    ON public.catalog_items FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- 4. Transactions Table (Append-Only Event Stream)
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    price_at_sale DOUBLE PRECISION NOT NULL,
    total DOUBLE PRECISION NOT NULL,
    payment_mode TEXT NOT NULL DEFAULT 'CASH',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source TEXT NOT NULL DEFAULT 'VOICE',
    device_id TEXT,
    job_id TEXT,
    audio_cloud_url TEXT,
    raw_transcript TEXT
);

ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public transactions access"
    ON public.transactions FOR ALL
    USING (true)
    WITH CHECK (true);

-- 5. Credits Table (Udhaar)
CREATE TABLE IF NOT EXISTS public.credits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    customer_name TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    due_date TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'PENDING',
    linked_transaction_id UUID REFERENCES public.transactions(id),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.credits ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public credits access"
    ON public.credits FOR ALL
    USING (true)
    WITH CHECK (true);

-- 5b. Suppliers Table (Supplier Balances Owed)
CREATE TABLE IF NOT EXISTS public.suppliers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    phone TEXT,
    balance_owed DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.suppliers ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public suppliers access"
    ON public.suppliers FOR ALL
    USING (true)
    WITH CHECK (true);

-- 6. Stock-In Table (Append-Only Stock Arrivals)
CREATE TABLE IF NOT EXISTS public.stock_in (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    cost_price DOUBLE PRECISION NOT NULL,
    supplier TEXT,
    supplier_id UUID REFERENCES public.suppliers(id),
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.stock_in ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public stock_in access"
    ON public.stock_in FOR ALL
    USING (true)
    WITH CHECK (true);

-- 7. Unmatched Queue Table
CREATE TABLE IF NOT EXISTS public.unmatched_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id TEXT UNIQUE,
    shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    audio_ref TEXT,
    raw_transcript TEXT NOT NULL,
    resolved_item_id UUID REFERENCES public.catalog_items(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.unmatched_queue ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public unmatched_queue access"
    ON public.unmatched_queue FOR ALL
    USING (true)
    WITH CHECK (true);

-- 8. STT Job Logs Table (Diagnostic Trace & Voice Recording Logs)
CREATE TABLE IF NOT EXISTS public.stt_job_logs (
    created_at TIMESTAMPTZ DEFAULT NOW(),
    raw_transcript TEXT,
    hold_duration_ms BIGINT,
    parsed_item_name TEXT,
    parsed_qty DOUBLE PRECISION,
    parsed_unit TEXT,
    parsed_total DOUBLE PRECISION,
    status TEXT,
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    job_id TEXT NOT NULL,
    recorded_at_ms BIGINT,
    is_sanity_flagged BOOLEAN DEFAULT false,
    error_message TEXT,
    audio_cloud_url TEXT,
    diagnostic_trace_json TEXT
);

ALTER TABLE public.stt_job_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Public stt_job_logs access"
    ON public.stt_job_logs FOR ALL
    USING (true)
    WITH CHECK (true);

-- Performance & Idempotency Indexes
CREATE INDEX IF NOT EXISTS idx_transactions_shop_time ON public.transactions(shop_id, timestamp DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_unique_job_id ON public.transactions(job_id) WHERE job_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_catalog_shop_active ON public.catalog_items(shop_id, active);
CREATE INDEX IF NOT EXISTS idx_credits_shop_status ON public.credits(shop_id, status);
CREATE INDEX IF NOT EXISTS idx_stt_job_logs_time ON public.stt_job_logs(created_at DESC);
