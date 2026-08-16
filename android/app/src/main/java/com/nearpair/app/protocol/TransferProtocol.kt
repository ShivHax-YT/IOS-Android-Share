package com.nearpair.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

const val PROTOCOL_VERSION = 1
const val SERVICE_ID = "com.nearpair.transfer.v1"

@Serializable
data class TransferMetadata(
    val type: String = "metadata",
    val version: Int = PROTOCOL_VERSION,
    val transferId: String,
    val payloadId: Long,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    fun validationError(): ProtocolErrorCode? = when {
        type != "metadata" -> ProtocolErrorCode.INVALID_METADATA
        version != PROTOCOL_VERSION -> ProtocolErrorCode.UNSUPPORTED_VERSION
        runCatching { UUID.fromString(transferId) }.isFailure -> ProtocolErrorCode.INVALID_METADATA
        fileName.isBlank() -> ProtocolErrorCode.INVALID_METADATA
        sizeBytes <= 0L -> ProtocolErrorCode.INVALID_METADATA
        !sha256.matches(Regex("^[0-9a-f]{64}$")) -> ProtocolErrorCode.INVALID_METADATA
        !isAllowedMimeType(mimeType) -> ProtocolErrorCode.UNSUPPORTED_TYPE
        else -> null
    }
}

@Serializable
data class VerifiedAcknowledgement(
    val type: String = "verified",
    val version: Int = PROTOCOL_VERSION,
    val transferId: String,
    val payloadId: Long,
)

@Serializable
data class ErrorAcknowledgement(
    val type: String = "error",
    val version: Int = PROTOCOL_VERSION,
    val transferId: String,
    val payloadId: Long,
    val code: ProtocolErrorCode,
)

@Serializable
enum class ProtocolErrorCode {
    @SerialName("unsupportedVersion")
    UNSUPPORTED_VERSION,

    @SerialName("invalidMetadata")
    INVALID_METADATA,

    @SerialName("unsupportedType")
    UNSUPPORTED_TYPE,

    @SerialName("insufficientStorage")
    INSUFFICIENT_STORAGE,

    @SerialName("sizeMismatch")
    SIZE_MISMATCH,

    @SerialName("checksumMismatch")
    CHECKSUM_MISMATCH,

    @SerialName("rejected")
    REJECTED,

    @SerialName("cancelled")
    CANCELLED,

    @SerialName("ioFailure")
    IO_FAILURE,
}

sealed interface WireMessage {
    data class Metadata(val value: TransferMetadata) : WireMessage
    data class Verified(val value: VerifiedAcknowledgement) : WireMessage
    data class Error(val value: ErrorAcknowledgement) : WireMessage
}

object WireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(metadata: TransferMetadata): ByteArray =
        json.encodeToString(TransferMetadata.serializer(), metadata).encodeToByteArray()

    fun encode(acknowledgement: VerifiedAcknowledgement): ByteArray =
        json.encodeToString(VerifiedAcknowledgement.serializer(), acknowledgement).encodeToByteArray()

    fun encode(error: ErrorAcknowledgement): ByteArray =
        json.encodeToString(ErrorAcknowledgement.serializer(), error).encodeToByteArray()

    fun decode(bytes: ByteArray): Result<WireMessage> = runCatching {
        val source = bytes.decodeToString()
        val element = json.parseToJsonElement(source)
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "metadata" -> WireMessage.Metadata(
                json.decodeFromString(TransferMetadata.serializer(), source),
            )

            "verified" -> WireMessage.Verified(
                json.decodeFromString(VerifiedAcknowledgement.serializer(), source),
            )

            "error" -> WireMessage.Error(
                json.decodeFromString(ErrorAcknowledgement.serializer(), source),
            )

            else -> error("Unknown protocol message type")
        }
    }
}

fun isAllowedMimeType(mimeType: String): Boolean {
    if (mimeType == "application/pdf") return true
    val slash = mimeType.indexOf('/')
    if (slash <= 0 || slash != mimeType.lastIndexOf('/')) return false
    val topLevel = mimeType.substring(0, slash)
    val subtype = mimeType.substring(slash + 1)
    return topLevel in setOf("image", "video") &&
        subtype.matches(Regex("^[A-Za-z0-9!#$&^_.+\\-]{1,127}$"))
}
