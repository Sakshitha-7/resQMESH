package org.cascade.pipeline

import org.cascade.domain.*

data class MergeResult(val retained: Set<String>, val superseded: Set<String>, val orphans: Set<String>, val log: List<String>)
class MergeResolver {
    fun resolve(state: FactSet, now: Long): MergeResult {
        val dispatches = state.dispatches()
        val active = dispatches.filter { !it.terminal && (it.acceptedAt != null || it.offerExpiresAt > now) }
        val retained = mutableSetOf<String>(); val superseded = mutableSetOf<String>(); val log = mutableListOf<String>()
        active.groupBy { "${it.incident}/${it.backupFor ?: "primary"}" }.toSortedMap().forEach { (_, group) ->
            val arrived = group.filter { it.arrived }
            val winners = if (arrived.isNotEmpty()) arrived else listOf(group.minWith(
                compareBy<Dispatch> { it.acceptedAt == null }.thenBy { it.acceptedAt?.clock ?: Long.MAX_VALUE }.thenBy { it.id }))
            retained += winners.map { it.id }
            val losers = group.filter { it !in winners }
            superseded += losers.map { it.id }
            if (losers.isNotEmpty()) log += "${losers.size} overlapping dispatch(es) superseded; ${arrived.size} at-door responder(s) protected"
            if (arrived.size > 1) log += "Multiple responders already at the door: keep all; coordinate locally"
        }
        // Resolve a responder assigned to multiple incidents with the same stable order.
        active.filter { it.id in retained }.groupBy { it.responder }.values.forEach { group ->
            if (group.size > 1) {
                val protected = group.filter { it.arrived }
                val winners = if (protected.isNotEmpty()) protected else listOf(group.minWith(
                    compareBy<Dispatch> { it.acceptedAt == null }.thenBy { it.acceptedAt?.clock ?: Long.MAX_VALUE }.thenBy { it.id }))
                group.filter { it !in winners }.forEach { retained -= it.id; superseded += it.id }
            }
        }
        val covered = active.filter { it.id in retained }.map { it.incident }.toSet() +
            dispatches.filter { it.status == DispatchStatus.COMPLETE }.map { it.incident }
        val orphans = state.incidents(now).map { it.id }.toSet() - covered
        return MergeResult(retained, superseded, orphans, log)
    }
}
