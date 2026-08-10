# Scoped plan 4/11 — WS-A: Item & customer identity is a photo (ISSUE-122)

**Scope:** this file ONLY. Client (Kotlin/Compose) + Room migration. No server, no edge function.
**Issue number is ISSUE-122** (not 115 — 115 was already taken by a 2026-08-09 entry).

## Pre-verified facts — do not re-derive, and do not "fix" these

- `io.coil-kt:coil-compose:2.6.0` and `libs.androidx.activity.compose` are already in `app/build.gradle.kts`. **Add no dependencies.**
- `AndroidManifest.xml` declares **no** `android.permission.CAMERA`. `ActivityResultContracts.TakePicture()` delegates to the system camera app and therefore needs **no runtime permission**. **Do NOT add the CAMERA permission** — declaring it would make a runtime grant mandatory and break the flow.
- `res/xml/provider_paths.xml` already contains `<files-path name="internal_files" path="." />`, so `filesDir/photos/` is already shareable. **No manifest or provider_paths change.**
- FileProvider authority is `${applicationId}.fileprovider`.
- `AppDatabase.kt` line 36 is currently `version = 27`. Highest migration is `MIGRATION_26_27`.
- `CustomerRecord.photoPath` **already exists** in the entity, in the migration DDL, and in `CloudSyncManager` — but nothing ever writes it and there is no camera UI anywhere in the app. You are building the missing capture, not the field.

---

## A1. Room migration 27 → 28

`app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt`

1. Line 36: `version = 27,` → `version = 28, // ISSUE-122: imagePath on catalog_items`
2. Add after `MIGRATION_26_27` (~line 780), matching the existing try/catch'd style:

```kotlin
/** `catalog_items`: local on-device photo path. ISSUE-122. */
private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE catalog_items ADD COLUMN imagePath TEXT DEFAULT NULL")
        } catch (e: Exception) {
            android.util.Log.w("AppDatabase", "MIGRATION_27_28 imagePath: ${e.message}")
        }
    }
}
```

3. Append `MIGRATION_27_28` to the `.addMigrations(...)` list (~line 894).
4. If the defensive column map near line 804 lists `"imageUrl" to "TEXT"`, add `"imagePath" to "TEXT"` beside it.

## A2. Entity

`data/local/entity/CatalogItem.kt` — add directly after the `imageUrl` property:

```kotlin
/**
 * On-device photo captured by the shopkeeper. Takes precedence over [imageUrl] when both
 * exist: the shopkeeper's own photo of their own stock is a better identity than a generic
 * pack shot. Device-local — deliberately NOT synced, because the file is not.
 */
val imagePath: String? = null,
```

**Do not** add `imagePath` to `CloudSyncManager` — a local file path is meaningless on another device.

## A3. New file — `app/src/main/java/com/voicetoinvoice/app/utils/PhotoCapture.kt`

```kotlin
package com.voicetoinvoice.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Camera capture + downscale for item and customer identity photos.
 *
 * Camera output is 4-12 MB; nothing in this app renders these above ~96.dp, so every capture
 * is immediately downscaled to [MAX_DIM] and re-encoded. Storing originals would bloat the
 * app's data directory by two orders of magnitude for no visible gain.
 */
object PhotoCapture {
    private const val MAX_DIM = 512
    private const val JPEG_QUALITY = 80
    private const val DIR = "photos"

    /** Creates the parent dir and returns an empty target file. */
    fun newPhotoFile(context: Context, prefix: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${prefix}_${UUID.randomUUID()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Downscales [file] in place so its longest edge is at most [MAX_DIM].
     * Uses inSampleSize on a bounds-only first pass so a 12 MP original is never fully
     * decoded into memory — doing so on a low-RAM shop phone is an OOM.
     */
    fun compressInPlace(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIM * 2 || bounds.outHeight / sample > MAX_DIM * 2) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return

            val longest = maxOf(decoded.width, decoded.height)
            val scaled = if (longest > MAX_DIM) {
                val ratio = MAX_DIM.toFloat() / longest
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else decoded

            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        } catch (e: Exception) {
            android.util.Log.w("PhotoCapture", "compressInPlace failed for ${file.name}: ${e.message}")
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
```

## A4. New file — `app/src/main/java/com/voicetoinvoice/app/ui/components/PhotoCaptureButton.kt`

```kotlin
@Composable
fun PhotoCaptureButton(
    currentPath: String?,
    filePrefix: String,               // "item" or "customer"
    onCaptured: (String?) -> Unit,    // absolute path, or null when cleared
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
)
```

Requirements:
- `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture())`.
- The contract returns only `Boolean`, so hold the pending `File` in `remember { mutableStateOf<File?>(null) }`.
- On `success == true`: run `PhotoCapture.compressInPlace(file)` on `Dispatchers.IO` (use `rememberCoroutineScope()`), then `onCaptured(file.absolutePath)`.
- On `success == false`: delete the empty file, do **not** call back.
- Renders the current photo (Coil `AsyncImage`, `model = File(currentPath)`) clipped to `CircleShape` when set; otherwise a bordered circle with `Icons.Default.AddAPhoto`.
- Long-press when a photo exists → `PhotoCapture.delete(currentPath)` then `onCaptured(null)`. Use `Modifier.combinedClickable` with `@OptIn(ExperimentalFoundationApi::class)`.

