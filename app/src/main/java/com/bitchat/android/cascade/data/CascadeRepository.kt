package com.bitchat.android.cascade.data

import android.content.Context
import android.os.BatteryManager
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.cascade.transport.BitchatTransport
import com.bitchat.android.crypto.EncryptionService
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.cascade.crypto.*
import org.cascade.domain.*
import org.cascade.pipeline.*
import org.cascade.simulator.NetworkSimulation
import org.cascade.transport.MeshStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Emitted to every observer when an SOS alert arrives from any mesh peer or the local device. */
const val SOS_ALERT_FIELD = "sos_alert"

data class PrivateRecord(val text: String, val latitude: Double?, val longitude: Double?, val photo: String?, val flags: Set<String>)
data class PeerReview(val capabilities: Set<String>, val group: String, val verifiedAt: Long)
data class SavedContact(val peer: String,val name: String)
data class DirectChat(val id: String,val peer: String,val text: String,val outgoing: Boolean,val time: Long,val status: String)
data class GpsFix(val latitude: Double, val longitude: Double, val accuracy: Float, val elapsedRealtime: Long) {
    fun isFresh(now: Long = android.os.SystemClock.elapsedRealtime()) = now - elapsedRealtime in 0..120_000
}
data class CascadeState(
    val ready: Boolean=false, val onboarded: Boolean=false, val identity: String="", val role: Role=Role.RELAY,
    val battery: Int=100, val saver: Boolean=false, val dark: Boolean=false, val language: String="EN",
    val cell: String="", val gps: GpsFix?=null, val locationStatus: String="Location not set", val ttlHours: Int=6, val hopLimit: Int=7, val mesh: MeshStatus=MeshStatus(),
    val facts: FactSet=FactSet(), val incidents: List<Distress> = emptyList(), val responders: List<Responder> = emptyList(),
    val dispatches: List<Dispatch> = emptyList(), val assignments: List<Assignment> = emptyList(),
    val hazards: Map<String,HazardCell> = emptyMap(), val merge: MergeResult=MergeResult(emptySet(),emptySet(),emptySet(),emptyList()),
    val events: List<String> = emptyList(), val mergeLog: List<String> = emptyList(), val securityLog: List<String> = emptyList(),
    val privateDetails: Map<String,PrivateRecord> = emptyMap(), val blocked: Set<String> = emptySet(),
    val reviews: Map<String,PeerReview> = emptyMap(), val simulation: String="", val simulationRunning: Boolean=false,
    val available: Boolean=false, val capabilities: Set<String> = emptySet(), val capacity: Int=1, val radius: Int=5000,
    val notice: String="", val rejectionCount: Int=0, val demo: Boolean=false,
    val contacts: List<SavedContact> = emptyList(), val chats: List<DirectChat> = emptyList(),
    val sosAlertActive: Boolean=false
)

