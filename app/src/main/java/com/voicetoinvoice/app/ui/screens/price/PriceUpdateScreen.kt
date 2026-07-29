package com.voicetoinvoice.app.ui.screens.price

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.ui.theme.Accent
import com.voicetoinvoice.app.ui.theme.AccentSoft
import com.voicetoinvoice.app.ui.theme.Money
import com.voicetoinvoice.app.ui.theme.Owed
import com.voicetoinvoice.app.ui.theme.SectionLabel

/**
 * Prices screen.
 *
 * Structured around one insight: an item sitting at ₹0 is not just "a row with a small
 * number", it is a sale the app *cannot book at all* (the ledger refuses ₹0 sales — see the
 * `price_at_sale > 0` commit gate). Those items are therefore hoisted into their own section
 * at the top, styled as a task to clear, rather than being left to be hunted down in an
 * alphabetical list where they look identical to everything else.
 *
 * That section is also where Catalog-Learning-From-History (ISSUE-033) surfaces: an item the
 * shopkeeper has said several times but never priced now auto-appears here asking for a rate,
 * instead of silently failing to match on every future recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceUpdateScreen(
    catalog: List<CatalogItem>,
    onUpdatePrice: (CatalogItem, Double) -> Unit,
    onRemoveItem: (CatalogItem) -> Unit,
    onNavigateBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(catalog, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) catalog else catalog.filter { it.name.lowercase().contains(q) }
    }
    val needsPrice = remember(filtered) { filtered.filter { it.price <= 0.0 }.sortedBy { it.name } }
    val priced = remember(filtered) { filtered.filter { it.price > 0.0 }.sortedBy { it.name } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Prices", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                    Text(
                        "Set your rates",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (needsPrice.isEmpty()) "${priced.size} items priced · all set"
                        else "${needsPrice.size} waiting for a rate · ${priced.size} priced",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item { SearchField(query = query, onQueryChange = { query = it }) }

            if (needsPrice.isNotEmpty()) {
                item {
                    SectionHeader(
                        label = "NEEDS A RATE",
                        accent = Owed,
                        caption = "These can't be billed until they have a price."
                    )
                }
                items(needsPrice, key = { it.id }) { item ->
                    PriceRow(
                        item = item,
                        highlight = true,
                        onSave = { onUpdatePrice(item, it) },
                        onRemove = { onRemoveItem(item) }
                    )
                }
            }

            if (priced.isNotEmpty()) {
                item {
                    SectionHeader(
                        label = "ALL ITEMS",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        caption = null
                    )
                }
                items(priced, key = { it.id }) { item ->
                    PriceRow(
                        item = item,
                        highlight = false,
                        onSave = { onUpdatePrice(item, it) },
                        onRemove = { onRemoveItem(item) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) "No items yet." else "Nothing matches \"$query\".",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search items") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedBorderColor = Accent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
private fun SectionHeader(label: String, accent: androidx.compose.ui.graphics.Color, caption: String?) {
    Column(Modifier.padding(top = 14.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(label, style = SectionLabel, color = accent)
        }
        if (caption != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PriceRow(
    item: CatalogItem,
    highlight: Boolean,
    onSave: (Double) -> Unit,
    onRemove: () -> Unit
) {
    // Keyed on id so recycling a row onto a different item resets the draft rather than
    // carrying the previous item's half-typed number over to it.
    var draft by remember(item.id, item.price) {
        mutableStateOf(if (item.price > 0.0) formatPrice(item.price) else "")
    }
    var justSaved by remember(item.id) { mutableStateOf(false) }
    var confirmingRemove by remember(item.id) { mutableStateOf(false) }

    val parsed = draft.trim().toDoubleOrNull()
    val canSave = parsed != null && parsed > 0.0 && parsed != item.price

    LaunchedEffect(justSaved) {
        if (justSaved) {
            kotlinx.coroutines.delay(1400)
            justSaved = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlight) Modifier.border(1.dp, AccentSoft, RoundedCornerShape(18.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (item.price > 0.0) "₹${formatPrice(item.price)} per ${item.unitId.lowercase()}"
                        else "per ${item.unitId.lowercase()} · no rate yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.price > 0.0) MaterialTheme.colorScheme.onSurfaceVariant else Owed
                    )
                }

                Spacer(Modifier.width(10.dp))

                OutlinedTextField(
                    value = draft,
                    onValueChange = { new ->
                        // Digits and at most one decimal point. Filtering here (rather than
                        // validating on save) means the field can never hold something the
                        // Save button would silently reject.
                        if (new.isEmpty() || new.matches(Regex("^\\d*\\.?\\d{0,2}$"))) draft = new
                    },
                    modifier = Modifier.width(104.dp),
                    singleLine = true,
                    placeholder = { Text("0") },
                    prefix = { Text("₹") },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = Accent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(Modifier.width(8.dp))

                FilledIconButton(
                    onClick = {
                        parsed?.let {
                            onSave(it)
                            justSaved = true
                        }
                    },
                    enabled = canSave,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (justSaved) Money else Accent,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Save price")
                }
            }

            // Only offered on unpriced rows. Those are the ones that can be STT noise
            // ("पंद्रह", "अठारह के लोग") auto-added by mistake; a priced item is one the
            // shopkeeper has already vouched for by typing a rate into it.
            if (highlight) {
                Spacer(Modifier.height(6.dp))
                AnimatedVisibility(
                    visible = !confirmingRemove,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    TextButton(
                        onClick = { confirmingRemove = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            "Not a real item",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                AnimatedVisibility(
                    visible = confirmingRemove,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Hide “${item.name}”?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            confirmingRemove = false
                            onRemove()
                        }) {
                            Text("Hide", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** Trims the trailing ".0" that Double.toString() puts on whole-rupee prices. */
private fun formatPrice(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", value)
