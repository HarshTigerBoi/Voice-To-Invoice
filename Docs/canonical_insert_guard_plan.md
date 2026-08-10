# Canonical Insert Guard — ISSUE-107 follow-up (v2, corrected)

**Author:** Claude Code · **Date:** 2026-08-09 · **Implementer:** Antigravity

> **v1 of this plan was wrong and was correctly rejected.** It told you to add `canonical_key`
> logic to `catalog_items` insert sites in `index.ts`. There are none — **verified**: the only two
> `from('catalog_items')` calls in `index.ts` are a `SELECT` (line 1066) and a price `UPDATE`
> (line 2471). Stopping on that step was the right call. This version targets the real write path.

## 0. Where catalog rows are actually created

Inside the Postgres function `public.record_unmatched_item_observation`, called from
`index.ts:2081` via `supabase.rpc(...)`. **Verified** by reading `prosrc` from the live DB
(project `lyowklxsbfznnqridtgr`). Current signature:

```
record_unmatched_item_observation(
  p_shop_id uuid, p_phonetic_key text, p_item_name text,
  p_unit_id text, p_job_id text, p_threshold integer)
RETURNS TABLE(is_in_catalog boolean, catalog_item_id uuid, newly_promoted boolean)
```

It **already** guards against duplicates before inserting:

```sql
SELECT id INTO v_new_item_id
    FROM public.catalog_items
    WHERE shop_id = p_shop_id
      AND active = true
      AND normalized_name_distance(name, p_item_name) <= catalog_learning_name_agreement_max()
    ORDER BY normalized_name_distance(name, p_item_name) ASC
    LIMIT 1;

IF v_new_item_id IS NULL THEN
    INSERT INTO public.catalog_items (shop_id, name, unit_id, price, active)
    VALUES (p_shop_id, p_item_name, v_unit_id, 0, true)
    RETURNING id INTO v_new_item_id;
END IF;
```

**The guard is literal, not canonical.** `normalized_name_distance('अदरक', 'Adrak')` is ~1.0 — two
scripts share no characters — so the guard never fires across scripts and a second row is created.
That is the actual duplicate factory. The lexicon lives in TypeScript/Kotlin and cannot be called
from PL/pgSQL, so the canonical must be **passed in**.

## 1. Scope

**In scope:** one new RPC parameter, the adoption lookup, the INSERT, and the one call site.
**Out of scope:** the lexicon contents, the segmenter, margin logic, Room, the two existing
migrations, every confidence constant. Do not touch them.

## 2. Steps

### 2.1 New migration `supabase/migrations/20260809000200_canonical_promotion_guard.sql`

`CREATE OR REPLACE FUNCTION public.record_unmatched_item_observation` with the **same body** as the
live version, changed in exactly three ways:

1. Append one parameter, last, with a default so existing callers keep working:
   `p_canonical_key text DEFAULT NULL`
2. Replace the adoption lookup with a canonical-first, distance-second version:
   ```sql
   -- Adopt an existing row for the SAME ITEM before creating a near-duplicate. The
   -- canonical key comes from the lexicon (lexicon.ts / ItemLexicon.kt) because PL/pgSQL
   -- cannot compute it: the literal distance guard below is blind across scripts
   -- (normalized_name_distance('अदरक','Adrak') ~ 1.0), which is what let one item become
   -- two rows. ISSUE-107.
   IF p_canonical_key IS NOT NULL AND p_canonical_key <> '' THEN
       SELECT id INTO v_new_item_id
           FROM public.catalog_items
           WHERE shop_id = p_shop_id
             AND active = true
             AND canonical_key = p_canonical_key
           ORDER BY price DESC
           LIMIT 1;
   END IF;

   IF v_new_item_id IS NULL THEN
       -- existing literal-distance guard, unchanged
       SELECT id INTO v_new_item_id
           FROM public.catalog_items
           WHERE shop_id = p_shop_id
             AND active = true
             AND normalized_name_distance(name, p_item_name) <= catalog_learning_name_agreement_max()
           ORDER BY normalized_name_distance(name, p_item_name) ASC
           LIMIT 1;
   END IF;
   ```
3. Stamp the canonical on the INSERT:
   ```sql
   INSERT INTO public.catalog_items (shop_id, name, unit_id, price, active, canonical_key)
   VALUES (p_shop_id, p_item_name, v_unit_id, 0, true,
           NULLIF(p_canonical_key, ''))
   RETURNING id INTO v_new_item_id;
   ```

Retrieve the current body with
`SELECT prosrc FROM pg_proc WHERE proname = 'record_unmatched_item_observation'`
and preserve everything else **byte for byte** — the advisory lock, the `FOR UPDATE`, the
`contributing_job_ids` handling, the three `RETURN QUERY` shapes. Do not "improve" any of it.

Keep the same `SECURITY` and `LANGUAGE` clauses the live function has.

This migration must run **after** `20260809000000_canonical_catalog_dedupe.sql`, which creates the
`canonical_key` column. The filename ordering already ensures that.

### 2.2 Pass the canonical from `index.ts`

At the `supabase.rpc('record_unmatched_item_observation', {...})` call (around line 2081), add:

```ts
p_canonical_key: canonicalOf(<the same name already passed as p_item_name>),
```

`canonicalOf` is already imported at `index.ts:25`. Use the **same** expression that feeds
`p_item_name` — do not re-derive the name from another variable.

### 2.3 Do not change `supabase/schema.sql` by hand

`schema.sql` is the source of truth for the schema. Update its copy of
`record_unmatched_item_observation` to match the new definition exactly, so the file and the
database do not diverge. This is a copy, not a redesign.

## 3. Verify

1. `cd supabase/functions/process-voice-job && node --experimental-strip-types --test phonetic_test.ts item_resolution_test.ts` — all 38 must pass.
2. `grep -c "p_canonical_key" supabase/functions/process-voice-job/index.ts` must be ≥ 1.
3. `grep -c "canonical_key" supabase/migrations/20260809000200_canonical_promotion_guard.sql` must be ≥ 3.
4. Do **not** deploy, do **not** apply the migration, do **not** run gradle. All three are handled
   separately.

## 4. Deviations

End with a Deviations section. If a named symbol or line does not exist, stop on that step, finish
everything that does not depend on it, and quote the plan line against what the code shows — as you
correctly did with v1 of this plan. Do not report "None" without having run every check in §3.
