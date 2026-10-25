package org.cascade.pipeline

data class Witness(val id: String, val ageHours: Int, val trust: Double, val proximityObservedLocally: Boolean,
    val hops: Int, val rssi: Int, val observedAt: Long, val independenceGroup: String)
data class TrustEvidence(val firstSeen: Long, val witnesses: List<Witness> = emptyList(),
    val completedOutcomes: Int = 0, val corroborations: Int = 0, val rateViolations: Int = 0,
    val previouslyEarned: Double = 0.0)

class TrustScorer {
    fun score(e: TrustEvidence, now: Long): Double {
        val ageHours = ((now - e.firstSeen).coerceAtLeast(0) / 3_600_000).coerceAtMost(24 * 30)
        val valid = e.witnesses.filter { it.ageHours >= 24 && it.trust >= 0.4 && it.proximityObservedLocally &&
            it.hops == 1 && it.rssi in -85..-20 && now - it.observedAt in 0..300_000 }
            .distinctBy { it.id }.distinctBy { it.independenceGroup }.size
        val age = (ageHours / 720.0 * 0.2).coerceAtMost(0.2)
        val earned = (valid * 0.08 + e.completedOutcomes.coerceAtLeast(0) * 0.025 + e.corroborations.coerceAtLeast(0) * 0.03)
            .coerceAtMost(e.previouslyEarned.coerceIn(0.0, 0.7) + 0.1) // Max gain per persisted evaluation window.
        return (0.03 + age + earned - e.rateViolations.coerceAtLeast(0) * 0.05).coerceIn(0.01, 1.0)
    }
    fun mayAttest(ageHours: Int, score: Double) = ageHours >= 24 && score >= 0.4
}
