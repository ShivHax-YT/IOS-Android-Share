import Foundation

enum ProtocolConstants {
    static let version = 1
    static let serviceID = "com.nearpair.transfer.v1"
}

struct TransferMetadata: Codable, Equatable {
    var type = "metadata"
    var version = ProtocolConstants.version
    let transferId: String
    let payloadId: Int64
    let fileName: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String

    func validationError() -> ProtocolErrorCode? {
        if type != "metadata" { return .invalidMetadata }
        if version != ProtocolConstants.version { return .unsupportedVersion }
        if UUID(uuidString: transferId) == nil || fileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .invalidMetadata
        }
        if sizeBytes <= 0 || sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) == nil {
            return .invalidMetadata
        }
        if !Self.isAllowed(mimeType: mimeType) { return .unsupportedType }
        return nil
    }

    static func isAllowed(mimeType: String) -> Bool {
        mimeType == "application/pdf" || mimeType.hasPrefix("image/") || mimeType.hasPrefix("video/")
    }
}

struct VerifiedAcknowledgement: Codable, Equatable {
    var type = "verified"
    var version = ProtocolConstants.version
    let transferId: String
    let payloadId: Int64
}

struct ErrorAcknowledgement: Codable, Equatable {
    var type = "error"
    var version = ProtocolConstants.version
    let transferId: String
    let payloadId: Int64
    let code: ProtocolErrorCode
}

enum ProtocolErrorCode: String, Codable, Equatable {
    case unsupportedVersion
    case invalidMetadata
    case unsupportedType
    case insufficientStorage
    case sizeMismatch
    case checksumMismatch
    case rejected
    case cancelled
    case ioFailure
}

enum WireMessage: Equatable {
    case metadata(TransferMetadata)
    case verified(VerifiedAcknowledgement)
    case error(ErrorAcknowledgement)
}

enum WireCodec {
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    private static let decoder = JSONDecoder()

    static func encode(_ metadata: TransferMetadata) throws -> Data { try encoder.encode(metadata) }
    static func encode(_ acknowledgement: VerifiedAcknowledgement) throws -> Data { try encoder.encode(acknowledgement) }
    static func encode(_ error: ErrorAcknowledgement) throws -> Data { try encoder.encode(error) }

    static func decode(_ data: Data) throws -> WireMessage {
        guard
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = object["type"] as? String
        else {
            throw WireCodecError.invalidEnvelope
        }
        switch type {
        case "metadata": return .metadata(try decoder.decode(TransferMetadata.self, from: data))
        case "verified": return .verified(try decoder.decode(VerifiedAcknowledgement.self, from: data))
        case "error": return .error(try decoder.decode(ErrorAcknowledgement.self, from: data))
        default: throw WireCodecError.unknownType
        }
    }
}

enum WireCodecError: Error {
    case invalidEnvelope
    case unknownType
}
