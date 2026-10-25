# resQ mesh 0.2.0

Install this update over the previous CASCADE MESH APK; the package and signing identity remain unchanged so existing app data is retained.

## Bluetooth repair

- Requests coarse and fine location together, as required for the Android 12+ location dialog and upstream BLE checks.
- Distinguishes missing Nearby devices permission, precise-location permission, disabled Location services, a disabled Bluetooth radio, and an absent adapter.
- Connect opens Android's Bluetooth-enable dialog when appropriate.
- Each foreground-service start command retries the radio; returning from Settings also rechecks readiness.
- Reports asynchronous GATT server/client startup results instead of declaring success before startup finishes.
- Unexpected startup exceptions show an exception class rather than incorrectly blaming permissions. Diagnostic output excludes exception messages, identifiers and payloads.

## People and contacts

People lists nearby nodes, saved contacts and existing conversations. Tap a person to open an individual chat. Save contact assigns a local name; removing the contact preserves the conversation. Wide layouts keep the people list beside the selected chat.

Messages require an established upstream Noise session. If the session is not ready, Send initiates the handshake and keeps the draft for retry. There is no plaintext broadcast fallback. Outgoing messages initially show “Queued · delivery unconfirmed”; an upstream delivery acknowledgment updates the status. No offline outbox/retry promise is made.

Contact names and the latest 500 messages are stored in encrypted app preferences. Contacts use the upstream persistent mesh identity; wiping the other phone's identity creates a different contact. Names are not verified identities. These contacts are app-local and do not access the Android address book.

## Validation scope

The build, targeted permission regression tests, emulator UI/startup checks and downloadable APK checksum are checked locally. Two physical Android phones are still required to validate BLE discovery, private chat delivery, radio recovery and power behavior. The inherited full-suite and lint failures documented in CASCADE-VALIDATION and website/VALIDATION are not resolved by this update.

Observed: three permission regression tests passed. With Bluetooth disabled, returning to the app displayed “Bluetooth is off.” Connect opened the Android enable dialog; accepting it restored “Waiting for another device” without restarting the app. Home, Requests, Map, People and More icons were rendered in the emulator. Physical peer discovery and chat delivery have not been verified.
