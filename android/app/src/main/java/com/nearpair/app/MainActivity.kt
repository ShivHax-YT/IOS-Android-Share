package com.nearpair.app

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nearpair.app.model.ReceivedFile
import com.nearpair.app.model.TransferDirection
import com.nearpair.app.model.TransferState
import com.nearpair.app.permissions.NearbyPermissions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: NearPairViewModel by viewModels()
    private var pendingPermissionAction: PermissionAction? = null

    private val directPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::stageForDirectTransfer)
    }

    private val systemSharePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::stageForSystemShare)
    }

    private val saveReceivedLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(viewModel::saveReceived)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val allGranted = NearbyPermissions.runtimePermissions(this).all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        val action = pendingPermissionAction
        pendingPermissionAction = null
        if (allGranted && action != null) performPermissionAction(action) else viewModel.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermissionAction = savedInstanceState
            ?.getString(STATE_PENDING_PERMISSION_ACTION)
            ?.let { saved -> PermissionAction.entries.firstOrNull { it.name == saved } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.shareRequests.collect { request ->
                    if (request != null) {
                        openAndroidShareSheet(request)
                        viewModel.shareRequestHandled(request)
                    }
                }
            }
        }
        setContent {
            MaterialTheme {
                NearPairApp(
                    viewModel = viewModel,
                    onSend = { requireNearbyPermissions(PermissionAction.SEND) },
                    onReceive = { requireNearbyPermissions(PermissionAction.RECEIVE) },
                    onSystemShare = { systemSharePicker.launch(allowedMimeTypes) },
                    onOpenSettings = ::openAppSettings,
                    onOpenConnectionsSettings = ::openConnectionsSettings,
                    onSaveReceived = ::createReceivedDocument,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingPermissionAction?.let { outState.putString(STATE_PENDING_PERMISSION_ACTION, it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun requireNearbyPermissions(action: PermissionAction) {
        val missing = NearbyPermissions.runtimePermissions(this).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            performPermissionAction(action)
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun performPermissionAction(action: PermissionAction) {
        val disabledRadios = disabledRadios()
        if (disabledRadios.isNotEmpty()) {
            viewModel.radiosDisabled(disabledRadios)
            return
        }
        when (action) {
            PermissionAction.SEND -> directPicker.launch(allowedMimeTypes)
            PermissionAction.RECEIVE -> viewModel.startReceiving()
        }
    }

    private fun openAndroidShareSheet(request: NearPairViewModel.ShareRequest) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", request.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = request.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(contentResolver, request.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share with Android"))
    }

    private fun createReceivedDocument(file: ReceivedFile) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = file.mimeType
            putExtra(Intent.EXTRA_TITLE, file.displayName)
        }
        saveReceivedLauncher.launch(intent)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

    private fun openConnectionsSettings() {
        val disabled = disabledRadios()
        val action = when {
            disabled == listOf("Bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            disabled == listOf("Wi-Fi") -> Settings.ACTION_WIFI_SETTINGS
            else -> Settings.ACTION_WIRELESS_SETTINGS
        }
        startActivity(Intent(action))
    }

    private fun disabledRadios(): List<String> = buildList {
        val bluetoothEnabled = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        val wifiEnabled = runCatching {
            getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        }.getOrDefault(false)
        if (!bluetoothEnabled) add("Bluetooth")
        if (!wifiEnabled) add("Wi-Fi")
    }

    private enum class PermissionAction { SEND, RECEIVE }

    companion object {
        private const val STATE_PENDING_PERMISSION_ACTION = "pendingPermissionAction"
        private val allowedMimeTypes = arrayOf("application/pdf", "image/*", "video/*")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearPairApp(
    viewModel: NearPairViewModel,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSystemShare: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnectionsSettings: () -> Unit,
    onSaveReceived: (ReceivedFile) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val received by viewModel.receivedFile.collectAsStateWithLifecycle()
    val isStaging by viewModel.isStaging.collectAsStateWithLifecycle()
    val uiError by viewModel.uiError.collectAsStateWithLifecycle()
    val uiErrorRecovery by viewModel.uiErrorRecovery.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color(0xFFF7F8FC),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NearPair", fontWeight = FontWeight.Bold)
                        Text("Direct. Nearby. Private.", style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            StateContent(
                state = state,
                received = received,
                isStaging = isStaging,
                uiError = uiError,
                uiErrorRecovery = uiErrorRecovery,
                deviceName = deviceName,
                onSend = onSend,
                onReceive = onReceive,
                onSystemShare = onSystemShare,
                onOpenSettings = onOpenSettings,
                onOpenConnectionsSettings = onOpenConnectionsSettings,
                onDismissError = viewModel::dismissUiError,
                onDeviceNameChanged = viewModel::updateDeviceName,
                onSelectDevice = viewModel::requestConnection,
                onAccept = viewModel::acceptConnection,
                onReject = viewModel::rejectConnection,
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onDone = viewModel::done,
                onSave = { received?.let(onSaveReceived) },
                onShareReceived = viewModel::shareReceived,
                onDelete = viewModel::deleteReceived,
            )
        }
    }
}

@Composable
private fun StateContent(
    state: TransferState,
    received: ReceivedFile?,
    isStaging: Boolean,
    uiError: String?,
    uiErrorRecovery: NearPairViewModel.UiErrorRecovery,
    deviceName: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSystemShare: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnectionsSettings: () -> Unit,
    onDismissError: () -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onSelectDevice: (String) -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onSave: () -> Unit,
    onShareReceived: () -> Unit,
    onDelete: () -> Unit,
) {
    if (uiError != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            icon = { Icon(Icons.Outlined.ErrorOutline, null) },
            title = { Text("Action needed") },
            text = { Text(uiError) },
            confirmButton = {
                when (uiErrorRecovery) {
                    NearPairViewModel.UiErrorRecovery.APP_PERMISSIONS -> {
                        TextButton(onClick = onOpenSettings) { Text("Open app permissions") }
                    }
                    NearPairViewModel.UiErrorRecovery.CONNECTIONS_SETTINGS -> {
                        TextButton(onClick = onOpenConnectionsSettings) { Text("Open Connections") }
                    }
                    NearPairViewModel.UiErrorRecovery.NONE -> {
                        TextButton(onClick = onDismissError) { Text("OK") }
                    }
                }
            },
            dismissButton = {
                if (uiErrorRecovery != NearPairViewModel.UiErrorRecovery.NONE) {
                    TextButton(onClick = onDismissError) { Text("Not now") }
                }
            },
        )
    }

    when (state) {
        TransferState.Idle -> HomeContent(
            isStaging,
            deviceName,
            onDeviceNameChanged,
            onSend,
            onReceive,
            onSystemShare,
        )
        is TransferState.PermissionsNeeded -> FailureContent(
            title = "Permissions needed",
            detail = state.action,
            primaryLabel = "Open Settings",
            onPrimary = onOpenSettings,
            onDone = onDone,
        )
        is TransferState.Advertising -> CenterStatus(
            icon = { Icon(Icons.Outlined.Bluetooth, null) },
            title = "Ready to receive",
            detail = "Visible nearby as ${state.deviceName}. Keep this screen open.",
            actionLabel = "Cancel",
            onAction = onCancel,
        )
        is TransferState.Discovering -> DiscoveringContent(state, onSelectDevice, onCancel)
        is TransferState.ConnectionRequested -> CenterStatus(
            icon = { CircularProgressIndicator() },
            title = "Connecting to ${state.device.name}",
            detail = "Waiting for both devices to show the authentication digits.",
            actionLabel = "Cancel",
            onAction = onCancel,
        )
        is TransferState.ConfirmCode -> {
            CenterStatus(
                icon = { Icon(Icons.Outlined.Lock, null) },
                title = "Compare authentication digits",
                detail = "Accept only if ${state.deviceName} shows the exact same digits.",
                actionLabel = "Cancel",
                onAction = { onReject(state.endpointId) },
            )
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Outlined.Lock, null) },
                title = { Text("Code on both devices") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(state.code, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Text("Does ${state.deviceName} show this exact code?", textAlign = TextAlign.Center)
                    }
                },
                confirmButton = { Button(onClick = { onAccept(state.endpointId) }) { Text("Codes match") } },
                dismissButton = { TextButton(onClick = { onReject(state.endpointId) }) { Text("Reject") } },
            )
        }
        is TransferState.Connected -> CenterStatus(
            icon = { CircularProgressIndicator() },
            title = "Securely connected",
            detail = "Preparing the file transfer with ${state.deviceName}.",
            actionLabel = "Cancel",
            onAction = onCancel,
        )
        is TransferState.Transferring -> TransferProgressContent(state, onCancel)
        is TransferState.Verifying -> CenterStatus(
            icon = { CircularProgressIndicator() },
            title = if (state.direction == TransferDirection.RECEIVE) "Verifying file" else "Waiting for verification",
            detail = if (state.direction == TransferDirection.RECEIVE) {
                "Checking byte size and SHA-256 before the file enters your inbox."
            } else {
                "Delivered will appear only after the receiver verifies the file."
            },
            actionLabel = "Cancel",
            onAction = onCancel,
        )
        is TransferState.Complete -> CompleteContent(state, received, onSave, onShareReceived, onDelete, onDone)
        is TransferState.Failed -> FailureContent(
            title = failureTitle(state),
            detail = state.detail,
            primaryLabel = when (state.reason) {
                com.nearpair.app.model.FailureReason.PERMISSION_DENIED -> "Open app permissions"
                com.nearpair.app.model.FailureReason.RADIOS_DISABLED -> "Open Connections"
                com.nearpair.app.model.FailureReason.APP_CONFIGURATION -> "Retry"
                else -> "Retry"
            },
            onPrimary = when (state.reason) {
                com.nearpair.app.model.FailureReason.PERMISSION_DENIED -> onOpenSettings
                com.nearpair.app.model.FailureReason.RADIOS_DISABLED -> onOpenConnectionsSettings
                com.nearpair.app.model.FailureReason.APP_CONFIGURATION -> onRetry
                else -> onRetry
            },
            secondaryLabel = if (state.reason == com.nearpair.app.model.FailureReason.PERMISSION_DENIED) {
                "Check again"
            } else {
                null
            },
            onSecondary = onRetry,
            onDone = onDone,
        )
        TransferState.Cancelled -> FailureContent(
            title = "Transfer cancelled",
            detail = "No partial file was kept. You can retry from the beginning.",
            primaryLabel = "Retry",
            onPrimary = onRetry,
            onDone = onDone,
        )
    }
}

