# Validation and field handoff

All test coordinates, identities, claims and actors are synthetic. No physical device location is needed for this suite.

## Automated checks

```sh
./gradlew :domain:test :pipeline:test :crypto:test :simulator:test
./gradlew :simulator:run --args="--nodes 50 --seed 42"
./gradlew :simulator:run --args="--nodes 100 --seed 42"
./gradlew :simulator:run --args="--nodes 200 --seed 42"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

The core suite tests CRDT union laws; authorization; arrival versus cancellation in both merge orders; multiple already-arrived responders; explicit backup; orphan recovery; deterministic ranking under reordered inputs; unique-medic reservation; fake clinical credentials; timed fallback; Hindi/Hinglish parsing; GPS/duplicate confidence penalties; hazard TTL and corroboration; Sybil bootstrapping; trust gain caps; battery cost; plain-language explanations; signature tampering; canonical wire bytes; replay; stale/future input; rate limits; encrypted recipient isolation; context substitution; public-health schema rejection; historical synchronization; and extreme Lamport jumps.

Reports are under each module's `build/reports/tests`. Android lint output is under `app/build/reports`. Upstream lint retains its baseline and non-aborting configuration; inspect actual reported errors rather than equating a successful lint task with zero issues.

## Synthetic network metrics

The 100-node baseline uses two partitioned islands, ten incidents and two conflicting dispatches per incident. Five second-island responders arrive before reconnection. Randomized pairwise gossip with a fixed seed must converge. The expected safety outcomes are ten superseded competing dispatches and zero automatic at-door cancellations. The baseline also injects 20 forged signatures, 20 replays, 150 burst packets and 40 young identities.

Results report observed counts in these fixtures, not a medical validation, probabilistic real-world attack detection estimate or battery benchmark. Battery cost is an abstract model unit. Run with 50 or 200 to change the network size. The CLI accepts `--nodes` from 50 through 200 and an integer `--seed`; it prints the seed with the results. The default seed is 42, and the positional node-count form remains supported. Repeating a node count and seed reproduces the synthetic report, not real-world network behavior.

## Two-phone acceptance test

Use dedicated test devices and synthetic coordinates only. Keep raw logs and captures local. The upstream Mesh Lab requirement in `AGENTS.md` remains applicable; its chat scenarios do not replace the CASCADE-specific checks below.

1. Install matching debug APKs; grant Bluetooth/nearby permissions. Confirm local peer count changes on connection and loss. Verify foreground service survives screen-off and app backgrounding.
2. Victim selects coarse cell `s0000`, enters a synthetic need, and sends. Responder publishes the same coarse cell and a matching non-clinical capability. Confirm a direct offer arrives, ACCEPT causes the signed key exchange, and private text unlocks only on the chosen responder.
3. Add a third relay. Confirm it cannot decrypt private health records. Inspect only synthetic packet payloads to check no free text, flags, photos or exact coordinates occur outside ciphertext.
4. DECLINE the first offer or let its deadline expire. Verify fallback skips that responder. NEED INFO and NEED BACKUP should produce signed events; explicit backup should leave the primary assignment intact.
5. Split the network into two groups with overlapping incident history. Accept two different offers, mark one ARRIVED, then reconnect. Verify every node eventually retains the arrived assignment and the UI warns the other responder. Repeat with both arrived; neither may be canceled automatically.
6. Repeat reconnect beyond record expiry. Verify HISTORY restores safety facts without reactivating expired SOS records.
7. Send a single SAFE observation. It must remain untrusted. Independently review two direct peers in person, then corroborate. A live danger observation must veto a conflicting safe claim.
8. Verify signature/replay rejection, ingress rate limits, recovery after the rate window, bounded memory and fair delivery under a realistic mix of fragmented legitimate traffic.
9. Exercise foreground-service permission revocation, Bluetooth disabled/enabled, process death during database write, low battery, reboot, manual clock skew and storage exhaustion. Verify persistent signing identity and replay state.
10. Complete or abort a dispatch, export sanitized logs, and wipe local data. Ensure another device's copies are not represented as erased. Check TalkBack, large font scale, small phones and dark mode.

## Deliberate limitations to evaluate

Metadata privacy is narrower than ciphertext-plus-geohash-only; broad triage metadata is public. Mesh IDs use upstream persistent Noise identity binding. Local trust evidence can differ between nodes. The map is a coarse schematic, not turn-by-turn routing. BLE/RSSI and a displayed challenge do not authenticate medical credentials or defeat colluding attackers. Complete UI localization and a bundled offline speech/model engine are not included. None of these limits should be hidden by a passing simulator result.
