package com.voicetoinvoice.app.ui.screens.price

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceUpdateScreen(
    catalog: List<CatalogItem>,
    onUpdatePrice: (CatalogItem, Double) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Price Update") },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            items(catalog) { item ->
                var priceText by remember(item.id) { mutableStateOf(item.price.toString()) }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("Unit: ${item.unitId}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            modifier = Modifier.width(100.dp),
                            singleLine = true,
                            prefix = { Text("₹") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                priceText.toDoubleOrNull()?.let { newPrice ->
                                    onUpdatePrice(item, newPrice)
                                }
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
