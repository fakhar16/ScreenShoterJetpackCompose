package com.samsung.screenshoterjetpackcompose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.samsung.screenshoterjetpackcompose.ui.theme.ScreenshoterTheme

class PreviewActivity : ComponentActivity() {

    private var decisionSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pending = ScreenCaptureManager.getPendingCapture()
        if (pending == null) {
            finish()
            return
        }

        val bitmap = pending.bitmap
        val folderLabel = ScreenCaptureManager.getFolderLabel(this, pending.subDirectory)

        onBackPressedDispatcher.addCallback(this) {
            sendDecision(false)
            finish()
        }

        setContent {
            ScreenshoterTheme {
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                PreviewContent(
                    folderLabel = folderLabel,
                    image = imageBitmap,
                    onConfirm = {
                        sendDecision(true)
                        finish()
                    },
                    onDismiss = {
                        sendDecision(false)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        if (!decisionSent) {
            sendDecision(false)
        }
        super.onDestroy()
    }

    private fun sendDecision(accepted: Boolean) {
        if (decisionSent) return
        decisionSent = true
        val intent = Intent(this, ScreenshotService::class.java).apply {
            action = ScreenshotService.ACTION_PREVIEW_DECISION
            putExtra(ScreenshotService.EXTRA_PREVIEW_ACCEPTED, accepted)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
private fun PreviewContent(
    folderLabel: String,
    image: ImageBitmap,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .sizeIn(maxWidth = 520.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.capture_preview_title, folderLabel),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp
                ) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.capture_preview_content_description),
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(maxWidth = 440.dp, maxHeight = 420.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                RowWithActions(
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun RowWithActions(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(text = stringResource(R.string.capture_preview_reject))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = stringResource(R.string.capture_preview_accept))
        }
    }
}

