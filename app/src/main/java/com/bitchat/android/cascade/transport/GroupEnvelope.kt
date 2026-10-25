package com.bitchat.android.cascade.transport

import com.google.gson.Gson
import com.bitchat.android.model.BitchatMessage
import java.util.Base64

/** resQ group extension: stock Bitchat peers can relay it but cannot open its group UI. */
object GroupEnvelope {
    const val PREFIX="RQGROUP1:"
    const val ENCRYPTED="RQENC1:"
    private data class Body(val channel:String,val text:String,val encrypted:Boolean)
    private val gson=Gson()
    fun encode(channel:String,text:String):String {
        require(channel.matches(Regex("#[a-zA-Z0-9_-]{1,40}")))
        require(text.length<=6000)
        return PREFIX+gson.toJson(Body(channel,text.removePrefix(ENCRYPTED),text.startsWith(ENCRYPTED)))
    }
    fun decode(message:BitchatMessage,decrypt:(ByteArray,String)->String?):BitchatMessage? = runCatching {
        require(message.content.length<=8192 && message.content.startsWith(PREFIX))
        val body=gson.fromJson(message.content.removePrefix(PREFIX),Body::class.java)
        require(body.channel.matches(Regex("#[a-zA-Z0-9_-]{1,40}")) && body.text.length<=6000)
        val text=if(body.encrypted) decrypt(Base64.getDecoder().decode(body.text),body.channel) ?: return null else body.text
        message.copy(content=text,channel=body.channel,isEncrypted=body.encrypted)
    }.getOrNull()
}
