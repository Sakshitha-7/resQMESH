package org.cascade.crypto

/** Bounds physical-link radio work before parsing, fragmentation and signature verification. */
class IngressBudget(private val perLink: Int=480,private val total: Int=2400,private val maxPayload: Int=4096) {
    private data class Window(var start: Long,var count: Int)
    private val windows=object : LinkedHashMap<String,Window>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Window>?)=size>128
    }
    private var global=Window(0,0)
    @Synchronized fun allow(link: String,bytes: Int,now: Long): Boolean {
        if(bytes !in 0..maxPayload) return false
        if(now-global.start>=60000 || now<global.start) global=Window(now,0)
        val units=maxOf(1,(bytes+4095)/4096)
        global.count+=units
        if(global.count>total) return false
        val window=windows.getOrPut(link) { Window(now,0) }
        if(now-window.start>=60000 || now<window.start) { window.start=now;window.count=0 }
        window.count+=units
        return window.count<=perLink
    }
}
