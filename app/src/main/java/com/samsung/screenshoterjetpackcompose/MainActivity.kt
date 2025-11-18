package com.samsung.screenshoterjetpackcompose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.samsung.screenshoterjetpackcompose.ui.theme.ScreenshoterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val OVERLAY_PERMISSION_PACKAGE_PREFIX = "package"

class MainActivity : ComponentActivity() {

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ScreenshotService.start(this, result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestMediaProjection()
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canDrawOverlays()) {
                ensurePermissionsAndRequestCapture()
            } else {
                Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val browseSnapshotsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenCaptureManager.initializeSettings(applicationContext)
        setContent {
            ScreenshoterTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val captureActive by ScreenCaptureManager.isSessionActive.collectAsState()
                    val selectedFolder by ScreenCaptureManager.currentSubdirectory.collectAsState()
                    val requiresConfirmation by ScreenCaptureManager.requiresConfirmation.collectAsState()
                    MainScreen(
                        isCaptureReady = captureActive,
                        selectedFolder = selectedFolder,
                        onSelectFolder = { ScreenCaptureManager.updateSubdirectory(it) },
                        onRequestCapture = { startCaptureFlow() },
                        onStopService = { stopCaptureService() },
                        onSaveCapture = { ScreenCaptureManager.captureAndStore(applicationContext) },
                        onBrowseSnapshots = { openSnapshotFolder() },
                        requiresConfirmation = requiresConfirmation,
                        onRequiresConfirmationChanged = {
                            ScreenCaptureManager.updateRequiresConfirmation(applicationContext, it)
                        }
                    )
                }
            }
        }
    }

    private fun startCaptureFlow() {
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        ensurePermissionsAndRequestCapture()
    }

    private fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("$OVERLAY_PERMISSION_PACKAGE_PREFIX:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openSnapshotFolder() {

        val initialUri = buildSnapshotDocumentUri()
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                data = initialUri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }

        if (intent.resolveActivity(packageManager) != null) {
            try {
                if (intent.action == Intent.ACTION_VIEW) {
                    startActivity(intent)
                } else {
                    browseSnapshotsLauncher.launch(intent)
                }
            } catch (error: Exception) {
                browseSnapshotsLauncher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                    }
                )
            }
        } else {
            browseSnapshotsLauncher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            )
        }
    }

    private fun stopCaptureService() {
        ScreenshotService.stop(this)
    }

    private fun buildSnapshotDocumentUri(): Uri {
        val docId = "primary:${android.os.Environment.DIRECTORY_PICTURES}/Screenshoter"
        val encodedDocId = Uri.encode(docId)
        return Uri.parse("content://com.android.externalstorage.documents/document/$encodedDocId")
    }

    private fun ensurePermissionsAndRequestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

private data class CollectionOption(val key: String, val label: String)

private data class DefaultCollectionDefinition(val key: String, @StringRes val labelRes: Int)

private val defaultCollectionDefinitions = listOf(
    DefaultCollectionDefinition("", R.string.folder_default),
    DefaultCollectionDefinition("movies", R.string.folder_movies),
    DefaultCollectionDefinition("food", R.string.folder_food),
    DefaultCollectionDefinition("shopping", R.string.folder_shopping),
    DefaultCollectionDefinition("conversation", R.string.folder_conversation),
    DefaultCollectionDefinition("location", R.string.folder_location),
    DefaultCollectionDefinition("coupon", R.string.folder_coupon),
    DefaultCollectionDefinition("calendar", R.string.folder_calendar),
    DefaultCollectionDefinition("restaurant", R.string.folder_restaurant),
    DefaultCollectionDefinition("fashion", R.string.folder_fashion),
    DefaultCollectionDefinition("transportation", R.string.folder_transportation),
    DefaultCollectionDefinition("humor", R.string.folder_humor),
    DefaultCollectionDefinition("article", R.string.folder_article),
    DefaultCollectionDefinition("music", R.string.folder_music),
    DefaultCollectionDefinition("people", R.string.folder_people),
    DefaultCollectionDefinition("books", R.string.folder_books),
    DefaultCollectionDefinition("stock", R.string.folder_stock),
    DefaultCollectionDefinition("sports", R.string.folder_sports),
    DefaultCollectionDefinition("health", R.string.folder_health)
)

