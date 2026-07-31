-- Migration: cloud mirrors for stock_ledger, stock_batches, customer_payments, shop_learning
-- (Docs/remaining_work_plan.md §1.2 -- these were local-only, so the on-hand/WAC/expiry/
-- learning state a shopkeeper built up never reached the server mirror.)
--
-- shop_id is TEXT with no FK to public.shops, matching the `customers` table's convention
-- (20260730000000_create_customers.sql), not the older UUID-FK-to-shops convention used by
-- catalog_items/transactions. ShopContext issues a device-scoped UUID string that has no
-- corresponding row in `shops` (only the sentinel default-shop row exists there) -- a UUID FK
-- would reject every real sync as a foreign-key violation. RLS stays permissive here, same as
-- stock_in/customers; real per-shop isolation is blocked on the phone-OTP auth work in
-- Docs/remaining_work_plan.md §3.1 (ISSUE-032), not on this migration.

-- daily_rollups is deliberately NOT mirrored here -- it is a derived cache
-- (DailyRollupRepository recomputes it from stock_ledger/transactions), so syncing it would
-- just be shipping a recomputable value over the wire for no read path that needs it server-side.

CREATE TABLE IF NOT EXISTS public.stock_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id TEXT NOT NULL DEFAULT 'default_shop',
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    delta_qty DOUBLE PRECISION NOT NULL,
    reason TEXT NOT NULL,
    unit_cost DOUBLE PRECISION,
    ref_id TEXT,
    batch_id TEXT,
    note TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.stock_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow all access to stock_ledger" ON public.stock_ledger FOR ALL USING (true) WITH CHECK (true);

CREATE INDEX IF NOT EXISTS idx_stock_ledger_shop_id ON public.stock_ledger(shop_id);
CREATE INDEX IF NOT EXISTS idx_stock_ledger_item_id ON public.stock_ledger(item_id);
CREATE INDEX IF NOT EXISTS idx_stock_ledger_occurred_at ON public.stock_ledger(occurred_at);

CREATE TABLE IF NOT EXISTS public.stock_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id TEXT NOT NULL DEFAULT 'default_shop',
    item_id UUID REFERENCES public.catalog_items(id),
    item_name TEXT NOT NULL,
    received_qty DOUBLE PRECISION NOT NULL,
    remaining_qty DOUBLE PRECISION NOT NULL,
    unit_cost DOUBLE PRECISION,
    expiry_date TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.stock_batches ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow all access to stock_batches" ON public.stock_batches FOR ALL USING (true) WITH CHECK (true);

CREATE INDEX IF NOT EXISTS idx_stock_batches_shop_id ON public.stock_batches(shop_id);
CREATE INDEX IF NOT EXISTS idx_stock_batches_item_id ON public.stock_batches(item_id);
CREATE INDEX IF NOT EXISTS idx_stock_batches_expiry_date ON public.stock_batches(expiry_date);

CREATE TABLE IF NOT EXISTS public.customer_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id TEXT NOT NULL DEFAULT 'default_shop',
    customer_id UUID REFERENCES public.customers(id),
    customer_name TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    mode TEXT NOT NULL DEFAULT 'CASH',
    note TEXT,
    job_id TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.customer_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow all access to customer_payments" ON public.customer_payments FOR ALL USING (true) WITH CHECK (true);

CREATE INDEX IF NOT EXISTS idx_customer_payments_shop_id ON public.customer_payments(shop_id);
CREATE INDEX IF NOT EXISTS idx_customer_payments_customer_id ON public.customer_payments(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_payments_received_at ON public.customer_payments(received_at);

-- Composite PK mirrors the Room entity (shopId, kind, key) exactly -- there is no separate
-- surrogate id locally, so upserts key on the same triple here.
CREATE TABLE IF NOT EXISTS public.shop_learning (
    shop_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    hit_count INT NOT NULL DEFAULT 1,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.6,
    PRIMARY KEY (shop_id, kind, key)
);

ALTER TABLE public.shop_learning ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Allow all access to shop_learning" ON public.shop_learning FOR ALL USING (true) WITH CHECK (true);

CREATE INDEX IF NOT EXISTS idx_shop_learning_shop_id ON public.shop_learning(shop_id);
