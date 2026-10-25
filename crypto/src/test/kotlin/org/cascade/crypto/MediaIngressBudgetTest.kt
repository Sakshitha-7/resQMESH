package org.cascade.crypto

import org.junit.Assert.*
import org.junit.Test

class MediaIngressBudgetTest {
    @Test fun largeFramesAreChargedBySize() {
        val budget=IngressBudget(perLink=4,total=8,maxPayload=16384)
        assertTrue(budget.allow("synthetic-peer",16384,1000))
        assertFalse(budget.allow("synthetic-peer",1,1001))
        assertTrue(budget.allow("synthetic-peer",1,61000))
    }
    @Test fun oversizedFramesAndGlobalFloodAreRejected() {
        val budget=IngressBudget(perLink=8,total=4,maxPayload=16384)
        assertFalse(budget.allow("a",16385,1000))
        assertTrue(budget.allow("a",8192,1001))
        assertTrue(budget.allow("b",8192,1002))
        assertFalse(budget.allow("c",1,1003))
    }
}
