-- ISSUE-115: Supabase performance advisor, 2026-08-09 — 11 foreign keys with no covering
-- index and one duplicate index. CONCURRENTLY is deliberately NOT used: these tables are
-- small and the migration runner wraps statements in a transaction, which CONCURRENTLY
-- cannot run inside.
CREATE INDEX IF NOT EXISTS idx_catalog_items_unit_id            ON public.catalog_items(unit_id);
CREATE INDEX IF NOT EXISTS idx_credits_customer_id              ON public.credits(customer_id);
CREATE INDEX IF NOT EXISTS idx_credits_linked_transaction_id    ON public.credits(linked_transaction_id);
CREATE INDEX IF NOT EXISTS idx_customers_merged_into_id         ON public.customers(merged_into_id);
CREATE INDEX IF NOT EXISTS idx_shops_user_id                    ON public.shops(user_id);
CREATE INDEX IF NOT EXISTS idx_stock_in_item_id                 ON public.stock_in(item_id);
CREATE INDEX IF NOT EXISTS idx_stock_in_shop_id                 ON public.stock_in(shop_id);
CREATE INDEX IF NOT EXISTS idx_transactions_customer_id         ON public.transactions(customer_id);
CREATE INDEX IF NOT EXISTS idx_transactions_item_id             ON public.transactions(item_id);
CREATE INDEX IF NOT EXISTS idx_unmatched_queue_resolved_item_id ON public.unmatched_queue(resolved_item_id);
CREATE INDEX IF NOT EXISTS idx_unmatched_queue_shop_id          ON public.unmatched_queue(shop_id);

-- Identical to idx_stt_job_logs_job_id_unique.
DROP INDEX IF EXISTS public.idx_stt_job_logs_unique_job_id;
