import SwiftUI

@main
struct NearPairApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = NearPairViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { viewModel.onAppBackgrounded() }
        }
    }
}

