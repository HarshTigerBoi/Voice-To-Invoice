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
import kotlinx.coroutines.launch
import java.util.Calendar

import com.voicetoinvoice.app.data.local.entity.SttJobStatus
import com.voicetoinvoice.app.data.sync.SyncEngine
import com.voicetoinvoice.app.domain.validation.SaleValidation
import com.voicetoinvoice.app.network.CloudSyncManager
import com.voicetoinvoice.app.network.SupabaseConfig
import com.voicetoinvoice.app.ui.screens.logs.DiagnosticLogsScreen
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = AppDatabase.getInstance(this)
        enableEdgeToEdge()

        try {
            val serviceIntent = android.content.Intent(this, com.voicetoinvoice.app.service.AppForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MaterialTheme {
                MainAppScreen(database)
            }
        }
    }
}

enum class Screen {
    ONBOARDING, HOME, CATALOG, UDHAAR, SUPPLIER, PRICE_UPDATE, STOCK_IN, SUMMARY, SETTINGS, DIAGNOSTIC_LOGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(database: AppDatabase) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

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
    val itemCostState by database.stockInDao().getLatestCostPricePerItem().collectAsState(initial = emptyList())
    val costPriceMap = remember(itemCostState) { itemCostState.associate { it.itemId to it.costPrice } }

    val creditsState by database.creditDao().getAllCredits().collectAsState(initial = emptyList())
    val unmatchedState by database.unmatchedQueueDao().getPendingItems().collectAsState(initial = emptyList())
    val sttJobsState by database.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    val syncEngine = remember { SyncEngine(database.transactionDao(), database.stockInDao(), database.catalogDao(), database.creditDao(), database.sttJobDao(), database.supplierDao()) }

    fun sendWhatsAppReminder(credit: com.voicetoinvoice.app.data.local.entity.CreditRecord) {
        val message = "नमस्ते ${credit.customerName} जी, आपका ₹${credit.amount.toInt()} का बकाया (Udhaar) बाकी है। कृपया भुगतान करें। धन्यवाद!"
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
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        selected = currentScreen == Screen.HOME,
                        onClick = { currentScreen = Screen.HOME }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Catalog") },
                        label = { Text("Catalog") },
                        selected = currentScreen == Screen.CATALOG,
                        onClick = { currentScreen = Screen.CATALOG }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.DateRange, contentDescription = "Summary") },
                        label = { Text("Summary") },
                        selected = currentScreen == Screen.SUMMARY,
                        onClick = { currentScreen = Screen.SUMMARY }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.AccountBox, contentDescription = "Udhaar") },
                        label = { Text("Udhaar") },
                        selected = currentScreen == Screen.UDHAAR,
                        onClick = { currentScreen = Screen.UDHAAR }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentScreen == Screen.SETTINGS,
                        onClick = { currentScreen = Screen.SETTINGS }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentScreen) {
                Screen.ONBOARDING -> {
                    OnboardingScreen(onOnboardingComplete = { currentScreen = Screen.HOME })
                }
                Screen.HOME -> {
                    HomeScreen(
                        todayTotalSales = todayTotalState ?: 0.0,
                        catalog = catalogState,
                        onNavigateToUdhaar = { currentScreen = Screen.UDHAAR },
                        onNavigateToSuppliers = { currentScreen = Screen.SUPPLIER },
                        onNavigateToPriceUpdate = { currentScreen = Screen.PRICE_UPDATE },
                        onNavigateToLogs = { currentScreen = Screen.DIAGNOSTIC_LOGS },
                        onNavigateToSummary = { currentScreen = Screen.SUMMARY },
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
                                                itemId = targetItem.id,
                                                itemName = targetItem.name,
                                                quantity = parsedSale.quantity,
                                                priceAtSale = effectiveUnitPrice,
                                                total = parsedSale.estimatedTotal,
                                                rawTranscript = parsedSale.rawTranscript
                                            )
                                            database.transactionDao().insert(tx)
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
                                database.catalogDao().insertOrUpdate(CatalogItem(name = name, unitId = unitId, price = price))
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
                            sendWhatsAppReminder(credit)
                        },
                        onNavigateBack = { currentScreen = Screen.HOME }
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
                        onNavigateBack = { currentScreen = Screen.HOME }
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
                        onNavigateBack = { currentScreen = Screen.HOME }
                    )
                }
                Screen.STOCK_IN -> {
                    StockInScreen(
                        catalog = catalogState,
                        suppliers = suppliersState,
                        onAddStockIn = { item, qty, cost, supplier, supplierId ->
                            scope.launch {
                                database.stockInDao().insert(
                                    com.voicetoinvoice.app.data.local.entity.StockInRecord(
                                        itemId = item.id,
                                        itemName = item.name,
                                        quantity = qty,
                                        costPrice = cost,
                                        supplier = supplier,
                                        supplierId = supplierId
                                    )
                                )
                                if (supplierId != null) {
                                    database.supplierDao().addToBalance(supplierId, cost)
                                }
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.HOME }
                    )
                }
                Screen.SUMMARY -> {
                    DailySummaryScreen(
                        rangeTransactions = rangeTransactionsState,
                        rangeMode = summaryRangeMode,
                        onRangeModeChange = { summaryRangeMode = it },
                        costPriceByItemId = costPriceMap,
                        onUpdateTxPrice = { tx, newUnitPrice ->
                            scope.launch {
                                val newTotal = newUnitPrice * tx.quantity
                                val updatedTx = tx.copy(priceAtSale = newUnitPrice, total = newTotal, synced = false)
                                database.transactionDao().insert(updatedTx)
                                database.catalogDao().updatePrice(tx.itemId, newUnitPrice)
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.HOME }
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
                        onNavigateBack = { currentScreen = Screen.HOME }
                    )
                }
            }
        }
    }
}
