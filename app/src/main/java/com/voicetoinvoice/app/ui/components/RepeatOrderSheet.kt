package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.domain.action.IndianCurrency

data class RepeatLine(
    val item: CatalogItem,
    val quantity: Double,
    val todayPrice: Double,
    val previousPrice: Double
)

/** Pure function over lists -- JVM testable without Room or Android context. */
fun buildRepeatLines(previous: List<TransactionRecord>, catalog: List<CatalogItem>): List<RepeatLine> {
    val catalogMap = catalog.filter { it.active }.associateBy { it.id }
    val catalogByName = catalog.filter { it.active }.associateBy { it.name }

    val lines = mutableListOf<RepeatLine>()
    for (prev in previous) {
        val catItem = catalogMap[prev.itemId] ?: catalogByName[prev.itemName] ?: continue
        lines += RepeatLine(
            item = catItem,
            quantity = prev.quantity,
            todayPrice = catItem.price,
            previousPrice = prev.priceAtSale
        )
    }
    return lines
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatOrderSheet(
    previousLines: List<TransactionRecord>,
    catalog: List<CatalogItem>,
    onConfirm: (List<RepeatLine>) -> Unit,
    onDismiss: () -> Unit
) {
    val initialLines = remember(previousLines, catalog) { buildRepeatLines(previousLines, catalog) }
    var quantities by remember(initialLines) {
        mutableStateOf(initialLines.map { it.quantity })
    }

    val droppedNames = remember(previousLines, catalog) {
        val activeIds = catalog.filter { it.active }.map { it.id }.toSet()
        val activeNames = catalog.filter { it.active }.map { it.name }.toSet()
        previousLines
            .filterNot { it.itemId in activeIds || it.itemName in activeNames }
            .map { it.itemName }
            .distinct()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "आज के भाव पर दोबारा ऑर्डर",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "पिछली खरीद की मात्रा को आज के भाव पर दर्ज करें",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (initialLines.isEmpty()) {
                Text("इस बिल का कोई सामान कैटलॉग में नहीं मिला", color = MaterialTheme.colorScheme.error)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(initialLines) { idx, line ->
                        val qty = quantities.getOrElse(idx) { line.quantity }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(line.item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    val priceUp = line.todayPrice > line.previousPrice
                                    if (line.todayPrice != line.previousPrice) {
                                        Text(
                                            "₹${IndianCurrency.format(line.todayPrice)} (पिछली बार ₹${IndianCurrency.format(line.previousPrice)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (priceUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            "₹${IndianCurrency.format(line.todayPrice)}/${line.item.unitId}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        if (qty > 0.0) {
                                            quantities = quantities.toMutableList().apply { this[idx] = (qty - 1.0).coerceAtLeast(0.0) }
                                        }
                                    }) { Text("-", style = MaterialTheme.typography.titleLarge) }
                                    Text(fmtQty(qty), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    IconButton(onClick = {
                                        quantities = quantities.toMutableList().apply { this[idx] = qty + 1.0 }
                                    }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                                }
                            }
                        }
                    }
                }
            }

            if (droppedNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                droppedNames.forEach { name ->
                    Text("$name अब कैटलॉग में नहीं है — छोड़ा गया", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))
            val hasValidLine = initialLines.indices.any { (quantities.getOrElse(it) { 0.0 }) > 0.0 }
            Button(
                enabled = hasValidLine,
                onClick = {
                    val finalLines = initialLines.mapIndexedNotNull { idx, line ->
                        val finalQty = quantities.getOrElse(idx) { 0.0 }
                        if (finalQty > 0.0) line.copy(quantity = finalQty) else null
                    }
                    onConfirm(finalLines)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("दर्ज करें")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun fmtQty(q: Double): String = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(q)
