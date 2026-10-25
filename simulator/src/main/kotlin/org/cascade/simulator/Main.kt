package org.cascade.simulator

import org.cascade.crypto.*
import org.cascade.domain.*
import org.cascade.pipeline.*
import java.util.Locale
import kotlin.random.Random

data class Metric(val name: String, val value: String, val passed: Boolean)
data class SimulationResult(val nodes: Int, val seed: Int, val metrics: List<Metric>) {
    fun table() = buildString {
        appendLine("CASCADE MESH · synthetic JVM network · $nodes nodes · seed $seed")
        appendLine("METRIC                           VALUE          RESULT")
        appendLine("-----------------------------------------------------")
        metrics.forEach { appendLine("${it.name.padEnd(32)} ${it.value.padEnd(14)} ${if (it.passed) "PASS" else "FAIL"}") }
        appendLine("Battery values are model units, not measured mAh. Detection is scenario-specific.")
    }
}

object NetworkSimulation {
    fun run(nodeCount: Int = 100, seed: Int = 42): SimulationResult {
        require(nodeCount in 50..200)
        val random=Random(seed); val now=1_700_000_000_000L
        val states=MutableList(nodeCount) { FactSet() }
        val metrics=mutableListOf<Metric>()
        val incidents=(0 until nodeCount/10).map { n -> Fact("incident-$n",EventKind.DISTRESS,"victim-$n",1,now,now+3600000,"s0000",mapOf("urgency" to "4","headcount" to "1")) }
        incidents.forEachIndexed { index,f -> states[index]=states[index].add(f) }
        val split=nodeCount/2
        fun gossip(range: IntRange, rounds: Int) {
            repeat(rounds) { for (i in range) {
                val j=range.first+random.nextInt(range.count()); val union=states[i].merge(states[j]); states[i]=union; states[j]=union
            } }
        }
        gossip(0 until split,10); gossip(split until nodeCount,10)
        incidents.forEachIndexed { index, d ->
            val offerA=Fact("dispatch-a-$index",EventKind.DISPATCH,d.author,2,now,now+60000,"s0000",mapOf("status" to "OFFER","incident" to d.id,"responder" to "r-a-$index"))
            val offerB=offerA.copy(id="dispatch-b-$index",fields=offerA.fields+("responder" to "r-b-$index"))
            fun accept(offer: Fact,clock: Long)=Fact("accept-${offer.id}",EventKind.DISPATCH,offer.value("responder"),clock,now+1000,now+3600000,"s0000",mapOf("status" to "ACCEPT","dispatch" to offer.id))
            states[0]=states[0].add(offerA).add(accept(offerA,3))
            states[split]=states[split].add(d).add(offerB).add(accept(offerB,4))
            if(index%2==0) states[split]=states[split].add(Fact("arrive-$index",EventKind.DISPATCH,offerB.value("responder"),5,now+2000,now+3600000,"s0000",mapOf("status" to "ARRIVED","dispatch" to offerB.id)))
        }
        gossip(0 until split,10); gossip(split until nodeCount,10)
        // Reconnect ring followed by randomized anti-entropy delivery, duplicates and reordering.
        gossip(0 until nodeCount,30)
        val converged=states.distinct().size==1
        val merged=MergeResolver().resolve(states[0],now+3000)
        val atDoor=states[0].dispatches().filter { it.arrived }.map { it.id }.toSet()
        metrics += Metric("Partition convergence",if(converged) "all $nodeCount" else "diverged",converged)
        metrics += Metric("Double-dispatches resolved",merged.superseded.size.toString(),merged.superseded.size==incidents.size)
        metrics += Metric("At-door cancellations",(atDoor intersect merged.superseded).size.toString(),(atDoor intersect merged.superseded).isEmpty())

        var detected=0; var attacks=0; var falsePositives=0
        val secret=PacketCrypto.signingSecret(); val author=PacketCrypto.identity(PacketCrypto.signingPublic(secret))
        fun packet(i: Int)=PacketCrypto.sign(Fact("",EventKind.DISTRESS,author,i.toLong()+1,now,now+600000,"s0000",mapOf("urgency" to "4","headcount" to "1","battery" to "80")),secret)
        val gate=SecurityLayer()
        repeat(20) { n ->
            val p=packet(n); val good=Wire.encode(p)
            if(gate.admit(good,"good-$n",now) !is Admission.Accepted) falsePositives++
            val bad=Wire.encode(p.copy(signature=PacketCrypto.b64(ByteArray(64))))
            attacks++; if(gate.admit(bad,"evil-$n",now) is Admission.Rejected) detected++
            attacks++; if(gate.admit(good,"replay-$n",now) is Admission.Rejected) detected++
        }
        val spam=SecurityLayer(); var limited=0
        repeat(150) { n -> if((spam.admit("CM1:bad","same-link",now) as? Admission.Rejected)?.reason=="rate-limit") limited++ }
        metrics += Metric("Forgery / replay detection","$detected / $attacks",detected==attacks)
        metrics += Metric("Honest false positives","$falsePositives / 20",falsePositives==0)
        metrics += Metric("Spam rejected before crypto","$limited / 120",limited==120)
        val sybils=(0 until 40).map { Witness("sybil-$it",0,0.03,true,1,-40,now,"sybil-$it") }
        val trust=TrustScorer().score(TrustEvidence(now,sybils),now)
        metrics += Metric("Sybil trust after 40 vouches",String.format(Locale.ROOT,"%.2f",trust),trust<0.1)
        val lure=Hazard("lure","s0000","SAFE",0,"evil",now+60000,now,eligible=false)
        val trusted=HazardEngine().grid(listOf(lure),now).getValue("s0000").safeClaimTrusted
        metrics += Metric("Single-source flood lure",if(trusted) "trusted" else "unknown",!trusted)
        val confidence=ClaimVerifier().assess("medical help insulin",10000,1,-35).confidence
        metrics += Metric("GPS inconsistency flagged",String.format(Locale.ROOT,"%.2f",confidence),confidence<0.2)
        val clinical=Distress("clinical","v","s0000",5,1,setOf("medic"),90)
        val falseMedic=Responder("fake-medic","s0000",setOf("medic"))
        val selected=DispatchPlanner().assign(listOf(clinical),listOf(falseMedic),emptyMap(),now)
        metrics += Metric("Forged medic excluded",selected.size.toString(),selected.isEmpty())
        val cost=CostModel().estimate(1000,400,0,50,10,3)
        metrics += Metric("Relay battery model","${cost.estimatedBatteryUnits} units / ${cost.relayDutyPercent}% duty",cost.relayDutyPercent==15)
        return SimulationResult(nodeCount,seed,metrics)
    }
}

