ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS canonical_key text;

-- Baseline: an item's own folded name. The app/edge function overwrite this with the
-- lexicon canonical on every write (see D3), which is what collapses cross-script pairs.
UPDATE catalog_items
   SET canonical_key = lower(regexp_replace(btrim(name), '\s+', ' ', 'g'))
 WHERE canonical_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_catalog_items_canonical
    ON catalog_items (shop_id, canonical_key) WHERE active;
