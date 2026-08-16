package com.nearpair.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {
    private val validMetadata = TransferMetadata(
        transferId = "7e51382d-1d59-4b7a-b327-c2d0a87d2eb9",
        payloadId = -9_223_372_036_854_775_000L,
        fileName = "vacation-video.mp4",
        mimeType = "video/mp4",
        sizeBytes = 2_147_483_648L,
        sha256 = "4f64b8c2b7b7423a88994bd53017c9c8f6f8f739d31513798b7f18df0e07f1d2",
    )

    @Test
    fun metadataRoundTripsAndAcceptsSignedPayloadIds() {
        assertNull(validMetadata.validationError())
        val decoded = WireCodec.decode(WireCodec.encode(validMetadata)).getOrThrow()
        assertEquals(WireMessage.Metadata(validMetadata), decoded)
    }

    @Test
    fun rejectsUnsupportedProtocolVersion() {
        assertEquals(
            ProtocolErrorCode.UNSUPPORTED_VERSION,
            validMetadata.copy(version = 2).validationError(),
        )
    }

    @Test
    fun rejectsExecutableMimeType() {
        assertEquals(
            ProtocolErrorCode.UNSUPPORTED_TYPE,
            validMetadata.copy(mimeType = "application/x-msdownload").validationError(),
        )
    }

    @Test
    fun rejectsMalformedImageAndVideoMimeTypes() {
        listOf("image/", "image/\ntext", "video/ ", "image/png/extra").forEach { mimeType ->
            assertEquals(
                mimeType,
                ProtocolErrorCode.UNSUPPORTED_TYPE,
                validMetadata.copy(mimeType = mimeType).validationError(),
            )
        }
    }

    @Test
    fun verifiedAcknowledgementRoundTrips() {
        val acknowledgement = VerifiedAcknowledgement(
            transferId = validMetadata.transferId,
            payloadId = validMetadata.payloadId,
        )
        assertEquals(
            WireMessage.Verified(acknowledgement),
            WireCodec.decode(WireCodec.encode(acknowledgement)).getOrThrow(),
        )
    }

    @Test
    fun unknownMessageTypeIsRejected() {
        assertTrue(WireCodec.decode("{\"type\":\"openThisFile\"}".encodeToByteArray()).isFailure)
    }
}
