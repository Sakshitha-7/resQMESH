package org.cascade.simulator
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test fun `CLI defaults to the documented baseline`() {
        assertEquals(SimulationOptions(100,42),parseSimulationOptions(emptyArray()))
    }

    @Test fun `CLI rejects missing duplicate and unknown options`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSimulationOptions(arrayOf("--seed"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSimulationOptions(arrayOf("--seed","1","--seed","2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSimulationOptions(arrayOf("100","--nodes","200"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSimulationOptions(arrayOf("--format","json"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CLI rejects unsupported node counts`() {
        parseSimulationOptions(arrayOf("201"))
    }

    @Test fun `CLI help prints usage and skips simulation`() {
        val originalOutput = System.out
        val capturedOutput = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(capturedOutput))
            main(arrayOf("--help"))
        } finally {
            System.setOut(originalOutput)
        }

        val output = capturedOutput.toString(Charsets.UTF_8)
        assertTrue(output.contains("Usage: simulator"))
        assertTrue(output.contains("Defaults: 100 nodes, seed 42"))
        assertTrue(!output.contains("METRIC"))
    }
}
