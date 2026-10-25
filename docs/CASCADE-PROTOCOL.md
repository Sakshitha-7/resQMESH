# CASCADE wire and state contract, version 1

## Framing

The outer transport remains bitchat's existing Bluetooth packet and fragmentation protocol. The application payload is `CM1:` followed by standard padded Base64 of:

1. A big-endian 32-bit canonical-body byte count.
2. The canonical body.
3. A Java `DataOutputStream.writeUTF` Base64 Ed25519 signature.

The whole text frame is at most 3,800 characters; the decoded canonical body is at most 2,800 bytes. Input is bounded before decoding. No compression is used. The receiver requires exact canonical re-encoding, unique field keys and no trailing bytes.

Canonical body: int32 version `1`; UTF signing-public-key Base64; UTF nonce Base64; UTF event-kind name; UTF author fingerprint; int64 Lamport clock; int64 created-at epoch milliseconds; int64 expires-at; UTF five-character geohash; int32 field count; then field-name/value UTF pairs in ascending key order. UTF means Java modified UTF-8 with a two-byte byte-length, not an unbounded string. The SHA-256 hex digest of this body is the fact ID. The author is SHA-256 of the raw 32-byte Ed25519 public key. The signature covers the complete body. A literal golden vector is in `SecurityTest`.

Do not add health data to a public field. The schema allowlist rejects unknown fields. Adding fields or changing semantics requires a protocol version migration and vectors.

## Fact kinds

| Kind | Fields |
|---|---|
| DISTRESS | urgency 1–5, headcount 1–100, broad required tags, battery 0–100 |
| SAFE | incident fact ID; only the incident author can clear it |
| BEACON | capabilities, capacity, battery, availability, radius; newest Lamport fact per author, 3-minute liveness |
| DISPATCH OFFER | incident ID, responder signing ID, ephemeral exchange public key, offer deadline, optional backupFor |
| DISPATCH ACCEPT | offer ID in dispatch, status, responder's new exchange key |
| Other DISPATCH | dispatch, status: DECLINE / EN_ROUTE / ARRIVED / BACKUP / ABORT / CANCEL / COMPLETE / INFO |
| HAZARD | type, severity, depth; expires in at most 60 minutes |
| VOUCH_REQUEST | short random challenge, 5-minute lifetime |
| ATTESTATION | subject, reviewed capabilities, displayed challenge; not automatically trusted remotely |
| PRIVATE_DETAILS | dispatch, acceptance, recipient, ciphertext, part, total |
| HISTORY | original complete signed frame; restricted to DISTRESS / SAFE / DISPATCH, nesting rejected |

The app keeps all authenticated facts in an immutable union. Reductions check ownership; a valid signature by a stranger is not authorization to clear another incident or change another responder's state. Blocking a node does not delete its already-recorded arrival latch.

## Key exchange

The owner creates a fresh X25519 key for an offer. The chosen responder creates a fresh X25519 key on ACCEPT. Both public keys are signed as part of their respective facts. HKDF salt is `CASCADE-MESH-v1`, HMAC is SHA-256, and the info/AAD context is `CM1/<offer>/<acceptance>/<owner>/<responder>/<part>/<total>`. The encryption IV is a fresh random 96-bit nonce; AES-GCM uses a 128-bit tag. Wrong recipient, wrong offer, changed ciphertext or changed chunk index must fail authentication.

Private record JSON is Base64-encoded, divided into 900-character chunks, then independently authenticated and encrypted. The plaintext record contains text, optional precise coordinates, optional recompressed photo and parsed flags. Reassembly is restricted to the authorized owner's matching offer/acceptance and at most 80 chunks. The recipient does not render a partial record. The sender releases records only for retained, accepted dispatches. Previously released information cannot be cryptographically recalled after a merge.

## Time, replay and historical merges

Normal envelopes accept at most 120 seconds of future clock skew and a 24-hour past timestamp window, and must not be expired. Nonces are tracked per signing identity. The persistent fact journal restores replay entries after process restart; old entries are pruned from the replay window. Live markers are not evicted to make room for a flood. Extreme Lamport jumps are bounded.

Offer deadlines are separate from record lifetime. An expired offer cannot be newly accepted, but an already valid acceptance remains part of dispatch state. Fresh HISTORY envelopes convey old, original signed safety facts; these can restore an arrival latch from a long partition. The outer fresh sender cannot modify the old fact's signature. Expired distress is not reactivated by history. This separates anti-entropy evidence from command freshness.

Physical-link admission permits 480 packet fragments/messages per minute per link, 2,400 globally, and at most 4,096 payload bytes per ingress packet. The application repository permits 30 envelopes/minute per immediate/origin label and per signer, with a 600/minute global cap and 20,000 live replay markers. The pre-relay signature gate uses 120 envelopes/minute per link/signer. These are conservative defaults, not tuned RF measurements. Fragment relaying before complete reassembly is bounded but cannot authenticate an entire original message until it has reassembled.

## Deterministic reduction

Union is associative, commutative and idempotent. Arrival is a grow-only latch. COMPLETE and voluntary ABORT are terminal; automatic cancellation cannot override ARRIVED. Ties use accepted Lamport clock, then offer ID. An explicitly requested backup is a distinct slot. Orphan detection and timed fallback operate on the resulting active assignment set. Partitioned nodes can make conflicting offers; reconciliation requires eventual exchange of the relevant facts, not a fixed number of radio hops.
