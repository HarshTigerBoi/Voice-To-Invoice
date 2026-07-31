package com.voicetoinvoice.app.ui.screens.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.dao.RollupItemTotal
import com.voicetoinvoice.app.data.local.dao.RollupTotals
import com.voicetoinvoice.app.data.repository.DailyRollupRepository
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.action.IndianCurrency
import com.voicetoinvoice.app.domain.alert.Alert
import com.voicetoinvoice.app.domain.alert.AlertEngine
import com.voicetoinvoice.app.domain.alert.AlertSeverity
import com.voicetoinvoice.app.domain.alert.AlertType
import com.voicetoinvoice.app.domain.intel.*
import com.voicetoinvoice.app.domain.query.CustomerBalance
import com.voicetoinvoice.app.domain.query.HealthScore
import com.voicetoinvoice.app.domain.query.HealthScoreResult
import com.voicetoinvoice.app.domain.query.ProfitCalculator
import com.voicetoinvoice.app.domain.query.ProfitResult
import com.voicetoinvoice.app.domain.query.ReceivablesAging
import com.voicetoinvoice.app.ui.screens.summary.RangeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private data class ReportsData(
    val profit: ProfitResult = ProfitResult.EMPTY,
    val cash: Double = 0.0,
    val upi: Double = 0.0,
    val credit: Double = 0.0,
    val topByRevenue: List<RollupItemTotal> = emptyList(),
    val topByQuantity: List<RollupItemTotal> = emptyList(),
    val totals: RollupTotals? = null,
    val receivablesTotal: Double = 0.0,
    val aging: ReceivablesAging? = null,
    val alerts: List<Alert> = emptyList(),
    val health: HealthScoreResult? = null,
    val prevRevenue: Double = 0.0,
    val prevProfit: Double = 0.0,
    val reorder: List<SupplierReorderGroup> = emptyList(),
    val forecast: List<ForecastResult> = emptyList(),
    val movers: List<MoverLine> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val rollups = remember { DailyRollupRepository(db) }
    val profitCalc = remember { ProfitCalculator(db) }
    val customerBalance = remember { CustomerBalance(db) }
    val alertEngine = remember { AlertEngine(db) }
    val healthScore = remember { HealthScore(db) }
    val reorderAdvisor = remember { ReorderAdvisor(db) }
    val demandForecast = remember { DemandForecast(db) }
    val stockLedgerRepo = remember { StockLedgerRepository(db) }

    val scope = rememberCoroutineScope()
    var reloadToken by remember { mutableStateOf(0) }
    var rangeMode by remember { mutableStateOf(RangeMode.DAY) }
    var data by remember { mutableStateOf(ReportsData()) }
    var isLoading by remember { mutableStateOf(true) }
    var pendingWriteOffAlert by remember { mutableStateOf<Alert?>(null) }

    val bounds = remember(rangeMode) {
        val todayMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val cal = Calendar.getInstance().apply {
            timeInMillis = todayMidnight
            when (rangeMode) {
                RangeMode.WEEK -> add(Calendar.DAY_OF_YEAR, -7)
                RangeMode.MONTH -> add(Calendar.DAY_OF_YEAR, -30)
                RangeMode.DAY -> {}
            }
        }
        val end = todayMidnight + 24 * 60 * 60 * 1000L
        cal.timeInMillis to end
    }

    LaunchedEffect(bounds, reloadToken) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val (start, end) = bounds
            val periodMs = end - start
            val prevStart = start - periodMs
            val prevProfit = profitCalc.compute(prevStart, start)

            data = ReportsData(
                profit = profitCalc.compute(start, end),
                cash = db.transactionDao().getTotalByPaymentMode("CASH", start, end),
                upi = db.transactionDao().getTotalByPaymentMode("UPI", start, end),
                credit = db.transactionDao().getTotalByPaymentMode("CREDIT", start, end),
                topByRevenue = rollups.getTopByRevenue(start, end),
                topByQuantity = rollups.getTopByQuantity(start, end),
                totals = rollups.getTotals(start, end),
                receivablesTotal = customerBalance.allBalances().values.sum(),
                aging = customerBalance.aging(),
                prevRevenue = prevProfit.revenue,
                prevProfit = prevProfit.grossProfit,
                alerts = alertEngine.computeAlerts(),
                health = healthScore.compute(),
                reorder = reorderAdvisor.suggestionsBySupplier(),
                forecast = demandForecast.forecastTopItems().filter {
                    it.confidence != ForecastConfidence.LOW
                },
                movers = MoverBuckets.classify(rollups.getTopByQuantity(start, end))
            )
            isLoading = false
        }
    }

    pendingWriteOffAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { pendingWriteOffAlert = null },
            title = { Text("एक्सपायरी माल हटाएँ?") },
            text = { Text("${alert.itemName ?: "सामान"} की ${fmtQty(alert.value)} मात्रा हटा दी जाएगी।") },
            confirmButton = {
                TextButton(onClick = {
                    val alertToProcess = alert
                    pendingWriteOffAlert = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            alertEngine.writeOffExpired(alertToProcess.itemId!!, stockLedgerRepo)
                        }
                        reloadToken++
                    }
                }) {
                    Text("हाँ, हटाएँ")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingWriteOffAlert = null }) {
                    Text("रहने दें")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("रिपोर्ट (Reports)") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(rangeMode == RangeMode.DAY, { rangeMode = RangeMode.DAY }, { Text("आज") }, Modifier.weight(1f))
                FilterChip(rangeMode == RangeMode.WEEK, { rangeMode = RangeMode.WEEK }, { Text("7 दिन") }, Modifier.weight(1f))
                FilterChip(rangeMode == RangeMode.MONTH, { rangeMode = RangeMode.MONTH }, { Text("30 दिन") }, Modifier.weight(1f))
            }
            Text(
                "नीचे के सुझाव और अनुमान अपनी-अपनी अवधि पर आधारित हैं",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            data.health?.let {
                HealthScoreCard(it)
                Spacer(Modifier.height(12.dp))
            }
            if (data.alerts.isNotEmpty()) {
                AlertsCard(
                    alerts = data.alerts,
                    onSnooze = { alert ->
                        scope.launch {
                            withContext(Dispatchers.IO) { alertEngine.snooze(alert) }
                            reloadToken++
                        }
                    },
                    onWriteOff = { alert ->
                        pendingWriteOffAlert = alert
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            RevenueProfitCard(data, rangeMode)
            Spacer(Modifier.height(12.dp))
            PaymentSplitCard(data)
            Spacer(Modifier.height(12.dp))
            ReorderCard(data.reorder)
            ForecastCard(data.forecast)
            MoversCard(data.movers)
            TopItemsCard("सबसे ज़्यादा बिकने वाले (राजस्व)", data.topByRevenue) { "₹${IndianCurrency.format(it.revenue)}" }
            Spacer(Modifier.height(12.dp))
            TopItemsCard("सबसे ज़्यादा बिकने वाले (मात्रा)", data.topByQuantity) { fmtQty(it.qtySold) }
            Spacer(Modifier.height(12.dp))
            WasteAndReturnsCard(data)
            Spacer(Modifier.height(12.dp))
            ReceivablesCard(data)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReorderCard(reorder: List<SupplierReorderGroup>) {
    if (reorder.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("क्या मंगवाएँ?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            reorder.forEach { group ->
                Text(group.supplierName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                group.lines.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(line.itemName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("${fmtQty(line.suggestQty)} ${line.unitId}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(line.reasoning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ForecastCard(forecast: List<ForecastResult>) {
    if (forecast.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("अगले 7 दिन का अनुमान", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            forecast.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val confLabel = if (item.confidence == ForecastConfidence.MEDIUM) " (मोटा अनुमान)" else ""
                    Text("${item.itemName}$confLabel", style = MaterialTheme.typography.bodyMedium)
                    Text("~${item.next7DayQty.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("पिछले हफ़्तों की बिक्री से अनुमान — मौसम/त्योहार शामिल नहीं", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun MoversCard(movers: List<MoverLine>) {
    val fast = movers.filter { it.bucket == MoverBucket.FAST }.take(5)
    val slowAndDead = movers.filter { it.bucket == MoverBucket.SLOW || it.bucket == MoverBucket.DEAD }.take(5)
    if (fast.isEmpty() && slowAndDead.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("तेज़ और धीमा बिकने वाला", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (fast.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("तेज़ 🔥", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color0xFF2E7D32)
                fast.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.itemName, style = MaterialTheme.typography.bodyMedium)
                        Text(fmtQty(item.qtySold), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (slowAndDead.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("धीमा 🐢", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                slowAndDead.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.itemName, style = MaterialTheme.typography.bodyMedium)
                        Text(fmtQty(item.qtySold), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RevenueProfitCard(data: ReportsData, rangeMode: RangeMode) {
    val revenueDelta = if (data.prevRevenue > 0.0) {
        ((data.profit.revenue - data.prevRevenue) / data.prevRevenue) * 100.0
    } else null

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "₹${IndianCurrency.format(data.profit.revenue)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("कुल बिक्री", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (revenueDelta != null) {
                    Spacer(Modifier.width(8.dp))
                    val up = revenueDelta >= 0
                    Text(
                        "${if (up) "▲" else "▼"} ${kotlin.math.abs(revenueDelta).toInt()}% पिछली अवधि से",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (up) Color0xFF2E7D32 else MaterialTheme.colorScheme.error
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("मुनाफ़ा", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹${IndianCurrency.format(data.profit.grossProfit)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("मार्जिन", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${data.profit.marginPct.toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (!data.profit.isFullyCosted) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (data.profit.isTooSparseToReport) "बहुत कम खरीद भाव दर्ज है -- मुनाफ़ा अनुमान अभी सटीक नहीं"
                    else "${data.profit.costCoveragePct.toInt()}% बिक्री का खरीद भाव पता है",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "औसत बिल: ₹${if (data.profit.linesWithCost + data.profit.linesWithoutCost > 0) (data.profit.revenue / (data.profit.linesWithCost + data.profit.linesWithoutCost)).toInt() else 0}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PaymentSplitCard(data: ReportsData) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("💵 नकद", style = MaterialTheme.typography.labelMedium); Text("₹${IndianCurrency.format(data.cash)}", fontWeight = FontWeight.Bold) }
            Column { Text("📲 UPI", style = MaterialTheme.typography.labelMedium); Text("₹${IndianCurrency.format(data.upi)}", fontWeight = FontWeight.Bold) }
            Column { Text("📜 उधार", style = MaterialTheme.typography.labelMedium); Text("₹${IndianCurrency.format(data.credit)}", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun TopItemsCard(title: String, items: List<RollupItemTotal>, valueText: (RollupItemTotal) -> String) {
    if (items.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            items.forEachIndexed { idx, item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${idx + 1}. ${item.itemName}", style = MaterialTheme.typography.bodyMedium)
                    Text(valueText(item), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WasteAndReturnsCard(data: ReportsData) {
    val totals = data.totals ?: return
    if (totals.wasteValue <= 0.0 && totals.returnValue <= 0.0) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("ख़राबी और वापसी (Waste & Returns)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (totals.wasteValue > 0.0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ख़राबी / नुकसान", style = MaterialTheme.typography.bodyMedium)
                    Text("₹${IndianCurrency.format(totals.wasteValue)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            }
            if (totals.returnValue > 0.0) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ग्राहक वापसी", style = MaterialTheme.typography.bodyMedium)
                    Text("₹${IndianCurrency.format(totals.returnValue)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReceivablesCard(data: ReportsData) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("कुल बाकी उधार", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("₹${IndianCurrency.format(data.receivablesTotal)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            data.aging?.let { aging ->
                if (aging.overdue > 0.0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "₹${IndianCurrency.format(aging.overdue)} — 30 दिन से ज़्यादा पुराना",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthScoreCard(health: HealthScoreResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${health.score}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = healthColor(health.score)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("व्यापार स्वास्थ्य स्कोर", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("100 में से", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            health.weakestTwo.forEach { component ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(component.name, style = MaterialTheme.typography.bodySmall)
                    Text(component.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun healthColor(score: Int) = when {
    score >= 70 -> Color0xFF2E7D32
    score >= 40 -> androidx.compose.ui.graphics.Color(0xFFF9A825)
    else -> androidx.compose.ui.graphics.Color(0xFFC62828)
}

@Composable
private fun AlertsCard(
    alerts: List<Alert>,
    onSnooze: (Alert) -> Unit,
    onWriteOff: (Alert) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("ध्यान दें", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            alerts.forEach { alert ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (alert.severity) {
                            AlertSeverity.HIGH -> "🔴"
                            AlertSeverity.MEDIUM -> "🟡"
                            AlertSeverity.LOW -> "⚪"
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(alert.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (alert.type == AlertType.EXPIRED) {
                        TextButton(onClick = { onWriteOff(alert) }) {
                            Text("हटाएँ")
                        }
                    }
                    IconButton(onClick = { onSnooze(alert) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "बाद में", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun fmtQty(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(q)

private val Color0xFF2E7D32 = androidx.compose.ui.graphics.Color(0xFF2E7D32)
