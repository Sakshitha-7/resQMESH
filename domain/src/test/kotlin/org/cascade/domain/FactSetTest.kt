package org.cascade.domain

import org.junit.Assert.*
import org.junit.Test

class FactSetTest {
    private fun fact(id: String) = Fact(id, EventKind.DISTRESS, "victim", 1, 1, 100, "s0000", mapOf("urgency" to "4", "headcount" to "1"))
    @Test fun unionIsCommutativeAssociativeAndIdempotent() {
        val a = FactSet(setOf(fact("a"))); val b = FactSet(setOf(fact("b"))); val c = FactSet(setOf(fact("c")))
        assertEquals(a.merge(b), b.merge(a)); assertEquals(a, a.merge(a)); assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)))
    }
    @Test fun strangersCannotClearIncidents() {
        val a = fact("a"); val clear = Fact("safe", EventKind.SAFE, "attacker", 9, 2, 100, "s0000", mapOf("incident" to "a"))
        assertEquals(1, FactSet(setOf(a, clear)).incidents(5).size)
        assertEquals(0, FactSet(setOf(a, clear.copy(author = "victim"))).incidents(5).size)
    }
    @Test fun coarseCoordinatesRoundTrip() { assertEquals("s0000", Geo.encode(0.01, 0.01)); assertTrue(Geo.valid("s0000")); assertFalse(Geo.valid("s00000")) }
}
