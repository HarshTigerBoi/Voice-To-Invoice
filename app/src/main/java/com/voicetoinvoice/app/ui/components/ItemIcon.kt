package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Visual thumbnail component for catalog items and transaction records.
 * Renders an image icon from Coil if [imageUrl] is provided, or a categorized fallback vector badge.
 */
@Composable
fun ItemIcon(
    itemName: String,
    imageUrl: String?,
    imagePath: String? = null,   // NEW — takes precedence over imageUrl
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val localFile = imagePath?.trim()?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() }
    val cleanUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(getCategoryBackgroundColor(itemName)),
        contentAlignment = Alignment.Center
    ) {
        if (localFile != null) {
            AsyncImage(
                model = localFile,
                contentDescription = itemName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else if (cleanUrl != null) {
            AsyncImage(
                model = cleanUrl,
                contentDescription = itemName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = getCategoryVectorIcon(itemName),
                contentDescription = itemName,
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

private fun getCategoryVectorIcon(itemName: String): ImageVector {
    val lower = itemName.lowercase()
    return when {
        lower.containsAny("milk", "dahi", "curd", "paneer", "chaas", "ghee", "butter", "दूध", "दही", "पनीर", "घी") ->
            Icons.Default.LocalDrink
        lower.containsAny("pyaz", "tamatar", "aaloo", "alo", "bhindi", "adrak", "mirchi", "palak", "gobhi", "lauki", "karela", "gajar", "matar", "kheera", "lahsun", "baingan", "आलू", "प्याज", "टमाटर", "भिंडी", "अदरक", "मिर्च", "पालक", "गोभी", "लौकी", "करेला", "गाजर", "मटर", "खीरा", "लहसुन", "बैंगन") ->
            Icons.Default.Spa
        lower.containsAny("chawal", "atta", "rice", "wheat", "dal", "daal", "cheeni", "sugar", "namak", "salt", "tel", "oil", "चावल", "आटा", "दाल", "चीनी", "नमक", "तेल") ->
            Icons.Default.LocalGroceryStore
        else -> Icons.Default.Category
    }
}

private fun getCategoryBackgroundColor(itemName: String): Color {
    val lower = itemName.lowercase()
    return when {
        lower.containsAny("milk", "dahi", "curd", "paneer", "chaas", "ghee", "butter", "दूध", "दही", "पनीर", "घी") ->
            Color(0xFF0288D1) // Blue for Dairy
        lower.containsAny("pyaz", "tamatar", "aaloo", "alo", "bhindi", "adrak", "mirchi", "palak", "gobhi", "lauki", "karela", "gajar", "matar", "kheera", "lahsun", "baingan", "आलू", "प्याज", "टमाटर", "भिंडी", "अदरक", "मिर्च", "पालक", "गोभी", "लौकी", "करेला", "गाजर", "मटर", "खीरा", "लहसुन", "बैंगन") ->
            Color(0xFF2E7D32) // Green for Veggies
        lower.containsAny("chawal", "atta", "rice", "wheat", "dal", "daal", "cheeni", "sugar", "namak", "salt", "tel", "oil", "चावल", "आटा", "दाल", "चीनी", "नमक", "तेल") ->
            Color(0xFFE65100) // Deep Amber/Orange for Grains & Grocery
        else -> Color(0xFF616161) // Neutral Gray
    }
}

private fun String.containsAny(vararg keywords: String): Boolean {
    return keywords.any { this.contains(it) }
}
