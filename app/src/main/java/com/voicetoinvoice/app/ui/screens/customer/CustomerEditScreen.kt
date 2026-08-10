package com.voicetoinvoice.app.ui.screens.customer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import com.voicetoinvoice.app.ui.components.PhotoCaptureButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerEditScreen(
    customerToEdit: CustomerRecord? = null,
    existingCustomers: List<CustomerRecord> = emptyList(),
    nextFreeCode: Int = 1,
    onSave: (name: String, keyword: String?, phone: String?, photoPath: String?) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(customerToEdit?.name ?: "") }
    var keyword by remember { mutableStateOf(customerToEdit?.keyword ?: "") }
    var phone by remember { mutableStateOf(customerToEdit?.phone ?: "") }
    var photoPath by remember { mutableStateOf(customerToEdit?.photoPath) }

    val code = customerToEdit?.code ?: nextFreeCode

    // Dynamic duplicate check when entering name
    val similarExisting = remember(name, existingCustomers) {
        if (name.trim().length < 2 || customerToEdit != null) {
            emptyList()
        } else {
            val key = PhoneticKey.of(name)
            existingCustomers.filter { existing ->
                val dist = PhoneticKey.normalizedDistance(key, existing.phoneticKey)
                dist <= 0.20
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (customerToEdit == null) "नया ग्राहक खाता" else "ग्राहक खाता बदलें") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header showing Code
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ग्राहक कोड (Code)",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = String.format("#%03d", code),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Customer photo capture button (Opt-in)
            PhotoCaptureButton(
                currentPath = photoPath,
                filePrefix = "customer",
                onCaptured = { path ->
                    photoPath = path
                    if (customerToEdit != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.customerDao().update(customerToEdit.copy(photoPath = path, synced = false))
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                size = 80.dp
            )

            // Duplicate warning if similar customer exists (Principle: make duplicate visible on creation)
            if (similarExisting.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFE65100))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "क्या यह पहले से मौजूद ग्राहक है?",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        similarExisting.forEach { existing ->
                            CustomerCard(
                                customer = existing,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Name Field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("ग्राहक का नाम * (उदा. रमेश)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Keyword Field (Identity descriptor)
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("पहचान / काम (उदा. दोसे वाले, बिजली वाला)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Phone Field
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("मोबाइल नंबर (Optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Save Button
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), keyword.trim().ifBlank { null }, phone.trim().ifBlank { null }, photoPath)
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Save")
                Spacer(modifier = Modifier.width(8.dp))
                Text("खाता सुरक्षित करें (Save)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
