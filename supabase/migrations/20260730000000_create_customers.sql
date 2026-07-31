-- Migration: Create customers table and add customer_id to credits/transactions
CREATE TABLE IF NOT EXISTS public.customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id TEXT NOT NULL DEFAULT 'default_shop',
    code INT NOT NULL,
    name TEXT NOT NULL,
    keyword TEXT,
    phone TEXT,
    photo_path TEXT,
    phonetic_key TEXT NOT NULL,
    keyword_phonetic_key TEXT,
    last_seen_ms BIGINT NOT NULL DEFAULT 0,
    txn_count INT NOT NULL DEFAULT 0,
    merged_into_id UUID REFERENCES public.customers(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

ALTER TABLE public.customers ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow all read access to customers" ON public.customers FOR SELECT USING (true);
CREATE POLICY "Allow all insert access to customers" ON public.customers FOR INSERT WITH CHECK (true);
CREATE POLICY "Allow all update access to customers" ON public.customers FOR UPDATE USING (true);

CREATE INDEX IF NOT EXISTS idx_customers_phonetic_key ON public.customers(phonetic_key);
CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_code ON public.customers(code);
CREATE INDEX IF NOT EXISTS idx_customers_phone ON public.customers(phone);

ALTER TABLE public.credits ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES public.customers(id);
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS customer_id UUID REFERENCES public.customers(id);
