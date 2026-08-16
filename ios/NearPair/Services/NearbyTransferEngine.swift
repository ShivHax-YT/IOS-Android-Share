import Foundation
import NearbyConnections

@MainActor
final class NearbyTransferEngine: NSObject, ObservableObject, TransferEngine {
    @Published private(set) var phase: TransferPhase = .idle
    @Published private(set) var devices: [NearbyDevice] = []
    @Published private(set) var receivedFile: ReceivedFile?

    private let fileStore: FileStore
    private var connectionManager: ConnectionManager?
    private var advertiser: Advertiser?
    private var discoverer: Discoverer?
    private var localDeviceName = "iOS device"
    private var currentEndpointID: EndpointID?
    private var endpointNames: [EndpointID: String] = [:]
    private var pendingVerification: (endpointID: EndpointID, handler: (Bool) -> Void)?
    private var outgoing: OutgoingTransfer?
    private var outgoingResourceToken: CancellationToken?
    private var incomingMetadata: [PayloadID: TransferMetadata] = [:]
    private var incomingResources: [PayloadID: IncomingResource] = [:]
    private var successfulIncomingPayloads: Set<PayloadID> = []
    private var storageApprovedPayloads: Set<PayloadID> = []
    private var finalizingPayloads: Set<PayloadID> = []
    private var sessionGeneration = 0

    init(fileStore: FileStore) {
        self.fileStore = fileStore
        super.init()
    }

