package com.voicetoinvoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.ui.screens.catalog.CatalogManagementScreen
import com.voicetoinvoice.app.ui.screens.home.HomeScreen
import com.voicetoinvoice.app.ui.screens.onboarding.OnboardingScreen
import com.voicetoinvoice.app.ui.screens.price.PriceUpdateScreen
import com.voicetoinvoice.app.ui.screens.settings.SettingsScreen
import com.voicetoinvoice.app.ui.screens.stockin.StockInScreen
import com.voicetoinvoice.app.ui.screens.summary.DailySummaryScreen
import com.voicetoinvoice.app.ui.screens.udhaar.UdhaarScreen
import com.voicetoinvoice.app.ui.theme.VoiceToInvoiceTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.data.sync.SyncEngine
import com.voicetoinvoice.app.domain.validation.SaleValidation
import com.voicetoinvoice.app.network.CloudSyncManager
import com.voicetoinvoice.app.network.SupabaseConfig
import com.voicetoinvoice.app.ui.screens.customer.CustomerListScreen
import com.voicetoinvoice.app.ui.screens.customer.CustomerEditScreen
import com.voicetoinvoice.app.ui.screens.customer.CustomerDetailScreen
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.CreditStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor
import com.voicetoinvoice.app.ui.components.AssistantFloatingButton
import com.voicetoinvoice.app.ui.screens.logs.DiagnosticLogsScreen
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.ShopIdBackfill
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.action.ActionExecutor
import com.voicetoinvoice.app.domain.action.ActionKind
import com.voicetoinvoice.app.domain.action.PendingActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.AddCircle
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resolve shop identity BEFORE the database is touched: every entity carries a shopId,
        // and ShopContext.requireShopId() throws rather than silently falling back to a shared
        // "default_shop" -- a wrong-but-plausible shopId is how one shop's ledger ends up mixed
        // into another's, which is unrecoverable once synced.
        ShopContext.initialize(this)
        database = AppDatabase.getInstance(this)

        lifecycleScope.launch(Dispatchers.IO) {
            // Must run before the first sync sweep: it re-tenants rows stranded on the legacy
            // "default_shop" literal and clears catalog_items.synced so the priced catalog
            // re-uploads under the real shop id.
            ShopIdBackfill.runIfNeeded(this@MainActivity, database)
            try {
                android.util.Log.e("MAIN_ACTIVITY", "🚀 Starting deduplicateCatalog from lifecycleScope...")
                database.catalogDao().deduplicateCatalog(
                    database.transactionDao(),
                    database.stockLedgerDao(),
                    database.stockInDao(),
                    database.stockBatchDao(),
                    database.unmatchedQueueDao()
                )
                android.util.Log.e("MAIN_ACTIVITY", "✅ deduplicateCatalog finished!")
            } catch (e: Exception) {
                android.util.Log.e("MAIN_ACTIVITY", "❌ deduplicateCatalog failed!", e)
            }
        }

        enableEdgeToEdge()

        try {
            val serviceIntent = android.content.Intent(this, com.voicetoinvoice.app.service.AppForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            VoiceToInvoiceTheme {
                MainAppScreen(database)
            }
        }
    }
}

enum class Screen {
    ONBOARDING, HOME, CATALOG, UDHAAR, SUPPLIER, PRICE_UPDATE, STOCK_IN, SUMMARY, SETTINGS, DIAGNOSTIC_LOGS, CUSTOMER_LIST, CUSTOMER_EDIT, CUSTOMER_DETAIL, REPORTS, EXPENSE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(database: AppDatabase) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    /** Single write path for stock quantity and weighted-average cost. */
    val stockLedgerRepo = remember { StockLedgerRepository(database) }
    val dailyRollupRepo = remember { com.voicetoinvoice.app.data.repository.DailyRollupRepository(database) }

