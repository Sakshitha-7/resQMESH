package org.cascade.crypto

import org.cascade.domain.*
import java.util.LinkedHashMap

sealed class Admission {
    data class Accepted(val packet: SignedPacket) : Admission()
    data class Rejected(val reason: String) : Admission()
}

/** Single synchronized gate: size and bounded token buckets BEFORE expensive signature verification. */
class SecurityLayer(private val maxPerMinute: Int = 30, private val windowMs: Long = 86_400_000,
    private val seen: MutableMap<String, Long> = mutableMapOf()) {
    private data class Bucket(var started: Long, var count: Int)
    private val buckets = object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>?) = size > 512
    }
    private var globalStart = 0L; private var globalCount = 0
    private var maxSeenClock = 0L
    private fun consume(key: String, now: Long): Boolean {
        val bucket = buckets.getOrPut(key) { Bucket(now, 0) }
        if (now - bucket.started >= 60_000 || now < bucket.started) { bucket.started = now; bucket.count = 0 }
        return ++bucket.count <= maxPerMinute
    }
    @Synchronized fun admit(frame: String, immediatePeer: String, now: Long): Admission {
        fun reject(reason: String) = Admission.Rejected(reason)
        if (frame.length > Wire.MAX_FRAME) return reject("oversized")
        if (now - globalStart >= 60_000 || now < globalStart) { globalStart = now; globalCount = 0 }
        if (++globalCount > 600 || !consume("link:$immediatePeer", now)) return reject("rate-limit")
        val packet = runCatching { Wire.decode(frame) }.getOrElse { return reject("malformed") }
        val fact = packet.fact
        if (fact.createdAt > now + 120_000 || fact.createdAt < now - windowMs || fact.expiresAt <= now ||
            fact.expiresAt <= fact.createdAt || fact.expiresAt - fact.createdAt > windowMs) return reject("timestamp")
        if (fact.clock !in 1..1_000_000_000_000 || !Geo.valid(fact.cell) || packet.nonce.length !in 22..32) return reject("schema")
        if (fact.clock > maxSeenClock + 1_000_000) return reject("clock-jump")
        if (!PacketCrypto.verify(packet)) return reject("signature")
        if (!validFields(fact)) return reject("schema")
        if (fact.kind==EventKind.HISTORY && historical(packet,now)==null) return reject("history")
        seen.entries.removeAll { it.value < now - windowMs }
        val replayKey = "${fact.author}:${packet.nonce}"
        if (seen.containsKey(replayKey)) return reject("replay")
        if (!consume("identity:${fact.author}", now)) return reject("rate-limit")
        // Fail closed under sustained flooding; never evict a still-live replay marker.
        if (seen.size >= 20_000) return reject("replay-capacity")
        seen[replayKey] = fact.createdAt
        maxSeenClock=maxOf(maxSeenClock,fact.clock)
        return Admission.Accepted(packet)
    }
    fun remember(packet: SignedPacket) { seen["${packet.fact.author}:${packet.nonce}"] = packet.fact.createdAt; maxSeenClock=maxOf(maxSeenClock,packet.fact.clock) }
    fun clear() { seen.clear(); buckets.clear(); globalCount = 0; maxSeenClock=0 }
    /** Historical state is authenticated evidence, never a new time-sensitive command. */
    fun historical(wrapper: SignedPacket,now: Long): SignedPacket? = runCatching {
        require(wrapper.fact.kind==EventKind.HISTORY)
        val original=Wire.decode(wrapper.fact.value("frame"))
        require(original.fact.kind in setOf(EventKind.DISTRESS,EventKind.SAFE,EventKind.DISPATCH))
        require(PacketCrypto.verify(original) && validFields(original.fact))
        require(Geo.valid(original.fact.cell) && original.fact.clock in 1..1_000_000_000_000 && original.fact.clock<=maxSeenClock+1_000_000)
        require(original.fact.createdAt in 1..(now+120000) && original.fact.expiresAt>original.fact.createdAt && original.fact.expiresAt-original.fact.createdAt<=windowMs)
        original
    }.getOrNull()
    private fun validFields(f: Fact): Boolean {
        if (f.fields.any { it.key.length > 32 || it.value.length > 2500 }) return false
        val allowed = when (f.kind) {
            EventKind.DISTRESS -> setOf("urgency", "headcount", "required", "battery")
            EventKind.SAFE -> setOf("incident")
            EventKind.BEACON -> setOf("capabilities", "capacity", "battery", "available", "radius")
            EventKind.DISPATCH -> setOf("dispatch", "incident", "responder", "status", "key", "deadline", "backupFor")
            EventKind.HAZARD -> setOf("type", "severity", "depth")
            EventKind.ATTESTATION -> setOf("subject", "capability", "challenge")
            EventKind.PRIVATE_DETAILS -> setOf("dispatch", "acceptance", "recipient", "ciphertext", "part", "total")
            EventKind.VOUCH_REQUEST -> setOf("challenge")
            EventKind.HISTORY -> setOf("frame")
        }
        if (!allowed.containsAll(f.fields.keys)) return false // Prevent accidental health data in public events.
        return when (f.kind) {
            EventKind.DISTRESS -> f.int("urgency") in 1..5 && f.int("headcount") in 1..100 && f.int("battery", -1) in 0..100
            EventKind.BEACON -> f.int("capacity") in 1..100 && f.int("battery", -1) in 0..100 && f.value("available") in setOf("true", "false")
            EventKind.DISPATCH -> runCatching { DispatchStatus.valueOf(f.value("status")) }.isSuccess &&
                (f.value("status") !in setOf("OFFER", "ACCEPT") || runCatching { PacketCrypto.unb64(f.value("key")).size == 32 }.getOrDefault(false))
            EventKind.HAZARD -> f.value("type") in setOf("FLOOD", "CURRENT", "DEBRIS", "ELECTRICAL", "SAFE") && f.int("severity", -1) in 0..5
            EventKind.PRIVATE_DETAILS -> f.value("ciphertext").length in 40..2100 && f.int("part", -1) in 0..79 && f.int("total") in 1..80
            else -> true
        }
    }
}
