package com.voicetoinvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Process-wide holder for the current shop's identity.
 *
 * Every entity in this app carries a `shopId`. Until now that was the string literal
 * `"default_shop"` defaulted on each entity, and NULL on every server row -- meaning the
 * moment a second Jaipur shop installed the app, both shops would write into the same
 * undifferentiated tables (ISSUE-032). This type is the single source of that id.
 *
 * ## Current identity source: device-scoped UUID
 *
 * The id is a UUID generated once and persisted in SharedPreferences. That is enough to make
 * every local write and every server row tenant-tagged, which is what unblocks RLS.
 *
 * It is deliberately NOT the final answer: a device-scoped id cannot survive a reinstall or a
 * device change, so a shopkeeper who reinstalls would start with an empty ledger. Replacing
 * the source with a Supabase Auth phone-OTP `auth.uid()` is a one-line change in
 * [bindAuthenticatedShopId] plus a migration of existing rows -- everything downstream reads
 * through [requireShopId] and does not care where the id came from. That swap is gated on an
 * SMS provider being configured (see Docs/master_build_plan.md, open question 1).
 */
object ShopContext {

    private const val PREFS = "shop_context"
    private const val KEY_SHOP_ID = "shop_id"

    /** The value every entity defaulted to before this existed. Migrated away from, never written. */
    const val LEGACY_SHOP_ID = "default_shop"

    @Volatile
    private var cachedShopId: String? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Resolves (creating on first call) this installation's shop id and caches it.
     * Call once from `MainActivity.onCreate()` before any DAO write, and from any background
     * entry point that can run with the Activity dead (`SttWorker`, the foreground service,
     * the notification listener) -- those can start cold, so none of them may assume the
     * Activity already initialized this.
     */
    fun initialize(context: Context): String {
        cachedShopId?.let { return it }
        synchronized(this) {
            cachedShopId?.let { return it }
            val p = prefs(context)
            val existing = p.getString(KEY_SHOP_ID, null)
            val resolved = if (existing.isNullOrBlank()) {
                val generated = UUID.randomUUID().toString()
                p.edit().putString(KEY_SHOP_ID, generated).apply()
                generated
            } else {
                existing
            }
            cachedShopId = resolved
            return resolved
        }
    }

    /**
     * The current shop id. Throws rather than silently falling back to a shared constant --
     * a wrong-but-plausible shopId is how one shop's ledger ends up mixed into another's, and
     * that is unrecoverable once synced. Fail loudly at the write instead.
     */
    fun requireShopId(): String = cachedShopId
        ?: throw IllegalStateException(
            "ShopContext.initialize(context) must be called before any shop-scoped DB write. " +
                "Cold background entry points (SttWorker, AppForegroundService, " +
                "UpiNotificationListenerService) must call it in their own onCreate/doWork."
        )

    /** Null-safe read for display paths that must not crash on an uninitialized context. */
    fun shopIdOrNull(): String? = cachedShopId

    /**
     * Default `shopId` for entity constructors.
     *
     * Entities used to hard-code `shopId: String = "default_shop"`. Replacing that literal with
     * this call means every construction site -- including ones not individually updated -- stamps
     * the real shop id, because a Kotlin default expression is evaluated at construction time.
     *
     * Falls back to [LEGACY_SHOP_ID] only when identity genuinely has not been resolved yet (a
     * cold background start racing initialization). Rows written that way are picked up by the
     * migration/backfill path rather than being silently lost. Deliberately non-throwing, unlike
     * [requireShopId]: an entity default is not a safe place to crash.
     */
    fun currentOrLegacy(): String = cachedShopId ?: LEGACY_SHOP_ID

    /**
     * Point of replacement for real auth: binds a server-issued shop id (Supabase
     * `auth.uid()`), persisting it and returning the previous local id so callers can migrate
     * existing rows from it. Returns null when the id was unchanged.
     */
    fun bindAuthenticatedShopId(context: Context, authUid: String): String? {
        require(authUid.isNotBlank()) { "authUid must not be blank" }
        synchronized(this) {
            val previous = prefs(context).getString(KEY_SHOP_ID, null)
            if (previous == authUid) {
                cachedShopId = authUid
                return null
            }
            prefs(context).edit().putString(KEY_SHOP_ID, authUid).apply()
            cachedShopId = authUid
            return previous
        }
    }
}