    // Rollups are a CACHE, not a source of truth, so they self-heal here rather than needing a
    // scheduled repair job: a short recent window on every launch (cheap, indexed, covers a
    // process killed mid-write) and, once ever, a full-history repair so upgrading shops see
    // correct reports immediately instead of only from the upgrade date forward.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                android.util.Log.e("MAIN_ACTIVITY", "🚀 Starting deduplicateCatalog from LaunchedEffect...")
                database.catalogDao().deduplicateCatalog(
                    database.transactionDao(),
                    database.stockLedgerDao(),
                    database.stockInDao(),
                    database.stockBatchDao(),
                    database.unmatchedQueueDao()
                )
                android.util.Log.e("MAIN_ACTIVITY", "✅ deduplicateCatalog completed successfully!")
            } catch (e: Exception) {
                android.util.Log.e("MAIN_ACTIVITY", "❌ deduplicateCatalog failed with exception: ${e.message}", e)
            }
            database.alertDismissalDao().purgeExpired(System.currentTimeMillis())
            com.voicetoinvoice.app.domain.action.BillBuilder.purgeOld(context)
            val prefs = context.getSharedPreferences("rollup_backfill", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("full_history_done", false)) {
                val earliestTx = database.transactionDao().getEarliestTimestamp()
                val earliestStock = database.stockLedgerDao().getEarliestTimestamp()
                val historyStart = listOfNotNull(earliestTx, earliestStock).minOrNull()
                    ?: System.currentTimeMillis()
                dailyRollupRepo.repairRange(dailyRollupRepo.dayStartFor(historyStart), System.currentTimeMillis() + 1)
                prefs.edit().putBoolean("full_history_done", true).apply()
            } else {
                val twoDaysAgo = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
                dailyRollupRepo.repairRange(dailyRollupRepo.dayStartFor(twoDaysAgo), System.currentTimeMillis() + 1)
            }
        }
    }

    /*
     * Voice-resolved actions ("रमेश को बिल भेजो") are resolved in SttWorker but must be LAUNCHED
     * from here -- starting a WhatsApp intent needs a UI context, and the shopkeeper's own tap on
     * Send is the consent checkpoint.
     *
     * This collector is the wiring whose absence was the original bug: ActionExecutor was complete
     * and had zero call sites, so every action command answered "यह काम अभी नहीं कर सकता".
     */
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        PendingActions.actions.collect { action ->
            val result = when (action.kind) {
                ActionKind.CALL -> ActionExecutor.dialCustomer(context, action.customer.phone.orEmpty())

                ActionKind.SEND_REMINDER -> ActionExecutor.sendPaymentReminder(
                    context = context,
                    customer = action.customer,
                    balance = action.amount
                )

                ActionKind.SEND_BILL -> {
                    // Most recent bill for this customer; fall back to a reminder when they have
                    // no bill yet, rather than opening an empty message.
                    val lastBillId = withContext(Dispatchers.IO) {
                        database.transactionDao()
                            .getRecentBillIdForCustomer(action.customer.id)
                    }
                    val lines = if (lastBillId != null) {
                        withContext(Dispatchers.IO) { database.transactionDao().getByBillId(lastBillId) }
                    } else emptyList()

                    if (lines.isEmpty()) {
                        ActionExecutor.sendPaymentReminder(context, action.customer, action.amount)
                    } else {
                        ActionExecutor.sendBill(
                            context = context,
                            customer = action.customer,
                            lines = lines,
                            previousBalance = action.amount.takeIf { it > 0.0 }
                        )
                    }
                }
            }
            // Never fail silently: "WhatsApp not installed" must look different from success.
            actionFeedback = when (result) {
                is ActionExecutor.Result.Launched -> null
                is ActionExecutor.Result.AppMissing -> result.message
                is ActionExecutor.Result.Failed -> result.message
            }
            PendingActions.consume()
        }
    }

    actionFeedback?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionFeedback = null },
            confirmButton = { TextButton(onClick = { actionFeedback = null }) { Text("ठीक है") } },
            title = { Text("भेजा नहीं जा सका") },
            text = { Text(msg) }
        )
    }

    val catalogState by database.catalogDao().getActiveCatalog().collectAsState(initial = emptyList())
    val stockLevelsState by database.catalogDao().getStockLevels().collectAsState(initial = emptyList())
    val stockLevelsMap = remember(stockLevelsState) { stockLevelsState.associate { it.itemId to it.onHand } }

    val suppliersState by database.supplierDao().getAllSuppliers().collectAsState(initial = emptyList())

    // Compute today's midnight timestamp so queries are scoped to today only
    val todayMidnight = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val todayTransactionsState by database.transactionDao().getTodayTransactions(todayMidnight).collectAsState(initial = emptyList())
    val todayTotalState by database.transactionDao().getTodayTotalSales(todayMidnight).collectAsState(initial = 0.0)

    var summaryOrigin by remember { mutableStateOf(Screen.CUSTOMER_LIST) }
    var reportsOrigin by remember { mutableStateOf(Screen.HOME) }
    var repeatOrderLines by remember { mutableStateOf<List<TransactionRecord>?>(null) }
    var summaryRangeMode by remember { mutableStateOf(com.voicetoinvoice.app.ui.screens.summary.RangeMode.DAY) }
    val summaryRangeBounds = remember(summaryRangeMode, todayMidnight) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (summaryRangeMode) {
                com.voicetoinvoice.app.ui.screens.summary.RangeMode.WEEK -> add(Calendar.DAY_OF_YEAR, -7)
                com.voicetoinvoice.app.ui.screens.summary.RangeMode.MONTH -> add(Calendar.DAY_OF_YEAR, -30)
                com.voicetoinvoice.app.ui.screens.summary.RangeMode.DAY -> {}
            }
        }
        cal.timeInMillis to (todayMidnight + 24 * 60 * 60 * 1000)
    }
    val rangeTransactionsState by remember(summaryRangeBounds) {
        database.transactionDao().getTransactionsBetween(summaryRangeBounds.first, summaryRangeBounds.second)
    }.collectAsState(initial = emptyList())
    val creditsState by database.creditDao().getAllCredits().collectAsState(initial = emptyList())
    val unmatchedState by database.unmatchedQueueDao().getPendingItems().collectAsState(initial = emptyList())
    val customersState by database.customerDao().getActiveCustomers().collectAsState(initial = emptyList())

    // Fix 3: CustomerListScreen/CustomerDetailScreen need outstanding-per-customer, not just
    // the raw credits list -- previously this map was never computed and CustomerListScreen
    // always rendered every balance as ₹0.
    val outstandingByCustomer = remember(creditsState) {
        creditsState
            .filter { it.customerId != null && it.status != CreditStatus.PAID }
            .groupBy { it.customerId!! }
            .mapValues { (_, credits) -> credits.sumOf { it.amount } }
    }

    var selectedCustomerId by remember { mutableStateOf<String?>(null) }
    val selectedCustomer = remember(selectedCustomerId, customersState) {
        customersState.find { it.id == selectedCustomerId }
    }
    // The "+ नया ग्राहक" flow launched from the udhaar picker (a credit sale with no
    // resolved customer yet) needs to link the newly-created customer back to that credit
    // once saved -- otherwise the picker's + button silently drops the link (Fix 2/3).
    var pendingCreditLinkId by remember { mutableStateOf<String?>(null) }
    val selectedCustomerLedger = remember(selectedCustomerId) {
        if (selectedCustomerId != null) database.customerDao().getLedgerFor(selectedCustomerId!!) else null
    }
    val selectedCustomerLedgerState by (selectedCustomerLedger ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val duplicateCustomersState by produceState(initialValue = emptyList<Pair<CustomerRecord, CustomerRecord>>(), customersState) {
        value = database.customerDao().findLikelyDuplicates()
    }

    val scope = rememberCoroutineScope()
    val syncEngine = remember {
        SyncEngine(
            database.transactionDao(), database.stockInDao(), database.catalogDao(),
            database.creditDao(), database.sttJobDao(), database.supplierDao(), database.customerDao(),
            database.stockLedgerDao(), database.stockBatchDao(), database.customerPaymentDao(), database.shopLearningDao()
        )
    }

    // Fix 7 (gap_analysis_and_fix_plan.md): a single shared audio pipeline for the WHOLE
    // app, owned here and passed down to every screen + the assistant button. Each screen
    // previously created its OWN RollingAudioBuffer/AudioRecorder -- giving the assistant a
    // fourth independent one (on top of Home's and StockIn's) would mean multiple
    // concurrent AudioRecord instances fighting for the same microphone. One instance,
    // started once, for the app's lifetime.
    val sharedAudioRecorder = remember { AudioRecorder(context) }
    val sharedRollingBuffer = remember { RollingAudioBuffer(context) }
    // Wire the Compose-owned buffer as the static singleton so SpeechOutput (in SttWorker)
    // calls setSuppressed() on the same instance that is actually recording.
    LaunchedEffect(sharedRollingBuffer) {
        RollingAudioBuffer.setSharedInstance(sharedRollingBuffer)
    }
    // Each intent group must track its own lastConsumedEndMs independently.
    // SALE + CREDIT_SALE (HomeScreen) and STOCK_IN (StockInScreen) each call
    // PttWindowLedger.getInstance() internally -- that singleton is correct for
    // keeping those two screens' presses from overlapping each other.
    // The ASSISTANT button MUST have its own isolated ledger: if it shared the
    // sale singleton, every ASSISTANT commitWindow() would advance lastEndMs and
    // cause the next SALE press's clampedStartMs to jump past the actual speech,
    // and vice versa -- giving the assistant an empty/wrong audio window.
    val assistantPttWindowLedger = remember { PttWindowLedger() }
    val salePttBurstCoalescer = remember(sharedRollingBuffer) {
        PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
    }
    val stockPttBurstCoalescer = remember(sharedRollingBuffer) {
        PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
    }
    val assistantPttBurstCoalescer = remember(sharedRollingBuffer) {
        PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
    }
    val sharedOnDeviceRecognizer = remember { OnDeviceSpeechRecognizer(context) }
    val sharedBackgroundProcessor = remember { BackgroundSttProcessor(context, scope) }
    val sharedPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // §5 (Docs/assistant_speed_and_pipeline_fix_plan.md): bound to the Activity
    // LIFECYCLE, not to composition -- the previous DisposableEffect(Unit) only tore
    // down on onDispose, which fires when the Activity is *destroyed*. Pressing Home
    // stops the Activity but does not destroy it, so the microphone stayed open
    // indefinitely in the background (confirmed: AppForegroundService is START_STICKY,
    // keeping the process and the capture thread alive for as long as Android allows).
    // From lifecycle-runtime-compose (already a project dependency) -- the canonical
    // home for this API; the older androidx.compose.ui.platform.LocalLifecycleOwner is
    // deprecated and not guaranteed to still exist on this project's Compose BOM.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val coldStarted = sharedRollingBuffer.smartStart()
                    if (coldStarted) {
                        // The ring was wiped and totalBytesWritten reset to 0. Every timestamp the
                        // ledger and the coalescers hold refers to the previous epoch's bytes.
                        PttWindowLedger.getInstance().reset()
                        assistantPttWindowLedger.reset()
                        salePttBurstCoalescer.reset()
                        stockPttBurstCoalescer.reset()
                        assistantPttBurstCoalescer.reset()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> sharedRollingBuffer.stopRollingBuffer()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sharedRollingBuffer.stopRollingBuffer()
            sharedOnDeviceRecognizer.release()
        }
    }

    // §3.2: kept hot for the assistant's fast path (LedgerSnapshot) -- started once,
    // for the app's lifetime, same pattern as the shared audio pipeline above.
    LaunchedEffect(Unit) {
        com.voicetoinvoice.app.domain.query.LedgerSnapshot.start(scope, database)
    }

    fun sendWhatsAppReminder(customerName: String, amount: Double, phone: String?) {
        val message = "नमस्ते ${customerName} जी, आपका ₹${amount.toInt()} का बकाया (Udhaar) बाकी है। कृपया भुगतान करें। धन्यवाद!"
        val cleanPhone = phone?.replace(Regex("[^0-9]"), "")?.trim().orEmpty()

        if (!cleanPhone.isNullOrBlank()) {
            val normalizedPhone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
            val encodedMessage = java.net.URLEncoder.encode(message, "UTF-8")
            val uri = android.net.Uri.parse("https://wa.me/$normalizedPhone?text=$encodedMessage")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
            }
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                try {
                    val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    context.startActivity(genericIntent)
                    return
                } catch (e2: Exception) {
                    android.util.Log.w("MainActivity", "Failed to launch wa.me link directly: ${e2.message}")
                }
            }
        }

        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, message)
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "Send Udhaar Reminder via")
        context.startActivity(shareIntent)
    }

    // Trigger immediate background sync sweep on screen startup
    LaunchedEffect(Unit) {
        scope.launch { syncEngine.syncAllUnsynced() }
    }

    Scaffold(
        bottomBar = {
            if (currentScreen != Screen.ONBOARDING) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "बेचो") },
                        label = { Text("बेचो ₹") },
                        selected = currentScreen == Screen.HOME,
                        onClick = { currentScreen = Screen.HOME }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.AddCircle, contentDescription = "माल") },
                        label = { Text("माल +") },
                        selected = currentScreen == Screen.STOCK_IN,
                        onClick = { currentScreen = Screen.STOCK_IN }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.AccountBox, contentDescription = "हिसाब") },
                        label = { Text("हिसाब 📊") },
                        selected = currentScreen == Screen.CUSTOMER_LIST || currentScreen == Screen.UDHAAR || currentScreen == Screen.SUMMARY,
                        onClick = { currentScreen = Screen.CUSTOMER_LIST }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
            when (currentScreen) {
                Screen.ONBOARDING -> {
                    OnboardingScreen(onOnboardingComplete = { currentScreen = Screen.HOME })
                }
                Screen.HOME -> {
                    HomeScreen(
                        todayTotalSales = todayTotalState ?: 0.0,
                        catalog = catalogState,
                        onNavigateToLogs = { currentScreen = Screen.DIAGNOSTIC_LOGS },
                        onNavigateToSummary = { summaryOrigin = Screen.HOME; currentScreen = Screen.SUMMARY },
                        onNavigateToReports = { reportsOrigin = Screen.HOME; currentScreen = Screen.REPORTS },
                        onAddNewCustomerForCredit = { creditId ->
                            pendingCreditLinkId = creditId
                            currentScreen = Screen.CUSTOMER_EDIT
                        },
                        sharedAudioRecorder = sharedAudioRecorder,
                        sharedRollingAudioBuffer = sharedRollingBuffer,
                        sharedPttBurstCoalescer = salePttBurstCoalescer,
                        sharedOnDeviceRecognizer = sharedOnDeviceRecognizer,
                        sharedBackgroundProcessor = sharedBackgroundProcessor,
                        onConfirmSale = { parsedSale ->
                            scope.launch {
                                var targetItem = parsedSale.matchedItem
                                if (targetItem != null) {
                                    when (parsedSale.priceIntent) {
                                        com.voicetoinvoice.app.domain.parser.PriceIntent.RATE_UPDATE -> {
                                            val newRate = if (parsedSale.updatedUnitPrice > 0) parsedSale.updatedUnitPrice else targetItem.price
                                            targetItem = targetItem.copy(price = newRate, synced = false)
                                            database.catalogDao().insertOrUpdate(targetItem)
                                            syncEngine.syncAllUnsynced()
                                        }
                                        com.voicetoinvoice.app.domain.parser.PriceIntent.BULK_SALE_TOTAL,
                                        com.voicetoinvoice.app.domain.parser.PriceIntent.NONE,
                                        com.voicetoinvoice.app.domain.parser.PriceIntent.AMBIGUOUS_UNTRUSTED -> {
                                            val effectiveUnitPrice = if (parsedSale.priceOverridden && parsedSale.updatedUnitPrice > 0) parsedSale.updatedUnitPrice else targetItem.price

                                            // Brand-new item exception: seed initial catalog price if standing catalog price was 0.0
                                            if (targetItem.price == 0.0 && effectiveUnitPrice > 0.0) {
                                                targetItem = targetItem.copy(price = effectiveUnitPrice, synced = false)
                                                database.catalogDao().insertOrUpdate(targetItem)
                                            }

                                            val tx = TransactionRecord(
                                                shopId = ShopContext.requireShopId(),
                                                itemId = targetItem.id,
                                                itemName = targetItem.name,
                                                quantity = parsedSale.quantity,
                                                priceAtSale = effectiveUnitPrice,
                                                total = parsedSale.estimatedTotal,
                                                rawTranscript = parsedSale.rawTranscript,
                                                costAtSale = targetItem.avgCostPrice,
                                                billId = java.util.UUID.randomUUID().toString()
                                            )
                                            database.transactionDao().insert(tx)
                                            stockLedgerRepo.recordSale(
                                                itemId = targetItem.id,
                                                itemName = targetItem.name,
                                                qty = parsedSale.quantity,
                                                transactionId = tx.id,
                                                occurredAtMs = tx.timestamp,
                                                total = tx.total,
                                                costAtSale = tx.costAtSale
                                            )
                                            syncEngine.syncAllUnsynced()
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
                Screen.CATALOG -> {
                    CatalogManagementScreen(
                        catalog = catalogState,
                        stockLevels = stockLevelsMap,
                        onAddItem = { name, unitId, price ->
                            scope.launch {
                                val norm = name.trim().lowercase()
                                val existing = database.catalogDao().getAllCatalogList().find { it.name.trim().lowercase() == norm }
                                if (existing != null) {
                                    database.catalogDao().insertOrUpdate(existing.copy(name = name, unitId = unitId, price = price, active = true, synced = false))
                                } else {
                                    database.catalogDao().insertOrUpdate(CatalogItem(name = name, unitId = unitId, price = price))
                                }
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onSetThreshold = { item, threshold ->
                            scope.launch {
                                database.catalogDao().updateLowStockThreshold(item.id, threshold)
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.HOME }
                    )
                }
                Screen.UDHAAR -> {
                    UdhaarScreen(
                        credits = creditsState,
                        onAddCredit = { name, amount ->
                            scope.launch {
                                database.creditDao().insertOrUpdate(com.voicetoinvoice.app.data.local.entity.CreditRecord(customerName = name, amount = amount))
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onMarkPaid = { credit ->
                            scope.launch {
                                database.creditDao().updateStatus(credit.id, com.voicetoinvoice.app.data.local.entity.CreditStatus.PAID)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onSendReminder = { credit ->
                            val linkedCustomer = customersState.find { it.id == credit.customerId }
                            sendWhatsAppReminder(
                                customerName = credit.customerName,
                                amount = credit.amount,
                                phone = linkedCustomer?.phone
                            )
                        },
                        onNavigateBack = { currentScreen = Screen.CUSTOMER_LIST }
                    )
                }
                Screen.SUPPLIER -> {
                    com.voicetoinvoice.app.ui.screens.supplier.SupplierScreen(
                        suppliers = suppliersState,
                        onAddSupplier = { name, phone ->
                            scope.launch {
                                database.supplierDao().insertOrUpdate(com.voicetoinvoice.app.data.local.entity.SupplierRecord(name = name, phone = phone))
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onSettleBalance = { supplier ->
                            scope.launch {
                                database.supplierDao().settleBalance(supplier.id)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.CUSTOMER_LIST }
                    )
                }
                Screen.PRICE_UPDATE -> {
                    PriceUpdateScreen(
                        catalog = catalogState,
                        onUpdatePrice = { item, newPrice ->
                            scope.launch {
                                database.catalogDao().updatePrice(item.id, newPrice)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onRemoveItem = { item ->
                            scope.launch {
                                // Soft delete — `transactions.itemId` still references this
                                // row, so it is hidden rather than removed.
                                database.catalogDao().deactivate(item.id)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.CUSTOMER_LIST }
                    )
                }
                Screen.STOCK_IN -> {
                    StockInScreen(
                        catalog = catalogState,
                        suppliers = suppliersState,
                        stockLevels = stockLevelsMap,
                        // `cost` arrives as the INVOICE TOTAL (StockInScreen's field is labelled
                        // "Total Cost Price (₹)"), but StockInRecord.costPrice stores cost
                        // PER UNIT. Converting here is what keeps the two conventions from
                        // being mixed in one column, which is what made voice-stock-in costs
                        // wrong by a factor of quantity. The supplier ledger still moves by the
                        // total, since that is what is actually owed.
                        onAddStockIn = { item, qty, cost, supplier, supplierId, expiry ->
                            scope.launch {
                                // `cost` is the INVOICE TOTAL (the field is labelled "Total Cost
                                // Price"); the repository converts it to the per-unit cost that
                                // StockInRecord.costPrice actually stores. The supplier ledger
                                // still moves by the total, since that is what is owed.
                                stockLedgerRepo.recordPurchaseFromInvoiceTotal(
                                    itemId = item.id,
                                    itemName = item.name,
                                    qty = qty,
                                    invoiceTotal = cost,
                                    supplier = supplier,
                                    supplierId = supplierId,
                                    expiryDateMs = expiry
                                )
                                if (supplierId != null) {
                                    database.supplierDao().addToBalance(supplierId, cost)
                                }
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.HOME },
                        sharedAudioRecorder = sharedAudioRecorder,
                        sharedRollingAudioBuffer = sharedRollingBuffer,
                        sharedPttBurstCoalescer = stockPttBurstCoalescer,
                        sharedOnDeviceRecognizer = sharedOnDeviceRecognizer,
                        sharedBackgroundProcessor = sharedBackgroundProcessor
                    )
                }
                Screen.SUMMARY -> {
                    DailySummaryScreen(
                        rangeTransactions = rangeTransactionsState,
                        rangeMode = summaryRangeMode,
                        onRangeModeChange = { summaryRangeMode = it },
                        catalog = catalogState,
                        onUpdateTxPrice = { tx, newUnitPrice ->
                            scope.launch {
                                val newTotal = newUnitPrice * tx.quantity
                                val updatedTx = tx.copy(priceAtSale = newUnitPrice, total = newTotal, synced = false)
                                database.transactionDao().insert(updatedTx)
                                database.catalogDao().updatePrice(tx.itemId, newUnitPrice)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onVoidTransaction = { tx ->
                            scope.launch {
                                // Must go through the repository, not TransactionDao.voidTransaction:
                                // voiding has to book the compensating SALE_VOID stock movement or
                                // the item stays permanently short (the old derived stock query
                                // never filtered voided rows, so this silently under-counted stock).
                                stockLedgerRepo.voidSale(tx.id, System.currentTimeMillis())
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = summaryOrigin },
                        onNavigateToReports = { reportsOrigin = Screen.SUMMARY; currentScreen = Screen.REPORTS }
                    )
                }
                Screen.REPORTS -> {
                    com.voicetoinvoice.app.ui.screens.reports.ReportsScreen(
                        onNavigateBack = { currentScreen = reportsOrigin },
                        onNavigateToExpense = { currentScreen = Screen.EXPENSE }
                    )
                }
                Screen.EXPENSE -> {
                    com.voicetoinvoice.app.ui.screens.expense.ExpenseScreen(
                        onNavigateBack = { currentScreen = reportsOrigin }
                    )
                }
                Screen.SETTINGS -> {
                    SettingsScreen(
                        onNavigateBack = { currentScreen = Screen.HOME },
                        onNavigateToLogs = { currentScreen = Screen.DIAGNOSTIC_LOGS }
                    )
                }
                Screen.DIAGNOSTIC_LOGS -> {
                    DiagnosticLogsScreen(
                        onNavigateBack = { currentScreen = Screen.CUSTOMER_LIST }
                    )
                }
                Screen.CUSTOMER_LIST -> {
                    CustomerListScreen(
                        customers = customersState,
                        outstandingMap = outstandingByCustomer,
                        duplicates = duplicateCustomersState,
                        onAddCustomerClick = { currentScreen = Screen.CUSTOMER_EDIT },
                        onCustomerClick = { customer ->
                            selectedCustomerId = customer.id
                            currentScreen = Screen.CUSTOMER_DETAIL
                        },
                        onMergeClick = { pair ->
                            scope.launch {
                                database.customerDao().mergeInto(sourceId = pair.second.id, targetId = pair.first.id)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateToSummary = { summaryOrigin = Screen.CUSTOMER_LIST; currentScreen = Screen.SUMMARY },
                        onNavigateToSuppliers = { currentScreen = Screen.SUPPLIER },
                        onNavigateToPriceUpdate = { currentScreen = Screen.PRICE_UPDATE },
                        onNavigateToLogs = { currentScreen = Screen.DIAGNOSTIC_LOGS }
                    )
                }
                Screen.CUSTOMER_DETAIL -> {
                    val customer = selectedCustomer
                    if (customer == null) {
                        // Guards against a stale/cleared selection (e.g. after a merge) --
                        // fall back instead of crashing on a null customer.
                        currentScreen = Screen.CUSTOMER_LIST
                    } else {
                        CustomerDetailScreen(
                            customer = customer,
                            outstandingAmount = outstandingByCustomer[customer.id] ?: 0.0,
                            ledger = selectedCustomerLedgerState,
                            onNavigateBack = { currentScreen = Screen.CUSTOMER_LIST },
                            onSendReminder = { c ->
                                sendWhatsAppReminder(
                                    customerName = c.name,
                                    amount = outstandingByCustomer[c.id] ?: 0.0,
                                    phone = c.phone
                                )
                            },
                            onSendBill = { c ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val billId = database.transactionDao().getRecentBillIdForCustomer(c.id)
                                        val lines = if (billId != null) database.transactionDao().getByBillId(billId) else emptyList()
                                        if (lines.isEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "इस ग्राहक का कोई बिल नहीं मिला", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val prevBal = outstandingByCustomer[c.id]
                                            val uri = com.voicetoinvoice.app.domain.action.BillBuilder.render(context, "", c.name, lines, prevBal)
                                            val result = if (uri != null) com.voicetoinvoice.app.domain.action.ActionExecutor.sendBillImage(context, uri, c.phone)
                                                         else com.voicetoinvoice.app.domain.action.ActionExecutor.sendBill(context, c, lines)
                                            val errorMsg = when (result) {
                                                is com.voicetoinvoice.app.domain.action.ActionExecutor.Result.AppMissing -> result.message
                                                is com.voicetoinvoice.app.domain.action.ActionExecutor.Result.Failed -> result.message
                                                else -> null
                                            }
                                            if (errorMsg != null) {
                                                withContext(Dispatchers.Main) {
                                                    android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onRepeatOrder = { lines -> repeatOrderLines = lines }
                        )
                    }
                }
                Screen.CUSTOMER_EDIT -> {
                    CustomerEditScreen(
                        existingCustomers = customersState,
                        onSave = { name, keyword, phone, photoPath ->
                            scope.launch {
                                val freeCode = database.customerDao().nextFreeCode()
                                val key = com.voicetoinvoice.app.domain.parser.PhoneticKey.of(name)
                                val record = CustomerRecord(
                                    name = name,
                                    code = freeCode,
                                    keyword = keyword,
                                    phone = phone,
                                    phoneticKey = key,
                                    photoPath = photoPath
                                )
                                database.customerDao().insert(record)

                                // Fix 2/3: if this customer was created from the udhaar
                                // picker's + button, link it back to the credit that was
                                // waiting for a customer instead of leaving it unassigned.
                                val creditIdToLink = pendingCreditLinkId
                                if (creditIdToLink != null) {
                                    // Was getAllCreditsList().find { } -- a full-table scan of an
                                    // unboundedly-growing table just to look up one row by id.
                                    val credit = database.creditDao().getById(creditIdToLink)
                                    if (credit != null) {
                                        database.customerDao().assignCustomerToCredit(credit, record.id)
                                    }
                                    pendingCreditLinkId = null
                                    syncEngine.syncAllUnsynced()
                                    currentScreen = Screen.HOME
                                } else {
                                    syncEngine.syncAllUnsynced()
                                    currentScreen = Screen.CUSTOMER_LIST
                                }
                            }
                        },
                        onBackClick = {
                            val wasLinkingCredit = pendingCreditLinkId != null
                            pendingCreditLinkId = null
                            currentScreen = if (wasLinkingCredit) Screen.HOME else Screen.CUSTOMER_LIST
                        }
                    )
                }
            }

            repeatOrderLines?.let { lines ->
                com.voicetoinvoice.app.ui.components.RepeatOrderSheet(
                    previousLines = lines,
                    catalog = catalogState,
                    onDismiss = { repeatOrderLines = null },
                    onConfirm = { repeatLines ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val customerId = selectedCustomerId
                                val billId = java.util.UUID.randomUUID().toString()
                                val now = System.currentTimeMillis()
                                val mode = if (customerId != null) com.voicetoinvoice.app.data.local.entity.PaymentMode.CREDIT else com.voicetoinvoice.app.data.local.entity.PaymentMode.CASH
                                repeatLines.forEach { rLine ->
                                    val tx = TransactionRecord(
                                        id = java.util.UUID.randomUUID().toString(),
                                        itemId = rLine.item.id,
                                        itemName = rLine.item.name,
                                        quantity = rLine.quantity,
                                        priceAtSale = rLine.todayPrice,
                                        total = rLine.quantity * rLine.todayPrice,
                                        paymentMode = mode,
                                        customerId = customerId,
                                        billId = billId,
                                        timestamp = now
                                    )
                                    database.transactionDao().insert(tx)
                                    stockLedgerRepo.recordSale(
                                        itemId = rLine.item.id,
                                        itemName = rLine.item.name,
                                        qty = rLine.quantity,
                                        transactionId = tx.id,
                                        occurredAtMs = now,
                                        total = tx.total,
                                        costAtSale = tx.costAtSale
                                    )
                                }
                                syncEngine.syncAllUnsynced()
                            }
                            repeatOrderLines = null
                            android.widget.Toast.makeText(context, "दोबारा ऑर्डर दर्ज हुआ", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Fix 7: visible on every screen except onboarding -- the discoverable entry
            // point for the assistant (principle #1). See ui/components/AssistantFloatingButton.kt.
            if (currentScreen != Screen.ONBOARDING) {
                AssistantFloatingButton(
                    db = database,
                    rollingAudioBuffer = sharedRollingBuffer,
                    audioRecorder = sharedAudioRecorder,
                    pttBurstCoalescer = assistantPttBurstCoalescer,
                    pttWindowLedger = assistantPttWindowLedger,
                    onDeviceRecognizer = sharedOnDeviceRecognizer,
                    backgroundProcessor = sharedBackgroundProcessor,
                    permissionLauncher = sharedPermissionLauncher,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                )
            }
            }
        }
    }
}
