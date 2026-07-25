-- Migration: 20260722_create_term_aliases.sql
-- Create shared term aliases & multi-shop confirmation tables with atomic UPSERT and RLS

CREATE TABLE IF NOT EXISTS public.term_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_term TEXT NOT NULL UNIQUE,
    canonical_value TEXT NOT NULL,
    domain TEXT NOT NULL DEFAULT 'modifier', -- 'modifier' or 'item'
    confirm_count INT NOT NULL DEFAULT 1,
    distinct_shop_count INT NOT NULL DEFAULT 1,
    verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Multi-shop confirmation join table
CREATE TABLE IF NOT EXISTS public.term_alias_confirmations (
    term_id UUID REFERENCES public.term_aliases(id) ON DELETE CASCADE,
    shop_id TEXT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (term_id, shop_id)
);

-- Global RLS Policies (Public Read, Service Role Write Only)
ALTER TABLE public.term_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.term_alias_confirmations ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow public read access on term_aliases" 
    ON public.term_aliases FOR SELECT TO anon, authenticated USING (true);

CREATE POLICY "Service role full write access on term_aliases" 
    ON public.term_aliases FOR ALL TO service_role USING (true);

CREATE POLICY "Service role full write access on term_alias_confirmations" 
    ON public.term_alias_confirmations FOR ALL TO service_role USING (true);

-- PL/pgSQL Function for Nightly Verification Promotion
CREATE OR REPLACE FUNCTION promote_verified_term_aliases() RETURNS void AS $$
BEGIN
    UPDATE public.term_aliases
    SET verified = true
    WHERE verified = false
      AND distinct_shop_count >= 2
      AND confirm_count >= 3;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
