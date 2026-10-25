package org.cascade.simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class NetworkSimulationTest {
    @Test fun allSupportedNetworkSizesConvergeUnderAttacks() {
        listOf(50,100,200).forEach { size ->
            val result=NetworkSimulation.run(size)
            assertEquals(size,result.nodes)
            assertTrue(result.table(),result.metrics.all { it.passed })
        }
    }

    @Test fun `100-node baseline preserves documented safety outcomes`() {
        val metrics=NetworkSimulation.run(100).metrics.associate { it.name to it.value }

        assertEquals("all 100",metrics["Partition convergence"])
        assertEquals("10",metrics["Double-dispatches resolved"])
        assertEquals("0",metrics["At-door cancellations"])
        assertEquals("40 / 40",metrics["Forgery / replay detection"])
        assertEquals("0 / 20",metrics["Honest false positives"])
        assertEquals("120 / 120",metrics["Spam rejected before crypto"])
        assertEquals("unknown",metrics["Single-source flood lure"])
    }

    @Test fun `same seed produces identical simulation results`() {
        assertEquals(NetworkSimulation.run(100,42),NetworkSimulation.run(100,42))
    }

    @Test fun `CLI accepts a reproducible seed and preserves positional node count`() {
        assertEquals(SimulationOptions(100,73),parseSimulationOptions(arrayOf("100","--seed","73")))
        assertTrue(NetworkSimulation.run(100,73).table().contains("100 nodes · seed 73"))
    }

    @Test fun `CLI accepts named node count and seed`() {
        assertEquals(SimulationOptions(50,9),parseSimulationOptions(arrayOf("--nodes","50","--seed","9")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CLI rejects unsupported node counts`() {
        parseSimulationOptions(arrayOf("201"))
    }
}
