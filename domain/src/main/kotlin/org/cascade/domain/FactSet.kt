package org.cascade.domain

/** Immutable union CRDT. Content-addressed IDs are checked by SecurityLayer before insertion. */
data class FactSet(val facts: Set<Fact> = emptySet()) {
    fun merge(other: FactSet) = FactSet(facts + other.facts)
    fun add(fact: Fact) = FactSet(facts + fact)
    val clock get() = facts.maxOfOrNull { it.clock } ?: 0L
    fun nextClock() = Math.addExact(clock, 1)

    fun incidents(now: Long): List<Distress> {
        val cleared = facts.filter { it.kind == EventKind.SAFE }.groupBy { it.value("incident") }
        return facts.filter { it.kind == EventKind.DISTRESS && it.expiresAt > now }
            .filter { original -> cleared[original.id].orEmpty().none { it.author == original.author } }
            .map {
                Distress(it.id, it.author, it.cell, it.int("urgency", 3).coerceIn(1, 5),
                    it.int("headcount", 1).coerceIn(1, 100), it.value("required").split(',').filter(String::isNotBlank).toSet(),
                    it.int("battery", 100).coerceIn(0, 100), expiresAt = it.expiresAt)
            }.sortedBy { it.id }
    }

    fun responders(now: Long): List<Responder> = facts.filter { it.kind == EventKind.BEACON }
        .groupBy { it.author }.values.map { events -> events.maxWith(compareBy<Fact> { it.stamp }.thenBy { it.id }) }
        .filter { it.expiresAt > now }.map {
            Responder(it.author, it.cell, it.value("capabilities").split(',').filter(String::isNotBlank).toSet(),
                capacity = it.int("capacity", 1).coerceIn(1, 100), battery = it.int("battery", 100).coerceIn(0, 100),
                available = it.value("available") == "true", radiusMeters = it.int("radius", 5000).coerceIn(250, 50_000),
                expiresAt = it.expiresAt)
        }.sortedBy { it.id }

    fun dispatches(): List<Dispatch> {
        val incidents = facts.filter { it.kind == EventKind.DISTRESS }.associateBy { it.id }
        val events = facts.filter { it.kind == EventKind.DISPATCH }
        return events.filter { it.value("status") == "OFFER" }
            .filter { incidents[it.value("incident")]?.author == it.author }
            .filter { offer ->
                val parentId=offer.value("backupFor")
                if(parentId.isEmpty()) true else {
                    val parent=events.find { it.id==parentId && it.value("status")=="OFFER" && it.author==offer.author && it.value("incident")==offer.value("incident") }
                    parent!=null && events.any { it.value("dispatch")==parentId && it.value("status")=="BACKUP" && it.author==parent.value("responder") && it.clock<offer.clock }
                }
            }
            .map { offer ->
                val responder = offer.value("responder")
                val authorized = events.filter { it.value("dispatch") == offer.id }
                    .filter { it.clock > offer.clock }
                    .filter { e ->
                        when (e.value("status")) {
                            "CANCEL" -> e.author == offer.author
                            "ACCEPT", "DECLINE", "EN_ROUTE", "ARRIVED", "BACKUP", "ABORT", "COMPLETE", "INFO" -> e.author == responder
                            else -> false
                        }
                    }
                val deadline = offer.value("deadline").toLongOrNull() ?: offer.expiresAt
                val accepted = authorized.filter { it.value("status") == "ACCEPT" && it.createdAt <= deadline }
                    .minWithOrNull(compareBy<Fact> { it.stamp }.thenBy { it.id })
                val progressed = authorized.filter { it.value("status") in setOf("DECLINE", "CANCEL", "INFO") ||
                    (accepted != null && it.clock > accepted.clock) || it == accepted }
                val arrived = progressed.any { it.value("status") == "ARRIVED" }
                val completed = progressed.any { it.value("status") == "COMPLETE" }
                val aborted = progressed.any { it.value("status") == "ABORT" }
                // Arrival is an irreversible safety latch. A concurrent cancel cannot undo it.
                val status = when {
                    completed -> DispatchStatus.COMPLETE
                    aborted -> DispatchStatus.ABORT // An on-site responder may explicitly withdraw for safety.
                    arrived -> DispatchStatus.ARRIVED
                    progressed.any { it.value("status") == "CANCEL" } -> DispatchStatus.CANCEL
                    progressed.any { it.value("status") == "DECLINE" } -> DispatchStatus.DECLINE
                    progressed.any { it.value("status") == "EN_ROUTE" } -> DispatchStatus.EN_ROUTE
                    accepted != null -> DispatchStatus.ACCEPT
                    else -> DispatchStatus.OFFER
                }
                Dispatch(offer.id, offer.value("incident"), responder, offer.author, status,
                    accepted?.stamp, arrived, status in setOf(DispatchStatus.COMPLETE, DispatchStatus.ABORT,
                    DispatchStatus.CANCEL, DispatchStatus.DECLINE), offer.createdAt, deadline, offer.value("backupFor").ifEmpty { null })
            }.sortedBy { it.id }
    }
}
