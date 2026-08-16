# Real-device QA and release gate

Nearby Connections behavior depends on the physical Bluetooth/Wi-Fi stack, Google Play services, iOS Local Network authorization, available storage, radio conditions, and package revisions. Simulator/emulator success is not a substitute for this matrix.

## Required hardware

- Samsung Galaxy S23 Ultra, current supported Android/Play-services updates
- Samsung Galaxy Tab S10+, current supported Android/Play-services updates
- iPhone 13, current supported iOS 16+ release
- A second Android device and, if available, a second iOS/iPadOS device for same-platform coverage

Record the OS build, Play-services version, NearPair commit, Android APK version, iOS build number, and resolved `google/nearby` Swift package revision with every run.

## Gate 0: transport prototype

This is the first required physical milestone before UI polish or store work:

| Route | File | Network | Expected |
| --- | --- | --- | --- |
| S23 Ultra → iPhone 13 | small PDF | internet unavailable; Bluetooth/Wi-Fi on | both approve identical digits; PDF byte count/hash match; Android shows Delivered only after iPhone acknowledgement |
| iPhone 13 → S23 Ultra | small PDF | same | inverse route succeeds with the same verification guarantees |
| Tab S10+ → iPhone 13 | small PDF | same | same |
| iPhone 13 → Tab S10+ | small PDF | same | same |

If any Gate 0 route fails, capture the exact state on both devices, Nearby/Play-services logs where permitted, permission/radio state, package revisions, and file metadata. Do not treat OS share-sheet behavior as a substitute.

## File matrix

Run each format in both directions for every cross-platform device pair:

| Class | Small | Medium | Large |
| --- | --- | --- | --- |
| PDF | <1 MiB | 25–100 MiB | 500 MiB+ if practical |
| JPEG | <2 MiB | 20–50 MiB | largest real camera image available |
| PNG | <2 MiB | 20–50 MiB | largest practical image |
| MP4 | <20 MiB | 500 MiB–1 GiB | multi-GB (target 3–5 GiB) |

For every success, independently compare original and received file byte size and SHA-256. Confirm the receiver never enables Save/Share/Delete before verification and the sender never reports Delivered before the receiver acknowledgement.

Also run:

- Android → Android for PDF, JPEG/PNG, and MP4
- iOS → iOS for PDF, JPEG/PNG, and MP4
- filenames containing spaces, Unicode, leading dots, `../`, backslashes, colons, and control characters
- duplicate filenames already present in the receiver inbox
- a deliberately unsupported file (ZIP/executable) to verify rejection before transport

## Permissions and radios

Test from a fresh install and after revocation:

- deny Bluetooth, retry, follow the exact Settings recovery path, then allow
- deny iOS Local Network, retry, open Settings, then allow
- deny Android nearby devices/nearby Wi-Fi, retry, then allow
- Android 10–12 location/file access paths required by the pinned Nearby implementation
- Android 17/API 37+ `ACCESS_LOCAL_NETWORK` once hardware/SDK is available
- Bluetooth off before Send and before Receive
- Wi-Fi off before Send and before Receive
- airplane mode with Bluetooth/Wi-Fi manually re-enabled
- internet unavailable with Bluetooth/Wi-Fi enabled (must still transfer)
- no receiver advertising for at least two minutes (must show useful no-device guidance and remain cancellable)

Permission prompts must appear only after Send/Receive, never on first launch. Copy must explain why Bluetooth and local-network access are required without claiming background scanning.

## Failure and interruption

For each case, confirm the session terminates, partial files are not exposed, and Retry starts a new transfer rather than claiming resume:

- sender rejects authentication digits
- receiver rejects authentication digits
- cancel at 0–5%, approximately 50%, and above 95%
- move devices out of range during metadata and during file transfer
- disable Bluetooth during transfer
- disable Wi-Fi during transfer
- background sender during discovery, authentication, and transfer
- background receiver during advertising, authentication, and transfer
- terminate either app during transfer, then relaunch
- lock either device during transfer
- rotate Android phone/tablet and iPad during each screen
- sender file changes or disappears while staging
- incoming metadata arrives before resource and resource arrives before metadata (instrumented/fake-engine test)
- malformed JSON, unknown message type, unsupported protocol version, and mismatched acknowledgement IDs

Expected foreground-v1 behavior: backgrounding an active direct session ends it with explicit guidance. No silent receiving or background completion is promised.

## Storage and integrity

- Leave less than `file size + max(5%, 32 MiB)` available at the receiver; metadata acceptance must fail with storage recovery text.
- Leave insufficient staging space at the sender; discovery must not start.
- Fill storage after metadata but before resource completion.
- Modify one byte in an instrumented received temporary file before verification; checksum must fail and the inbox file must be deleted.
- Supply incorrect `sizeBytes`; size verification must fail and the inbox file must be deleted.
- Confirm large videos do not load wholly into memory when selected through Files/document pickers.
- After Save or Delete, confirm the app-private inbox copy is removed. After cancel/failure, confirm `.part` files are removed.

## System share sheets (separate test track)

Android:

- Select PDF/image/video and verify `ACTION_SEND`, a `content://` URI, exact MIME type, read grant, and Android chooser.
- If Quick Share appears, record only that Android offered it; do not label a selected recipient as NearPair discovery.

iOS:

- Select PDF/image/video and verify `UIActivityViewController` appears with the file URL.
- If AirDrop appears, record only that iOS offered it; do not claim NearPair selected or automated the recipient.

On unsupported hardware, the share-sheet test passes when the correct OS UI opens. It does not require cross-platform AirDrop/Quick Share interoperability.

## Usability, accessibility, and privacy

- Dynamic Type through accessibility sizes; no clipped authentication digits or primary actions
- VoiceOver/TalkBack names and focus order for Send, Receive, device rows, code approval, progress, cancel, Save, Share, Delete
- color contrast in light/dark mode and high-contrast settings
- phone/tablet split layouts and iPad multitasking sizes
- device names with Unicode, emoji, very long text, and privacy-sensitive owner names
- no automatic file opening or executable handling
- no transfer history after restart
- no outbound app analytics/network request during offline transfer (separate expected Google Play-services diagnostics from app traffic)
- privacy copy accurately states no NearPair cloud/account and discloses Google Play-services opt-out performance diagnostics on Android

## Production exit criteria

- Gate 0 and the complete bidirectional format matrix pass on the named devices.
- No P0/P1 data-loss, wrong-recipient, authentication-bypass, integrity, or temp-file leakage defect remains.
- Large multi-GB video succeeds repeatedly without memory pressure termination.
- All permission/revocation, connection-loss, cancellation, background, and storage cases have deterministic recovery text.
- Android release build, lint, unit tests, and signed APK/AAB installation pass.
- iOS archive, unit tests, privacy manifest validation, TestFlight installation, and App Review permission-copy review pass.
- The pinned Swift package revision passes the matrix; any later revision change reruns the complete matrix.
