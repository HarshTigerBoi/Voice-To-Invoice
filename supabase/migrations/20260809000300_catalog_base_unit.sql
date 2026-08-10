ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS base_unit text;

UPDATE catalog_items c
   SET base_unit = COALESCE(u.base_unit, c.unit_id)
  FROM item_units u
 WHERE u.id = c.unit_id AND c.base_unit IS DISTINCT FROM COALESCE(u.base_unit, c.unit_id);

UPDATE catalog_items SET base_unit = unit_id WHERE base_unit IS NULL;

CREATE INDEX IF NOT EXISTS idx_catalog_items_identity_unit
    ON catalog_items (shop_id, canonical_key, base_unit) WHERE active;
