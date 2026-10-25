<p align="center">
  <img src="docs/media/readme-cover.svg" alt="resQ mesh — People are the network. Offline Android Bluetooth mesh." width="1200">
</p>

<h1 align="center">resQ mesh</h1>
<p align="center"><strong>Offline emergency coordination over nearby Bluetooth radios.</strong></p>
<p align="center">
  <a href="https://resq-meshnet.web.app"><strong>Explore the website ↗</strong></a> &nbsp; · &nbsp;
  <a href="https://github.com/hydra-eng/resq-mesh/releases/tag/v0.3.0"><strong>Download APK ↓</strong></a> &nbsp; · &nbsp;
  <a href="#tech-stack"><strong>Tech stack</strong></a> &nbsp; · &nbsp;
  <a href="#build-from-source"><strong>Build locally</strong></a>
</p>

---

```text
  [ REQUEST ] ──── [ RELAY ] ──── [ RESPONDER ]
       │               │               │
     signed          offline         human-led
       └───────────────┴───────────────┘
                  resQ mesh
```

| Request & respond | Connect & chat | Reconnect & reconcile |
|:---|:---|:---|
| Share structured needs and review offers. | Discover nearby nodes and save local contacts. | Merge signed events after a network split. |
| Explain why a responder is suggested. | Open an individual encrypted conversation. | Preserve responders already at the door. |

An Android app for sending emergency requests and coordinating nearby responders over Bluetooth mesh when internet access is missing. **No server is required for app-to-app mesh use.** This repository also contains an explainer site and a downloadable debug APK.

> **Status:** version 0.3.0 is an early development build. It is not a certified emergency service and has not been field-validated. Do not rely on it as your only means of getting help. BLE range, delivery, routing and battery life vary by device and conditions.

<details>
<summary><strong>Project at a glance</strong></summary>

