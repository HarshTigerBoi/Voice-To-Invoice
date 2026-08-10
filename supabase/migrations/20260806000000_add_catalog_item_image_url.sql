-- Migration: 20260806000000_add_catalog_item_image_url.sql
-- Add image_url column to catalog_items table and create item-icons storage bucket

ALTER TABLE public.catalog_items ADD COLUMN IF NOT EXISTS image_url TEXT DEFAULT NULL;

-- Create item-icons storage bucket if it does not exist
INSERT INTO storage.buckets (id, name, public)
VALUES ('item-icons', 'item-icons', true)
ON CONFLICT (id) DO NOTHING;

-- Allow public read access to item-icons storage bucket
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies 
        WHERE tablename = 'objects' AND policyname = 'Public read access for item icons'
    ) THEN
        CREATE POLICY "Public read access for item icons"
        ON storage.objects FOR SELECT
        USING (bucket_id = 'item-icons');
    END IF;
END $$;
