package com.voicetoinvoice.app.ui.screens.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.ExpenseCategory
import com.voicetoinvoice.app.data.local.entity.ExpenseRecord
import com.voicetoinvoice.app.domain.action.IndianCurrency
import com.voicetoinvoice.app.ui.theme.LedgerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class CategoryItem(
    val category: ExpenseCategory,
    val label: String,
    val icon: ImageVector
)

val EXPENSE_CATEGORIES = listOf(
    CategoryItem(ExpenseCategory.RENT, "किराया", Icons.Default.Home),
    CategoryItem(ExpenseCategory.ELECTRICITY, "बिजली", Icons.Default.Bolt),
    CategoryItem(ExpenseCategory.SALARY, "तनख्वाह", Icons.Default.Person),
    CategoryItem(ExpenseCategory.TRANSPORT, "भाड़ा", Icons.Default.LocalShipping),
    CategoryItem(ExpenseCategory.SUPPLIES, "सामान", Icons.Default.Inventory),
    CategoryItem(ExpenseCategory.TEA, "चाय", Icons.Default.LocalCafe),
    CategoryItem(ExpenseCategory.OTHER, "अन्य", Icons.Default.MoreHoriz)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    val todayStartMs = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val todayEndMs = remember(todayStartMs) {
        todayStartMs + 24 * 60 * 60 * 1000L
    }

    val todayExpenses by db.expenseDao().getBetween(todayStartMs, todayEndMs).collectAsState(initial = emptyList())
    val todayTotal = remember(todayExpenses) { todayExpenses.sumOf { it.amount } }

    var selectedCategoryForEntry by remember { mutableStateOf<CategoryItem?>(null) }
    var amountText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }

    if (selectedCategoryForEntry != null) {
        val cat = selectedCategoryForEntry!!
        AlertDialog(
            onDismissRequest = { selectedCategoryForEntry = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(cat.icon, contentDescription = cat.label, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("${cat.label} खर्चा")
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("रकम (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("विवरण (नोट - optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        if (amount > 0.0) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.expenseDao().insert(
                                        ExpenseRecord(
                                            category = cat.category,
                                            amount = amount,
                                            note = noteText.takeIf { it.isNotBlank() }
                                        )
                                    )
                                }
                            }
                        }
                        amountText = ""
                        noteText = ""
                        selectedCategoryForEntry = null
                    }
                ) {
                    Text("सहेजें (Save)")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    amountText = ""
                    noteText = ""
                    selectedCategoryForEntry = null
                }) {
                    Text("रद्द करें")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("दुकान के ख़र्चे (Expenses)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "आज का कुल खर्चा:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "₹${IndianCurrency.format(todayTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LedgerColors.MoneyOut
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "कैटेगरी चुनें:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                items(EXPENSE_CATEGORIES) { cat ->
                    Card(
                        modifier = Modifier
                            .height(96.dp)
                            .clickable {
                                selectedCategoryForEntry = cat
                                amountText = ""
                                noteText = ""
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = cat.icon,
                                contentDescription = cat.label,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = cat.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                "आज दर्ज किए गए ख़र्चे:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (todayExpenses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "आज कोई खर्चा दर्ज नहीं किया गया है",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(todayExpenses, key = { it.id }) { expense ->
                        val catInfo = EXPENSE_CATEGORIES.find { it.category == expense.category }
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = catInfo?.icon ?: Icons.Default.MoreHoriz,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = catInfo?.label ?: expense.category.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (!expense.note.isNullOrBlank()) {
                                            Text(
                                                text = expense.note,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "₹${IndianCurrency.format(expense.amount)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = LedgerColors.MoneyOut
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    db.expenseDao().voidExpense(expense.id)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Void Expense",
                                            tint = MaterialTheme.colorScheme.error
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
}