    func startReceiving(deviceName: String) {
        resetSession(clearReceived: false)
        localDeviceName = normalizedDeviceName(deviceName)
        let manager = makeConnectionManager()
        let advertiser = Advertiser(connectionManager: manager)
        advertiser.delegate = self
        self.advertiser = advertiser
        phase = .advertising(deviceName: localDeviceName)
        let generation = sessionGeneration
        advertiser.startAdvertising(using: Data(localDeviceName.utf8)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                guard let self, generation == self.sessionGeneration else { return }
                self.startFailure(action: "receiving", error: error)
            }
        }
    }

    func stopReceiving() {
        advertiser?.stopAdvertising()
        advertiser = nil
        if case .advertising = phase { phase = .idle }
    }

    func discoverNearbyDevices(deviceName: String) {
        resetSession(clearReceived: false)
        localDeviceName = normalizedDeviceName(deviceName)
        devices = []
        let manager = makeConnectionManager()
        let discoverer = Discoverer(connectionManager: manager)
        discoverer.delegate = self
        self.discoverer = discoverer
        phase = .discovering
        let generation = sessionGeneration
        discoverer.startDiscovery { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                guard let self, generation == self.sessionGeneration else { return }
                self.startFailure(action: "searching", error: error)
            }
        }
    }

    func stopDiscovery() {
        discoverer?.stopDiscovery()
        discoverer = nil
        if case .discovering = phase { phase = .idle }
    }

    func requestConnection(to device: NearbyDevice, localDeviceName: String) {
        guard currentEndpointID == nil || currentEndpointID == device.id else { return }
        self.localDeviceName = normalizedDeviceName(localDeviceName)
        currentEndpointID = device.id
        endpointNames[device.id] = device.name
        phase = .connectionRequested(device: device)
        let generation = sessionGeneration
        discoverer?.requestConnection(to: device.id, using: Data(self.localDeviceName.utf8)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                guard let self, generation == self.sessionGeneration else { return }
                self.fail(.connectionLost, "Could not request the connection: \(error.localizedDescription)")
            }
        }
    }

    func acceptConnection(endpointID: String) {
        guard pendingVerification?.endpointID == endpointID else { return }
        let handler = pendingVerification?.handler
        pendingVerification = nil
        handler?(true)
    }

    func rejectConnection(endpointID: String) {
        guard pendingVerification?.endpointID == endpointID else { return }
        let handler = pendingVerification?.handler
        pendingVerification = nil
        sessionGeneration += 1
        phase = .failed(reason: .rejected, detail: "Connection rejected. No file was transferred.")
        handler?(false)
        stopTransport()
    }

    func sendFile(_ file: StagedFile) {
        guard let manager = connectionManager, let endpointID = currentEndpointID else {
            fail(.connectionLost, "No verified nearby connection is available.")
            return
        }
        guard case .connected = phase else {
            fail(.connectionLost, "The nearby connection is not ready to transfer a file.")
            return
        }

        let payloadID = PayloadID.unique()
        let transfer = OutgoingTransfer(transferID: UUID().uuidString, payloadID: payloadID, file: file)
        outgoing = transfer
        let metadata = TransferMetadata(
            transferId: transfer.transferID,
            payloadId: payloadID,
            fileName: file.displayName,
            mimeType: file.mimeType,
            sizeBytes: file.sizeBytes,
            sha256: file.sha256
        )

        do {
            let metadataData = try WireCodec.encode(metadata)
            let generation = sessionGeneration
            manager.send(metadataData, to: [endpointID]) { [weak self] error in
                guard let error else { return }
                Task { @MainActor [weak self] in
                    guard let self, generation == self.sessionGeneration else { return }
                    self.fail(.connectionLost, error.localizedDescription)
                }
            }
            outgoingResourceToken = manager.sendResource(
                at: file.url,
                withName: file.displayName,
                to: [endpointID],
                id: payloadID
            ) { [weak self] error in
                guard let error else { return }
                Task { @MainActor [weak self] in
                    guard let self, generation == self.sessionGeneration else { return }
                    self.fail(.connectionLost, error.localizedDescription)
                }
            }
            phase = .transferring(
                direction: .send,
                fileName: file.displayName,
                transferredBytes: 0,
                totalBytes: file.sizeBytes
            )
        } catch {
            fail(.invalidMetadata, "Could not encode the file metadata.")
        }
    }

    func cancelTransfer() {
        sessionGeneration += 1
        if let outgoing {
            sendError(transferID: outgoing.transferID, payloadID: outgoing.payloadID, code: .cancelled)
        }
        outgoingResourceToken?.cancel()
        incomingResources.values.forEach { $0.cancellationToken.cancel() }
        phase = .cancelled
        stopTransport()
    }

    func reset() {
        resetSession(clearReceived: true)
        phase = .idle
    }

    func onAppBackgrounded() {
        switch phase {
        case .idle, .complete, .failed, .cancelled:
            return
        default:
            sessionGeneration += 1
            outgoingResourceToken?.cancel()
            incomingResources.values.forEach { $0.cancellationToken.cancel() }
            phase = .failed(
                reason: .backgrounded,
                detail: "NearPair must remain open on both devices during a direct transfer."
            )
            stopTransport()
        }
    }

    private func makeConnectionManager() -> ConnectionManager {
        let manager = ConnectionManager(serviceID: ProtocolConstants.serviceID, strategy: .pointToPoint)
        manager.delegate = self
        connectionManager = manager
        return manager
    }

    private func handleMetadata(_ metadata: TransferMetadata) {
        if let validationError = metadata.validationError() {
            sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: validationError)
            fail(failureReason(for: validationError), "The incoming metadata is not compatible with NearPair protocol v1.")
            return
        }

        incomingMetadata[metadata.payloadId] = metadata
        phase = .transferring(
            direction: .receive,
            fileName: metadata.fileName,
            transferredBytes: 0,
            totalBytes: metadata.sizeBytes
        )

        let generation = sessionGeneration
        Task { [weak self] in
            guard let self else { return }
            let hasCapacity = await fileStore.hasCapacity(for: metadata)
            guard generation == sessionGeneration, incomingMetadata[metadata.payloadId] != nil else { return }
            if !hasCapacity {
                incomingResources[metadata.payloadId]?.cancellationToken.cancel()
                sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: .insufficientStorage)
                fail(.insufficientStorage, "Free up storage on this device and retry the transfer.")
                return
            }
            storageApprovedPayloads.insert(metadata.payloadId)
            attemptFinalizeIncoming(metadata.payloadId)
        }
    }

    private func handleVerified(_ acknowledgement: VerifiedAcknowledgement) {
        guard
            let outgoing,
            acknowledgement.version == ProtocolConstants.version,
            acknowledgement.transferId == outgoing.transferID,
            acknowledgement.payloadId == outgoing.payloadID
        else {
            fail(.invalidMetadata, "The delivery acknowledgement did not match this transfer.")
            return
        }
        phase = .complete(direction: .send, fileName: outgoing.file.displayName)
        disconnectCurrent()
    }

    private func handleError(_ acknowledgement: ErrorAcknowledgement) {
        fail(failureReason(for: acknowledgement.code), "The other device ended the transfer: \(acknowledgement.code.rawValue).")
    }

    private func attemptFinalizeIncoming(_ payloadID: PayloadID) {
        switch phase {
        case .idle, .complete, .failed, .cancelled: return
        default: break
        }
        guard
            let metadata = incomingMetadata[payloadID],
            let resource = incomingResources[payloadID],
            successfulIncomingPayloads.contains(payloadID),
            storageApprovedPayloads.contains(payloadID),
            finalizingPayloads.insert(payloadID).inserted
        else { return }

        phase = .verifying(direction: .receive, fileName: metadata.fileName)
        let generation = sessionGeneration
        Task { [weak self] in
            guard let self else { return }
            do {
                let received = try await fileStore.commitIncoming(resourceURL: resource.url, metadata: metadata)
                guard generation == sessionGeneration else {
                    await fileStore.delete(url: received.url)
                    return
                }
                receivedFile = received
                if let endpointID = currentEndpointID, let manager = connectionManager {
                    let acknowledgement = VerifiedAcknowledgement(
                        transferId: metadata.transferId,
                        payloadId: metadata.payloadId
                    )
                    if let data = try? WireCodec.encode(acknowledgement) {
                        manager.send(data, to: [endpointID])
                    }
                }
                phase = .complete(direction: .receive, fileName: received.displayName)
            } catch FileStoreError.sizeMismatch {
                guard generation == sessionGeneration else { return }
                sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: .sizeMismatch)
                fail(.sizeMismatch, "The byte count did not match. The partial file was deleted; retry from the beginning.")
            } catch FileStoreError.checksumMismatch {
                guard generation == sessionGeneration else { return }
                sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: .checksumMismatch)
                fail(.checksumMismatch, "SHA-256 verification failed. The received file was deleted; retry from the beginning.")
            } catch FileStoreError.insufficientStorage {
                guard generation == sessionGeneration else { return }
                sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: .insufficientStorage)
                fail(.insufficientStorage, "Free up storage on this device and retry.")
            } catch {
                guard generation == sessionGeneration else { return }
                sendError(transferID: metadata.transferId, payloadID: metadata.payloadId, code: .ioFailure)
                fail(.ioFailure, "The received file could not be stored safely. Partial data was deleted.")
            }
            finalizingPayloads.remove(payloadID)
        }
    }

    private func sendError(transferID: String, payloadID: PayloadID, code: ProtocolErrorCode) {
        guard let manager = connectionManager, let endpointID = currentEndpointID else { return }
        let message = ErrorAcknowledgement(transferId: transferID, payloadId: payloadID, code: code)
        if let data = try? WireCodec.encode(message) {
            manager.send(data, to: [endpointID])
        }
    }

    private func fail(_ reason: FailureReason, _ detail: String) {
        sessionGeneration += 1
        phase = .failed(reason: reason, detail: detail)
        stopTransport()
    }

    private func startFailure(action: String, error: Error) {
        let detail = error.localizedDescription
        let lower = detail.lowercased()
        let reason: FailureReason
        if lower.contains("permission") || lower.contains("denied") || lower.contains("not authorized") {
            reason = .permissionDenied
        } else if lower.contains("bluetooth") || lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("local network") {
            reason = .radiosDisabled
        } else {
            reason = .unknown
        }
        fail(reason, "Could not start \(action): \(detail) Check Bluetooth, Wi-Fi, and NearPair permissions in Settings, then retry.")
    }

    private func stopTransport() {
        advertiser?.stopAdvertising()
        discoverer?.stopDiscovery()
        advertiser = nil
        discoverer = nil
        disconnectCurrent()
    }

    private func disconnectCurrent() {
        if let endpointID = currentEndpointID {
            connectionManager?.disconnect(from: endpointID)
        }
        currentEndpointID = nil
    }

    private func resetSession(clearReceived: Bool) {
        sessionGeneration += 1
        stopTransport()
        connectionManager = nil
        pendingVerification = nil
        endpointNames = [:]
        devices = []
        outgoing = nil
        outgoingResourceToken = nil
        incomingMetadata = [:]
        incomingResources = [:]
        successfulIncomingPayloads = []
        storageApprovedPayloads = []
        finalizingPayloads = []
        if clearReceived { receivedFile = nil }
    }

    private func normalizedDeviceName(_ value: String) -> String {
        String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(48)).isEmpty
            ? "iOS device"
            : String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(48))
    }

    private func failureReason(for code: ProtocolErrorCode) -> FailureReason {
        switch code {
        case .unsupportedVersion: return .protocolMismatch
        case .invalidMetadata: return .invalidMetadata
        case .unsupportedType: return .unsupportedType
        case .insufficientStorage: return .insufficientStorage
        case .sizeMismatch: return .sizeMismatch
        case .checksumMismatch: return .checksumMismatch
        case .rejected, .cancelled: return .rejected
        case .ioFailure: return .ioFailure
        }
    }
}