| Property | Value |
|---|---|
| Android application ID | `org.cascade.mesh` |
| Version | `0.3.0` (version code 3) |
| Minimum Android | 8.0 / API 26 |
| Language and UI | Kotlin, Jetpack Compose, Material 3 |
| Radio transport | Bluetooth Low Energy, using the Bitchat Android mesh stack |
| App data | Local Room database, encrypted preferences, Android Keystore |
| License | GPL-3.0-or-later; see [`LICENSE.md`](LICENSE.md) and upstream notices |
| Repository | [GitHub](https://github.com/hydra-eng/resq-mesh) |
| Website | [resQ mesh](https://resq-meshnet.web.app) |
| Android download | [Debug APK](https://resq-meshnet.web.app/downloads/resq-mesh-0.3.0-debug.apk) |

</details>

## Contents

- [Tech stack](#tech-stack)
- [Install and connect two phones](#install-and-connect-two-phones)
- [A normal request flow](#a-normal-request-flow)
- [What the app does](#what-the-app-does)
- [How the mesh works](#how-the-mesh-works)
- [Privacy and security](#privacy-and-security)
- [Build from source](#build-from-source)
- [Project layout](#project-layout)
- [Validation and known limitations](#validation-and-known-limitations)
- [Website and simulator](#website-and-simulator)
- [Contributing](#contributing)

## Tech stack

The technologies below are used by the current project. Versions come from the checked-in build configuration; the website uses plain HTML, CSS and JavaScript.

| Logo | Technology | Version / specification | Role in resQ mesh |
|:---:|---|---|---|
| <img src="docs/media/stack/android.svg" width="28" height="28" alt="Android"> | **Android** | API 26+ · compile / target 37 | Native phone app, BLE permissions and foreground service. |
| <img src="docs/media/stack/kotlin.svg" width="28" height="28" alt="Kotlin"> | **Kotlin** | 2.4.10 | Android app and shared domain, pipeline, crypto and simulator modules. |
| <img src="docs/media/stack/jetpackcompose.svg" width="28" height="28" alt="Jetpack Compose"> | **Jetpack Compose** | BOM 2026.06.01 | Declarative screens, navigation, responsive layouts and state-driven UI. |
| <img src="docs/media/stack/materialdesign.svg" width="28" height="28" alt="Material Design"> | **Material 3** | Compose BOM managed | Theme, accessible controls, light mode and black/green dark mode. |
| <img src="docs/media/stack/kotlin.svg" width="28" height="28" alt="Kotlin"> | **Coroutines & StateFlow** | 1.11.0 | Background work, event handling and observable UI state. |
| <img src="docs/media/stack/bluetooth.svg" width="28" height="28" alt="Bluetooth"> | **Bluetooth Low Energy** | Bitchat Android transport | Nearby discovery, GATT connections, fragmentation, gossip and relaying. |
| <img src="docs/media/stack/sqlite.svg" width="28" height="28" alt="SQLite"> | **Room / SQLite** | Room 2.8.4 | Durable local journal of signed operational facts. |
| <img src="docs/media/stack/android.svg" width="28" height="28" alt="Android platform"> | **Android Keystore** | Platform API | Protection for local keys and encrypted private storage. |
| — | **Hilt / KSP** | 2.60.1 / 2.3.12 | Dependency injection and generated Room/Hilt code. |
| — | **Cryptography** | Ed25519 · X25519 · HKDF-SHA256 · AES-256-GCM · Noise | Signed facts, per-offer private data and authenticated peer sessions. |
| <img src="docs/media/stack/gradle.svg" width="28" height="28" alt="Gradle"> | **Gradle / JDK** | Gradle 9.6.1 · AGP 9.3.1 · JDK 21 | Android/JVM builds, dependency locks and verification metadata. |
| <img src="docs/media/stack/html5.svg" width="28" height="28" alt="HTML5"> <img src="docs/media/stack/css.svg" width="28" height="28" alt="CSS"> <img src="docs/media/stack/javascript.svg" width="28" height="28" alt="JavaScript"> | **HTML / CSS / JavaScript** | Single HTML file | Responsive explainer, theme switching, ASCII graphics and canvas simulator. |
| <img src="docs/media/stack/threedotjs.svg" width="28" height="28" alt="Three.js"> | **Three.js** | 0.160.1 · pinned CDNJS UMD | Interactive mesh, flood, attack, partition and routing scenes. |
| <img src="docs/media/stack/firebase.svg" width="28" height="28" alt="Firebase"> | **Firebase Hosting** | Static hosting | Public website at `resq-meshnet.web.app`; APK path redirects to GitHub. |
| <img src="docs/media/stack/github.svg" width="28" height="28" alt="GitHub"> | **GitHub Releases** | v0.3.0 | Versioned debug APK, public source and SHA-256 asset digest. |
| — | **JUnit / Robolectric / Mockito** | 4.13.2 / 4.15 / 5.23.0 | JVM and Android unit testing; see the validation results below. |

<sub>Brand logos: <a href="https://simpleicons.org">Simple Icons</a>, stored locally in this repository. Logos identify the technologies and do not imply endorsement. <a href="docs/media/stack/README.md">Asset attribution</a>.</sub>

## Install and connect two phones

1. Download the same APK onto both Android phones. The current debug APK is about 75 MiB. Check its SHA-256 before sharing or installing:

   ```text
   7a91088e82c0f11d8df0b0aea8e004c53cb48c53e15210839c852c0cb310bfff
   ```

2. Install the APK on each phone and open resQ mesh. Android may ask you to allow installation from the browser or file manager used to open the APK.
3. Allow **Nearby devices**. On older Android versions, allow location while using the app; some phones also require the system Location switch to be on for BLE scanning. Notifications allow the foreground mesh service to show its status.
4. On both phones, tap the **Bluetooth button at the top**, then **Nearby devices** on Home and leave the app open while it starts discovery. Keep Bluetooth enabled and the phones nearby. The peer count changes when the other phone is discovered.
5. On one phone choose **Offer help**; on the other choose **Request help**. Use synthetic details for demonstrations. An SOS can be queued before a peer is reachable; the screen does not claim delivery until the transport reports it.

Phones need compatible radios and the app needs to stay permitted to run in the foreground. Manufacturer power management may stop background scanning. Internet is not needed for the app's peer-to-peer mesh, but installation and opening the hosted website require an internet connection. Two phones must have compatible versions and both run resQ mesh.

## Offline chats in 0.3

Open **Home → Offline chats** for the nearby public timeline. Open the people panel and select a discovered peer for a private conversation. Saved contacts remain available from **People**.

- Public nearby text, private messages, mentions, replies, delivery/read indicators and peer blocking use the upstream chat interface.
- Photos, files and short voice notes are available in the nearby timeline and private chats; each attachment is limited to **256 KiB**.
- Join a named text group with `/join flood-team`. For a password group, use `/join flood-team your-shared-password` on both phones (8–128 characters). Share a strong password through a trusted channel. Group names remain visible.
- Named groups use the **resQ RQGROUP1 extension** and require compatible resQ versions. Password groups use PBKDF2-SHA256 and AES-GCM; they are not stock Bitchat group interoperability. Group attachments are disabled.
- Settings includes **Share app** so an installed APK can be passed to another phone through Android’s share sheet.
- Bluetooth availability and peer connectivity are shown separately. “Mesh online” means a nearby mesh peer is connected, not that internet is available.
- Request, responder and hazard forms request location permission and obtain a fresh fix automatically. Manual area identifiers and the precise-location switch are removed. Routing still uses approximate areas internally; accepted responders receive encrypted exact coordinates.

This build enables BLE offline features. It does not enable Wi-Fi Aware, Nostr, Tor or internet location channels, and does not claim full Bitchat feature parity. See [0.3 release notes](docs/RESQ-0.3.md) for validation and limitations.

## A normal request flow

```mermaid
flowchart TD
  A[Open app] --> B{Choose a task}
  B -->|Need help| C[Describe need and urgency]
  C --> D[Allow location and wait for a fresh GPS fix]
  D --> E[Review and send signed request]
  B -->|Offer help| F[Set capabilities and battery]
  F --> G[Discover nearby peers]
  E --> H[BLE peers relay bounded packets]
  G --> H
  H --> I[Responder reviews request and offer]
  I -->|Accept| J[Private offer handshake]
  I -->|Decline or timeout| K[Try another eligible responder]
  J --> L[En route]
  L --> M[At the door / arrived]
  M --> N[Complete or ask for backup]
  H --> O[People: save a local contact or open a private chat]
```

Contact names are stored on the device and are not verified identities. Private chat uses the upstream authenticated Noise session; messages are not sent as public fallback if a secure session is unavailable.

## What the app does

### Main screens and actions

The bottom navigation presents **Home**, **Requests**, **Map**, **People**, and **More**. The interface covers: onboarding, home, request creation, incoming request feed, request detail, map, people, one-to-one chat, capability profile, network topology, trust review, hazard reporting, simulation, and settings/help content. Larger layouts can display the people list beside the active conversation.

- **Request help:** record a signed SOS with urgency, automatic GPS and structured assistance needs.
- **Offer help:** advertise responder capabilities and current availability for local matching.
- **Requests:** inspect active requests and their dispatch state; delivery is not inferred from local queueing.
- **Map and topology:** show a coarse schematic and locally observed peers, not fabricated turn-by-turn directions or remote battery readings.
- **People and chat:** see nearby nodes and existing conversations, save or rename a local contact, and open a separate conversation. Contact labels do not authenticate someone's identity.
- **Hazards and trust:** record time-bounded observations and review direct peers locally. A single safe report is not enough to certify a cell as safe.
- **Settings:** change theme, radio settings and privacy controls; inspect permission/radio readiness and local-data actions.
- **Accessibility:** large touch targets, system font scaling, TalkBack labels, light/dark themes, reduced-motion handling and explicit status text.

### Feature map

| Need | Behavior |
|---|---|
| Offline coordination | BLE peer discovery, packet forwarding and gossip through the bundled upstream Android mesh infrastructure |
| Structured emergency facts | Signed, bounded, immutable event records and deterministic projections |
| Partition recovery | Merge replicated event sets after peers reconnect; retain arrived responders |
| Responder matching | Stable scoring over urgency, capability, confidence, local trust, coarse distance, hazard and modeled cost |
| Scarce skills | Greedy allocation reserves a uniquely qualified responder for a more urgent eligible case |
| Private health details | Per-offer X25519 exchange, HKDF-SHA256 key derivation and AES-256-GCM encryption |
| Abuse controls | Signature validation, nonce and timestamp replay checks, schema and packet bounds, per-origin and global rate limits |
| Hazard lures | Safe-cell confidence requires independent corroboration; live danger vetoes conflicting safe evidence |
| Individual chat | Separate conversations over an established Noise session, without plaintext broadcast fallback |
| Simulation | Seeded JVM simulator for partitions, conflicting dispatches and attacker traffic |
| Website | Responsive single-file explainer, interactive diagrams, 2D simulator, WebGL fallback and APK checksum display |

## How the mesh works

The Android client builds on [permissionlesstech/bitchat-android](https://github.com/permissionlesstech/bitchat-android), which provides the Kotlin Bluetooth mesh, multi-hop relay, fragmentation, gossip, deduplication, persistent identity and Noise-session implementation. The Apple repository at [permissionlesstech/bitchat](https://github.com/permissionlesstech/bitchat) is a different client; the Android repository is used here because this is an Android application. Existing upstream notices and source are retained. See [`UPSTREAM-README.md`](UPSTREAM-README.md).

The app has no `INTERNET` permission and does not require Firebase or a central server to exchange peer messages. Firebase Hosting serves the public explainer website and redirects APK downloads to GitHub Releases.

### Architecture

```mermaid
flowchart TB
  UI["Android UI · Jetpack Compose"] --> APP["App actor · StateFlow · Hilt"]
  APP --> DATA["Room journal + encrypted local vault"]
  APP --> CORE["Domain facts + deterministic dispatch pipeline"]
  CORE --> CRYPTO["Signatures · replay checks · recipient encryption"]
  CRYPTO --> ADAPTER["Transport adapter"]
  ADAPTER --> BLE["Bitchat BLE mesh + Noise sessions"]
  BLE <--> PEERS["Nearby phones / relay nodes"]
  SIM["Seeded JVM simulator"] --> CORE
  WEB["Explainer website · Firebase Hosting"] -. "APK download redirect" .-> GH["GitHub Releases"]
  classDef app fill:#0b2116,stroke:#00d26a,color:#e8fff0
  classDef core fill:#10251b,stroke:#73e6a0,color:#e8fff0
  classDef external fill:#18201b,stroke:#9eb6a5,color:#f1f5f2
  class UI,APP,DATA app
  class CORE,CRYPTO,ADAPTER,SIM core
  class BLE,PEERS,WEB,GH external
```

<details>
<summary><strong>Watch the mesh concept — animated ASCII</strong></summary>

![Illustrative ASCII animation of an SOS relayed between phones](docs/media/mesh-pulse.gif)

This animation illustrates packet relaying. It is not a live capture or proof of message delivery.

</details>

### Evaluation pipeline

```mermaid
flowchart LR
  A[Claim verification] --> B[Hazard avoidance]
  B --> C[Trust scoring]
  C --> D[Battery and routing cost]
  D --> E[Safe dispatch]
  E --> F[Human acceptance]
  F -->|decline or timeout| D
  F -->|accepted| G[En route]
  G --> H[Arrived]
```

### Replication and conflict rules

Each operation is a signed fact. Replication merges by set union, so a merge does not depend on packet arrival order. SAFE, CANCEL and COMPLETE events are facts, not deletion of history.

1. An authenticated **ARRIVED** fact is never automatically canceled, including when a competing dispatch is merged.
2. Otherwise, the accepted dispatch with the smallest Lamport clock wins; dispatch ID is the stable tie-break.
3. A responder may explicitly abort or complete. A duplicate that has already arrived remains visible for human resolution.
4. Offers expire for response after 60 seconds; retained signed history can still carry safety facts across reconnection.
5. If disconnected islands accept different assignments, neither can guarantee a global single responder before reconnection. The deterministic merge resolves replicated facts afterward.

The clock provides a shared deterministic logical ordering over the same events; it is not proof of real wall-clock acceptance order. Detailed record types and merge behavior are in [`docs/CASCADE-PROTOCOL.md`](docs/CASCADE-PROTOCOL.md).

### Matching and routing

Every node runs the same fixed-point scoring and stable tie-breaks when given the same candidate and evidence set. The planner filters for eligibility, ranks by urgency/capability/confidence/trust/coarse distance, accounts for hazard and modeled battery/routing cost, and greedily assigns responders. It can reserve a uniquely qualified responder for a higher-urgency nearby case. Nodes can temporarily differ when their radio observations or in-person reviews differ; the app does not claim a coordinator-free consensus on facts that have not propagated.

The map is a coarse-cell schematic. A route is only produced through cells with corroborated safe evidence and known adjacency. It is not GPS turn-by-turn navigation; no report means **unknown**, not safe.

## Privacy and security

- Publicly relayed records are signed and bounded. Relays need routing and matching context, so they can still observe identity, timing, record type, dispatch references and some coarse operational metadata.
- Free-text health details and exact coordinates are encrypted for the selected recipient per offer. The design uses ephemeral X25519 public keys, HKDF-SHA256, and AES-256-GCM with offer/acceptance context bound to the ciphertext.
- Local private data is protected by encrypted app storage backed by Android Keystore. Chat messages and saved contacts remain app-local.
- Nonces, timestamps, signatures, canonical wire encoding, allowlisted schemas, input-size limits, bounded queues and rate budgets mitigate tampering, replay and battery-drain attempts.
- New identities do not earn trust just by arriving in a swarm. Self-asserted medical qualifications are not treated as verified credentials.
- Hazard corroboration requires independent reviewed groups. It helps resist a single false-safe lure; it cannot prove that any route is safe or defeat collusion.
- Location reports can be spoofed, RSSI can be manipulated and peers may collude. These controls raise attack cost; they are not a guarantee against an adaptive adversary.
- Clearing local data cannot erase copies already forwarded to another phone. Do not use real health information in development or demonstrations.

No Firebase SDK, account login, analytics upload or cloud health-data database is configured. Hosting logs are controlled by the hosting provider; the Android app itself exchanges data over nearby radios.

## Build from source

### Requirements

- Android Studio or a full JDK 21 (including `javac` and `jlink`)
- Android SDK platform 37 and Build Tools 37.0.0
- Gradle wrapper downloads Gradle 9.6.1; initial dependency resolution requires internet on the development computer
- Windows: use `gradlew.bat`; macOS/Linux: use `./gradlew`

```sh
./gradlew :app:assembleDebug
```

The universal debug APK is at `app/build/outputs/apk/debug/app-universal-debug.apk`. To run the focused JVM suites and simulator:

```sh
./gradlew :domain:test :pipeline:test :crypto:test :simulator:test
./gradlew :simulator:run --args=100
```

To run the inherited Android unit suite and lint:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Known inherited suite/lint findings are documented below; a successful build does not mean all inherited lint findings are resolved.

### Optional release signing

```sh
./gradlew :app:assembleRelease
```

Without signing inputs the release APK is unsigned. To sign a release yourself, set `CASCADE_KEYSTORE`, `CASCADE_STORE_PASSWORD`, `CASCADE_KEY_ALIAS` and `CASCADE_KEY_PASSWORD` in your local environment. Never commit keystores or credentials. This repository does not use the upstream release signing identity.

Dependency lockfiles and Gradle SHA-256 verification metadata are committed. When deliberately changing dependencies, review the updated lock and verification files as part of the change.

## Project layout

| Path | Responsibility |
|---|---|
| `app/src/main/java/com/bitchat/android/mesh` | Upstream Bluetooth mesh integration |
| `app/src/main/java/com/bitchat/android/cascade` | Android adapter, persistence, app actor, permissions and Compose UI |
| `app/src/cascade` | CASCADE Android manifest and resources |
| `domain` | Immutable facts, dispatch records, coarse geohash and grow-only set |
| `pipeline` | Verification, hazards, trust, cost, assignment, explanations and merge resolution |
| `crypto` | Canonical wire format, signatures, encrypted offers, nonce/time and ingress budgets |
| `transport` | Transport contracts shared across the Android adapter and simulator |
| `simulator` | Seeded network, partition and attacker scenarios |
| `docs/CASCADE-PROTOCOL.md` | Protocol, record framing and deterministic reduction |
| `docs/CASCADE-VALIDATION.md` | Automated evidence, synthetic scenarios and physical-device checklist |
| `website/index.html` | Self-contained explainer site (inline CSS/JS; Three.js loaded from pinned CDNJS) |
| `website/downloads` | Versioned APK download and SHA-256 manifest |

## Validation and known limitations

Version 0.3 passed 37 focused tests (7 Android, 14 crypto, 3 domain, 12 pipeline, 1 simulator). See [release validation](docs/RESQ-0.3.md). The earlier core JVM suite passed 26 focused tests covering event-set laws, dispatch arrival protection, authorization, ranking, opportunity cost, replay, signatures, rate limits, privacy-context binding, hazard corroboration and claim parsing. Three focused Android Bluetooth-readiness regression tests passed. These checks use synthetic data and do not establish field safety.

The earlier inherited full Android suite had 23 failing tests out of 693 (with three skipped), including database-opening and chat/media-delivery assertions. The inherited Android lint run reported 313 errors and 386 warnings, including findings in upstream translation, permission, quantity and messaging code. These remain unresolved; consult [`website/VALIDATION.md`](website/VALIDATION.md) for the UI/browser validation scope and [`docs/CASCADE-VALIDATION.md`](docs/CASCADE-VALIDATION.md) for protocol checks.

The currently hosted APK is a **debug** build. Two physical Android devices are still needed to validate BLE discovery, delivery, private chat, reconnection, background restrictions and power behavior. The simulator measures outcomes in defined synthetic scenarios only; it does not measure real-world attack detection or phone battery consumption. The project is not a replacement for emergency services.

## Website and simulator

Open `website/index.html` locally, or start a static server from the repository:

```sh
node website/serve.cjs
```

The site includes the problem, the five-stage evaluation pipeline, interactive network/flood/Sybil/partition/dispatch scenes, security notes, app-screen gallery, feature grid, 2D simulator, download and installation instructions. It respects reduced-motion settings and has a static fallback when WebGL is unavailable. Three.js is loaded from pinned CDNJS; all page styles and scripts are inline. The app's offline radio operation does not depend on this website.

## Contributing

1. Use synthetic identities, coordinates and health details in issues, logs, demos and test fixtures.
2. Keep the signed fact schema bounded and deterministic. Document changes to authorization, replay, persistence or merge behavior.
3. Add or update focused tests for changes to the deterministic JVM modules.
4. Do not describe model results, RSSI, user claims or BLE presence as verified clinical credentials or guaranteed safety.
5. Test radio behavior on real Android devices before claiming hardware compatibility.

See [the GPL-3.0 license](LICENSE.md) and the upstream project's retained attribution.

---

