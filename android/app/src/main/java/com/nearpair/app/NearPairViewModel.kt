package com.nearpair.app

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearpair.app.files.FileStore
import com.nearpair.app.model.FailureReason
import com.nearpair.app.model.ReceivedFile
import com.nearpair.app.model.StagedFile
import com.nearpair.app.model.TransferState
import com.nearpair.app.nearby.NearbyTransferEngine
import com.nearpair.app.nearby.TransferEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class NearPairViewModel(application: Application) : AndroidViewModel(application) {
    private val fileStore = FileStore(application)
    private val engine: TransferEngine = NearbyTransferEngine(application, fileStore)

    val state: StateFlow<TransferState> = engine.state
    val devices = engine.devices
    val receivedFile: StateFlow<ReceivedFile?> = engine.receivedFile

    private val _isStaging = MutableStateFlow(false)
    val isStaging: StateFlow<Boolean> = _isStaging.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _uiErrorRecovery = MutableStateFlow(UiErrorRecovery.NONE)
    val uiErrorRecovery: StateFlow<UiErrorRecovery> = _uiErrorRecovery.asStateFlow()

    private val _shareRequests = MutableStateFlow<ShareRequest?>(null)
    val shareRequests: StateFlow<ShareRequest?> = _shareRequests.asStateFlow()

    private var pendingOutgoing: StagedFile? = null
    private var sendStartedForEndpoint: String? = null
    private var lastRole: Role? = null
    private var appInForeground = false

    private val _deviceName = MutableStateFlow(
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(48)
            .ifBlank { "Android device" },
    )
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    init {
        viewModelScope.launch {
            state.collect { current ->
                if (current is TransferState.Connected && pendingOutgoing != null && sendStartedForEndpoint != current.endpointId) {
                    sendStartedForEndpoint = current.endpointId
                    engine.sendFile(requireNotNull(pendingOutgoing))
                }
            }
        }
    }

    fun stageForDirectTransfer(uri: Uri) {
        lastRole = Role.SEND
        _uiError.value = null
        _uiErrorRecovery.value = UiErrorRecovery.NONE
        _isStaging.value = true
        viewModelScope.launch {
            fileStore.stageOutgoing(uri)
                .onSuccess { staged ->
                    if (appInForeground) {
                        pendingOutgoing?.let(fileStore::delete)
                        pendingOutgoing = staged
                        sendStartedForEndpoint = null
                        engine.discoverNearbyDevices(deviceName.value)
                    } else {
                        fileStore.delete(staged)
                        _uiError.value = "NearPair must remain open while preparing a direct transfer. Return and tap Send again."
                    }
                }
                .onFailure { _uiError.value = it.localizedMessage ?: "The selected file could not be staged." }
            _isStaging.value = false
        }
    }

    fun stageForSystemShare(uri: Uri) {
        _uiError.value = null
        _uiErrorRecovery.value = UiErrorRecovery.NONE
        _isStaging.value = true
        viewModelScope.launch {
            fileStore.stageOutgoing(uri)
                .onSuccess { staged ->
                    _shareRequests.value = ShareRequest(
                        file = staged.file,
                        displayName = staged.displayName,
                        mimeType = staged.mimeType,
                    )
                }
                .onFailure { _uiError.value = it.localizedMessage ?: "The selected file could not be shared." }
            _isStaging.value = false
        }
    }

    fun startReceiving() {
        lastRole = Role.RECEIVE
        _uiError.value = null
        engine.startReceiving(deviceName.value)
    }

    fun requestConnection(endpointId: String) {
        val device = devices.value.firstOrNull { it.endpointId == endpointId } ?: return
        engine.requestConnection(device, deviceName.value)
    }

    fun acceptConnection(endpointId: String) = engine.acceptConnection(endpointId)

    fun rejectConnection(endpointId: String) = engine.rejectConnection(endpointId)

    fun cancel() = engine.cancelTransfer()

    fun retry() {
        _uiError.value = null
        sendStartedForEndpoint = null
        engine.reset()
        when (lastRole) {
            Role.SEND -> if (pendingOutgoing != null) engine.discoverNearbyDevices(deviceName.value)
            Role.RECEIVE -> engine.startReceiving(deviceName.value)
            null -> Unit
        }
    }

    fun done() {
        pendingOutgoing?.let(fileStore::delete)
        pendingOutgoing = null
        sendStartedForEndpoint = null
        lastRole = null
        engine.reset()
        _uiError.value = null
        _uiErrorRecovery.value = UiErrorRecovery.NONE
    }

    fun permissionDenied() {
        _uiErrorRecovery.value = UiErrorRecovery.APP_PERMISSIONS
        _uiError.value = "Allow NearPair's Nearby devices permission on the app settings screen, then return and retry. Bluetooth and Wi-Fi are turned on separately in Android's Connections settings."
    }

    fun radiosDisabled(radios: List<String>) {
        _uiErrorRecovery.value = UiErrorRecovery.CONNECTIONS_SETTINGS
        _uiError.value = "Turn on ${radios.joinToString(" and ")} in Android's Connections settings, then return and retry."
    }

    fun dismissUiError() {
        _uiError.value = null
        _uiErrorRecovery.value = UiErrorRecovery.NONE
    }

    fun updateDeviceName(value: String) {
        _deviceName.value = value.take(48)
    }

    fun shareReceived() {
        val received = receivedFile.value ?: return
        _shareRequests.value = ShareRequest(received.file, received.displayName, received.mimeType)
    }

    fun shareRequestHandled(request: ShareRequest) {
        // Android has no reliable callback for when the selected share target
        // finishes reading a granted URI. Keep this cache file available and
        // let FileStore's stale-file cleanup remove it on a later app start.
        _shareRequests.compareAndSet(request, null)
    }

    fun saveReceived(destination: Uri) {
        val received = receivedFile.value ?: return
        viewModelScope.launch {
            fileStore.copyReceivedTo(received, destination)
                .onSuccess {
                    fileStore.delete(received)
                    done()
                }
                .onFailure { _uiError.value = it.localizedMessage ?: "The file could not be saved." }
        }
    }

    fun deleteReceived() {
        receivedFile.value?.let(fileStore::delete)
        done()
    }

    fun onAppForegrounded() {
        appInForeground = true
    }

    fun onAppBackgrounded() {
        appInForeground = false
        engine.onAppBackgrounded()
    }

    override fun onCleared() {
        pendingOutgoing?.let(fileStore::delete)
        engine.close()
        super.onCleared()
    }

    data class ShareRequest(
        val file: File,
        val displayName: String,
        val mimeType: String,
    )

    enum class UiErrorRecovery { NONE, APP_PERMISSIONS, CONNECTIONS_SETTINGS }

    private enum class Role { SEND, RECEIVE }
}