/** One actor owns state, database writes, Lamport clock and admission; UI only sends intents. */
@Singleton
class CascadeRepository @Inject constructor(@ApplicationContext private val context: Context, val transport: BitchatTransport) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val actions=Channel<suspend () -> Unit>(128)
    private val mutable=MutableStateFlow(CascadeState())
    val state=mutable.asStateFlow()
    /** Hot flow that fires once per incoming SOS alert (sender identity). Subscribers play an alarm. */
    private val _sosAlertFlow=MutableSharedFlow<String>(extraBufferCapacity=4)
    val sosAlertFlow: SharedFlow<String> = _sosAlertFlow.asSharedFlow()
    private val gson=Gson()
    private val db by lazy { Room.databaseBuilder(context,CascadeDatabase::class.java,"cascade.db").build() }
    private val vault by lazy {
        val key=MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context,"cascade_vault",key,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val crypto by lazy { EncryptionService(context) }
    private val gate=SecurityLayer()
    private val planner=DispatchPlanner()
    private val resolver=MergeResolver()
    private var packets=linkedMapOf<String,SignedPacket>()
    private var nextSync=0
    private var lastBeacon=0L
    private val drafts=mutableMapOf<String,PrivateRecord>()
    private val receivedParts=mutableMapOf<String,MutableMap<Int,String>>()

    init {
        scope.launch { for(action in actions) runCatching { action() }.onFailure { notice("Action could not finish. Check Bluetooth, storage, or input.") } }
        enqueue {
            val pub=requireNotNull(crypto.getSigningPublicKey())
            val identity=PacketCrypto.identity(pub)
            mutable.value=mutable.value.copy(ready=true,identity=identity,onboarded=vault.getBoolean("onboarded",false),
                role=runCatching { Role.valueOf(vault.getString("role","RELAY")!!) }.getOrDefault(Role.RELAY),
                cell="",saver=vault.getBoolean("saver",false),dark=vault.getBoolean("dark",false),
                language=vault.getString("language","EN").orEmpty(),
                ttlHours=vault.getInt("ttl",6),hopLimit=vault.getInt("hops",7),capabilities=vault.getStringSet("capabilities",emptySet()).orEmpty().toSet(),
                capacity=vault.getInt("capacity",1),radius=vault.getInt("radius",5000),available=vault.getBoolean("available",false),
                blocked=vault.getStringSet("blocked",emptySet()).orEmpty().toSet())
            transport.hopLimit=mutable.value.hopLimit
            mutable.value=mutable.value.copy(
                contacts=runCatching { gson.fromJson(vault.getString("contacts","[]"),Array<SavedContact>::class.java).toList() }.getOrDefault(emptyList()),
                chats=runCatching { gson.fromJson(vault.getString("chats","[]"),Array<DirectChat>::class.java).toList() }.getOrDefault(emptyList()))
            packets=db.facts().all().mapNotNull { runCatching { Wire.decode(it.frame) }.getOrNull() }.filter(PacketCrypto::verify).associateByTo(linkedMapOf()) { it.fact.id }
            packets.values.forEach(gate::remember)
            val reviews=vault.all.filterKeys { it.startsWith("review-") }.mapNotNull { (k,v) -> runCatching { k.removePrefix("review-") to gson.fromJson(v as String,PeerReview::class.java) }.getOrNull() }.toMap()
            mutable.value=mutable.value.copy(facts=FactSet(packets.values.map { it.fact }.toSet()),reviews=reviews)
            packets.values.filter { it.fact.kind==EventKind.DISTRESS && it.fact.author==identity }.forEach { p ->
                vault.getString("private-${p.fact.id}",null)?.let { drafts[p.fact.id]=gson.fromJson(it,PrivateRecord::class.java) }
            }
            refresh(); event("Identity ready · local storage encrypted · internet disabled")
            processPrivate()
        }
        scope.launch { transport.chatDelivered.collect { (id,peer) -> enqueue {
            val chats=mutable.value.chats.map { if(it.outgoing && it.id==id && it.peer==peer) it.copy(status="Delivered") else it }
            vault.edit().putString("chats",gson.toJson(chats)).commit()
            mutable.value=mutable.value.copy(chats=chats)
        } } }
        scope.launch { transport.chatIncoming.collect { frame -> enqueue {
            val observation=mutable.value.mesh.observations.firstOrNull { it.meshId==frame.peer }
            if(observation?.signingId in mutable.value.blocked || frame.peer in mutable.value.blocked) return@enqueue
            if(mutable.value.chats.any { it.id==frame.id && it.peer==frame.peer }) return@enqueue
            storeChat(DirectChat(frame.id,frame.peer,frame.text,false,System.currentTimeMillis(),"Received"))
        } } }
        scope.launch { transport.incoming.collect { frame -> enqueue {
            if(mutable.value.demo) return@enqueue
            when(val result=gate.admit(frame.payload,frame.immediatePeer,System.currentTimeMillis())) {
                is Admission.Accepted -> {
                    val packet=if(result.packet.fact.kind==EventKind.HISTORY) gate.historical(result.packet,System.currentTimeMillis()) ?: return@enqueue else result.packet
                    if(packet.fact.author in mutable.value.blocked) return@enqueue
                    // Fire SOS alert for incoming sos_alert facts from other devices
                    if(packet.fact.kind==EventKind.DISTRESS && packet.fact.value(SOS_ALERT_FIELD)=="true" && packet.fact.author!=mutable.value.identity) {
                        _sosAlertFlow.tryEmit(packet.fact.author)
                        event("⚡ SOS ALERT received from nearby device — alarm triggered")
                    }
                    save(packet,false)
                    processPrivate()
                    planOffers()
                }
                is Admission.Rejected -> if(result.reason!="replay") {
                    mutable.value=mutable.value.copy(rejectionCount=mutable.value.rejectionCount+1,
                        securityLog=(listOf("Rejected: ${result.reason}")+mutable.value.securityLog).take(60))
                }
            }
        } } }
        scope.launch { transport.status.collect { mesh -> enqueue {
            val old=mutable.value.mesh
            mutable.value=mutable.value.copy(mesh=mesh)
            if(old.peers.size!=mesh.peers.size) {
                event("Mesh links changed · ${mesh.peers.size} peers visible")
                mutable.value=mutable.value.copy(mergeLog=(listOf("${old.peers.size} → ${mesh.peers.size} visible peers; recomputed dispatch union")+mutable.value.mergeLog).take(60))
                refresh(); syncBatch()
            }
        } } }
        scope.launch { while(isActive) { delay(15_000); enqueue {
            if(!mutable.value.demo) {
                refresh()
                if(mutable.value.available && System.currentTimeMillis()-lastBeacon>60_000) publishBeacon()
                planOffers(); processPrivate(); syncBatch()
            }
        } } }
    }
    private fun enqueue(action: suspend () -> Unit) { actions.trySend(action) }
    private fun event(text: String) { mutable.value=mutable.value.copy(events=(listOf(text)+mutable.value.events).take(40)) }
    private fun notice(text: String) { mutable.value=mutable.value.copy(notice=text); event(text) }
    fun dismissNotice() { mutable.value=mutable.value.copy(notice="") }
    fun startTransport() { enqueue { if(!mutable.value.demo) transport.start() } }
    fun stopTransport() { enqueue { transport.stop() } }
    fun saveContact(peer: String,name: String) { enqueue {
        if(!peer.matches(Regex("[0-9a-fA-F]{16}")) || name.isBlank()) return@enqueue
        val contacts=(mutable.value.contacts.filterNot { it.peer==peer }+SavedContact(peer,name.trim().take(40))).takeLast(200)
        vault.edit().putString("contacts",gson.toJson(contacts)).commit()
        mutable.value=mutable.value.copy(contacts=contacts)
        notice("Contact saved on this phone.")
    } }
    fun removeContact(peer: String) { enqueue {
        val contacts=mutable.value.contacts.filterNot { it.peer==peer }
        vault.edit().putString("contacts",gson.toJson(contacts)).commit()
        mutable.value=mutable.value.copy(contacts=contacts)
    } }
    fun sendChat(peer: String,text: String) { enqueue {
        val body=text.trim().take(1000)
        if(body.isEmpty()) return@enqueue
        val observation=mutable.value.mesh.observations.firstOrNull { it.meshId==peer }
        if(observation?.signingId in mutable.value.blocked || peer in mutable.value.blocked) { notice("This person is blocked."); return@enqueue }
        val id=UUID.randomUUID().toString()
        if(!transport.sendChat(peer,body,id)) { notice("Secure connection is not ready. Keep both phones nearby and tap Send again."); return@enqueue }
        storeChat(DirectChat(id,peer,body,true,System.currentTimeMillis(),"Queued · delivery unconfirmed"))
    } }
    private fun storeChat(chat: DirectChat) {
        val chats=(mutable.value.chats+chat).takeLast(500)
        vault.edit().putString("chats",gson.toJson(chats)).commit()
        mutable.value=mutable.value.copy(chats=chats)
    }
    fun onboard(role: Role) { enqueue { vault.edit().putBoolean("onboarded",true).putString("role",role.name).apply(); mutable.value=mutable.value.copy(onboarded=true,role=role) } }
    fun setRole(role: Role) { enqueue { vault.edit().putString("role",role.name).apply(); mutable.value=mutable.value.copy(role=role); if(role!=Role.RESPONDER && mutable.value.available) { mutable.value=mutable.value.copy(available=false); publishBeacon() } } }
    fun setting(key: String,value: String) { enqueue {
        when(key) {
            "saver" -> { vault.edit().putBoolean(key,value.toBoolean()).apply(); mutable.value=mutable.value.copy(saver=value.toBoolean()) }
            "dark" -> { vault.edit().putBoolean(key,value.toBoolean()).apply(); mutable.value=mutable.value.copy(dark=value.toBoolean()) }
            "language" -> { vault.edit().putString(key,value).apply(); mutable.value=mutable.value.copy(language=value) }
            "ttl" -> { val hours=(value.toIntOrNull() ?: 6).coerceIn(1,24); vault.edit().putInt("ttl",hours).apply(); mutable.value=mutable.value.copy(ttlHours=hours) }
            "hops" -> { val limit=(value.toIntOrNull() ?: 7).coerceIn(1,7); vault.edit().putInt("hops",limit).apply(); transport.hopLimit=limit; mutable.value=mutable.value.copy(hopLimit=limit) }
        }
    } }
    fun clearLocation() { enqueue { mutable.value=mutable.value.copy(gps=null,cell="",locationStatus="Location permission needed") } }
    fun locationStatus(message: String) { enqueue { mutable.value=mutable.value.copy(locationStatus=message) } }
    fun acceptLocation(fix: GpsFix) { enqueue {
        if(!fix.isFresh() || !fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 || !fix.accuracy.isFinite() || fix.accuracy !in 0f..5000f) {
            mutable.value=mutable.value.copy(locationStatus="Waiting for a reliable location"); return@enqueue
        }
        mutable.value=mutable.value.copy(gps=fix,cell=Geo.encode(fix.latitude,fix.longitude),locationStatus="Location ready · accuracy about ${fix.accuracy.toInt()} m")
    } }
    fun sendLocatedSos(text: String, urgency: Int, count: Int, photo: String?) {
        val fix=mutable.value.gps
        if(fix==null || !fix.isFresh()) { notice("Refresh your location before sending."); return }
        sendSos(text,Geo.encode(fix.latitude,fix.longitude),urgency,count,fix.latitude,fix.longitude,photo)
    }
    fun setCell(cell: String) { enqueue {
        require(Geo.valid(cell.lowercase()))
        vault.edit().putString("cell",cell.lowercase()).apply(); mutable.value=mutable.value.copy(cell=cell.lowercase())
    } }
    private fun newPacket(kind: EventKind,fields: Map<String,String>,cell: String=mutable.value.cell,life: Long=mutable.value.ttlHours*3_600_000L): SignedPacket {
        require(Geo.valid(cell)) { "Wait for your location to be ready" }
        val now=System.currentTimeMillis(); val pub=PacketCrypto.b64(requireNotNull(crypto.getSigningPublicKey()))
        val nonce=PacketCrypto.b64(PacketCrypto.randomBytes(16))
        val fact=Fact("",kind,mutable.value.identity,mutable.value.facts.nextClock(),now,now+life,cell,fields)
        val body=Wire.canonical(fact,nonce,pub)
        return SignedPacket(fact.copy(id=Wire.hash(body)),nonce,pub,PacketCrypto.b64(requireNotNull(crypto.signData(body))))
    }
    private suspend fun save(packet: SignedPacket,broadcast: Boolean=true) {
        if(packet.fact.id in packets) return
        val frame=Wire.encode(packet)
        db.facts().insert(StoredFact(packet.fact.id,frame,System.currentTimeMillis()))
        packets[packet.fact.id]=packet; gate.remember(packet)
        mutable.value=mutable.value.copy(facts=mutable.value.facts.add(packet.fact))
        refresh()
        if(broadcast && !mutable.value.demo) {
            if(packet.fact.kind==EventKind.PRIVATE_DETAILS) transport.sendSealed(packet.fact.value("recipient"),frame)
            else transport.broadcast(frame)
        }
        event("${packet.fact.kind.name.replace('_',' ')} · signed fact stored")
    }
    private fun refresh() {
        val s=mutable.value; val now=System.currentTimeMillis()
        val battery=(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)
        // Blocking future ingress must never erase an already witnessed arrival latch.
        val filtered=s.facts
        val hazards=HazardEngine().grid(filtered.facts.filter { it.kind==EventKind.HAZARD }.map {
            val review=s.reviews[it.author]
            Hazard(it.id,it.cell,it.value("type"),it.int("severity"),it.author,it.expiresAt,it.createdAt,review?.group ?: it.author,review!=null)
        },now)
        val incidents=filtered.incidents(now).map { d ->
            // Age is capped by local receive time in persistent evidence, never trusted from a claimed credential.
            val localFirst=vault.getLong("first-${d.owner}",now)
            if(!vault.contains("first-${d.owner}")) vault.edit().putLong("first-${d.owner}",now).apply()
            d.copy(confidence=if(d.owner==s.identity) 0.7 else 0.3,trust=TrustScorer().score(TrustEvidence(localFirst),now))
        }
        val responders=filtered.responders(now).map { r -> r.copy(verifiedCapabilities=s.reviews[r.id]?.capabilities.orEmpty()) }
        val dispatches=filtered.dispatches(); val merged=resolver.resolve(filtered,now)
        val occupied=dispatches.filter { it.id in merged.retained && it.acceptedAt!=null }.map { it.responder }.toSet()
        val covered=dispatches.filter { it.id in merged.retained || it.status==DispatchStatus.COMPLETE }.map { it.incident }.toSet()
        val rejected=dispatches.filter { it.status in setOf(DispatchStatus.DECLINE,DispatchStatus.ABORT,DispatchStatus.CANCEL) || (it.acceptedAt==null && it.offerExpiresAt<=now) }.map { it.incident to it.responder }.toSet()
        val assignments=planner.assign(incidents.filter { it.id !in covered && it.owner !in s.blocked },responders.filter { it.id !in s.blocked },hazards,now,rejected,occupied)
        mutable.value=s.copy(battery=battery,incidents=incidents,responders=responders,dispatches=dispatches,hazards=hazards,merge=merged,assignments=assignments,
            mergeLog=(merged.log+s.mergeLog).distinct().take(60))
    }
    fun sendSos(text: String,cell: String,urgency: Int,count: Int,latitude: Double?,longitude: Double?,photo: String?) { enqueue {
        require(!mutable.value.demo); require(text.isNotBlank() && text.length<=800 && Geo.valid(cell))
        require((latitude==null && longitude==null) || (latitude!=null && longitude!=null && latitude in -90.0..90.0 && longitude in -180.0..180.0 && Geo.encode(latitude,longitude)==cell))
        val parsed=ClaimVerifier().parse(text)
        mutable.value=mutable.value.copy(cell=cell,role=Role.VICTIM)
        vault.edit().putString("cell",cell).putString("role","VICTIM").apply()
        // Public routing tags are deliberately coarse; no disease-specific tags are broadcast.
        val tags=parsed.tags.map { if(it=="dialysis") "medic" else it }.toSet()
        val packet=newPacket(EventKind.DISTRESS,mapOf("urgency" to urgency.coerceIn(1,5).toString(),"headcount" to count.coerceIn(1,100).toString(),"required" to tags.sorted().joinToString(","),"battery" to mutable.value.battery.toString()),cell)
        val details=PrivateRecord(text,latitude,longitude,photo,parsed.medicalFlags)
        drafts[packet.fact.id]=details; vault.edit().putString("private-${packet.fact.id}",gson.toJson(details)).commit()
        save(packet); notice("SOS stored and queued for BLE relay. Delivery needs another nearby device."); planOffers()
    } }
    fun clearSos(incident: String) { enqueue {
        require(mutable.value.facts.facts.any { it.id==incident && it.author==mutable.value.identity })
        save(newPacket(EventKind.SAFE,mapOf("incident" to incident)))
        mutable.value.dispatches.filter { it.incident==incident && !it.arrived && !it.terminal }.forEach { dispatch ->
            save(newPacket(EventKind.DISPATCH,mapOf("dispatch" to dispatch.id,"status" to "CANCEL")))
        }
        notice("SOS marked safe. Responders already at the door remain protected.")
    } }
    fun profile(capabilities: String,capacity: Int,radius: Int,available: Boolean) { enqueue {
        if(available && mutable.value.gps?.isFresh()!=true) { notice("Refresh your location before offering help."); return@enqueue }
        val normalized=capabilities.lowercase().split(Regex("[,\\s]+" )).filter { it.matches(Regex("[a-z]{2,20}")) }.take(12).toSet()
        mutable.value=mutable.value.copy(capabilities=normalized,capacity=capacity.coerceIn(1,100),radius=radius.coerceIn(250,50000),available=available,role=Role.RESPONDER)
        vault.edit().putStringSet("capabilities",normalized).putInt("capacity",mutable.value.capacity).putInt("radius",mutable.value.radius).putBoolean("available",available).putString("role","RESPONDER").apply()
        publishBeacon(); notice("Responder profile published. Clinical skills require in-person verification on assigning devices."); planOffers()
    } }
    private suspend fun publishBeacon() {
        val s=mutable.value; if(!Geo.valid(s.cell) || s.gps?.isFresh()!=true) return
        save(newPacket(EventKind.BEACON,mapOf("capabilities" to s.capabilities.sorted().joinToString(","),"capacity" to s.capacity.toString(),"radius" to s.radius.toString(),"battery" to s.battery.toString(),"available" to s.available.toString()),life=180000))
        lastBeacon=System.currentTimeMillis()
    }
    private suspend fun planOffers() {
        if(mutable.value.demo) return
        mutable.value.assignments.filter { it.incident.owner==mutable.value.identity && it.responder.id!=mutable.value.identity }.forEach { assignment ->
            val secret=PacketCrypto.exchangeSecret()
            val offer=newPacket(EventKind.DISPATCH,mapOf("incident" to assignment.incident.id,"responder" to assignment.responder.id,"status" to "OFFER","key" to PacketCrypto.b64(PacketCrypto.exchangePublic(secret)),"deadline" to (System.currentTimeMillis()+60000).toString()),assignment.incident.cell)
            vault.edit().putString("exchange-${offer.fact.id}",PacketCrypto.b64(secret)).commit(); secret.fill(0)
            save(offer); event("Direct offer issued · 60 second fallback window")
        }
        val s=mutable.value
        s.dispatches.filter { it.owner==s.identity && !it.terminal && it.backupFor==null && it.id in s.merge.retained }.forEach { primary ->
            val requested=s.facts.facts.any { it.kind==EventKind.DISPATCH && it.value("dispatch")==primary.id && it.value("status")=="BACKUP" && it.author==primary.responder && it.clock>(primary.acceptedAt?.clock ?: Long.MAX_VALUE) }
            val existing=s.dispatches.any { it.backupFor==primary.id && !it.terminal && (it.acceptedAt!=null || it.offerExpiresAt>System.currentTimeMillis()) }
            val incident=s.incidents.find { it.id==primary.incident } ?: return@forEach
            if(requested && !existing) {
                val busy=s.dispatches.filter { !it.terminal && it.id in s.merge.retained }.map { it.responder }.toSet()
                val rejected=s.dispatches.filter { it.backupFor==primary.id }.map { it.incident to it.responder }.toSet()
                val next=planner.rank(listOf(incident),s.responders.filter { it.id !in s.blocked },s.hazards,System.currentTimeMillis(),rejected,busy).firstOrNull() ?: return@forEach
                val secret=PacketCrypto.exchangeSecret()
                val offer=newPacket(EventKind.DISPATCH,mapOf("incident" to incident.id,"responder" to next.responder.id,"status" to "OFFER","key" to PacketCrypto.b64(PacketCrypto.exchangePublic(secret)),"deadline" to (System.currentTimeMillis()+60000).toString(),"backupFor" to primary.id),incident.cell)
                vault.edit().putString("exchange-${offer.fact.id}",PacketCrypto.b64(secret)).commit(); secret.fill(0); save(offer)
                event("Backup offer issued; primary responder remains assigned")
            }
        }
    }
    fun dispatch(id: String,status: DispatchStatus) { enqueue {
        val d=mutable.value.dispatches.first { it.id==id }; require(d.responder==mutable.value.identity)
        require(!d.terminal && (id in mutable.value.merge.retained || status==DispatchStatus.ARRIVED && d.acceptedAt!=null))
        if(status==DispatchStatus.ACCEPT) require(d.status==DispatchStatus.OFFER && System.currentTimeMillis()<d.offerExpiresAt)
        if(status in setOf(DispatchStatus.ARRIVED,DispatchStatus.EN_ROUTE,DispatchStatus.COMPLETE,DispatchStatus.ABORT,DispatchStatus.BACKUP)) require(d.acceptedAt!=null)
        val fields=mutableMapOf("dispatch" to id,"status" to status.name)
        if(status==DispatchStatus.ACCEPT) {
            val secret=PacketCrypto.exchangeSecret(); fields["key"]=PacketCrypto.b64(PacketCrypto.exchangePublic(secret))
            vault.edit().putString("exchange-$id",PacketCrypto.b64(secret)).commit(); secret.fill(0)
        }
        val cell=mutable.value.facts.facts.first { it.id==id }.cell
        save(newPacket(EventKind.DISPATCH,fields,cell)); notice("Dispatch ${status.name.replace('_',' ')} recorded"); processPrivate(); planOffers()
    } }
    private suspend fun processPrivate() {
        val s=mutable.value
        s.dispatches.filter { it.acceptedAt!=null && !it.terminal && it.id in s.merge.retained }.forEach { d ->
            val offer=packets[d.id]?.fact ?: return@forEach
            val acceptance=s.facts.facts.filter { it.kind==EventKind.DISPATCH && it.value("dispatch")==d.id && it.value("status")=="ACCEPT" && it.author==d.responder && it.stamp==d.acceptedAt }.minByOrNull { it.id } ?: return@forEach
            val context=PacketCrypto.context(offer,acceptance)
            if(d.owner==s.identity && !vault.getBoolean("sent-${acceptance.id}",false)) {
                val details=drafts[d.incident] ?: return@forEach
                val secret=vault.getString("exchange-${d.id}",null)?.let(PacketCrypto::unb64) ?: return@forEach
                val shared=details
                val encoded=PacketCrypto.b64(gson.toJson(shared).toByteArray())
                val chunks=encoded.chunked(900); require(chunks.size<=80)
                chunks.forEachIndexed { index,chunk ->
                    val ciphertext=PacketCrypto.encrypt(chunk.toByteArray(),secret,PacketCrypto.unb64(acceptance.value("key")),"$context/$index/${chunks.size}")
                    save(newPacket(EventKind.PRIVATE_DETAILS,mapOf("dispatch" to d.id,"acceptance" to acceptance.id,"recipient" to d.responder,"ciphertext" to ciphertext,"part" to index.toString(),"total" to chunks.size.toString()),offer.cell))
                }
                secret.fill(0); vault.edit().putBoolean("sent-${acceptance.id}",true).commit()
            }
            if(d.responder==s.identity && d.id !in mutable.value.privateDetails) {
                val secret=vault.getString("exchange-${d.id}",null)?.let(PacketCrypto::unb64) ?: return@forEach
                val chunks=s.facts.facts.filter { it.kind==EventKind.PRIVATE_DETAILS && it.author==d.owner && it.value("dispatch")==d.id && it.value("acceptance")==acceptance.id && it.value("recipient")==s.identity }
                val parts=receivedParts.getOrPut(d.id) { mutableMapOf() }
                chunks.sortedBy { it.id }.forEach { part ->
                    runCatching { String(PacketCrypto.decrypt(part.value("ciphertext"),secret,PacketCrypto.unb64(offer.value("key")),"$context/${part.int("part")}/${part.int("total")}")) }
                        .getOrNull()?.let { parts[part.int("part")]=it }
                }
                val total=chunks.firstOrNull()?.int("total") ?: 0
                if(total>0 && (0 until total).all(parts::containsKey)) {
                    val decoded=String(PacketCrypto.unb64((0 until total).joinToString("") { parts.getValue(it) }))
                    val details=gson.fromJson(decoded,PrivateRecord::class.java)
                    mutable.value=mutable.value.copy(privateDetails=mutable.value.privateDetails+(d.id to details))
                    receivedParts.remove(d.id); event("Private health details unlocked for accepted responder")
                }
                secret.fill(0)
            }
        }
    }
    fun hazard(type: String,severity: Int,depth: Int,minutes: Int,cell: String=mutable.value.cell) { enqueue {
        if(mutable.value.gps?.isFresh()!=true || cell!=mutable.value.cell) { notice("Refresh your location and report only the area you are in."); return@enqueue }
        require(type in setOf("FLOOD","CURRENT","DEBRIS","ELECTRICAL","SAFE"))
        save(newPacket(EventKind.HAZARD,mapOf("type" to type,"severity" to if(type=="SAFE") "0" else severity.coerceIn(1,5).toString(),"depth" to depth.coerceIn(0,500).toString()),cell,minutes.coerceIn(1,60)*60000L))
        notice("Observation published. Independent in-person-verified reporters are needed for corroboration.")
    } }
    fun requestVouch() { enqueue { val challenge=UUID.randomUUID().toString().take(8); save(newPacket(EventKind.VOUCH_REQUEST,mapOf("challenge" to challenge),life=300000)); notice("Show nearby peer this challenge: $challenge") } }
    fun saveHazardPhoto(encoded: String) { enqueue {
        require(PacketCrypto.unb64(encoded).size<=12000)
        vault.edit().putString("hazard-photo-${System.currentTimeMillis()}",encoded).apply()
        notice("Hazard photo saved in encrypted local evidence storage")
    } }
    fun vouch(subject: String,challenge: String,capabilities: Set<String>,group: String) { enqueue {
        require(subject!=mutable.value.identity && group.isNotBlank())
        if(transport.proximity(subject)==null) { notice("Vouch needs a recently verified direct BLE peer with a nearby signal. Move closer and retry."); return@enqueue }
        require(mutable.value.facts.facts.any { it.kind==EventKind.VOUCH_REQUEST && it.author==subject && it.value("challenge")==challenge && it.expiresAt>System.currentTimeMillis() })
        val review=PeerReview(capabilities,group,System.currentTimeMillis())
        vault.edit().putString("review-$subject",gson.toJson(review)).commit()
        mutable.value=mutable.value.copy(reviews=mutable.value.reviews+(subject to review))
        save(newPacket(EventKind.ATTESTATION,mapOf("subject" to subject,"capability" to capabilities.sorted().joinToString(","),"challenge" to challenge)))
        notice("Local in-person review saved. Remote attestations alone do not verify credentials."); planOffers()
    } }
    fun block(identity: String) { enqueue {
        val blocked=mutable.value.blocked+identity; vault.edit().putStringSet("blocked",blocked).apply()
        mutable.value=mutable.value.copy(blocked=blocked,securityLog=(listOf("Locally blocked peer / suspected decoy")+mutable.value.securityLog).take(60)); refresh()
    } }
    fun simulate(nodes: Int=100) { enqueue {
        mutable.value=mutable.value.copy(simulationRunning=true)
        val result=NetworkSimulation.run(nodes)
        mutable.value=mutable.value.copy(simulation=result.table(),simulationRunning=false); notice("Simulation finished · synthetic traffic stayed inside the JVM")
    } }
    private fun syncBatch() {
        if(!mutable.value.mesh.running || mutable.value.mesh.peers.isEmpty() || mutable.value.demo) return
        // Signed immutable envelopes are re-gossiped for reconnect. App admission deduplicates them.
        val now=System.currentTimeMillis()
        val safetyKinds=setOf(EventKind.DISTRESS,EventKind.SAFE,EventKind.DISPATCH)
        val live=packets.values.filter { it.fact.expiresAt>now || it.fact.kind in safetyKinds }.sortedBy { it.fact.id }
        if(live.isEmpty()) return
        val count=if(mutable.value.saver || mutable.value.battery<20) 1 else 3
        repeat(minOf(count,live.size)) {
            val packet=live[(nextSync++)%live.size]
            val frame=if(packet.fact.expiresAt>now) Wire.encode(packet) else Wire.encode(newPacket(EventKind.HISTORY,mapOf("frame" to Wire.encode(packet)),packet.fact.cell,300000))
            transport.broadcast(frame)
        }
    }
    fun exportLog(): String {
        val s=mutable.value
        return "resQ mesh — sanitized local log\nNodes visible: ${s.mesh.peers.size}\nIncidents: ${s.incidents.size}\nRejected packets: ${s.rejectionCount}\n\n"+
            s.events.filterNot { it.contains("challenge",true) }.joinToString("\n")+"\n\n"+s.mergeLog.joinToString("\n")+"\n\n"+s.simulation
    }
    fun wipe() {
        // Android clears all app-owned stores, including both emergency and upstream chat data.
        if(!context.getSystemService(android.app.ActivityManager::class.java).clearApplicationUserData())
            notice("Android could not clear all data. Use App info > Storage > Clear storage.")
    }
    fun notifyUser(message: String) { notice(message) }

    /**
     * Broadcasts an immediate SOS alert over the mesh to all nearby devices.
     * Uses urgency=5 DISTRESS with [SOS_ALERT_FIELD]=true so receiving nodes can trigger an audible alarm.
     * Does NOT require GPS — falls back to a default cell when location is unavailable.
     */
    fun sendSosAlert() { enqueue {
        require(!mutable.value.demo)
        val cell = if (Geo.valid(mutable.value.cell)) mutable.value.cell else
            mutable.value.gps?.let { Geo.encode(it.latitude, it.longitude) } ?: "s0000"
        val packet = newPacket(
            EventKind.DISTRESS,
            mapOf(
                "urgency" to "5",
                "headcount" to "1",
                "required" to "rescue",
                "battery" to mutable.value.battery.toString(),
                SOS_ALERT_FIELD to "true"
            ),
            cell,
            life = 300_000L  // 5 minutes TTL
        )
        mutable.value = mutable.value.copy(sosAlertActive = true)
        save(packet)
        notice("⚡ SOS ALERT sent to all nearby devices!")
        event("⚡ SOS ALERT broadcast · all nearby devices will sound an alarm")
        // Auto-clear the sent state after 5 minutes to allow re-trigger
        scope.launch { delay(300_000L); mutable.value = mutable.value.copy(sosAlertActive = false) }
    } }

    fun cancelSosAlert() { enqueue {
        if (mutable.value.demo || !mutable.value.sosAlertActive) return@enqueue
        mutable.value = mutable.value.copy(sosAlertActive = false)
        notice("SOS Alert cancelled locally. Already-relayed packets cannot be recalled.")
        event("SOS ALERT cancelled by user · mesh copies cannot be retracted")
    } }
}
