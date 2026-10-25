package org.cascade.pipeline

import org.cascade.domain.*
import java.util.Locale

data class ClaimAssessment(val parsed: ParsedClaim, val confidence: Double, val reasons: List<String>)

class ClaimVerifier(private val localModel: LocalClaimModel? = null) {
    fun parse(text: String): ParsedClaim {
        require(text.length <= 2000)
        localModel?.parse(text)?.let { return it.copy(urgency = it.urgency.coerceIn(1, 5), confidence = it.confidence.coerceIn(0.0, 1.0)) }
        val words = text.lowercase(Locale.ROOT)
        fun contains(vararg keys: String) = keys.any { key ->
            Regex("(?<![\\p{L}])${Regex.escape(key)}(?![\\p{L}])").containsMatchIn(words)
        }
        val flags = buildSet {
            if (contains("insulin", "diabetes", "diabetic", "इंसुलिन", "शुगर")) add("insulin")
            if (contains("dialysis", "dialisis", "डायलिसिस")) add("dialysis")
            if (contains("bleeding", "khoon", "खून", "रक्त")) add("bleeding")
            if (contains("unconscious", "behosh", "बेहोश", "not breathing", "saans nahi", "सांस नहीं")) add("critical")
        }
        val need = when {
            flags.isNotEmpty() || contains("medical", "medicine", "dawai", "dawa", "doctor", "दवा", "डॉक्टर") -> Need.MEDICAL
            contains("boat", "naav", "nao", "नाव") -> Need.BOAT
            contains("trapped", "phas", "phase", "fasa", "roof", "छत", "फंसे", "फंसा", "bachao", "बचाओ") -> Need.RESCUE
            contains("food", "khana", "भोजन", "खाना", "water", "paani", "पानी") -> Need.FOOD
            contains("generator", "power", "बिजली") -> Need.POWER
            else -> Need.ASSISTANCE
        }
        val urgency = when {
            flags.any { it in setOf("critical", "bleeding") } -> 5
            need in setOf(Need.MEDICAL, Need.BOAT, Need.RESCUE) || contains("rising", "badh", "बढ़", "child", "बच्चा") -> 4
            need == Need.ASSISTANCE -> 3
            else -> 2
        }
        val count = Regex("(\\d{1,3})\\s*(people|persons|log|लोग|बच्चे)").find(words)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val tags = when (need) {
            Need.MEDICAL -> if ("dialysis" in flags) setOf("medic", "dialysis") else setOf("medic")
            Need.BOAT -> setOf("boat")
            Need.RESCUE -> setOf("rope")
            Need.FOOD -> setOf("food")
            Need.POWER -> setOf("generator")
            else -> emptySet()
        }
        return ParsedClaim(need, urgency, count.coerceIn(1, 100), flags, tags, if (need == Need.ASSISTANCE) 0.3 else 0.7)
    }

    fun assess(text: String, claimedDistanceMeters: Int?, hops: Int?, rssi: Int?, nearbyTexts: List<String> = emptyList()): ClaimAssessment {
        val parsed = parse(text); val reasons = mutableListOf<String>()
        var confidence = parsed.confidence
        if (claimedDistanceMeters != null && hops != null && hops > 0 && claimedDistanceMeters > hops * 500 + 1000) {
            confidence *= 0.25; reasons += "Location inconsistent with observed BLE reach; review required"
        }
        if (rssi != null && rssi > -50 && (claimedDistanceMeters ?: 0) > 2000 && hops == 1) {
            confidence *= 0.5; reasons += "Strong direct signal conflicts with distant location claim"
        }
        val tokens = normalizedTokens(text)
        if (tokens.size >= 3 && nearbyTexts.any { jaccard(tokens, normalizedTokens(it)) > 0.85 }) {
            confidence *= 0.65; reasons += "Similar nearby claim; possible duplicate, not proof of deception"
        }
        if (hops == null || rssi == null) reasons += "BLE proximity unavailable; location unverified"
        return ClaimAssessment(parsed, confidence.coerceIn(0.0, 1.0), reasons)
    }
    private fun normalizedTokens(text: String) = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+" )).filter(String::isNotBlank).toSet()
    private fun jaccard(a: Set<String>, b: Set<String>) = (a intersect b).size.toDouble() / (a union b).size.coerceAtLeast(1)
}
