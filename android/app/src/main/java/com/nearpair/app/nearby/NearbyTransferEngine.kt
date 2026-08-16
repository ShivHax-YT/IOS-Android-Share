package com.nearpair.app.nearby

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nearpair.app.files.FileStore
import com.nearpair.app.model.FailureReason
import com.nearpair.app.model.NearbyDevice
import com.nearpair.app.model.ReceivedFile
import com.nearpair.app.model.StagedFile
import com.nearpair.app.model.TransferDirection
import com.nearpair.app.model.TransferState
import com.nearpair.app.protocol.ErrorAcknowledgement
import com.nearpair.app.protocol.PROTOCOL_VERSION
import com.nearpair.app.protocol.ProtocolErrorCode
import com.nearpair.app.protocol.SERVICE_ID
import com.nearpair.app.protocol.TransferMetadata
import com.nearpair.app.protocol.VerifiedAcknowledgement
import com.nearpair.app.protocol.WireCodec
import com.nearpair.app.protocol.WireMessage
import com.nearpair.app.permissions.NearbyPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class NearbyTransferEngine(
    context: Context,
    private val fileStore: FileStore,
) : TransferEngine {
    private val appContext = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    override val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    override val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private val _receivedFile = MutableStateFlow<ReceivedFile?>(null)
    override val receivedFile: StateFlow<ReceivedFile?> = _receivedFile.asStateFlow()

    private var localDeviceName = "Android device"
    private var currentEndpointId: String? = null
    private var currentEndpointName: String? = null
    private var activePayloadId: Long? = null
    private var outgoing: OutgoingTransfer? = null
    private val incomingMetadata = mutableMapOf<Long, TransferMetadata>()
    private val incomingPayloads = mutableMapOf<Long, Payload>()
    private val successfulIncomingPayloads = mutableSetOf<Long>()
    private val finalizingPayloads = mutableSetOf<Long>()
    private var outgoingPayloadSucceeded = false
    private var outgoingVerified = false
    private var pendingNearbyStart = false
    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    private var queuedAdvertisingStart: PendingAdvertisingStart? = null
    private var queuedDiscoveryStart: PendingDiscoveryStart? = null
    private var sessionGeneration = 0L

    private fun discoveryCallbackFor(generation: Long) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            scope.launch {
                if (generation != sessionGeneration) return@launch
                if (_devices.value.none { it.endpointId == endpointId }) {
                    _devices.value = (_devices.value + NearbyDevice(endpointId, info.endpointName))
                        .sortedBy { it.name.lowercase() }
                }
                if (_state.value is TransferState.Discovering) {
                    _state.value = TransferState.Discovering(_devices.value)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            scope.launch {
                if (generation != sessionGeneration) return@launch
                _devices.value = _devices.value.filterNot { it.endpointId == endpointId }
                if (_state.value is TransferState.Discovering) {
                    _state.value = TransferState.Discovering(_devices.value)
                }
            }
        }
    }

    private fun connectionCallbackFor(generation: Long) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            scope.launch {
                if (generation != sessionGeneration) {
                    return@launch
                }
                val state = _state.value
                val canPair = state is TransferState.Advertising ||
                    state is TransferState.ConnectionRequested || pendingNearbyStart
                if (!canPair) {
                    client.rejectConnection(endpointId)
                    return@launch
                }
                val alreadyWorkingWith = currentEndpointId
                if (alreadyWorkingWith != null && alreadyWorkingWith != endpointId) {
                    client.rejectConnection(endpointId)
                    return@launch
                }
                currentEndpointId = endpointId
                currentEndpointName = info.endpointName
                _state.value = TransferState.ConfirmCode(
                    endpointId = endpointId,
                    deviceName = info.endpointName,
                    code = info.authenticationDigits,
                )
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            scope.launch {
                if (generation != sessionGeneration) {
                    return@launch
                }
                if (endpointId != currentEndpointId) {
                    if (result.status.isSuccess) client.disconnectFromEndpoint(endpointId)
                    return@launch
                }
                val state = _state.value
                if (state !is TransferState.ConnectionRequested && state !is TransferState.ConfirmCode) {
                    return@launch
                }
                if (result.status.isSuccess) {
                    client.stopAdvertising()
                    client.stopDiscovery()
                    _state.value = TransferState.Connected(
                        endpointId = endpointId,
                        deviceName = currentEndpointName ?: "Nearby device",
                    )
                } else {
                    fail(FailureReason.REJECTED, "The other device rejected or could not establish the connection.")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            scope.launch {
                if (generation != sessionGeneration || currentEndpointId != endpointId) return@launch
                currentEndpointId = null
                val terminal = _state.value is TransferState.Complete ||
                    _state.value is TransferState.Cancelled || _state.value is TransferState.Failed
                if (!terminal) {
                    fail(FailureReason.CONNECTION_LOST, "The devices disconnected. Move closer and retry from the beginning.")
                }
            }
        }
    }

    private fun payloadCallbackFor(generation: Long) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            scope.launch {
                if (generation != sessionGeneration || endpointId != currentEndpointId ||
                    _state.value is TransferState.Cancelled ||
                    _state.value is TransferState.Failed || _state.value is TransferState.Complete ||
                    _state.value is TransferState.Idle
                ) {
                    if (payload.type == Payload.Type.FILE || payload.type == Payload.Type.STREAM) {
                        discardPayload(payload)
                    }
                    return@launch
                }
                when (payload.type) {
                    Payload.Type.BYTES -> payload.asBytes()?.let { handleBytes(endpointId, it) }
                    Payload.Type.FILE -> {
                        val currentPayloadId = activePayloadId
                        if (currentPayloadId != null && currentPayloadId != payload.id) {
                            discardPayload(payload)
                            return@launch
                        }
                        if (incomingPayloads.containsKey(payload.id)) return@launch
                        activePayloadId = payload.id
                        incomingPayloads[payload.id] = payload
                        val metadata = incomingMetadata[payload.id]
                        _state.value = TransferState.Transferring(
                            direction = TransferDirection.RECEIVE,
                            fileName = metadata?.fileName ?: "Receiving file",
                            transferredBytes = 0L,
                            totalBytes = metadata?.sizeBytes ?: 0L,
                        )
                        attemptFinalizeIncoming(payload.id)
                    }
                    else -> {
                        discardPayload(payload)
                        fail(FailureReason.UNSUPPORTED_TYPE, "This protocol version does not accept stream payloads.")
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            scope.launch {
                if (generation != sessionGeneration || endpointId != currentEndpointId) return@launch
                if (_state.value is TransferState.Cancelled || _state.value is TransferState.Failed ||
                    _state.value is TransferState.Complete || _state.value is TransferState.Idle
                ) return@launch
                val outgoingTransfer = outgoing
                val incoming = incomingMetadata[update.payloadId]
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        when {
                            outgoingTransfer?.payloadId == update.payloadId -> {
                                _state.value = TransferState.Transferring(
                                    TransferDirection.SEND,
                                    outgoingTransfer.file.displayName,
                                    update.bytesTransferred,
                                    outgoingTransfer.file.sizeBytes,
                                )
                            }
                            incoming != null -> {
                                _state.value = TransferState.Transferring(
                                    TransferDirection.RECEIVE,
                                    incoming.fileName,
                                    update.bytesTransferred,
                                    incoming.sizeBytes,
                                )
                            }
                        }
                    }

                    PayloadTransferUpdate.Status.SUCCESS -> {
                        when {
                            outgoingTransfer?.payloadId == update.payloadId -> {
                                outgoingPayloadSucceeded = true
                                _state.value = TransferState.Verifying(
                                    TransferDirection.SEND,
                                    outgoingTransfer.file.displayName,
                                )
                                attemptCompleteOutgoing()
                            }
                            incomingPayloads.containsKey(update.payloadId) -> {
                                successfulIncomingPayloads += update.payloadId
                                attemptFinalizeIncoming(update.payloadId)
                            }
                        }
                    }

                    PayloadTransferUpdate.Status.CANCELED -> {
                        if (update.payloadId == activePayloadId) {
                            finishCancelled()
                        }
                    }

                    PayloadTransferUpdate.Status.FAILURE -> {
                        if (update.payloadId == activePayloadId || incomingPayloads.containsKey(update.payloadId)) {
                            fail(FailureReason.CONNECTION_LOST, "The file transfer failed. Partial data was discarded.")
                        }
                    }
                }
            }
        }
    }

    override fun startReceiving(deviceName: String) {
        resetSession(keepTerminalState = true)
        val generation = sessionGeneration
        pendingNearbyStart = true
        localDeviceName = deviceName.trim().ifBlank { "Android device" }
        scheduleAdvertising(PendingAdvertisingStart(generation, localDeviceName))
    }

    private fun scheduleAdvertising(start: PendingAdvertisingStart) {
        if (start.generation != sessionGeneration) return
        if (advertisingStartInFlight) {
            queuedAdvertisingStart = start
            return
        }
        launchAdvertising(start)
    }

    private fun launchAdvertising(start: PendingAdvertisingStart) {
        if (start.generation != sessionGeneration) return
        advertisingStartInFlight = true
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startAdvertising(start.deviceName, SERVICE_ID, connectionCallbackFor(start.generation), options)
            .addOnCompleteListener { task ->
                advertisingStartInFlight = false
                val replacement = queuedAdvertisingStart
                queuedAdvertisingStart = null
                when {
                    replacement != null && replacement.generation == sessionGeneration -> {
                        client.stopAdvertising()
                        launchAdvertising(replacement)
                    }
                    start.generation != sessionGeneration -> {
                        client.stopAdvertising()
                    }
                    task.isSuccessful -> {
                        pendingNearbyStart = false
                        _state.value = TransferState.Advertising(start.deviceName)
                    }
                    else -> {
                        pendingNearbyStart = false
                        startFailure(
                            "receiving",
                            task.exception ?: IllegalStateException("Nearby advertising did not start."),
                        )
                    }
                }
            }
    }

    override fun stopReceiving() {
        sessionGeneration += 1
        pendingNearbyStart = false
        queuedAdvertisingStart = null
        client.stopAdvertising()
        if (_state.value is TransferState.Advertising) _state.value = TransferState.Idle
    }

    override fun discoverNearbyDevices(deviceName: String) {
        resetSession(keepTerminalState = true)
        val generation = sessionGeneration
        pendingNearbyStart = true
        localDeviceName = deviceName.trim().ifBlank { "Android device" }
        _devices.value = emptyList()
        scheduleDiscovery(PendingDiscoveryStart(generation))
    }

    private fun scheduleDiscovery(start: PendingDiscoveryStart) {
        if (start.generation != sessionGeneration) return
        if (discoveryStartInFlight) {
            queuedDiscoveryStart = start
            return
        }
        launchDiscovery(start)
    }

    private fun launchDiscovery(start: PendingDiscoveryStart) {
        if (start.generation != sessionGeneration) return
        discoveryStartInFlight = true
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        client.startDiscovery(SERVICE_ID, discoveryCallbackFor(start.generation), options)
            .addOnCompleteListener { task ->
                discoveryStartInFlight = false
                val replacement = queuedDiscoveryStart
                queuedDiscoveryStart = null
                when {
                    replacement != null && replacement.generation == sessionGeneration -> {
                        client.stopDiscovery()
                        launchDiscovery(replacement)
                    }
                    start.generation != sessionGeneration -> {
                        client.stopDiscovery()
                    }
                    task.isSuccessful -> {
                        pendingNearbyStart = false
                        _state.value = TransferState.Discovering(emptyList())
                    }
                    else -> {
                        pendingNearbyStart = false
                        startFailure(
                            "searching",
                            task.exception ?: IllegalStateException("Nearby discovery did not start."),
                        )
                    }
                }
            }
    }

    override fun stopDiscovery() {
        sessionGeneration += 1
        pendingNearbyStart = false
        queuedDiscoveryStart = null
        client.stopDiscovery()
        if (_state.value is TransferState.Discovering) _state.value = TransferState.Idle
    }

    override fun requestConnection(device: NearbyDevice, localDeviceName: String) {
        if (currentEndpointId != null && currentEndpointId != device.endpointId) return
        this.localDeviceName = localDeviceName.trim().ifBlank { "Android device" }
        currentEndpointId = device.endpointId
        currentEndpointName = device.name
        _state.value = TransferState.ConnectionRequested(device)
        val generation = sessionGeneration
        client.requestConnection(this.localDeviceName, device.endpointId, connectionCallbackFor(generation))
            .addOnFailureListener { error ->
                if (generation == sessionGeneration) {
                    fail(FailureReason.CONNECTION_LOST, error.localizedMessage ?: "Could not request the connection.")
                }
            }
    }

    override fun acceptConnection(endpointId: String) {
        if (currentEndpointId != endpointId || _state.value !is TransferState.ConfirmCode) return
        val generation = sessionGeneration
        client.acceptConnection(endpointId, payloadCallbackFor(generation))
            .addOnFailureListener { error ->
                if (generation == sessionGeneration) {
                    fail(FailureReason.CONNECTION_LOST, error.localizedMessage ?: "Could not accept the connection.")
                }
            }
    }

    override fun rejectConnection(endpointId: String) {
        sessionGeneration += 1
        client.rejectConnection(endpointId)
        _state.value = TransferState.Failed(FailureReason.REJECTED, "Connection rejected. No file was transferred.")
        resetTransportOnly()
    }

    override fun sendFile(file: StagedFile) {
        val endpointId = currentEndpointId
        if (endpointId == null || _state.value !is TransferState.Connected) {
            fail(FailureReason.CONNECTION_LOST, "No verified nearby connection is available.")
            return
        }

        val filePayload = Payload.fromFile(file.file)
        val transfer = OutgoingTransfer(
            transferId = UUID.randomUUID().toString(),
            payloadId = filePayload.id,
            file = file,
        )
        outgoing = transfer
        outgoingPayloadSucceeded = false
        outgoingVerified = false
        activePayloadId = filePayload.id

        val metadata = TransferMetadata(
            transferId = transfer.transferId,
            payloadId = filePayload.id,
            fileName = file.displayName,
            mimeType = file.mimeType,
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
        )
        _state.value = TransferState.Transferring(
            TransferDirection.SEND,
            file.displayName,
            0L,
            file.sizeBytes,
        )
        val generation = sessionGeneration

        client.sendPayload(endpointId, Payload.fromBytes(WireCodec.encode(metadata)))
            .addOnFailureListener { error ->
                if (generation == sessionGeneration) {
                    fail(FailureReason.CONNECTION_LOST, error.localizedMessage ?: "Could not send file metadata.")
                }
            }
        client.sendPayload(endpointId, filePayload)
            .addOnFailureListener { error ->
                if (generation == sessionGeneration) {
                    fail(FailureReason.CONNECTION_LOST, error.localizedMessage ?: "Could not start the file transfer.")
                }
            }
    }

    override fun cancelTransfer() {
        sessionGeneration += 1
        val cancellationGeneration = sessionGeneration
        val transfer = outgoing
        val incoming = activePayloadId?.let(incomingMetadata::get)
        val cancellationTask = when {
            transfer != null -> sendError(
                transfer.transferId,
                transfer.payloadId,
                ProtocolErrorCode.CANCELLED,
            )
            incoming != null -> sendError(
                incoming.transferId,
                incoming.payloadId,
                ProtocolErrorCode.CANCELLED,
            )
            else -> null
        }
        activePayloadId?.let(client::cancelPayload)
        _state.value = TransferState.Cancelled
        if (cancellationTask != null) {
            cancellationTask.addOnCompleteListener {
                if (cancellationGeneration == sessionGeneration) {
                    resetTransportOnly()
                    deleteReceivedFile()
                    clearTransferTracking()
                }
            }
        } else {
            resetTransportOnly()
            deleteReceivedFile()
            clearTransferTracking()
        }
    }

    override fun reset() {
        resetSession(keepTerminalState = false)
        _state.value = TransferState.Idle
    }

    override fun onAppBackgrounded() {
        val state = _state.value
        val terminalOrIdle = when (state) {
            TransferState.Idle,
            TransferState.Cancelled,
            is TransferState.Complete,
            is TransferState.Failed,
            is TransferState.PermissionsNeeded -> true
            else -> false
        }
        if (!terminalOrIdle || pendingNearbyStart) {
            sessionGeneration += 1
            activePayloadId?.let(client::cancelPayload)
            resetTransportOnly()
            deleteReceivedFile()
            clearTransferTracking()
            _state.value = TransferState.Failed(
                FailureReason.BACKGROUNDED,
                "NearPair must remain open on both devices during a direct transfer.",
            )
        }
    }

    override fun close() {
        resetSession(keepTerminalState = true)
        scope.cancel()
    }

    private fun handleBytes(endpointId: String, bytes: ByteArray) {
        val decoded = WireCodec.decode(bytes).getOrElse {
            fail(FailureReason.INVALID_METADATA, "The other app sent an invalid protocol message.")
            return
        }

        when (decoded) {
            is WireMessage.Metadata -> handleMetadata(decoded.value)
            is WireMessage.Verified -> handleVerified(decoded.value)
            is WireMessage.Error -> handleError(decoded.value)
        }
    }

    private fun handleError(acknowledgement: ErrorAcknowledgement) {
        val outgoingMatch = outgoing?.let { transfer ->
            acknowledgement.transferId == transfer.transferId && acknowledgement.payloadId == transfer.payloadId
        } == true
        val incomingMatch = incomingMetadata[acknowledgement.payloadId]?.let { metadata ->
            acknowledgement.transferId == metadata.transferId
        } == true
        if (!outgoingMatch && !incomingMatch) {
            Log.w(LOG_TAG, "Ignoring an error acknowledgement that does not match the active transfer.")
            return
        }
        if (acknowledgement.version != PROTOCOL_VERSION) {
            fail(FailureReason.PROTOCOL_MISMATCH, "The other device uses an incompatible NearPair protocol version.")
            return
        }
        if (acknowledgement.code == ProtocolErrorCode.CANCELLED) {
            finishCancelled()
            return
        }
        val reason = failureReasonFor(acknowledgement.code)
        fail(reason, "The other device ended the transfer: ${acknowledgement.code.name.lowercase()}.")
    }

    private fun handleMetadata(metadata: TransferMetadata) {
        val validationError = metadata.validationError()
        if (validationError != null) {
            sendError(metadata.transferId, metadata.payloadId, validationError)
            fail(failureReasonFor(validationError), "The incoming file metadata is not compatible with NearPair v1.")
            return
        }
        val existingMetadata = incomingMetadata.values.firstOrNull()
        if (existingMetadata != null) {
            if (existingMetadata == metadata) return
            sendError(metadata.transferId, metadata.payloadId, ProtocolErrorCode.REJECTED)
            client.cancelPayload(metadata.payloadId)
            return
        }
        val currentPayloadId = activePayloadId
        if (currentPayloadId != null && currentPayloadId != metadata.payloadId) {
            sendError(metadata.transferId, metadata.payloadId, ProtocolErrorCode.REJECTED)
            client.cancelPayload(metadata.payloadId)
            return
        }
        if (!fileStore.hasCapacityForIncomingTransfer(metadata)) {
            client.cancelPayload(metadata.payloadId)
            sendError(metadata.transferId, metadata.payloadId, ProtocolErrorCode.INSUFFICIENT_STORAGE)
            fail(FailureReason.INSUFFICIENT_STORAGE, "Free up storage on this device and retry the transfer.")
            return
        }
        activePayloadId = metadata.payloadId
        incomingMetadata[metadata.payloadId] = metadata
        _state.value = TransferState.Transferring(
            TransferDirection.RECEIVE,
            metadata.fileName,
            0L,
            metadata.sizeBytes,
        )
        attemptFinalizeIncoming(metadata.payloadId)
    }

    private fun handleVerified(acknowledgement: VerifiedAcknowledgement) {
        val transfer = outgoing ?: return
        if (acknowledgement.version != PROTOCOL_VERSION ||
            acknowledgement.transferId != transfer.transferId ||
            acknowledgement.payloadId != transfer.payloadId
        ) {
            fail(FailureReason.INVALID_METADATA, "The delivery acknowledgement did not match this transfer.")
            return
        }
        outgoingVerified = true
        attemptCompleteOutgoing()
    }

    private fun attemptCompleteOutgoing() {
        val transfer = outgoing ?: return
        if (!outgoingPayloadSucceeded || !outgoingVerified) return
        if (_state.value is TransferState.Cancelled || _state.value is TransferState.Failed) return
        _state.value = TransferState.Complete(TransferDirection.SEND, transfer.file.displayName)
        disconnectCurrent()
    }

    private fun attemptFinalizeIncoming(payloadId: Long) {
        if (_state.value is TransferState.Cancelled || _state.value is TransferState.Failed ||
            _state.value is TransferState.Complete || _state.value is TransferState.Idle
        ) return
        val metadata = incomingMetadata[payloadId] ?: return
        val payload = incomingPayloads[payloadId] ?: return
        if (payloadId !in successfulIncomingPayloads || !finalizingPayloads.add(payloadId)) return
        val uri = payload.asFile()?.asUri()
        if (uri == null) {
            finalizingPayloads.remove(payloadId)
            sendError(metadata.transferId, metadata.payloadId, ProtocolErrorCode.IO_FAILURE)
            fail(FailureReason.IO_FAILURE, "Nearby did not provide the received temporary file.")
            return
        }

        _state.value = TransferState.Verifying(TransferDirection.RECEIVE, metadata.fileName)
        val generation = sessionGeneration
        scope.launch {
            fileStore.commitIncoming(uri, metadata)
                .onSuccess { received ->
                    if (generation != sessionGeneration) {
                        fileStore.delete(received)
                        return@onSuccess
                    }
                    _receivedFile.value = received
                    val endpointId = currentEndpointId
                    if (endpointId != null) {
                        val ack = VerifiedAcknowledgement(
                            transferId = metadata.transferId,
                            payloadId = metadata.payloadId,
                        )
                        client.sendPayload(endpointId, Payload.fromBytes(WireCodec.encode(ack)))
                            .addOnSuccessListener {
                                if (generation == sessionGeneration) {
                                    _state.value = TransferState.Complete(
                                        TransferDirection.RECEIVE,
                                        received.displayName,
                                    )
                                }
                            }
                            .addOnFailureListener {
                                if (generation == sessionGeneration) {
                                    fileStore.delete(received)
                                    _receivedFile.value = null
                                    fail(
                                        FailureReason.CONNECTION_LOST,
                                        "The file was verified, but delivery confirmation could not be sent. Retry the transfer.",
                                    )
                                }
                            }
                    } else {
                        fileStore.delete(received)
                        _receivedFile.value = null
                        fail(
                            FailureReason.CONNECTION_LOST,
                            "The connection ended before delivery could be confirmed. Retry the transfer.",
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != sessionGeneration) return@onFailure
                    val code = when (error.message) {
                        ProtocolErrorCode.SIZE_MISMATCH.name -> ProtocolErrorCode.SIZE_MISMATCH
                        ProtocolErrorCode.CHECKSUM_MISMATCH.name -> ProtocolErrorCode.CHECKSUM_MISMATCH
                        ProtocolErrorCode.INSUFFICIENT_STORAGE.name -> ProtocolErrorCode.INSUFFICIENT_STORAGE
                        else -> ProtocolErrorCode.IO_FAILURE
                    }
                    sendError(metadata.transferId, metadata.payloadId, code)
                    fail(failureReasonFor(code), recoveryTextFor(code))
                }
            finalizingPayloads.remove(payloadId)
        }
    }

    private fun sendError(transferId: String, payloadId: Long, code: ProtocolErrorCode) =
        currentEndpointId?.let { endpointId ->
            val message = ErrorAcknowledgement(
                transferId = transferId,
                payloadId = payloadId,
                code = code,
            )
            client.sendPayload(endpointId, Payload.fromBytes(WireCodec.encode(message)))
        }

    private fun startFailure(action: String, error: Exception) {
        val disabledRadios = buildList {
            val bluetoothEnabled = runCatching {
                appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            }.getOrDefault(false)
            val wifiEnabled = runCatching {
                appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
            }.getOrDefault(false)
            if (!bluetoothEnabled) add("Bluetooth")
            if (!wifiEnabled) add("Wi-Fi")
        }
        val missingRuntimePermissions = NearbyPermissions.runtimePermissions(appContext).filter { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED
        }
        val statusCode = (error as? ApiException)?.statusCode
        val classified = NearbyStartFailureClassifier.classify(
            action = action,
            disabledRadios = disabledRadios,
            missingRuntimePermissions = missingRuntimePermissions,
            statusCode = statusCode,
            isSecurityException = error is SecurityException,
            fallbackDetail = error.localizedMessage ?: "No additional diagnostic was provided.",
        )
        Log.e(
            LOG_TAG,
            "Nearby failed to start $action; statusCode=$statusCode; " +
                "missingRuntimePermissions=$missingRuntimePermissions; disabledRadios=$disabledRadios",
            error,
        )
        fail(classified.reason, classified.detail)
    }

    private fun fail(reason: FailureReason, detail: String) {
        sessionGeneration += 1
        activePayloadId?.let(client::cancelPayload)
        resetTransportOnly()
        deleteReceivedFile()
        clearTransferTracking()
        _state.value = TransferState.Failed(reason, detail)
    }

    private fun finishCancelled() {
        sessionGeneration += 1
        activePayloadId?.let(client::cancelPayload)
        resetTransportOnly()
        deleteReceivedFile()
        clearTransferTracking()
        _state.value = TransferState.Cancelled
    }

    private fun deleteReceivedFile() {
        _receivedFile.value?.let(fileStore::delete)
        _receivedFile.value = null
    }

    private fun discardPayload(payload: Payload) {
        client.cancelPayload(payload.id)
        if (payload.type == Payload.Type.FILE) {
            payload.asFile()?.asUri()?.let(fileStore::deleteTemporary)
        }
    }

    private fun disconnectCurrent() {
        currentEndpointId?.let(client::disconnectFromEndpoint)
        currentEndpointId = null
    }

    private fun resetTransportOnly() {
        pendingNearbyStart = false
        queuedAdvertisingStart = null
        queuedDiscoveryStart = null
        client.stopAdvertising()
        client.stopDiscovery()
        disconnectCurrent()
    }

    private fun resetSession(keepTerminalState: Boolean) {
        sessionGeneration += 1
        resetTransportOnly()
        currentEndpointName = null
        clearTransferTracking()
        _devices.value = emptyList()
        if (!keepTerminalState) _receivedFile.value = null
    }

    private fun clearTransferTracking() {
        incomingPayloads.values.forEach { payload ->
            payload.asFile()?.asUri()?.let(fileStore::deleteTemporary)
        }
        activePayloadId = null
        outgoing = null
        outgoingPayloadSucceeded = false
        outgoingVerified = false
        incomingMetadata.clear()
        incomingPayloads.clear()
        successfulIncomingPayloads.clear()
        finalizingPayloads.clear()
    }

    private fun failureReasonFor(code: ProtocolErrorCode): FailureReason = when (code) {
        ProtocolErrorCode.UNSUPPORTED_VERSION -> FailureReason.PROTOCOL_MISMATCH
        ProtocolErrorCode.INVALID_METADATA -> FailureReason.INVALID_METADATA
        ProtocolErrorCode.UNSUPPORTED_TYPE -> FailureReason.UNSUPPORTED_TYPE
        ProtocolErrorCode.INSUFFICIENT_STORAGE -> FailureReason.INSUFFICIENT_STORAGE
        ProtocolErrorCode.SIZE_MISMATCH -> FailureReason.SIZE_MISMATCH
        ProtocolErrorCode.CHECKSUM_MISMATCH -> FailureReason.CHECKSUM_MISMATCH
        ProtocolErrorCode.REJECTED -> FailureReason.REJECTED
        ProtocolErrorCode.CANCELLED -> FailureReason.REJECTED
        ProtocolErrorCode.IO_FAILURE -> FailureReason.IO_FAILURE
    }

    private fun recoveryTextFor(code: ProtocolErrorCode): String = when (code) {
        ProtocolErrorCode.SIZE_MISMATCH -> "The received byte count did not match. The partial file was deleted; retry from the beginning."
        ProtocolErrorCode.CHECKSUM_MISMATCH -> "Integrity verification failed. The received file was deleted; retry from the beginning."
        ProtocolErrorCode.INSUFFICIENT_STORAGE -> "Free up storage on the receiving device and retry."
        else -> "The received file could not be stored safely. Partial data was deleted."
    }

    private data class OutgoingTransfer(
        val transferId: String,
        val payloadId: Long,
        val file: StagedFile,
    )

    private data class PendingAdvertisingStart(
        val generation: Long,
        val deviceName: String,
    )

    private data class PendingDiscoveryStart(
        val generation: Long,
    )

    private companion object {
        const val LOG_TAG = "NearPairNearby"
    }
}
