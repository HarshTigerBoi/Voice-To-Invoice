package com.voicetoinvoice.app.ui.screens.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var shopName by remember { mutableStateOf("") }
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                1 -> {
                    Text("Select Shop Vertical", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Vegetable / Kirana (Pilot)")
                    }
                }
                2 -> {
                    Text("Shop Details", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = shopName,
                        onValueChange = { shopName = it },
                        label = { Text("Shop Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Next")
                    }
                }
                3 -> {
                    Text("Battery Optimization", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "To prevent background service shutdown on Xiaomi/Vivo/Oppo devices, please grant battery optimization exemption.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                            step = 4
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Battery Exemption")
                    }
                    TextButton(onClick = { step = 4 }) {
                        Text("Skip")
                    }
                }
                4 -> {
                    Text("Catalog Setup", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("Pre-seeded master catalog loaded with 10 produce items.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOnboardingComplete, modifier = Modifier.fillMaxWidth()) {
                        Text("Start Ledger")
                    }
                }
            }
        }
    }
}
