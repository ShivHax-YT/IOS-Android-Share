# NearPair

NearPair is a foreground-to-foreground nearby file-transfer MVP for Android and iOS/iPadOS. It sends one PDF, image, or video directly between two devices using Google Nearby Connections. It has no account, backend, cloud relay, contacts integration, or transfer history.

The direct-transfer flow is intentionally separate from the platform share sheets:

- **Send with NearPair** requires NearPair on both devices and works without internet when Bluetooth and Wi-Fi are enabled.
- **Android share sheet** lets Android choose Quick Share or another installed share target.
- **iOS share sheet** lets iOS choose AirDrop or another installed activity.

NearPair never discovers, selects, or automates Quick Share or AirDrop recipients.

## Repository layout

- `android/` — Kotlin, Jetpack Compose, coroutines, Android 10+
- `ios/` — Swift, SwiftUI, iOS/iPadOS 16+
- `protocol/` — the shared, versioned JSON wire contract and fixtures
- `docs/PROTOCOL.md` — sequencing, integrity, compatibility, and failure rules
- `docs/REAL_DEVICE_QA.md` — the required hardware test matrix and milestone runbook

## Fixed interoperability values

| Setting | Value |
| --- | --- |
| App protocol | `1` |
| Nearby service ID | `com.nearpair.transfer.v1` |
| Strategy | point-to-point |
| iOS Bonjour service | `_EBD1B4122871._tcp` |
| Maximum participants | one sender and one receiver |

Do not change the service ID or Bonjour value independently. A service-ID change is a wire compatibility break and must ship in both apps together.

## Build

### Android

Open `android/` in Android Studio, use JDK 17, sync Gradle, and run the `app` configuration on a physical device. Nearby Connections depends on Google Play services; use a device image/device with current Play services.

The project pins `com.google.android.gms:play-services-nearby:19.3.0`. Android Studio can generate or refresh Gradle wrapper files if your checkout does not already supply a local wrapper JAR.

### iOS / iPadOS

Open `ios/NearPair.xcodeproj` in Xcode on macOS. Select your development team and a physical iPhone/iPad. Xcode resolves the official `google/nearby` Swift package and links the `NearbyConnections` product.

If `com.nearpair.app` is unavailable to your signing team, change the Android application ID and iOS bundle identifier to identifiers you control. Do **not** change `com.nearpair.transfer.v1` or `_EBD1B4122871._tcp` unless both platform transports and the protocol version are deliberately migrated together.

The open-source Nearby Swift package currently publishes its active implementation from the `main` branch rather than a current semantic-version release. The Xcode project pins official revision `30bae19cc2ab97aab6fec519a1201e9f0ccd36a9` (verified 2026-08-14) for reproducible builds. Update that revision only in a dedicated dependency change followed by the complete real-device interoperability pass.

## First milestone

1. Install both apps on a Galaxy S23 Ultra and iPhone 13.
2. Disable internet access while leaving Bluetooth and Wi-Fi enabled.
3. On iPhone, tap **Receive**.
4. On Android, tap **Send with NearPair**, select a PDF, and select the iPhone.
5. Confirm the same authentication digits on both devices.
6. Verify that the receiver reports the expected size and SHA-256, then save or share the received PDF.
7. Verify Android reports **Delivered** only after the iPhone sends the app-level verified acknowledgement.

See `docs/REAL_DEVICE_QA.md` for the full matrix.

## Privacy and security boundary

- Files are staged only in app-private local storage and are never auto-opened.
- Incoming names are sanitized and cannot supply path components.
- Receivers verify byte size and SHA-256 before a transfer can complete.
- Both people manually approve matching Nearby authentication digits.
- Temporary files are removed on delete/cancel and after an explicit save where the platform flow permits it.
- The app itself sends no analytics and stores no content in the cloud. Google Play services' Android Nearby implementation may collect opt-out performance diagnostics under Google's published terms.

This repository is an MVP, not a claim of AirDrop interoperability. Production release still requires the physical-device tests, interruption/background tests, accessibility review, app-store assets, signing, and privacy disclosures described in the QA document.
