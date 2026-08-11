package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
                modifier = Modifier.heightIn(max = 2000.dp),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(topItems) { item ->
                    var qty by remember { mutableStateOf(1.0) }
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
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
                                FilledTonalIconButton(
                                    onClick = { if (qty > 0.5) qty -= 0.5 },
                                    modifier = Modifier.size(36.dp)
                                ) { Icon(Icons.Default.Remove, contentDescription = "घटाएं", modifier = Modifier.size(18.dp)) }
                                Text(
                                    "$qty",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                                FilledTonalIconButton(
                                    onClick = { qty += 0.5 },
                                    modifier = Modifier.size(36.dp)
                                ) { Icon(Icons.Default.Add, contentDescription = "बढ़ाएं", modifier = Modifier.size(18.dp)) }
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
