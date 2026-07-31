package com.voicetoinvoice.app.ui.screens.stockin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.voicetoinvoice.app.audio.AudioRecorder
import com.voicetoinvoice.app.audio.OnDeviceSpeechRecognizer
import com.voicetoinvoice.app.audio.PttBurstCoalescer
import com.voicetoinvoice.app.audio.PttWindowLedger
import com.voicetoinvoice.app.audio.RollingAudioBuffer
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CaptureIntent
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor
import com.voicetoinvoice.app.ui.components.PttMicButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val STOCK_PRE_ROLL_MS = 300L
private const val STOCK_POST_ROLL_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockInScreen(
    catalog: List<CatalogItem>,
    suppliers: List<SupplierRecord> = emptyList(),
    stockLevels: Map<String, Double> = emptyMap(),
    onAddStockIn: (CatalogItem, Double, Double, String, String?, Long?) -> Unit,
    onNavigateBack: () -> Unit,
    sharedAudioRecorder: AudioRecorder? = null,
    sharedRollingAudioBuffer: RollingAudioBuffer? = null,
    sharedPttBurstCoalescer: PttBurstCoalescer? = null,
    sharedOnDeviceRecognizer: OnDeviceSpeechRecognizer? = null,
    sharedBackgroundProcessor: BackgroundSttProcessor? = null
) {
    val context = LocalContext.current
    var selectedItem by remember { mutableStateOf<CatalogItem?>(catalog.firstOrNull()) }
    var itemDropdownExpanded by remember { mutableStateOf(false) }

    var selectedSupplier by remember { mutableStateOf<SupplierRecord?>(null) }
    var supplierDropdownExpanded by remember { mutableStateOf(false) }

    var qtyText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var supplierText by remember { mutableStateOf("") }
    var expiryDateMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(selectedItem) {
        expiryDateMs = null
    }

    val db = remember { AppDatabase.getInstance(context) }
    val audioRecorder = sharedAudioRecorder ?: remember { AudioRecorder(context) }
    val rollingAudioBuffer = sharedRollingAudioBuffer ?: remember { RollingAudioBuffer(context) }
    val pttWindowLedger = remember { PttWindowLedger.getInstance() }
    val pttBurstCoalescer = sharedPttBurstCoalescer ?: remember(rollingAudioBuffer) {
        PttBurstCoalescer(STOCK_PRE_ROLL_MS, STOCK_POST_ROLL_MS, (rollingAudioBuffer.getBufferDurationSeconds() - 5) * 1000L)
    }
    val onDeviceRecognizer = sharedOnDeviceRecognizer ?: remember { OnDeviceSpeechRecognizer(context) }
    val stockScope = rememberCoroutineScope()
    val backgroundProcessor = sharedBackgroundProcessor ?: remember { BackgroundSttProcessor(context, stockScope) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val datePickerDialog = remember(context) {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val pickedCal = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                expiryDateMs = pickedCal.timeInMillis
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis()
        }
    }

    if (sharedRollingAudioBuffer == null) {
        DisposableEffect(Unit) {
            rollingAudioBuffer.startRollingBuffer()
            onDispose {
                rollingAudioBuffer.stopRollingBuffer()
                onDeviceRecognizer.release()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("माल (Stock)") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PttMicButton(
                    intent = CaptureIntent.STOCK_IN,
                    label = "माल आया",
                    size = 120.dp,
                    containerColor = Color(0xFF1565C0),
                    db = db,
                    rollingAudioBuffer = rollingAudioBuffer,
                    audioRecorder = audioRecorder,
                    pttBurstCoalescer = pttBurstCoalescer,
                    pttWindowLedger = pttWindowLedger,
                    onDeviceRecognizer = onDeviceRecognizer,
                    backgroundProcessor = backgroundProcessor,
                    permissionLauncher = permissionLauncher
                )
                Spacer(Modifier.width(24.dp))
                PttMicButton(
                    intent = CaptureIntent.WASTE,
                    label = "खराब",
                    size = 100.dp,
                    containerColor = Color(0xFFC62828),
                    db = db,
                    rollingAudioBuffer = rollingAudioBuffer,
                    audioRecorder = audioRecorder,
                    pttBurstCoalescer = pttBurstCoalescer,
                    pttWindowLedger = pttWindowLedger,
                    onDeviceRecognizer = onDeviceRecognizer,
                    backgroundProcessor = backgroundProcessor,
                    permissionLauncher = permissionLauncher
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("वर्तमान स्टॉक (Current Stock Levels)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (catalog.isEmpty()) {
                Text("No catalog items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(catalog) { item ->
                        val onHand = stockLevels[item.id] ?: 0.0
                        val isLowStock = item.lowStockThreshold != null && onHand <= item.lowStockThreshold
                        Card(
                            colors = if (isLowStock) {
                                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                            } else {
                                CardDefaults.cardColors()
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text("${onHand} ${item.unitId}", style = MaterialTheme.typography.bodyMedium)
                                if (isLowStock) {
                                    Text("Low stock!", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("या हाथ से भरें (Manual Entry)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            // Catalog Item Selector Dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedItem?.name ?: "Select Item",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Catalog Item") },
                    modifier = Modifier.fillMaxWidth().clickable { itemDropdownExpanded = true }
                )
                DropdownMenu(
                    expanded = itemDropdownExpanded,
                    onDismissRequest = { itemDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    catalog.forEach { item ->
                        DropdownMenuItem(
                            text = { Text("${item.name} (₹${item.price}/${item.unitId})") },
                            onClick = {
                                selectedItem = item
                                itemDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = qtyText,
                onValueChange = { qtyText = it },
                label = { Text("Quantity Received (KG/Pcs)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it },
                label = { Text("Total Cost Price (₹)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (selectedItem?.trackExpiry == true) {
                val expiryText = expiryDateMs?.let {
                    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))
                } ?: "एक्सपायरी तारीख चुनें"
                OutlinedButton(
                    onClick = { datePickerDialog.show() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(expiryText)
                }
                Spacer(Modifier.height(12.dp))
            }

            // Supplier Picker Dropdown
            if (suppliers.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedSupplier?.name ?: "Select Saved Supplier (Optional)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Saved Supplier Ledger") },
                        modifier = Modifier.fillMaxWidth().clickable { supplierDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = supplierDropdownExpanded,
                        onDismissRequest = { supplierDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("None (Use Free Text / Cash)") },
                            onClick = {
                                selectedSupplier = null
                                supplierDropdownExpanded = false
                            }
                        )
                        suppliers.forEach { supp ->
                            DropdownMenuItem(
                                text = { Text("${supp.name} (Owed: ₹${supp.balanceOwed.toInt()})") },
                                onClick = {
                                    selectedSupplier = supp
                                    supplierText = supp.name
                                    supplierDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = supplierText,
                onValueChange = {
                    supplierText = it
                    if (selectedSupplier != null && selectedSupplier?.name != it) {
                        selectedSupplier = null
                    }
                },
                label = { Text("Supplier Name (Free Text)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val item = selectedItem
                    val qty = qtyText.toDoubleOrNull()
                    val cost = costText.toDoubleOrNull() ?: 0.0
                    if (item != null && qty != null) {
                        onAddStockIn(item, qty, cost, supplierText, selectedSupplier?.id, expiryDateMs)
                        onNavigateBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Stock Entry")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            var recentMovements by remember {
                mutableStateOf<List<com.voicetoinvoice.app.data.local.entity.StockLedgerEntry>>(emptyList())
            }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    recentMovements = db.stockLedgerDao().recentMovements(12)
                }
            }

            if (recentMovements.isNotEmpty()) {
                Text("हाल की स्टॉक हलचल (Recent Stock Movements)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    recentMovements.forEach { entry ->
                        val outbound = entry.deltaQty < 0
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(entry.itemName, style = MaterialTheme.typography.titleSmall)
                                    val qtyText2 = (if (outbound) "−" else "+") +
                                        kotlin.math.abs(entry.deltaQty).let {
                                            if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
                                        }
                                    val costText2 = entry.unitCost?.let { " | भाव: ₹${it}/यूनिट" } ?: ""
                                    Text(
                                        "${entry.reason.hindiLabel}: $qtyText2$costText2",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (outbound) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val needsCost = entry.reason ==
                                    com.voicetoinvoice.app.data.local.entity.StockReason.STOCK_IN &&
                                    entry.unitCost == null
                                if (needsCost) {
                                    AssistChip(
                                        onClick = {
                                            selectedItem = catalog.find { it.id == entry.itemId }
                                                ?: CatalogItem(id = entry.itemId, name = entry.itemName, unitId = "PACKET", price = 0.0)
                                            qtyText = kotlin.math.abs(entry.deltaQty).let {
                                                if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
                                            }
                                            costText = ""
                                        },
                                        label = { Text("भाव डालें", color = MaterialTheme.colorScheme.error) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