internal data class SimulationOptions(val nodeCount: Int, val seed: Int)

private const val SIMULATOR_USAGE =
    "Usage: simulator [node-count] [--nodes count] [--seed value]\n" +
        "Defaults: 100 nodes, seed 42. Node count must be between 50 and 200."

internal fun parseSimulationOptions(args: Array<String>): SimulationOptions {
    var nodeCount = 100
    var seed = 42
    var index = 0
    val seen = mutableSetOf<String>()

    if (args.firstOrNull()?.startsWith("--") == false) {
        nodeCount = args[0].toIntOrNull()
            ?: throw IllegalArgumentException("Node count must be an integer.")
        seen += "--nodes"
        index = 1
    }

    while (index < args.size) {
        val option = args[index]
        require(option == "--nodes" || option == "--seed") {
            SIMULATOR_USAGE
        }
        require(seen.add(option)) { "$option may only be specified once." }
        require(index + 1 < args.size) { "$option requires an integer value." }
        val value = args[index + 1].toIntOrNull()
            ?: throw IllegalArgumentException("$option requires an integer value.")
        when (option) {
            "--nodes" -> nodeCount = value
            "--seed" -> seed = value
        }
        index += 2
    }

    require(nodeCount in 50..200) { "Node count must be between 50 and 200." }
    return SimulationOptions(nodeCount, seed)
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h"))) {
        println(SIMULATOR_USAGE)
        return
    }

    val options = parseSimulationOptions(args)
    val result = NetworkSimulation.run(options.nodeCount, options.seed)
    println(result.table())
    check(result.metrics.all { it.passed }) { "Simulation regression" }
}
