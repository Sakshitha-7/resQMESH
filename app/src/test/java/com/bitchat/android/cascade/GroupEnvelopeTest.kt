package com.bitchat.android.cascade
import com.bitchat.android.cascade.transport.GroupEnvelope
import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.Date
class GroupEnvelopeTest {
    @Test fun preservesGroupRoutingAndRejectsBadFrames() {
        val message=BitchatMessage(sender="synthetic",content=GroupEnvelope.encode("#demo","hello"),timestamp=Date(0))
        val decoded=GroupEnvelope.decode(message) { _,_ -> null }
        assertEquals("#demo",decoded?.channel)
        assertEquals("hello",decoded?.content)
        assertNull(GroupEnvelope.decode(message.copy(content="RQGROUP1:{}")) { _,_ -> null })
    }
    @Test fun lockedGroupsNeverFallBackToPlaintext() {
        val message=BitchatMessage(sender="synthetic",content=GroupEnvelope.encode("#demo","RQENC1:AA=="),timestamp=Date(0))
        assertNull(GroupEnvelope.decode(message) { _,_ -> null })
    }
}
