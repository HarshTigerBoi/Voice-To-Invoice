package com.voicetoinvoice.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Visual thumbnail component for catalog items and transaction records.
 * Renders an image icon from Coil if [imageUrl] or [imagePath] is provided, or a categorized fallback badge.
 */
@Composable
fun ItemIcon(
    itemName: String,
    imageUrl: String?,
    imagePath: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val localFile = imagePath?.trim()?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() }
    val cleanUrl = imageUrl?.trim()?.takeIf { it.isNotBlank() }
    val (gradient, emoji, fallbackIcon) = getCategoryVisuals(itemName)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(gradient)
            .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape),
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
        } else if (emoji != null) {
            Text(
                text = emoji,
                fontSize = (size.value * 0.48f).sp,
                textAlign = TextAlign.Center
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = itemName,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

private data class CategoryVisuals(
    val gradient: Brush,
    val emoji: String?,
    val fallbackIcon: ImageVector
)

private fun getCategoryVisuals(itemName: String): CategoryVisuals {
    val lower = itemName.lowercase()
    return when {
        lower.containsAny("milk", "dahi", "curd", "paneer", "chaas", "ghee", "butter", "दूध", "दही", "पनीर", "घी", "अमूल") ->
            CategoryVisuals(
                Brush.linearGradient(listOf(Color(0xFF0288D1), Color(0xFF00B0FF))),
                "🥛",
                Icons.Default.LocalDrink
            )
        lower.containsAny("aaloo", "alo", "pyaz", "tamatar", "bhindi", "adrak", "mirchi", "palak", "gobhi", "lauki", "karela", "gajar", "matar", "kheera", "lahsun", "baingan", "bharma", "आलू", "प्याज", "टमाटर", "भिंडी", "अदरक", "मिर्च", "पालक", "गोभी", "लौकी", "करेला", "गाजर", "मटर", "खीरा", "लहसुन", "बैंगन") ->
            CategoryVisuals(
                Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF10B981))),
                if (lower.containsAny("aaloo", "alo", "आलू")) "🥔"
                else if (lower.containsAny("tamatar", "टमाटर")) "🍅"
                else if (lower.containsAny("pyaz", "प्याज")) "🧅"
                else if (lower.containsAny("baingan", "bharma", "बैंगन")) "🍆"
                else "🥦",
                Icons.Default.Spa
            )
        lower.containsAny("chawal", "atta", "rice", "wheat", "dal", "daal", "cheeni", "sugar", "namak", "salt", "tel", "oil", "चावल", "आटा", "दाल", "चीनी", "नमक", "तेल") ->
            CategoryVisuals(
                Brush.linearGradient(listOf(Color(0xFFD97706), Color(0xFFF59E0B))),
                if (lower.containsAny("chawal", "rice", "चावल")) "🌾"
                else if (lower.containsAny("dal", "daal", "दाल")) "🥣"
                else if (lower.containsAny("tel", "oil", "तेल")) "🛢️"
                else "🛒",
                Icons.Default.LocalGroceryStore
            )
        lower.containsAny("bread", "biscuit", "toast", "cake", "नमकीन", "बिस्कुट", "टोस्ट") ->
            CategoryVisuals(
                Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFA855F7))),
                "🍞",
                Icons.Default.Category
            )
        else ->
            CategoryVisuals(
                Brush.linearGradient(listOf(Color(0xFF475569), Color(0xFF64748B))),
                "📦",
                Icons.Default.Category
            )
    }
}

private fun String.containsAny(vararg keywords: String): Boolean {
    return keywords.any { this.contains(it) }
}

