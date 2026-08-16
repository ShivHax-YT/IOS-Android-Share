import Foundation

struct NearbyDevice: Identifiable, Equatable {
    let id: String
    let name: String
}

struct StagedFile: Equatable {
    let url: URL
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String
}

struct ReceivedFile: Identifiable, Equatable {
    var id: URL { url }
    let url: URL
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
    let sha256: String
}

enum TransferDirection: Equatable {
    case send
    case receive
}

enum FailureReason: Equatable {
    case permissionDenied
    case radiosDisabled
    case rejected
    case connectionLost
    case insufficientStorage
    case invalidMetadata
    case unsupportedType
    case sizeMismatch
    case checksumMismatch
    case ioFailure
    case protocolMismatch
    case backgrounded
    case unknown
}

enum TransferPhase: Equatable {
    case idle
    case advertising(deviceName: String)
    case discovering
    case connectionRequested(device: NearbyDevice)
    case confirmCode(endpointID: String, deviceName: String, code: String)
    case connected(endpointID: String, deviceName: String)
    case transferring(direction: TransferDirection, fileName: String, transferredBytes: Int64, totalBytes: Int64)
    case verifying(direction: TransferDirection, fileName: String)
    case complete(direction: TransferDirection, fileName: String)
    case failed(reason: FailureReason, detail: String)
    case cancelled
}

@MainActor
protocol TransferEngine: AnyObject {
    var phase: TransferPhase { get }
    var devices: [NearbyDevice] { get }
    var receivedFile: ReceivedFile? { get }

    func startReceiving(deviceName: String)
    func stopReceiving()
    func discoverNearbyDevices(deviceName: String)
    func stopDiscovery()
    func requestConnection(to device: NearbyDevice, localDeviceName: String)
    func acceptConnection(endpointID: String)
    func rejectConnection(endpointID: String)
    func sendFile(_ file: StagedFile)
    func cancelTransfer()
    func reset()
    func onAppBackgrounded()
}

