# Move NearPair from the MSI laptop to a Mac Mini

The file `NearPair-Mac-Transfer.zip` is the clean source bundle. It contains the
Android app, iOS app, shared protocol, documentation, and build wrappers. It
intentionally excludes generated build output, Android Studio caches,
`local.properties`, and machine-specific Xcode files.

## Transfer the bundle

Use any one of these methods:

1. Copy `NearPair-Mac-Transfer.zip` to a USB drive formatted as exFAT, then copy
   it to the Mac Mini.
2. Put only the ZIP in OneDrive and download it on the Mac Mini. In Finder,
   right-click it and choose **Download Now** before opening it.
3. If Windows file sharing is enabled, copy the ZIP over the local network.

Keep the original folder on the MSI laptop until both apps build successfully
on the Mac.

## Set up the Mac Mini

1. Install all macOS updates.
2. Install Xcode from the Mac App Store, open it once, and allow its additional
   components to install.
3. Install Android Studio from the official Android developer website. During
   first launch, install the Android SDK and use the bundled JDK 17.
4. Expand `NearPair-Mac-Transfer.zip` into a normal local folder such as
   `~/Developer/NearPair`. Do not build directly inside a cloud-synced folder.
5. In Terminal, run:

   ```bash
   cd ~/Developer/NearPair
   chmod +x android/gradlew
   ```

## Build the iOS app

1. Open `ios/NearPair.xcodeproj` in Xcode.
2. Let Xcode resolve the Nearby Connections Swift package.
3. Open the project target's **Signing & Capabilities** section.
4. Select your Apple ID development team. If Xcode reports that
   `com.nearpair.app` is unavailable, change only the app bundle identifier to a
   unique value such as `com.yourname.nearpair`.
5. Connect the iPhone by cable, unlock it, trust the Mac, enable Developer Mode
   if prompted, select the iPhone as the run destination, and press Run.

Do not change the Nearby service ID `com.nearpair.transfer.v1` or Bonjour value
`_EBD1B4122871._tcp`; both platforms depend on those exact values.

## Build the Android app

1. In Android Studio, open the extracted `android` folder.
2. Allow Gradle sync to finish and accept any requested Android SDK install.
3. Connect the Android phone with USB debugging enabled, select it, and Run the
   `app` configuration.

## Test the transfer

Install NearPair on both physical phones. Keep Bluetooth and Wi-Fi enabled on
both. On iPhone tap **Receive**; on Android tap **Send with NearPair**, choose a
file, select the iPhone, and approve the matching authentication digits on both
devices. The detailed test checklist is in `docs/REAL_DEVICE_QA.md`.

## ChatGPT and project files

Installing and signing in to the ChatGPT/Codex app on the Mac does not copy this
local project automatically. The ZIP transfer above is still required. After
extracting it, open that folder as the workspace in the Mac app.

