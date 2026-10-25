package org.cascade.crypto

import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.cascade.domain.Fact
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object PacketCrypto {
    private val random = SecureRandom()
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun signingSecret() = Ed25519PrivateKeyParameters(random).encoded
    fun signingPublic(secret: ByteArray) = Ed25519PrivateKeyParameters(secret, 0).generatePublicKey().encoded
    fun identity(publicKey: ByteArray) = Wire.hash(publicKey)
    fun sign(fact: Fact, secret: ByteArray, nonce: String = b64(randomBytes(16))): SignedPacket {
        val publicKey = b64(signingPublic(secret)); require(fact.author == identity(unb64(publicKey)))
        val canonical = Wire.canonical(fact, nonce, publicKey)
        val signer = Ed25519Signer(); signer.init(true, Ed25519PrivateKeyParameters(secret, 0)); signer.update(canonical, 0, canonical.size)
        return SignedPacket(fact.copy(id = Wire.hash(canonical)), nonce, publicKey, b64(signer.generateSignature()))
    }
    fun verify(packet: SignedPacket): Boolean = runCatching {
        val publicKey = unb64(packet.publicKey); require(publicKey.size == 32)
        val canonical = Wire.canonical(packet.fact, packet.nonce, packet.publicKey)
        require(packet.fact.author == identity(publicKey) && packet.fact.id == Wire.hash(canonical))
        val verifier = Ed25519Signer(); verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0)); verifier.update(canonical, 0, canonical.size)
        verifier.verifySignature(unb64(packet.signature))
    }.getOrDefault(false)

    fun exchangeSecret() = X25519PrivateKeyParameters(random).encoded
    fun exchangePublic(secret: ByteArray) = X25519PrivateKeyParameters(secret, 0).generatePublicKey().encoded
    private fun derive(secret: ByteArray, peerPublic: ByteArray, context: String): ByteArray {
        require(peerPublic.size == 32 && context.isNotBlank())
        val agreement = X25519Agreement(); agreement.init(X25519PrivateKeyParameters(secret, 0))
        val shared = ByteArray(32); agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
        require(shared.any { it != 0.toByte() })
        fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
        val extracted = hmac("CASCADE-MESH-v1".toByteArray(), shared)
        shared.fill(0)
        return hmac(extracted, context.toByteArray(Charsets.UTF_8) + byteArrayOf(1)).also { extracted.fill(0) }
    }
    /** Signed offer and acceptance bind both one-time public keys to this context. */
    fun encrypt(plain: ByteArray, secret: ByteArray, peerPublic: ByteArray, context: String): String {
        require(plain.size <= 1500)
        val key = derive(secret, peerPublic, context); val iv = randomBytes(12)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(context.toByteArray()); b64(iv + cipher.doFinal(plain))
        } finally { key.fill(0) }
    }
    fun decrypt(sealed: String, secret: ByteArray, peerPublic: ByteArray, context: String): ByteArray {
        val bytes = unb64(sealed); require(bytes.size in 28..1528)
        val key = derive(secret, peerPublic, context)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(context.toByteArray()); cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        } finally { key.fill(0) }
    }
    fun context(offer: Fact, acceptance: Fact) = "CM1/${offer.id}/${acceptance.id}/${offer.author}/${offer.value("responder")}"
}