@Composable
fun MainScreen(
    isCaptureReady: Boolean,
    selectedFolder: String,
    onSelectFolder: (String) -> Unit,
    onRequestCapture: () -> Unit,
    onStopService: () -> Unit,
    onSaveCapture: suspend () -> Uri?,
    onBrowseSnapshots: () -> Unit,
    requiresConfirmation: Boolean,
    onRequiresConfirmationChanged: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val scope = rememberCoroutineScope()
    val (statusMessage, setStatusMessage) = remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val captureEvents by ScreenCaptureManager.captureEvents.collectAsState()

    val customCollectionsState = remember { mutableStateOf<List<CollectionRepository.CustomCollection>>(emptyList()) }
    val customCollections = customCollectionsState.value

    val defaultOptions = defaultCollectionDefinitions.map {
        CollectionOption(it.key, stringResource(id = it.labelRes))
    }
    val defaultKeys = remember { defaultCollectionDefinitions.map { ScreenCaptureManager.normalizeDirectoryName(it.key) }.toSet() }

    val folderOptions = defaultOptions + customCollections.map { CollectionOption(it.key, it.label) }
    val folderKeys = remember(folderOptions) { folderOptions.map { it.key } }

    val folderCountsState = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val folderCounts = folderCountsState.value

    val selectedOption = folderOptions.firstOrNull { it.key == selectedFolder }
    val selectedLabel = selectedOption?.label
        ?: selectedFolder.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercaseChar() }
        ?: defaultOptions.first().label
    val selectedCount = folderCounts[selectedFolder] ?: 0
    val selectedLabelWithCount = "$selectedLabel ($selectedCount)"

    val totalCollections = folderOptions.size
    val totalSnapshots = folderCounts.values.sum()

    val showAddDialog = remember { mutableStateOf(false) }
    val newCollectionName = remember { mutableStateOf("") }
    val addCollectionError = remember { mutableStateOf<Int?>(null) }
    val isAddingCollection = remember { mutableStateOf(false) }

    val showZipDialog = remember { mutableStateOf(false) }
    val zipUsername = remember {
        mutableStateOf(
            CollectionRepository.getLastZipUsername(context.applicationContext) ?: ""
        )
    }
    val zipErrorMessage = remember { mutableStateOf<Int?>(null) }
    val zipStatusMessage = remember { mutableStateOf<String?>(null) }
    val isZipping = remember { mutableStateOf(false) }

    LaunchedEffect(isPreview) {
        if (!isPreview) {
            customCollectionsState.value = CollectionRepository.load(context.applicationContext)
        }
        folderCountsState.value = folderKeys.associateWith { 0 }
    }

    LaunchedEffect(folderKeys, captureEvents, isPreview, customCollections) {
        if (!isPreview) {
            folderCountsState.value = ScreenCaptureManager.getFolderItemCounts(
                context.applicationContext,
                folderKeys
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.screen_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        val sortedFolderOptions = listOf(folderOptions.first()) +
                folderOptions.drop(1).sortedBy { it.label.lowercase(Locale.getDefault()) }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.setup_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = onRequestCapture,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(text = stringResource(R.string.button_start_capture))
                                }

                                Button(
                                    onClick = onStopService,
                                    enabled = isCaptureReady,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Text(text = stringResource(R.string.button_stop_capture))
                                }
                            }

                            Text(
                                text = if (isCaptureReady) {
                                    stringResource(R.string.capture_ready)
                                } else {
                                    stringResource(R.string.capture_not_ready)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCaptureReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = requiresConfirmation,
                                    onCheckedChange = { onRequiresConfirmationChanged(it) }
                                )
                                Column(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clickable { onRequiresConfirmationChanged(!requiresConfirmation) },
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_require_confirmation_title),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.setting_require_confirmation_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val uri = onSaveCapture()
                                            if (uri != null) {
                                                val currentOptions = folderOptions
                                                val folderLabel = currentOptions.firstOrNull { it.key == selectedFolder }?.label ?: selectedLabel
                                                val message = context.getString(R.string.capture_saved_simple, folderLabel)
                                                setStatusMessage(null)
                                                setStatusMessage(message)
                                            } else {
                                                val message = context.getString(R.string.capture_failed)
                                                setStatusMessage(null)
                                                setStatusMessage(message)
                                            }
                                        }
                                    },
                                    enabled = isCaptureReady,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    )
                                ) {
                                    Text(text = stringResource(R.string.button_save_capture))
                                }

                                Button(
                                    onClick = onBrowseSnapshots,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary
                                    )
                                ) {
                                    Text(text = stringResource(R.string.button_browse_snapshots))
                                }
                            }

                            statusMessage?.let { message ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    tonalElevation = 2.dp,
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    Text(
                                        text = message,
                                        modifier = Modifier
                                            .padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.collections_section_title),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.selected_folder, selectedLabelWithCount),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.total_collections_format, totalCollections),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                Text(
                                    text = stringResource(R.string.total_snapshots_format, totalSnapshots),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            zipStatusMessage.value?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        zipErrorMessage.value = null
                                        showZipDialog.value = true
                                    },
                                    enabled = !isZipping.value,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Text(text = stringResource(R.string.button_zip_collections))
                                }

                                Button(
                                    onClick = {
                                        newCollectionName.value = ""
                                        addCollectionError.value = null
                                        showAddDialog.value = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Text(text = stringResource(R.string.button_add_collection))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Folder list",
                    style = MaterialTheme.typography.titleMedium
                )
            }


            items(sortedFolderOptions, key = { it.key }) { option ->
                val count = folderCounts[option.key] ?: 0
                val displayLabel = "${option.label} ($count)"
                val isSelected = option.key == selectedFolder
                CollectionItem(
                    label = displayLabel,
                    isSelected = isSelected,
                    onClick = { onSelectFolder(option.key) }
                )
            }
        }
    }

    if (showAddDialog.value) {
        AlertDialog(
            onDismissRequest = {
                if (!isAddingCollection.value) {
                    showAddDialog.value = false
                    newCollectionName.value = ""
                    addCollectionError.value = null
                }
            },
            title = {
                Text(text = stringResource(R.string.add_collection_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCollectionName.value,
                        onValueChange = {
                            newCollectionName.value = it
                            addCollectionError.value = null
                        },
                        label = { Text(text = stringResource(R.string.add_collection_label)) },
                        singleLine = true
                    )
                    addCollectionError.value?.let { resId ->
                        Text(
                            text = stringResource(id = resId),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isAddingCollection.value) return@TextButton
                        isAddingCollection.value = true
                        scope.launch {
                            val reservedKeys =
                                defaultKeys + customCollectionsState.value.map { it.key }
                            val result = withContext(Dispatchers.IO) {
                                CollectionRepository.add(
                                    context.applicationContext,
                                    newCollectionName.value,
                                    reservedKeys
                                )
                            }
                            when (result) {
                                is CollectionRepository.AddResult.Success -> {
                                    customCollectionsState.value = result.collections
                                    showAddDialog.value = false
                                    newCollectionName.value = ""
                                    addCollectionError.value = null
                                }
                                is CollectionRepository.AddResult.Error -> {
                                    addCollectionError.value = result.messageRes
                                }
                            }
                            isAddingCollection.value = false
                        }
                    },
                    enabled = !isAddingCollection.value
                ) {
                    Text(text = stringResource(R.string.add_collection_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isAddingCollection.value) return@TextButton
                        showAddDialog.value = false
                        newCollectionName.value = ""
                        addCollectionError.value = null
                    }
                ) {
                    Text(text = stringResource(R.string.add_collection_cancel))
                }
            }
        )
    }

    if (showZipDialog.value) {
        AlertDialog(
            onDismissRequest = {
                if (!isZipping.value) {
                    showZipDialog.value = false
                    zipErrorMessage.value = null
                }
            },
            title = {
                Text(text = stringResource(R.string.zip_collections_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = zipUsername.value,
                        onValueChange = {
                            zipUsername.value = it
                            zipErrorMessage.value = null
                        },
                        label = { Text(text = stringResource(R.string.zip_collections_username_label)) },
                        singleLine = true
                    )
                    zipErrorMessage.value?.let { resId ->
                        Text(
                            text = stringResource(id = resId),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isZipping.value) return@TextButton
                        val enteredName = zipUsername.value.trim()
                        if (enteredName.isEmpty()) {
                            zipErrorMessage.value = R.string.error_zip_username_empty
                            return@TextButton
                        }
                        isZipping.value = true
                        zipStatusMessage.value = context.getString(R.string.zip_collections_progress)
                        zipErrorMessage.value = null
                        scope.launch {
                            try {
                                val collectionsForZip = folderOptions.map { it.key to it.label }
                                val results = withContext(Dispatchers.IO) {
                                    ScreenCaptureManager.zipCollections(
                                        context.applicationContext,
                                        collectionsForZip,
                                        enteredName
                                    ) { current, total, labelText ->
                                        withContext(Dispatchers.Main) {
                                            zipStatusMessage.value = context.getString(
                                                R.string.zip_collections_progress_item,
                                                current,
                                                total,
                                                labelText
                                            )
                                        }
                                    }
                                }
                                val successCount = results.count { it.success }
                                val totalZips = results.size
                                val failureCount = totalZips - successCount
                                zipStatusMessage.value = when {
                                    totalZips == 0 -> context.getString(R.string.zip_collections_none)
                                    successCount == 0 -> context.getString(R.string.zip_collections_failure)
                                    failureCount == 0 -> context.getString(
                                        R.string.zip_collections_success,
                                        successCount
                                    )
                                    else -> context.getString(
                                        R.string.zip_collections_partial,
                                        successCount,
                                        failureCount
                                    )
                                }
                                if (successCount > 0) {
                                    CollectionRepository.saveLastZipUsername(
                                        context.applicationContext,
                                        enteredName
                                    )
                                }
                                zipUsername.value = enteredName
                            } catch (_: Throwable) {
                                zipStatusMessage.value = context.getString(R.string.zip_collections_failure)
                            } finally {
                                zipErrorMessage.value = null
                                showZipDialog.value = false
                                isZipping.value = false
                            }
                        }
                    },
                    enabled = !isZipping.value
                ) {
                    Text(text = stringResource(R.string.zip_collections_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isZipping.value) return@TextButton
                        showZipDialog.value = false
                        zipErrorMessage.value = null
                    }
                ) {
                    Text(text = stringResource(R.string.zip_collections_cancel))
                }
            }
        )
    }

}

@Composable
private fun CollectionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Text(
            text = if (isSelected) {
                stringResource(R.string.selected_folder_format, label)
            } else {
                label
            },
            modifier = Modifier
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ScreenshoterTheme {
        MainScreen(
            isCaptureReady = false,
            selectedFolder = "",
            onSelectFolder = {},
            onRequestCapture = {},
            onStopService = {},
            onSaveCapture = { null },
            onBrowseSnapshots = {},
            requiresConfirmation = true,
            onRequiresConfirmationChanged = {},
            contentPadding = PaddingValues()
        )
    }
}