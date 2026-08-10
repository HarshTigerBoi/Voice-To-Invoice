package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ManualStepperComponent(
    topItems: List<CatalogItem>,
    onAddSale: (CatalogItem, Double) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Quick Manual Stepper (+/-)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(topItems) { item ->
                    var qty by remember { mutableStateOf(1.0) }
                    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ItemIcon(
                                itemName = item.name,
                                imageUrl = item.imageUrl,
                                imagePath = item.imagePath,
                                size = 72.dp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "₹${item.price.toInt()}/${item.unitId}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (qty > 0.5) qty -= 0.5 }) { Text("-") }
                                Text("${qty}", style = MaterialTheme.typography.bodyMedium)
                                IconButton(onClick = { qty += 0.5 }) { Text("+") }
                            }
                            Button(
                                onClick = { onAddSale(item, qty) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add ₹${(qty * item.price).toInt()}")
                            }
                        }
                    }
                }
            }
        }
    }
}
