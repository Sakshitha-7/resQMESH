package org.cascade.crypto

import org.cascade.domain.*
import java.io.*
import java.security.MessageDigest
import java.util.Base64

data class SignedPacket(val fact: Fact, val nonce: String, val publicKey: String, val signature: String)

/** Bounded, canonical binary inside a versioned Base64 frame. No JSON parser ambiguity. */
object Wire {
    const val PREFIX = "CM1:"
    const val MAX_FRAME = 3800
    private fun DataOutputStream.text(value: String) { require(value.length <= 2500); writeUTF(value) }
    fun canonical(fact: Fact, nonce: String, publicKey: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.text(publicKey); out.text(nonce); out.text(fact.kind.name)
            out.text(fact.author); out.writeLong(fact.clock); out.writeLong(fact.createdAt); out.writeLong(fact.expiresAt)
            out.text(fact.cell); require(fact.fields.size <= 16); out.writeInt(fact.fields.size)
            fact.fields.toSortedMap().forEach { (k, v) -> out.text(k); out.text(v) }
        }
        return bytes.toByteArray()
    }
    fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    fun encode(packet: SignedPacket): String {
        val body = canonical(packet.fact, packet.nonce, packet.publicKey)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(body.size); out.write(body); out.writeUTF(packet.signature)
        }
        return (PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())).also { require(it.length <= MAX_FRAME) }
    }
    fun decode(frame: String): SignedPacket {
        require(frame.startsWith(PREFIX) && frame.length <= MAX_FRAME)
        val all = Base64.getDecoder().decode(frame.removePrefix(PREFIX))
        return DataInputStream(ByteArrayInputStream(all)).use { input ->
            val size = input.readInt(); require(size in 1..2800 && size <= input.available())
            val body = ByteArray(size); input.readFully(body)
            val signature = input.readUTF(); require(signature.length == 88 && input.available() == 0)
            DataInputStream(ByteArrayInputStream(body)).use { data ->
                require(data.readInt() == 1)
                val publicKey = data.readUTF(); val nonce = data.readUTF(); val kind = EventKind.valueOf(data.readUTF())
                val author = data.readUTF(); val clock = data.readLong(); val created = data.readLong(); val expires = data.readLong()
                val cell = data.readUTF(); val count = data.readInt(); require(count in 0..16)
                val fields = linkedMapOf<String, String>()
                repeat(count) { val key = data.readUTF(); require(!fields.containsKey(key)); fields[key] = data.readUTF() }
                require(data.available() == 0)
                val fact = Fact(hash(body), kind, author, clock, created, expires, cell, fields)
                require(canonical(fact, nonce, publicKey).contentEquals(body))
                SignedPacket(fact, nonce, publicKey, signature)
            }
        }
    }
}
