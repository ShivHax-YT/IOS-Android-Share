import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @ObservedObject var viewModel: NearPairViewModel
    @Environment(\.openURL) private var openURL
    @State private var showingFilePicker = false
    @State private var pickerPurpose: PickerPurpose = .direct
    @State private var selectedPhoto: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            Group {
                switch viewModel.phase {
                case .idle:
                    home
                case .advertising(let name):
                    status(
                        symbol: "antenna.radiowaves.left.and.right",
                        title: "Ready to receive",
                        detail: "Visible nearby as \(name). Keep this screen open.",
                        action: "Cancel",
                        actionHandler: viewModel.cancel
                    )
                case .discovering:
                    discovering
                case .connectionRequested(let device):
                    status(
                        symbol: "link",
                        title: "Connecting to \(device.name)",
                        detail: "Waiting for both devices to show the authentication digits.",
                        action: "Cancel",
                        actionHandler: viewModel.cancel
                    )
                case .confirmCode(let endpointID, let deviceName, let code):
                    confirmation(endpointID: endpointID, deviceName: deviceName, code: code)
                case .connected(_, let deviceName):
                    status(
                        symbol: "lock.shield",
                        title: "Securely connected",
                        detail: "Preparing the transfer with \(deviceName).",
                        action: "Cancel",
                        actionHandler: viewModel.cancel
                    )
                case .transferring(let direction, let fileName, let transferred, let total):
                    transferProgress(direction: direction, fileName: fileName, transferred: transferred, total: total)
                case .verifying(let direction, let fileName):
                    status(
                        symbol: "checkmark.shield",
                        title: direction == .receive ? "Verifying file" : "Waiting for verification",
                        detail: direction == .receive
                            ? "Checking byte size and SHA-256 before the file enters your inbox."
                            : "Delivered appears only after the receiver verifies \(fileName).",
                        action: "Cancel",
                        actionHandler: viewModel.cancel
                    )
                case .complete(let direction, let fileName):
                    complete(direction: direction, fileName: fileName)
                case .failed(let reason, let detail):
                    failure(reason: reason, detail: detail)
                case .cancelled:
                    failure(reason: .rejected, detail: "No partial file was kept. Retry from the beginning.")
                }
            }
            .navigationTitle("NearPair")
            .navigationBarTitleDisplayMode(.inline)
        }
        .fileImporter(
            isPresented: $showingFilePicker,
            allowedContentTypes: [.pdf, .image, .movie],
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            if pickerPurpose == .direct { viewModel.chooseDirectFile(url) }
            else { viewModel.chooseSystemShareFile(url) }
        }
        .onChange(of: selectedPhoto) { item in
            if let item { viewModel.chooseMedia(item) }
            selectedPhoto = nil
        }
        .sheet(item: $viewModel.shareItem, onDismiss: viewModel.shareFinished) { item in
            ActivityView(url: item.url, completion: viewModel.shareFinished)
        }
        .sheet(item: $viewModel.exportItem) { item in
            DocumentExportPicker(url: item.url, completion: viewModel.exportFinished)
        }
        .alert("Action needed", isPresented: Binding(
            get: { viewModel.alertMessage != nil },
            set: { if !$0 { viewModel.alertMessage = nil } }
        )) {
            Button("Open Settings") { openSettings() }
            Button("Not now", role: .cancel) { viewModel.alertMessage = nil }
        } message: {
            Text(viewModel.alertMessage ?? "")
        }
    }

    private var home: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Send files between Android and iPhone")
                    .font(.largeTitle.bold())
                Text("Both people install NearPair, keep it open, and approve matching digits. No internet, account, or cloud storage is required.")
                    .foregroundStyle(.secondary)

                GroupBox {
                    Label {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Why permissions are needed").font(.headline)
                            Text("Bluetooth finds nearby devices. Local Network moves the selected file directly. NearPair never scans your library in the background.")
                                .font(.subheadline)
                        }
                    } icon: {
                        Image(systemName: "lock.shield")
                    }
                }

                TextField("Nearby device name", text: $viewModel.deviceName)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: viewModel.deviceName) { value in
                        if value.count > 48 { viewModel.deviceName = String(value.prefix(48)) }
                    }
                Text("Shown only while you send or receive")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button {
                    pickerPurpose = .direct
                    showingFilePicker = true
                } label: {
                    Label("Send with NearPair", systemImage: "doc.badge.arrow.up")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isStaging)

                PhotosPicker(selection: $selectedPhoto, matching: .any(of: [.images, .videos])) {
                    Label("Choose a photo or video", systemImage: "photo.on.rectangle")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isStaging)

                Button(action: viewModel.startReceiving) {
                    Label("Receive", systemImage: "arrow.down.circle")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isStaging)

                Divider().padding(.vertical, 4)
                Text("iOS-owned shortcut").font(.headline)
                Text("Opens the iOS share sheet. iOS—not NearPair—controls AirDrop recipients and compatibility.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button {
                    pickerPurpose = .systemShare
                    showingFilePicker = true
                } label: {
                    Label("Open iOS share sheet", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isStaging)

                if viewModel.isStaging {
                    HStack {
                        ProgressView()
                        Text("Staging and hashing the selected file…")
                    }
                }
            }
            .padding(20)
        }
    }

    private var discovering: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Choose a receiver").font(.title.bold())
            Text("The other person must tap Receive and keep NearPair open.")
                .foregroundStyle(.secondary)
            if viewModel.devices.isEmpty {
                Spacer()
                VStack(spacing: 12) {
                    ProgressView()
                    Text("No nearby receivers yet").font(.headline)
                    Text("Check Bluetooth and Wi-Fi on both devices, keep them close, and allow Bluetooth and Local Network access in Settings if previously denied.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Open Settings", action: openSettings)
                }
                .frame(maxWidth: .infinity)
                Spacer()
            } else {
                List(viewModel.devices) { device in
                    Button { viewModel.requestConnection(device) } label: {
                        Label(device.name, systemImage: "iphone.gen3")
                    }
                }
                .listStyle(.plain)
            }
            Button("Cancel", role: .cancel, action: viewModel.cancel)
                .frame(maxWidth: .infinity)
        }
        .padding(20)
    }

    private func confirmation(endpointID: String, deviceName: String, code: String) -> some View {
        VStack(spacing: 22) {
            Image(systemName: "lock.shield").font(.system(size: 44)).foregroundStyle(.tint)
            Text("Code on both devices").font(.title.bold())
            Text(code).font(.system(size: 42, weight: .bold, design: .rounded)).monospacedDigit()
            Text("Accept only if \(deviceName) shows these exact digits.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("Codes match") { viewModel.accept(endpointID: endpointID) }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            Button("Reject", role: .destructive) { viewModel.reject(endpointID: endpointID) }
        }
        .padding(28)
    }

    private func transferProgress(direction: TransferDirection, fileName: String, transferred: Int64, total: Int64) -> some View {
        VStack(spacing: 18) {
            Text(direction == .send ? "Sending" : "Receiving").font(.headline).foregroundStyle(.secondary)
            Text(fileName).font(.title2.bold()).multilineTextAlignment(.center)
            ProgressView(value: total > 0 ? Double(transferred) / Double(total) : 0)
            Text("\(formatBytes(transferred)) of \(formatBytes(total))")
            Button("Cancel transfer", role: .destructive, action: viewModel.cancel)
                .buttonStyle(.bordered)
        }
        .padding(24)
    }

    private func complete(direction: TransferDirection, fileName: String) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 52)).foregroundStyle(.green)
            Text(direction == .send ? "Delivered" : "Verified and received").font(.title.bold())
            Text(fileName).multilineTextAlignment(.center)
            if direction == .receive, let received = viewModel.receivedFile {
                Text("\(formatBytes(received.sizeBytes)) • SHA-256 matched")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Save to Files", action: viewModel.exportReceived)
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                Button("Share", action: viewModel.shareReceived)
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                Button("Delete", role: .destructive, action: viewModel.deleteReceived)
            } else {
                Button("Done", action: viewModel.done).buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
    }

    private func failure(reason: FailureReason, detail: String) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 48)).foregroundStyle(.orange)
            Text(failureTitle(reason)).font(.title.bold()).multilineTextAlignment(.center)
            Text(detail).multilineTextAlignment(.center).foregroundStyle(.secondary)
            if reason == .permissionDenied || reason == .radiosDisabled || reason == .unknown {
                Button("Open Settings", action: openSettings).buttonStyle(.borderedProminent)
            } else {
                Button("Retry", action: viewModel.retry).buttonStyle(.borderedProminent)
            }
            Button("Back to home", action: viewModel.done)
        }
        .padding(24)
    }

    private func status(symbol: String, title: String, detail: String, action: String, actionHandler: @escaping () -> Void) -> some View {
        VStack(spacing: 18) {
            ProgressView().controlSize(.large)
            Image(systemName: symbol).font(.title)
            Text(title).font(.title.bold()).multilineTextAlignment(.center)
            Text(detail).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button(action, role: .cancel, action: actionHandler).buttonStyle(.bordered)
        }
        .padding(24)
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }

    private func failureTitle(_ reason: FailureReason) -> String {
        switch reason {
        case .permissionDenied: return "Permission denied"
        case .radiosDisabled: return "Bluetooth or Wi-Fi is unavailable"
        case .insufficientStorage: return "Not enough storage"
        case .checksumMismatch, .sizeMismatch: return "Integrity check failed"
        case .connectionLost: return "Connection lost"
        case .backgrounded: return "Transfer stopped"
        case .rejected: return "Transfer cancelled"
        default: return "Transfer failed"
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private enum PickerPurpose { case direct, systemShare }
}
