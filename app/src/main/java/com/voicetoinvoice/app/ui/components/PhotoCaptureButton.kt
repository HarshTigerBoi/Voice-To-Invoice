package com.voicetoinvoice.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.voicetoinvoice.app.utils.PhotoCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoCaptureButton(
    currentPath: String?,
    filePrefix: String,               // "item" or "customer"
    onCaptured: (String?) -> Unit,    // absolute path, or null when cleared
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingFile by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingFile
        if (success && file != null) {
            scope.launch(Dispatchers.IO) {
                PhotoCapture.compressInPlace(file)
                withContext(Dispatchers.Main) {
                    onCaptured(file.absolutePath)
                }
            }
        } else {
            file?.let { PhotoCapture.delete(it.absolutePath) }
        }
        pendingFile = null
    }

    val photoFile = remember(currentPath) {
        currentPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (photoFile != null) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primaryContainer
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .combinedClickable(
                onClick = {
                    val file = PhotoCapture.newPhotoFile(context, filePrefix)
                    pendingFile = file
                    val uri = PhotoCapture.uriFor(context, file)
                    launcher.launch(uri)
                },
                onLongClick = {
                    if (photoFile != null) {
                        PhotoCapture.delete(photoFile.absolutePath)
                        onCaptured(null)
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (photoFile != null) {
            AsyncImage(
                model = photoFile,
                contentDescription = "$filePrefix photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = "Capture $filePrefix photo",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}
