package com.nearpair.app.model

import java.io.File

data class NearbyDevice(
    val endpointId: String,
    val name: String,
)

data class StagedFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ReceivedFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class TransferDirection { SEND, RECEIVE }

enum class FailureReason {
    PERMISSION_DENIED,
    APP_CONFIGURATION,
    RADIOS_DISABLED,
    NO_DEVICES,
    REJECTED,
    CONNECTION_LOST,
    INSUFFICIENT_STORAGE,
    INVALID_METADATA,
    UNSUPPORTED_TYPE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    IO_FAILURE,
    PROTOCOL_MISMATCH,
    BACKGROUNDED,
    UNKNOWN,
}

sealed interface TransferState {
    data object Idle : TransferState

    data class PermissionsNeeded(val action: String) : TransferState

    data class Advertising(val deviceName: String) : TransferState

    data class Discovering(val devices: List<NearbyDevice>) : TransferState

    data class ConnectionRequested(val device: NearbyDevice) : TransferState

    data class ConfirmCode(
        val endpointId: String,
        val deviceName: String,
        val code: String,
    ) : TransferState

    data class Connected(
        val endpointId: String,
        val deviceName: String,
    ) : TransferState

    data class Transferring(
        val direction: TransferDirection,
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
    ) : TransferState {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f else (transferredBytes.toDouble() / totalBytes).toFloat()
                .coerceIn(0f, 1f)
    }

    data class Verifying(
        val direction: TransferDirection,
        val fileName: String,
    ) : TransferState

    data class Complete(
        val direction: TransferDirection,
        val fileName: String,
    ) : TransferState

    data class Failed(
        val reason: FailureReason,
        val detail: String,
    ) : TransferState

    data object Cancelled : TransferState
}
