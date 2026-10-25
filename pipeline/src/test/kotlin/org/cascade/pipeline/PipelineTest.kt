package org.cascade.pipeline

import org.cascade.domain.*
import org.junit.Assert.*
import org.junit.Test

class PipelineTest {
    private val now = 1_000_000L
    @Test fun hinglishAndHindiNeedParsing() {
        val parser = ClaimVerifier()
        assertEquals(Need.MEDICAL, parser.parse("Dawai chahiye insulin khatam 3 log").need)
        assertEquals(3, parser.parse("Dawai chahiye insulin khatam 3 log").headcount)
        assertEquals(5, parser.parse("बच्चा बेहोश है").urgency)
        assertEquals(Need.BOAT, parser.parse("हमें नाव चाहिए").need)
        assertEquals(Need.ASSISTANCE, parser.parse("boathouse painting").need)
    }
    @Test fun spoofAndDuplicateLowerConfidenceWithoutDiscardingDistress() {
        val v = ClaimVerifier(); val text = "insulin needed for 3 people"
        assertTrue(v.assess(text, 5000, 1, -40).confidence < v.assess(text, 50, 1, -40).confidence)
        assertTrue(v.assess(text, null, null, null, listOf(text)).confidence > 0)
    }
    @Test fun hazardCorroborationExpiryAndConflictingSafeClaims() {
        val h = Hazard("a", "s0000", "SAFE", 0, "a", now + 1000, now - 1000, "a", true)
        val e = HazardEngine()
        assertFalse(e.grid(listOf(h), now).getValue("s0000").safeClaimTrusted)
        assertTrue(e.grid(listOf(h, h.copy(id="b", reporter="b", independentGroup="b")), now).getValue("s0000").safeClaimTrusted)
        assertFalse(e.grid(listOf(h, h.copy(id="b", reporter="b", independentGroup="b"), h.copy(id="c", reporter="c", type="FLOOD", severity=5)), now).getValue("s0000").safeClaimTrusted)
        assertTrue(e.grid(listOf(h), now+2000).isEmpty())
        assertNull(e.trustedPath("s0000", "s0001", mapOf("s0000" to setOf("s0001")), emptyMap()))
    }
    @Test fun sybilPeersCannotBootstrapEachOther() {
        val witnesses = (1..40).map { Witness("s$it", 0, 0.01, true, 1, -50, now, "s$it") }
        val scorer = TrustScorer()
        assertEquals(scorer.score(TrustEvidence(now), now), scorer.score(TrustEvidence(now, witnesses), now), 0.0001)
        assertFalse(scorer.mayAttest(0, 0.9))
        assertTrue(scorer.score(TrustEvidence(now, completedOutcomes=1000), now) <= 0.13)
    }
    @Test fun batteryCostChangesDutyAndRankingCost() {
        val m = CostModel(); val high = m.estimate(500,0,0,100,100,1); val low = m.estimate(500,0,0,10,10,1)
        assertTrue(low.denominator > high.denominator); assertEquals(15, low.relayDutyPercent)
    }
    @Test fun deterministicGreedyReservesOnlyQualifiedMedic() {
        val routine = Distress("routine", "v1", "s0000", 2, 1, emptySet(), 80, 0.9, 0.9)
        val critical = routine.copy(id="critical", urgency=5, required=setOf("medic"))
        val medic = Responder("medic", "s0000", setOf("medic"), setOf("medic"))
        val helper = Responder("helper", "s0000", emptySet())
        val planner = DispatchPlanner()
        val a = planner.assign(listOf(routine,critical),listOf(medic,helper),emptyMap(),now)
        val b = planner.assign(listOf(critical,routine),listOf(helper,medic),emptyMap(),now)
        assertEquals(a,b); assertEquals("medic",a.single { it.incident.id=="critical" }.responder.id)
        assertEquals("helper",a.single { it.incident.id=="routine" }.responder.id)
        assertTrue(planner.assign(listOf(critical),listOf(medic.copy(verifiedCapabilities=emptySet())),emptyMap(),now).isEmpty())
    }
    @Test fun timedFallbackSkipsRejectedResponder() {
        val d = Distress("d", "v", "s0000", 3,1,emptySet(),80)
        val list = listOf(Responder("a","s0000",emptySet()),Responder("b","s0000",emptySet()))
        assertEquals("b",DispatchPlanner().assign(listOf(d),list,emptyMap(),now,setOf("d" to "a")).single().responder.id)
    }
    @Test fun explanationIsOneLineAndDoesNotInventConfirmation() {
        val line = Explainer().explain(Distress("d","v","s0000",4,1,setOf("boat"),80),400,900)
        assertFalse(line.contains('\n')); assertTrue(line.contains("0 independent")); assertTrue(line.contains("unverified"))
    }
    private fun incident() = Fact("incident",EventKind.DISTRESS,"v",1,1,now+10000,"s0000",mapOf("urgency" to "5","headcount" to "1"))
    private fun offer(id: String, responder: String) = Fact(id,EventKind.DISPATCH,"v",2,2,now+10000,"s0000",mapOf("status" to "OFFER","incident" to "incident","responder" to responder))
    private fun event(id: String, dispatch: String, actor: String, clock: Long, status: String) = Fact(id,EventKind.DISPATCH,actor,clock,clock,now+10000,"s0000",mapOf("dispatch" to dispatch,"status" to status))
    @Test fun arrivalSurvivesCancelBeforeOrAfterMergeAndMultipleArrivals() {
        val a = FactSet(setOf(incident(),offer("a","r1"),event("aa","a","r1",3,"ACCEPT"),event("ar","a","r1",5,"ARRIVED")))
        val b = FactSet(setOf(offer("b","r2"),event("ba","b","r2",4,"ACCEPT"),event("cancel","a","v",6,"CANCEL")))
        val resolver = MergeResolver()
        val resolved = resolver.resolve(a.merge(b),now)
        assertEquals(setOf("a"),resolved.retained); assertEquals(setOf("b"),resolved.superseded)
        assertEquals(resolved,resolver.resolve(b.merge(a),now))
        assertEquals(setOf("a","b"),resolver.resolve(a.merge(b).add(event("br","b","r2",7,"ARRIVED")),now).retained)
    }
    @Test fun forgedArrivalAndCancellationCannotHijackDispatch() {
        val state = FactSet(setOf(incident(),offer("a","r1"),event("aa","a","r1",3,"ACCEPT"),event("fake","a","evil",4,"ARRIVED"),event("cancel","a","evil",5,"CANCEL")))
        assertFalse(state.dispatches().single().arrived); assertEquals(DispatchStatus.ACCEPT,state.dispatches().single().status)
    }
    @Test fun firstAcceptedThenLowestIdWinsAndOrphansReappear() {
        val state = FactSet(setOf(incident(),offer("b","r1"),offer("a","r2"),event("ba","b","r1",3,"ACCEPT"),event("aa","a","r2",3,"ACCEPT")))
        assertEquals(setOf("a"),MergeResolver().resolve(state,now).retained)
        assertEquals(setOf("incident"),MergeResolver().resolve(FactSet(setOf(incident())),now).orphans)
    }
    @Test fun requestedBackupDoesNotSupersedeThePrimaryResponder() {
        val backup=offer("b","r2").copy(clock=6,fields=offer("b","r2").fields+("backupFor" to "a"))
        val facts=setOf(incident(),offer("a","r1"),event("aa","a","r1",3,"ACCEPT"),event("ar","a","r1",4,"ARRIVED"),event("help","a","r1",5,"BACKUP"),backup,event("ba","b","r2",7,"ACCEPT"))
        assertEquals(setOf("a","b"),MergeResolver().resolve(FactSet(facts),now).retained)
        assertEquals(setOf("a"),MergeResolver().resolve(FactSet(facts.filterNot { it.id=="help" }.toSet()),now).retained)
    }
}
