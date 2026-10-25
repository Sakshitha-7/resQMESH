package org.cascade.crypto

import org.cascade.domain.*
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    @Test fun canonicalWireGoldenVector() {
        val fact=Fact("ignored",EventKind.DISTRESS,"actor",1,2,3,"s0000",mapOf("a" to "b"))
        val actual=Wire.canonical(fact,"n","p").joinToString("") { "%02x".format(it) }
        assertEquals("0000000100017000016e0008444953545245535300056163746f720000000000000001000000000000000200000000000000030005733030303000000001000161000162",actual)
    }
    @Test fun physicalLinkAndGlobalFloodLimitsCannotBeEvadedWithSenderIds() {
        val budget=IngressBudget(perLink=3,total=5)
        repeat(3) { assertTrue(budget.allow("physical-link",512,1000)) }
        assertFalse(budget.allow("physical-link",512,1000))
        assertTrue(budget.allow("other-link",512,1000))
        assertFalse(budget.allow("third-link",512,1000))
        assertTrue(budget.allow("physical-link",512,61000))
        assertFalse(budget.allow("physical-link",5000,61000))
    }
    private val now = 1_000_000L
    private val secret = PacketCrypto.signingSecret()
    private fun packet() = PacketCrypto.sign(Fact("",EventKind.DISTRESS,PacketCrypto.identity(PacketCrypto.signingPublic(secret)),1,now,now+60000,"s0000",
        mapOf("urgency" to "4","headcount" to "2","battery" to "70")),secret)
    @Test fun signaturesCoverEveryFieldAndRoundTrip() {
        val p=packet(); assertEquals(p,Wire.decode(Wire.encode(p))); assertTrue(PacketCrypto.verify(p))
        assertFalse(PacketCrypto.verify(p.copy(fact=p.fact.copy(cell="s0001"))))
        assertFalse(PacketCrypto.verify(p.copy(fact=p.fact.copy(clock=2))))
    }
    @Test fun replayStaleAndOversizeRejected() {
        val gate=SecurityLayer(); val p=packet(); val frame=Wire.encode(p)
        assertTrue(gate.admit(frame,"peer",now) is Admission.Accepted)
        assertEquals("replay",(gate.admit(frame,"other",now) as Admission.Rejected).reason)
        assertEquals("timestamp",(SecurityLayer().admit(frame,"peer",now+90000000) as Admission.Rejected).reason)
        assertEquals("oversized",(gate.admit("x".repeat(5000),"peer",now) as Admission.Rejected).reason)
    }
    @Test fun malformedOrForgedPacketCannotPoisonNonceCache() {
        val p=packet(); val gate=SecurityLayer()
        val bad=p.copy(signature=PacketCrypto.b64(ByteArray(64)))
        assertTrue(gate.admit(Wire.encode(bad),"peer",now) is Admission.Rejected)
        assertTrue(gate.admit(Wire.encode(p),"peer",now) is Admission.Accepted)
    }
    @Test fun peerRateLimitRunsBeforeCryptographyAndRecovers() {
        val gate=SecurityLayer(2); val f=Wire.encode(packet())
        gate.admit(f,"spam",now); gate.admit(f,"spam",now)
        assertEquals("rate-limit",(gate.admit(f,"spam",now) as Admission.Rejected).reason)
        assertNotEquals("rate-limit",(gate.admit(f,"spam",now+60000) as Admission.Rejected).reason)
    }
    @Test fun onlyRecipientCanReadHealthAndContextPreventsSubstitution() {
        val a=PacketCrypto.exchangeSecret(); val b=PacketCrypto.exchangeSecret(); val eve=PacketCrypto.exchangeSecret()
        val sealed=PacketCrypto.encrypt("insulin + precise location".toByteArray(),a,PacketCrypto.exchangePublic(b),"offer/accept/victim/responder")
        assertEquals("insulin + precise location",String(PacketCrypto.decrypt(sealed,b,PacketCrypto.exchangePublic(a),"offer/accept/victim/responder")))
        assertTrue(runCatching { PacketCrypto.decrypt(sealed,eve,PacketCrypto.exchangePublic(a),"offer/accept/victim/responder") }.isFailure)
        assertTrue(runCatching { PacketCrypto.decrypt(sealed,b,PacketCrypto.exchangePublic(a),"other-offer") }.isFailure)
    }
    @Test fun publicHealthTextFailsSchema() {
        val p=packet(); val leaked=PacketCrypto.sign(p.fact.copy(fields=p.fact.fields + ("health" to "insulin")),secret)
        assertEquals("schema",(SecurityLayer().admit(Wire.encode(leaked),"peer",now) as Admission.Rejected).reason)
    }
    @Test fun reconnectHistoryPreservesOldSignedFactsWithoutAcceptingStaleCommands() {
        val original=packet(); val later=now+172800000
        val outer=PacketCrypto.sign(Fact("",EventKind.HISTORY,original.fact.author,2,later,later+60000,"s0000",mapOf("frame" to Wire.encode(original))),secret)
        val gate=SecurityLayer()
        assertEquals("timestamp",(gate.admit(Wire.encode(original),"peer",later) as Admission.Rejected).reason)
        assertTrue(gate.admit(Wire.encode(outer),"peer",later) is Admission.Accepted)
        assertEquals(original,gate.historical(outer,later))
        val nested=PacketCrypto.sign(outer.fact.copy(fields=mapOf("frame" to Wire.encode(outer))),secret)
        assertTrue(gate.admit(Wire.encode(nested),"peer",later) is Admission.Rejected)
    }
    @Test fun extremeLamportClockCannotPoisonLocalClock() {
        val p=packet(); val jump=PacketCrypto.sign(p.fact.copy(clock=2_000_000),secret)
        assertEquals("clock-jump",(SecurityLayer().admit(Wire.encode(jump),"peer",now) as Admission.Rejected).reason)
    }
}
