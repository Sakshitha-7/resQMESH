package org.cascade.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object GroupCipher {
    fun key(password:String,channel:String):SecretKeySpec {
        require(password.length in 8..128)
        val spec=PBEKeySpec(password.toCharArray(),channel.toByteArray(Charsets.UTF_8),100000,256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES") } finally { spec.clearPassword() }
    }
    fun seal(text:String,channel:String,key:SecretKeySpec):ByteArray {
        require(text.toByteArray(Charsets.UTF_8).size<=4000)
        val iv=ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(128,iv))
        cipher.updateAAD(channel.toByteArray(Charsets.UTF_8))
        return iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun open(bytes:ByteArray,channel:String,key:SecretKeySpec):String? = runCatching {
        require(bytes.size in 28..4028)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD(channel.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }.getOrNull()
}