@Composable
private fun HomeContent(
    isStaging: Boolean,
    deviceName: String,
    onDeviceNameChanged: (String) -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSystemShare: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Send files between Android and iPhone", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Both people install NearPair, keep it open, and approve matching digits. No internet, account, or cloud storage is required.")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF0FF))) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = Color(0xFF245DD8))
                    Column {
                        Text("Why permissions are needed", fontWeight = FontWeight.SemiBold)
                        Text("Bluetooth finds nearby devices. Wi-Fi/local network moves the file directly. File access is limited to files you choose and Nearby's required transfer access.")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = deviceName,
                onValueChange = onDeviceNameChanged,
                label = { Text("Nearby device name") },
                supportingText = { Text("Shown only while you send or receive") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = onSend, enabled = !isStaging, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.FileOpen, null)
                Text("  Send with NearPair")
            }
        }
        item {
            OutlinedButton(onClick = onReceive, enabled = !isStaging, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.Download, null)
                Text("  Receive")
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Android-owned shortcut", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Opens the Android share sheet. Android—not NearPair—controls Quick Share recipients and compatibility.")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onSystemShare, enabled = !isStaging, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.IosShare, null)
                Text("  Open Android share sheet")
            }
        }
        if (isStaging) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Staging and hashing the selected file…")
            }
        }
        item {
            Text(
                "NearPair itself sends no analytics and stores no files in the cloud. Google Play services may collect opt-out Nearby performance diagnostics under Google's published terms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DiscoveringContent(state: TransferState.Discovering, onSelectDevice: (String) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Search, null)
            Column {
                Text("Choose a receiver", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("The other person must tap Receive and keep NearPair open.")
            }
        }
        Spacer(Modifier.height(20.dp))
        if (state.devices.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(14.dp))
                    Text("No nearby receivers yet", fontWeight = FontWeight.SemiBold)
                    Text("Check Bluetooth and Wi-Fi on both devices, then keep them close.", textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(state.devices, key = { it.endpointId }) { device ->
                    Card(onClick = { onSelectDevice(device.endpointId) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PhoneAndroid, null)
                            Text(device.name, Modifier.padding(start = 14.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
    }
}

@Composable
private fun TransferProgressContent(state: TransferState.Transferring, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (state.direction == TransferDirection.SEND) "Sending" else "Receiving", style = MaterialTheme.typography.labelLarge)
        Text(state.fileName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("${formatBytes(state.transferredBytes)} of ${formatBytes(state.totalBytes)}")
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel transfer") }
    }
}

@Composable
private fun CompleteContent(
    state: TransferState.Complete,
    received: ReceivedFile?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF18864B))
        Spacer(Modifier.height(12.dp))
        Text(if (state.direction == TransferDirection.SEND) "Delivered" else "Verified and received", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(state.fileName, textAlign = TextAlign.Center)
        if (state.direction == TransferDirection.RECEIVE && received != null) {
            Spacer(Modifier.height(8.dp))
            Text("${formatBytes(received.sizeBytes)} • SHA-256 matched", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save a copy") }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.IosShare, null)
                Text("  Share")
            }
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.Delete, null)
                Text("  Delete")
            }
        } else {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun FailureContent(
    title: String,
    detail: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDone: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(detail, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            if (primaryLabel == "Open Settings") Icon(Icons.Outlined.Settings, null)
            Text("  $primaryLabel")
        }
        if (secondaryLabel != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
        TextButton(onClick = onDone) { Text("Back to home") }
    }
}

@Composable
private fun CenterStatus(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(detail, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

private fun failureTitle(state: TransferState.Failed): String = when (state.reason) {
    com.nearpair.app.model.FailureReason.PERMISSION_DENIED -> "Permission denied"
    com.nearpair.app.model.FailureReason.APP_CONFIGURATION -> "NearPair needs an updated build"
    com.nearpair.app.model.FailureReason.RADIOS_DISABLED -> "Bluetooth or Wi-Fi is unavailable"
    com.nearpair.app.model.FailureReason.INSUFFICIENT_STORAGE -> "Not enough storage"
    com.nearpair.app.model.FailureReason.CHECKSUM_MISMATCH,
    com.nearpair.app.model.FailureReason.SIZE_MISMATCH -> "Integrity check failed"
    com.nearpair.app.model.FailureReason.CONNECTION_LOST -> "Connection lost"
    com.nearpair.app.model.FailureReason.BACKGROUNDED -> "Transfer stopped"
    else -> "Transfer failed"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index += 1
    } while (value >= 1024.0 && index < units.lastIndex)
    return "%.1f %s".format(value, units[index])
}
