-- Migration: 20260811000000_add_catalog_concept.sql
-- ISSUE-126: base-commodity ("concept") identity on catalog items.
--
-- Lets a spoken generic word resolve to the shop's own SKU without enumerating surface
-- spellings: 'rice' / 'chawal' / 'चावल' all carry concept 'rice', which joins to whatever
-- rice that shop actually stocks. See Docs/concept_layer_plan.md.
--
-- Additive and nullable by design. A row with concept IS NULL behaves exactly as it does
-- today, so this migration cannot regress an unbackfilled shop.

ALTER TABLE public.catalog_items
    ADD COLUMN IF NOT EXISTS concept TEXT;

-- Resolution always queries "SKUs in THIS shop carrying THIS concept", never concept alone.
CREATE INDEX IF NOT EXISTS idx_catalog_items_shop_concept
    ON public.catalog_items (shop_id, concept);

COMMENT ON COLUMN public.catalog_items.concept IS
    'Base commodity id (rice, milk, dal). Language- and brand-independent. Assigned once per SKU, never on the speech path. NULL = unassigned, resolves as before.';