extension NearbyTransferEngine: DiscovererDelegate {
    func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        let name = String(data: context, encoding: .utf8) ?? "Nearby device"
        endpointNames[endpointID] = name
        if !devices.contains(where: { $0.id == endpointID }) {
            devices.append(NearbyDevice(id: endpointID, name: name))
            devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        devices.removeAll { $0.id == endpointID }
        endpointNames[endpointID] = nil
    }
}

extension NearbyTransferEngine: AdvertiserDelegate {
    func advertiser(
        _ advertiser: Advertiser,
        didReceiveConnectionRequestFrom endpointID: EndpointID,
        with context: Data,
        connectionRequestHandler: @escaping (Bool) -> Void
    ) {
        if let currentEndpointID, currentEndpointID != endpointID {
            connectionRequestHandler(false)
            return
        }
        let name = String(data: context, encoding: .utf8) ?? "Nearby device"
        currentEndpointID = endpointID
        endpointNames[endpointID] = name

        // This admits the endpoint to Nearby's verification step. The secure connection is
        // still rejected unless the user explicitly accepts the matching verification code.
        connectionRequestHandler(true)
    }
}

extension NearbyTransferEngine: ConnectionManagerDelegate {
    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive verificationCode: String,
        from endpointID: EndpointID,
        verificationHandler: @escaping (Bool) -> Void
    ) {
        currentEndpointID = endpointID
        pendingVerification = (endpointID, verificationHandler)
        phase = .confirmCode(
            endpointID: endpointID,
            deviceName: endpointNames[endpointID] ?? "Nearby device",
            code: verificationCode
        )
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive data: Data,
        withID payloadID: PayloadID,
        from endpointID: EndpointID
    ) {
        do {
            switch try WireCodec.decode(data) {
            case .metadata(let metadata): handleMetadata(metadata)
            case .verified(let acknowledgement): handleVerified(acknowledgement)
            case .error(let acknowledgement): handleError(acknowledgement)
            }
        } catch {
            fail(.invalidMetadata, "The other app sent an invalid protocol message.")
        }
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceive stream: InputStream,
        withID payloadID: PayloadID,
        from endpointID: EndpointID,
        cancellationToken token: CancellationToken
    ) {
        token.cancel()
        fail(.unsupportedType, "NearPair protocol v1 does not accept stream payloads.")
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didStartReceivingResourceWithID payloadID: PayloadID,
        from endpointID: EndpointID,
        at localURL: URL,
        withName name: String,
        cancellationToken token: CancellationToken
    ) {
        incomingResources[payloadID] = IncomingResource(url: localURL, cancellationToken: token)
        let metadata = incomingMetadata[payloadID]
        phase = .transferring(
            direction: .receive,
            fileName: metadata?.fileName ?? "Receiving file",
            transferredBytes: 0,
            totalBytes: metadata?.sizeBytes ?? 0
        )
        attemptFinalizeIncoming(payloadID)
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didReceiveTransferUpdate update: TransferUpdate,
        from endpointID: EndpointID,
        forPayload payloadID: PayloadID
    ) {
        switch update {
        case .success:
            if outgoing?.payloadID == payloadID, let outgoing {
                phase = .verifying(direction: .send, fileName: outgoing.file.displayName)
            } else if incomingResources[payloadID] != nil {
                successfulIncomingPayloads.insert(payloadID)
                attemptFinalizeIncoming(payloadID)
            }
        case .canceled:
            if outgoing?.payloadID == payloadID || incomingResources[payloadID] != nil {
                phase = .cancelled
                stopTransport()
            }
        case .failure:
            if outgoing?.payloadID == payloadID || incomingResources[payloadID] != nil {
                fail(.connectionLost, "The file transfer failed. Partial data was discarded.")
            }
        case .progress(let progress):
            if outgoing?.payloadID == payloadID, let outgoing {
                phase = .transferring(
                    direction: .send,
                    fileName: outgoing.file.displayName,
                    transferredBytes: progress.completedUnitCount,
                    totalBytes: outgoing.file.sizeBytes
                )
            } else if let metadata = incomingMetadata[payloadID] {
                phase = .transferring(
                    direction: .receive,
                    fileName: metadata.fileName,
                    transferredBytes: progress.completedUnitCount,
                    totalBytes: metadata.sizeBytes
                )
            }
        }
    }

    func connectionManager(
        _ connectionManager: ConnectionManager,
        didChangeTo state: ConnectionState,
        for endpointID: EndpointID
    ) {
        switch state {
        case .connecting:
            break
        case .connected:
            currentEndpointID = endpointID
            advertiser?.stopAdvertising()
            discoverer?.stopDiscovery()
            phase = .connected(
                endpointID: endpointID,
                deviceName: endpointNames[endpointID] ?? "Nearby device"
            )
        case .disconnected:
            currentEndpointID = nil
            switch phase {
            case .complete, .failed, .cancelled: break
            default: fail(.connectionLost, "The devices disconnected. Move closer and retry from the beginning.")
            }
        case .rejected:
            fail(.rejected, "The other device rejected the connection.")
        }
    }
}

private struct OutgoingTransfer {
    let transferID: String
    let payloadID: PayloadID
    let file: StagedFile
}

private struct IncomingResource {
    let url: URL
    let cancellationToken: CancellationToken
}
