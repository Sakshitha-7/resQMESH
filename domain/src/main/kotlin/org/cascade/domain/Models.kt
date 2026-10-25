package org.cascade.domain

enum class Role { VICTIM, RESPONDER, RELAY }
enum class Need { ASSISTANCE, MEDICAL, BOAT, RESCUE, FOOD, POWER }
enum class DispatchStatus { OFFER, ACCEPT, DECLINE, EN_ROUTE, ARRIVED, BACKUP, ABORT, CANCEL, COMPLETE, INFO }
enum class EventKind { DISTRESS, SAFE, BEACON, DISPATCH, HAZARD, ATTESTATION, PRIVATE_DETAILS, VOUCH_REQUEST, HISTORY }

data class Stamp(val clock: Long, val actor: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int = compareValuesBy(this, other, Stamp::clock, Stamp::actor)
}

/** Transport-independent grow-only facts. Private health text is NEVER a field here. */
data class Fact(
    val id: String,
    val kind: EventKind,
    val author: String,
    val clock: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val cell: String,
    val fields: Map<String, String> = emptyMap()
) {
    val stamp get() = Stamp(clock, author)
    fun int(key: String, fallback: Int = 0) = fields[key]?.toIntOrNull() ?: fallback
    fun value(key: String) = fields[key].orEmpty()
}

data class Distress(
    val id: String, val owner: String, val cell: String, val urgency: Int,
    val headcount: Int, val required: Set<String>, val battery: Int,
    val confidence: Double = 0.25, val trust: Double = 0.05,
    val confirmations: Int = 0, val expiresAt: Long = Long.MAX_VALUE
)

data class Responder(
    val id: String, val cell: String, val capabilities: Set<String>,
    val verifiedCapabilities: Set<String> = emptySet(), val capacity: Int = 1,
    val battery: Int = 100, val available: Boolean = true, val radiusMeters: Int = 5000,
    val expiresAt: Long = Long.MAX_VALUE
)

data class Dispatch(
    val id: String, val incident: String, val responder: String, val owner: String,
    val status: DispatchStatus, val acceptedAt: Stamp?, val arrived: Boolean,
    val terminal: Boolean, val offerAt: Long, val offerExpiresAt: Long, val backupFor: String? = null
)

data class Hazard(
    val id: String, val cell: String, val type: String, val severity: Int,
    val reporter: String, val expiresAt: Long, val observedAt: Long,
    val independentGroup: String = reporter, val eligible: Boolean = false
)

data class ParsedClaim(val need: Need, val urgency: Int, val headcount: Int,
    val medicalFlags: Set<String>, val tags: Set<String>, val confidence: Double)

/** Optional local model implementation must never use a network service. */
fun interface LocalClaimModel { fun parse(text: String): ParsedClaim? }

data class SensitiveDetails(val text: String, val latitude: Double?, val longitude: Double?,
    val medicalFlags: Set<String> = emptySet(), val photoRef: String? = null)

object Geo {
    private const val alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
    fun valid(cell: String) = cell.length == 5 && cell.all { it in alphabet }
    fun encode(latitude: Double, longitude: Double): String {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var value = 0; val out = StringBuilder()
        repeat(25) { bit ->
            val longitudeBit = bit % 2 == 0
            val mid = if (longitudeBit) (lonLo + lonHi) / 2 else (latLo + latHi) / 2
            val high = (if (longitudeBit) longitude else latitude) >= mid
            value = (value shl 1) or if (high) 1 else 0
            if (longitudeBit) { if (high) lonLo = mid else lonHi = mid }
            else { if (high) latLo = mid else latHi = mid }
            if (bit % 5 == 4) { out.append(alphabet[value]); value = 0 }
        }
        return out.toString()
    }
    fun center(cell: String): Pair<Double, Double> {
        require(valid(cell))
        var latLo = -90.0; var latHi = 90.0; var lonLo = -180.0; var lonHi = 180.0
        var index = 0
        cell.forEach { c ->
            val n = alphabet.indexOf(c)
            for (b in 4 downTo 0) {
                val high = n and (1 shl b) != 0
                if (index++ % 2 == 0) { val m = (lonLo + lonHi) / 2; if (high) lonLo = m else lonHi = m }
                else { val m = (latLo + latHi) / 2; if (high) latLo = m else latHi = m }
            }
        }
        return (latLo + latHi) / 2 to (lonLo + lonHi) / 2
    }
    /** Coarse-cell estimate, never presented as a navigation-grade measurement. */
    fun distanceMeters(a: String, b: String): Int {
        if (a == b) return 250
        val (la, lo) = center(a); val (lb, lob) = center(b)
        val dy = (la - lb) * 111_320
        val dx = (lo - lob) * 111_320 * kotlin.math.cos(Math.toRadians((la + lb) / 2))
        return kotlin.math.sqrt(dx * dx + dy * dy).toInt().coerceAtLeast(250)
    }
}
