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
    ONBOARDING, HOME, CATALOG, UDHAAR, PRICE_UPDATE, STOCK_IN, SUMMARY, SETTINGS, DIAGNOSTIC_LOGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(database: AppDatabase) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    val catalogState by database.catalogDao().getActiveCatalog().collectAsState(initial = emptyList())

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
    val creditsState by database.creditDao().getAllCredits().collectAsState(initial = emptyList())
    val unmatchedState by database.unmatchedQueueDao().getPendingItems().collectAsState(initial = emptyList())
    val sttJobsState by database.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    val syncEngine = remember { SyncEngine(database.transactionDao(), database.stockInDao(), database.catalogDao(), database.creditDao(), database.sttJobDao()) }

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
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (currentScreen) {
                Screen.ONBOARDING -> {
                    OnboardingScreen(
                        onOnboardingComplete = { currentScreen = Screen.HOME }
                    )
                }
                Screen.HOME -> {
                    HomeScreen(
                        todayTotalSales = todayTotalState ?: 0.0,
                        catalog = catalogState,
                        onNavigateToUdhaar = { currentScreen = Screen.UDHAAR },
                        onNavigateToPriceUpdate = { currentScreen = Screen.PRICE_UPDATE },
                        onNavigateToLogs = { currentScreen = Screen.DIAGNOSTIC_LOGS },
                        onNavigateToSummary = { currentScreen = Screen.SUMMARY },
                        onConfirmSale = { parsedSale ->
                            scope.launch {
                                var targetItem = parsedSale.matchedItem
                                if (targetItem != null) {
                                    val finalPrice = if (parsedSale.priceOverridden && parsedSale.updatedUnitPrice > 0) {
                                        parsedSale.updatedUnitPrice
                                    } else if (targetItem.price == 0.0 && parsedSale.estimatedTotal > 0) {
                                        parsedSale.estimatedTotal / parsedSale.quantity
                                    } else {
                                        targetItem.price
                                    }

                                    targetItem = targetItem.copy(price = finalPrice, synced = false)
                                    database.catalogDao().insertOrUpdate(targetItem)
                                }

                                val finalItem = targetItem
                                if (finalItem != null) {
                                    val tx = TransactionRecord(
                                        itemId = finalItem.id,
                                        itemName = finalItem.name,
                                        quantity = parsedSale.quantity,
                                        priceAtSale = finalItem.price,
                                        total = parsedSale.estimatedTotal,
                                        rawTranscript = parsedSale.rawTranscript
                                    )
                                    database.transactionDao().insert(tx)
                                }
                                syncEngine.syncAllUnsynced()
                            }
                        }
                    )
                }
                Screen.CATALOG -> {
                    CatalogManagementScreen(
                        catalog = catalogState,
                        onAddItem = { name, unit, price ->
                            scope.launch {
                                database.catalogDao().insertOrUpdate(CatalogItem(name = name, unitId = unit, price = price))
                                syncEngine.syncAllUnsynced()
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
                        onAddStockIn = { item, qty, cost, supplier ->
                            scope.launch {
                                database.stockInDao().insert(com.voicetoinvoice.app.data.local.entity.StockInRecord(itemId = item.id, itemName = item.name, quantity = qty, costPrice = cost, supplier = supplier))
                                syncEngine.syncAllUnsynced()
                            }
                        },
                        onNavigateBack = { currentScreen = Screen.HOME }
                    )
                }
                Screen.SUMMARY -> {
                    DailySummaryScreen(
                        todayTransactions = todayTransactionsState,
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