## A5. `ItemIcon` prefers the local photo

`ui/components/ItemIcon.kt` — add one parameter, **keeping the existing parameter order** so no call site breaks:

```kotlin
@Composable
fun ItemIcon(
    itemName: String,
    imageUrl: String?,
    imagePath: String? = null,   // NEW — takes precedence over imageUrl
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
)
```

Resolution order inside: `imagePath` (as `java.io.File`, only when `exists()`) → `imageUrl` → existing category vector fallback. Coil's `AsyncImage` accepts a `File` directly as `model`.

Then pass `imagePath` at the four call sites that have a `CatalogItem` in scope:
- `ui/screens/catalog/CatalogManagementScreen.kt:74` → `imagePath = item.imagePath`
- `ui/components/ConfirmSaleDialog.kt:40` → `imagePath = parsedSale.matchedItem?.imagePath`
- `ui/components/PendingConfirmationsSheet.kt:369` → `imagePath = matchedCatalogItem?.imagePath`
- `ui/components/ManualStepperComponent.kt:41` → `imagePath = item.imagePath`

**Leave `CommandFeedSheet.kt:109` and `DailySummaryScreen.kt:326` unchanged** — both only have an item-name string, not a `CatalogItem`. Wiring those needs a lookup map and is out of scope here.

## A6. DAO

`data/local/dao/CatalogDao.kt` — add:

```kotlin
@Query("UPDATE catalog_items SET imagePath = :path, updatedAt = :timestamp WHERE id = :id")
suspend fun updateImagePath(id: String, path: String?, timestamp: Long = System.currentTimeMillis())
```

Note: deliberately does **not** set `synced = 0` — `imagePath` is device-local and never syncs, so dirtying the sync flag would push a pointless update.

## A7. Capture entry point in catalog management

`ui/screens/catalog/CatalogManagementScreen.kt` — replace the `ItemIcon` at line 74 with a `PhotoCaptureButton` that shows the current photo and, on capture, persists via `db.catalogDao().updateImagePath(item.id, path)` inside `scope.launch { withContext(Dispatchers.IO) { ... } }`, then triggers the screen's existing reload.

If the screen has no reload token, add `var reloadToken by remember { mutableStateOf(0) }`, increment it after the write, and include it in the `LaunchedEffect` key list.

## A8. Photo-first manual entry — the actual payoff

`ui/components/ManualStepperComponent.kt` already uses `LazyVerticalGrid(GridCells.Fixed(2))` with a 32.dp icon in a `Row`. Restructure each cell so the **photo leads**:

- `columns = GridCells.Adaptive(minSize = 108.dp)`
- Change the container `Modifier.height(180.dp)` to `Modifier.heightIn(max = 320.dp)` so more than one row of items is reachable.
- Inside each cell: `ItemIcon(..., size = 72.dp)` centred on its own line, then the name below it at `bodyMedium`, then price, then the existing −/+ row and Add button.

Sort the incoming list by recency at the call site so the shop's real movers surface first: `topItems.sortedByDescending { it.lastSoldAtMs ?: 0L }`. (`lastSoldAtMs` already exists and is indexed.)

## A9. Customer faces — finish what was already schema'd

- `ui/screens/customer/CustomerEditScreen.kt` — add `PhotoCaptureButton(currentPath = <customer>.photoPath, filePrefix = "customer", ...)`, persisting via `db.customerDao().update(customer.copy(photoPath = path, synced = false))`.
- `ui/screens/customer/CustomerCard.kt` — when `photoPath` is non-null and the file exists, render it as a circular `AsyncImage`; otherwise keep the existing initial-circle exactly as-is.

Photo capture is **opt-in and shopkeeper-initiated** (`Docs/voice_assistant_framework.md` §R4 — photographing customers is not an established norm in Indian retail). Do **not** auto-prompt, and do not make it required to create a customer.

## OUT OF SCOPE — do not attempt

- **Bundled stock photos for the 53 seeded catalog items.** Blocked on user-supplied licensed assets. Note for the record: `CatalogManagementScreen`'s add-item dialog already has an "Icon / Image Link (Optional)" field whose placeholder reads `"https://... or Vecteezy link"`, so remote URLs are already supported via `imageUrl` — this workstream adds the *camera* path alongside it.
- Any change to `ItemIcon`'s `getCategoryBackgroundColor` / `getCategoryVectorIcon` category palette.
- Any sync of `imagePath`.

---

## Verification (do this yourself; a build is not verification)

1. `./gradlew.bat assembleDebug` compiles.
2. **Migration safety**: the app must open on an existing v27 database without a destructive fallback. Confirm `MIGRATION_27_28` is in `addMigrations(...)` and that no `fallbackToDestructiveMigration()` was added.
3. State plainly in your report that on-device capture was **not** exercised (no phone attached to this run) — do not claim the camera flow works.

## Audit log

Add a 🟢 RESOLVED entry for **ISSUE-122** in `Docs/audit.md`'s single `### 🟢 RESOLVED ISSUES` section (there is exactly one — do not create another). Record what was verified (compile, migration registration) vs. what was not (actual camera capture on a device). Do not write "Verified" for anything you did not observe.

## Deviations

End with a "Deviations" section. If none, say "None."
