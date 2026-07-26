-- Supabase Postgres Database Migration for Voice-First Shop Ledger
-- Multi-Tenant Row-Level Security (RLS) Enabled

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Shops Table
CREATE TABLE IF NOT EXISTS public.shops (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    price_at_sale DOUBLE PRECISION NOT NULL,
    total DOUBLE PRECISION NOT NULL,
    payment_mode TEXT NOT NULL DEFAULT 'CASH',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source TEXT NOT NULL DEFAULT 'VOICE',
    device_id TEXT
);

ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for transactions"
    ON public.transactions FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- 5. Credits Table (Udhaar)
CREATE TABLE IF NOT EXISTS public.credits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    customer_name TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    due_date TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'PENDING',
    linked_transaction_id UUID REFERENCES public.transactions(id),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.credits ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for credits"
    ON public.credits FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- 6. Stock-In Table (Append-Only Stock Arrivals)
CREATE TABLE IF NOT EXISTS public.stock_in (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    cost_price DOUBLE PRECISION NOT NULL,
    supplier TEXT,
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.stock_in ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for stock_in"
    ON public.stock_in FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- 7. Unmatched Queue Table
CREATE TABLE IF NOT EXISTS public.unmatched_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    audio_ref TEXT,
    raw_transcript TEXT NOT NULL,
    resolved_item_id UUID REFERENCES public.catalog_items(id),
    status TEXT NOT NULL DEFAULT 'PENDING',
    timestamp TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.unmatched_queue ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Shop isolation for unmatched_queue"
    ON public.unmatched_queue FOR ALL
    USING (shop_id IN (SELECT id FROM public.shops WHERE user_id = auth.uid()));

-- Performance Indexes
CREATE INDEX IF NOT EXISTS idx_transactions_shop_time ON public.transactions(shop_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_catalog_shop_active ON public.catalog_items(shop_id, active);
CREATE INDEX IF NOT EXISTS idx_credits_shop_status ON public.credits(shop_id, status);
