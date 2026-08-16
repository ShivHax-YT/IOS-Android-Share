import CryptoKit
import XCTest
@testable import NearPair

final class TransferProtocolTests: XCTestCase {
    private let metadata = TransferMetadata(
        transferId: "7e51382d-1d59-4b7a-b327-c2d0a87d2eb9",
        payloadId: -9_223_372_036_854_775_000,
        fileName: "vacation-video.mp4",
        mimeType: "video/mp4",
        sizeBytes: 2_147_483_648,
        sha256: "4f64b8c2b7b7423a88994bd53017c9c8f6f8f739d31513798b7f18df0e07f1d2"
    )

    func testMetadataRoundTripAcceptsSignedPayloadID() throws {
        XCTAssertNil(metadata.validationError())
        XCTAssertEqual(try WireCodec.decode(WireCodec.encode(metadata)), .metadata(metadata))
    }

    func testRejectsUnsupportedVersionAndFileClass() {
        var wrongVersion = metadata
        wrongVersion.version = 2
        XCTAssertEqual(wrongVersion.validationError(), .unsupportedVersion)

        let executable = TransferMetadata(
            transferId: metadata.transferId,
            payloadId: metadata.payloadId,
            fileName: "payload.exe",
            mimeType: "application/x-msdownload",
            sizeBytes: metadata.sizeBytes,
            sha256: metadata.sha256
        )
        XCTAssertEqual(executable.validationError(), .unsupportedType)
    }

    func testBonjourServiceMatchesCommittedServiceID() {
        let digest = SHA256.hash(data: Data(ProtocolConstants.serviceID.utf8))
            .map { String(format: "%02X", $0) }
            .joined()
        XCTAssertEqual("_\(digest.prefix(12))._tcp", "_EBD1B4122871._tcp")
    }

    func testSanitizerRemovesPathTraversalAndSeparators() {
        let result = FileStore.sanitizeFileName("../../private\\movie:final?.mp4")
        XCTAssertEqual(result, "movie_final_.mp4")
        XCTAssertFalse(result.contains("/"))
        XCTAssertFalse(result.contains("\\"))
    }
}

