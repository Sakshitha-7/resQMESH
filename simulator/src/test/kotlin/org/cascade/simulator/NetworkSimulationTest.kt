package org.cascade.simulator
import org.junit.Assert.assertTrue
import org.junit.Test
class NetworkSimulationTest {
    @Test fun allSupportedNetworkSizesConvergeUnderAttacks() {
        listOf(50,100,200).forEach { size ->
            val result=NetworkSimulation.run(size)
            assertTrue(result.table(),result.metrics.all { it.passed })
        }
    }
}
