# resQ mesh 0.3.0 — offline conversations and simpler connection controls

Development APK for Android 8.0+. Install the same version on both phones.

## Changes

- Home has Nearby devices and Offline chats. A compact top Bluetooth control requests the applicable permissions and opens Android’s enable dialog.
- Mesh online/offline reflects peer connectivity; Bluetooth availability is shown independently.
- Location forms request permission and automatically obtain a fresh foreground GPS/network fix. Missing permissions, disabled location and timeouts have retry guidance. Manual area identifiers and the precise-location toggle are removed from the UI.
- Nearby public chat, private conversations, mentions, replies, peer management and read/delivery indicators use the existing Bitchat chat interface over the shared BLE service.
- Photos, files and voice notes have a 256 KiB per-attachment limit. Media is available in public nearby chat and private conversations, not named groups.
- Named groups use the resQ RQGROUP1 extension. `/join flood-team` opens a public group. `/join flood-team your-shared-password` opens a password group (8–128 characters). All group participants need compatible resQ builds and matching passwords. Group names are visible; no global directory verifies passwords.
- Password group keys use PBKDF2-HMAC-SHA256 (100,000 iterations, channel name as salt, 256-bit key). AES-GCM uses random 96-bit nonces and the channel as authenticated context. Incorrect keys, altered ciphertext and unencrypted messages in locally locked groups are rejected. Use strong shared passwords; this does not prevent offline password guessing.
- Settings can share the installed APK. Clear local data uses Android’s application-data reset.

## Protocol scope

RQGROUP1 is a JSON envelope carried inside signed mesh text: `channel`, `text`, `encrypted`. Encrypted text is Base64 of nonce followed by AES-GCM ciphertext and tag. Application text is limited to 4 KB. Unit tests cover group decoding, tampering, key/channel mismatch, fresh nonces and bounded ingress.

This is a BLE-only integration. Wi-Fi Aware, Nostr, Tor and internet location channels are disabled. Named groups are not interoperable with stock Bitchat group UIs. This is not a claim of complete Bitchat feature parity. Existing simple resQ contact conversations remain separate from the upstream chat history.

Approximate routing areas remain internal protocol metadata. Health details and automatic exact coordinates are shared encrypted with the accepted responder. The UI does not expose manual cell/geohash controls. GPS requires device location services and may be unavailable indoors or during a disaster.

## Validation

- Debug APK assembled successfully.
- 37 focused tests passed: Android 7, crypto 14, domain 3, pipeline 12, simulator 1.
- Emulator install/launch and Home, nearby-device, offline-chat, theme and location-off flows exercised. An emulator does not validate BLE delivery.
- Physical Mesh Lab: **blocked (not run)**; two authorized physical lab phones are required. Radio delivery, transfers, reconnect and background behavior remain unverified on hardware.
- The earlier inherited full suite had 23 failures out of 693 and lint had 313 errors/386 warnings. This targeted pass does not resolve or supersede those results.

For a disposable, authorized phone pair, keep raw evidence local and follow the repository Mesh Lab runbook:

```sh
python tools/release_gate/mesh_lab.py setup --serial-a <device-a> --serial-b <device-b> --apk <debug-apk>
python tools/release_gate/mesh_lab.py scenario all --serial-a <device-a> --serial-b <device-b> --out <local-evidence-directory>
```

Setup clears lab app data. Add resQ-specific group and GPS checks; upstream scenarios alone do not cover this extension. This debug prerelease is not a certified emergency service or a field-validated rescue system.
