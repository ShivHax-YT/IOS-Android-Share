import Combine
import Foundation
import PhotosUI
import UIKit

@MainActor
final class NearPairViewModel: ObservableObject {
    @Published private(set) var phase: TransferPhase = .idle
    @Published private(set) var devices: [NearbyDevice] = []
    @Published private(set) var receivedFile: ReceivedFile?
    @Published private(set) var isStaging = false
    @Published var alertMessage: String?
    @Published var shareItem: ShareItem?
    @Published var exportItem: ExportItem?
    @Published var deviceName = String(UIDevice.current.name.prefix(48))

    private let fileStore: FileStore
    private let engine: NearbyTransferEngine
    private var subscriptions: Set<AnyCancellable> = []
    private var pendingOutgoing: StagedFile?
    private var sendStartedForEndpoint: String?
    private var lastRole: Role?

    init() {
        let store = FileStore()
        fileStore = store
        engine = NearbyTransferEngine(fileStore: store)

        engine.$phase
            .sink { [weak self] phase in
                guard let self else { return }
                self.phase = phase
                if case .connected(let endpointID, _) = phase,
                   let file = self.pendingOutgoing,
                   self.sendStartedForEndpoint != endpointID {
                    self.sendStartedForEndpoint = endpointID
                    self.engine.sendFile(file)
                }
            }
            .store(in: &subscriptions)

        engine.$devices.assign(to: &$devices)
        engine.$receivedFile.assign(to: &$receivedFile)
    }

    func chooseDirectFile(_ url: URL) {
        lastRole = .send
        stage(url: url, purpose: .direct)
    }

    func chooseSystemShareFile(_ url: URL) {
        stage(url: url, purpose: .systemShare)
    }

    func chooseMedia(_ item: PhotosPickerItem) {
        lastRole = .send
        alertMessage = nil
        isStaging = true
        Task {
            defer { isStaging = false }
            do {
                guard let media = try await item.loadTransferable(type: PickedMedia.self) else {
                    throw ViewModelError.photoUnavailable
                }
                let staged = try await fileStore.stageOwnedTemporary(media.url)
                pendingOutgoing = staged
                sendStartedForEndpoint = nil
                engine.discoverNearbyDevices(deviceName: deviceName)
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    func startReceiving() {
        lastRole = .receive
        alertMessage = nil
        engine.startReceiving(deviceName: deviceName)
    }

    func requestConnection(_ device: NearbyDevice) {
        engine.requestConnection(to: device, localDeviceName: deviceName)
    }

    func accept(endpointID: String) { engine.acceptConnection(endpointID: endpointID) }
    func reject(endpointID: String) { engine.rejectConnection(endpointID: endpointID) }
    func cancel() { engine.cancelTransfer() }

    func retry() {
        alertMessage = nil
        sendStartedForEndpoint = nil
        engine.reset()
        switch lastRole {
        case .send where pendingOutgoing != nil:
            engine.discoverNearbyDevices(deviceName: deviceName)
        case .receive:
            engine.startReceiving(deviceName: deviceName)
        default:
            break
        }
    }

    func done() {
        let outgoingURL = pendingOutgoing?.url
        pendingOutgoing = nil
        sendStartedForEndpoint = nil
        lastRole = nil
        engine.reset()
        if let outgoingURL { Task { await fileStore.delete(url: outgoingURL) } }
    }

    func shareReceived() {
        guard let receivedFile else { return }
        shareItem = ShareItem(url: receivedFile.url, cleanupAfterShare: false)
    }

    func exportReceived() {
        guard let receivedFile else { return }
        exportItem = ExportItem(url: receivedFile.url)
    }

    func exportFinished(saved: Bool) {
        guard let item = exportItem else { return }
        exportItem = nil
        if saved {
            Task {
                await fileStore.delete(url: item.url)
                done()
            }
        }
    }

    func shareFinished() {
        guard let item = shareItem else { return }
        shareItem = nil
        if item.cleanupAfterShare {
            Task { await fileStore.delete(url: item.url) }
        }
    }

    func deleteReceived() {
        guard let receivedFile else { return }
        Task {
            await fileStore.delete(url: receivedFile.url)
            done()
        }
    }

    func onAppBackgrounded() { engine.onAppBackgrounded() }

    private func stage(url: URL, purpose: StagePurpose) {
        alertMessage = nil
        isStaging = true
        Task {
            defer { isStaging = false }
            do {
                let staged = try await fileStore.stageOutgoing(url)
                switch purpose {
                case .direct:
                    pendingOutgoing = staged
                    sendStartedForEndpoint = nil
                    engine.discoverNearbyDevices(deviceName: deviceName)
                case .systemShare:
                    shareItem = ShareItem(url: staged.url, cleanupAfterShare: true)
                }
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    struct ShareItem: Identifiable {
        let id = UUID()
        let url: URL
        let cleanupAfterShare: Bool
    }

    struct ExportItem: Identifiable {
        let id = UUID()
        let url: URL
    }

    private enum Role { case send, receive }
    private enum StagePurpose { case direct, systemShare }
}

private enum ViewModelError: LocalizedError {
    case photoUnavailable

    var errorDescription: String? { "The selected photo or video could not be loaded." }
}
