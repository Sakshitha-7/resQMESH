package org.cascade.pipeline

import org.cascade.domain.*
import kotlin.math.roundToLong

data class Cost(val denominator: Int, val estimatedBatteryUnits: Int, val relayDutyPercent: Int)
class CostModel {
    fun estimate(distance: Int, hazardPenalty: Int, opportunityCost: Int, responderBattery: Int, relayBattery: Int, hops: Int): Cost {
        val batteryPenalty = (100 - responderBattery.coerceIn(0, 100)) * 15 + (100 - relayBattery.coerceIn(0, 100)) * 5
        return Cost((distance.coerceAtLeast(250) + hazardPenalty.coerceAtLeast(0) + opportunityCost.coerceAtLeast(0) + batteryPenalty + hops.coerceIn(0, 7) * 50),
            1 + hops.coerceIn(0, 7) * 2 + distance.coerceAtLeast(0) / 1000,
            if (relayBattery < 15) 15 else if (relayBattery < 35) 40 else 100)
    }
}
data class Assignment(val incident: Distress, val responder: Responder, val score: Long, val cost: Cost,
    val distance: Int, val hazardPenalty: Int, val explanation: String)

class Explainer {
    fun explain(incident: Distress, distance: Int, hazard: Int): String {
        val need = incident.required.sorted().joinToString(" + ").ifEmpty { "Assistance" }
        return "$need needed · ~${distance} m by coarse cell · ${if (hazard > 0) "route unverified / hazard caution" else "corroborated cell reports; check route"} · ${incident.confirmations} independent confirmations"
    }
}

class DispatchPlanner(private val costModel: CostModel = CostModel(), private val explainer: Explainer = Explainer()) {
    private val clinical = setOf("medic", "dialysis")
    fun rank(incidents: List<Distress>, responders: List<Responder>, hazards: Map<String, HazardCell>, now: Long,
        rejectedPairs: Set<Pair<String, String>> = emptySet(), busy: Set<String> = emptySet()): List<Assignment> {
        val active = incidents.filter { it.expiresAt > now }
        val available = responders.filter { it.available && it.expiresAt > now && it.battery > 5 && it.id !in busy }
        fun eligible(r: Responder, d: Distress): Boolean = d.required.all { it in r.capabilities && (it !in clinical || it in r.verifiedCapabilities) } &&
            r.capacity >= d.headcount && Geo.distanceMeters(r.cell, d.cell) <= r.radiusMeters && (d.id to r.id) !in rejectedPairs
        val candidates = active.flatMap { d ->
            available.filter { eligible(it, d) }.mapNotNull { r ->
                // Keep a unique qualified responder for a more urgent reachable case.
                val reserved = active.any { higher -> higher.id != d.id && higher.urgency > d.urgency && eligible(r, higher) &&
                    higher.required.isNotEmpty() && available.count { eligible(it, higher) } == 1 }
                if (reserved) return@mapNotNull null
                val distance = Geo.distanceMeters(r.cell, d.cell)
                val hazard = hazards[d.cell]?.penalty ?: 400
                val scarce = r.capabilities.count { skill -> available.count { skill in it.capabilities } == 1 }
                val opportunity = if (d.required.isEmpty()) scarce * 800 else 0
                val cost = costModel.estimate(distance, hazard, opportunity, r.battery, 100, 1)
                val confidence = d.confidence.coerceIn(0.0, 1.0); val trust = d.trust.coerceIn(0.0, 1.0)
                val score = (1_000_000_000.0 * d.urgency * confidence * trust / cost.denominator).roundToLong()
                Assignment(d, r, score, cost, distance, hazard, explainer.explain(d, distance, hazard))
            }
        }
        return candidates.sortedWith(compareByDescending<Assignment> { it.score }.thenByDescending { it.incident.urgency }
            .thenBy { it.incident.id }.thenBy { it.responder.id })
    }

    fun assign(incidents: List<Distress>, responders: List<Responder>, hazards: Map<String, HazardCell>, now: Long,
        rejectedPairs: Set<Pair<String, String>> = emptySet(), busy: Set<String> = emptySet()): List<Assignment> {
        val usedResponders = mutableSetOf<String>(); val usedIncidents = mutableSetOf<String>()
        return rank(incidents, responders, hazards, now, rejectedPairs, busy).filter {
            if (it.responder.id in usedResponders || it.incident.id in usedIncidents) false
            else { usedResponders += it.responder.id; usedIncidents += it.incident.id; true }
        }
    }
}
