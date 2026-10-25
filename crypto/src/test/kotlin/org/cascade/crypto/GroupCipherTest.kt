package org.cascade.crypto
import org.junit.Assert.*
import org.junit.Test
class GroupCipherTest {
    @Test fun authenticatesPasswordAndChannel() {
        val key=GroupCipher.key("synthetic-password","#demo")
        val sealed=GroupCipher.seal("synthetic message","#demo",key)
        assertEquals("synthetic message",GroupCipher.open(sealed,"#demo",key))
        assertNull(GroupCipher.open(sealed,"#other",key))
        assertNull(GroupCipher.open(sealed,"#demo",GroupCipher.key("wrong-password","#demo")))
        sealed[sealed.lastIndex]=(sealed.last().toInt() xor 1).toByte()
        assertNull(GroupCipher.open(sealed,"#demo",key))
    }
    @Test fun usesFreshNoncesAndRejectsTruncation() {
        val key=GroupCipher.key("synthetic-password","#demo")
        assertFalse(GroupCipher.seal("same","#demo",key).contentEquals(GroupCipher.seal("same","#demo",key)))
        assertNull(GroupCipher.open(ByteArray(15),"#demo",key))
    }
}
