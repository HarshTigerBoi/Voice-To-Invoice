package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicetoinvoice.app.data.local.entity.CatalogItem

@Composable
fun ManualStepperComponent(
    topItems: List<CatalogItem>,
    onAddSale: (CatalogItem, Double) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF131722),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Quick Manual Sale",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    Text(
                        text = "${topItems.size} items",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 135.dp),
                modifier = Modifier.heightIn(max = 2000.dp),
                userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(topItems) { item ->
                    var qty by remember { mutableStateOf(1.0) }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFF1C2232),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ItemIcon(
                                itemName = item.name,
                                imageUrl = item.imageUrl,
                                imagePath = item.imagePath,
                                size = 56.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.15f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "₹${item.price.toInt()}/${item.unitId}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF34D399),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalIconButton(
                                    onClick = { if (qty > 0.5) qty -= 0.5 },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    text = "$qty",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                FilledTonalIconButton(
                                    onClick = { qty += 0.5 },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.12f),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onAddSale(item, qty) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2563EB),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(vertical = 6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "+ ₹${(qty * item.price).toInt()}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

