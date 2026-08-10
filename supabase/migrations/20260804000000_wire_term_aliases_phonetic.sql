-- Migration: 20260804000000_wire_term_aliases_phonetic.sql
-- Drop the raw_term unique constraint and add shop_id & phonetic_key to term_aliases

ALTER TABLE public.term_aliases DROP CONSTRAINT IF EXISTS term_aliases_raw_term_key;

ALTER TABLE public.term_aliases ADD COLUMN IF NOT EXISTS shop_id UUID REFERENCES public.shops(id) ON DELETE CASCADE;

ALTER TABLE public.term_aliases ADD COLUMN IF NOT EXISTS phonetic_key TEXT;

-- Create a unique constraint/index for (shop_id, phonetic_key)
-- Since shop_id can be NULL (global aliases), we use COALESCE to handle NULL in the unique index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_term_aliases_shop_phonetic
    ON public.term_aliases (COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid), phonetic_key);
