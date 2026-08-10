DO $$
DECLARE
    grp RECORD;
    survivor RECORD;
    loser RECORD;
    conflict_found boolean;
    loser_ids text;
    prices text;
    units text;
BEGIN
    FOR grp IN
        SELECT shop_id, canonical_key, array_agg(id) AS ids, count(*) AS cnt
        FROM catalog_items
        WHERE active
        GROUP BY shop_id, canonical_key
        HAVING count(*) > 1
    LOOP
        -- Survivor = the row with greatest updated_at; ties broken by greatest price
        SELECT * INTO survivor
        FROM catalog_items
        WHERE id = ANY(grp.ids)
        ORDER BY updated_at DESC, price DESC, id ASC
        LIMIT 1;

        -- Check if any other row in group differs in unit_id or price
        conflict_found := false;
        FOR loser IN
            SELECT * FROM catalog_items
            WHERE id = ANY(grp.ids) AND id <> survivor.id
        LOOP
            IF loser.unit_id IS DISTINCT FROM survivor.unit_id OR loser.price IS DISTINCT FROM survivor.price THEN
                conflict_found := true;
            END IF;
        END LOOP;

        IF conflict_found THEN
            SELECT string_agg(id::text, ','), string_agg(price::text, ','), string_agg(unit_id::text, ',')
              INTO loser_ids, prices, units
              FROM catalog_items
             WHERE id = ANY(grp.ids);

            RAISE NOTICE 'CANONICAL_MERGE_CONFLICT shop=% canonical=% ids=% prices=% units=%',
                grp.shop_id, grp.canonical_key, loser_ids, prices, units;
        ELSE
            -- Repoint all six FK references to survivor, then deactivate loser
            FOR loser IN
                SELECT * FROM catalog_items
                WHERE id = ANY(grp.ids) AND id <> survivor.id
            LOOP
                UPDATE transactions SET item_id = survivor.id WHERE item_id = loser.id;
                UPDATE stock_in SET item_id = survivor.id WHERE item_id = loser.id;
                UPDATE stock_ledger SET item_id = survivor.id WHERE item_id = loser.id;
                UPDATE stock_batches SET item_id = survivor.id WHERE item_id = loser.id;
                UPDATE unmatched_queue SET resolved_item_id = survivor.id WHERE resolved_item_id = loser.id;
                UPDATE unmatched_item_observations SET promoted_catalog_item_id = survivor.id WHERE promoted_catalog_item_id = loser.id;

                UPDATE catalog_items SET active = false, updated_at = now() WHERE id = loser.id;
            END LOOP;
        END IF;
    END LOOP;
END $$;
