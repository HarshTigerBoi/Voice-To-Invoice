-- FINDING 3: transactions/unmatched_queue/stock_in/catalog_items/credits all have
-- shop_id -> shops(id). ShopContext issues a per-install UUID that has no shops row, so
-- every write carrying a real shop id fails with 23503. Auto-provision the shops row
-- instead of dropping the FK: the FK is what will make RLS meaningful later (ISSUE-032),
-- and dropping it would trade a loud failure for a silent orphan-row problem.
CREATE OR REPLACE FUNCTION public.ensure_shop(p_shop_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF p_shop_id IS NULL THEN RETURN NULL; END IF;
    INSERT INTO public.shops (id, name, vertical, language, tier)
    VALUES (p_shop_id, 'Auto-provisioned shop', 'vegetable', 'hinglish', 'pilot')
    ON CONFLICT (id) DO NOTHING;
    RETURN p_shop_id;
END $$;
