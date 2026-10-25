package org.cascade.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class MeshFrame(val payload: String, val immediatePeer: String, val privateSession: Boolean)
data class PeerObservation(val meshId: String,val signingId: String,val rssi: Int?,val direct: Boolean,val observedAt: Long)
data class MeshStatus(val running: Boolean = false, val peers: List<String> = emptyList(), val problem: String? = null,
    val observations: List<PeerObservation> = emptyList())

interface MeshTransport {
    val incoming: Flow<MeshFrame>
    val status: StateFlow<MeshStatus>
    fun start()
    fun stop()
    fun broadcast(payload: String)
    fun sendPrivate(peer: String, payload: String)
}
