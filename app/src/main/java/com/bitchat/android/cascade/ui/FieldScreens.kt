@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.bitchat.android.cascade.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.bitchat.android.cascade.DeviceActions
import com.bitchat.android.cascade.data.*
import org.cascade.crypto.PacketCrypto
import org.cascade.domain.*
import org.cascade.domain.Role
import kotlin.math.*

@Composable fun PrivatePhoto(encoded: String) {
    val image=remember(encoded) { runCatching { val bytes=PacketCrypto.unb64(encoded); BitmapFactory.decodeByteArray(bytes,0,bytes.size).asImageBitmap() }.getOrNull() }
    if(image!=null) Image(image,"Encrypted incident photo, visible to the assigned responder",Modifier.fillMaxWidth().heightIn(max=240.dp))
}

@Composable fun MapScreen(s: CascadeState,r: CascadeRepository,report: ()->Unit) {
    var victims by rememberSaveable { mutableStateOf(true) }; var responders by rememberSaveable { mutableStateOf(true) }; var hazards by rememberSaveable { mutableStateOf(true) }
    Headline("06 / OFFLINE GRID","READ THE\nGROUND.","A schematic of reported areas. No downloaded tiles, no internet, no invented safe routes.")
    val cells=(s.incidents.map { it.cell }+s.responders.map { it.cell }+s.hazards.keys+listOf(s.cell)).filter(Geo::valid).distinct().sorted()
    val centers=remember(cells) { cells.associateWith(Geo::center) }
    val minLat=centers.values.minOfOrNull { it.first } ?: 0.0; val maxLat=centers.values.maxOfOrNull { it.first } ?: 1.0
    val minLon=centers.values.minOfOrNull { it.second } ?: 0.0; val maxLon=centers.values.maxOfOrNull { it.second } ?: 1.0
    val ink=Ink; val paper=Paper; val accent=Blue; val green=Green; val yellow=Yellow; val signal=Signal
    Canvas(Modifier.fillMaxWidth().height(320.dp).hardPanel(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription="Offline map: ${s.incidents.size} incidents, ${s.hazards.size} reported hazards. Long press to report a hazard." }.pointerInput(Unit) { detectTapGestures(onLongPress={ report() }) }) {
        repeat(9) { i -> val x=size.width*i/8; val y=size.height*i/8; drawLine(ink.copy(alpha=0.15f),Offset(x,0f),Offset(x,size.height)); drawLine(ink.copy(alpha=0.15f),Offset(0f,y),Offset(size.width,y)) }
        fun pos(cell: String): Offset {
            val center=centers[cell] ?: (0.0 to 0.0)
            val x=if(maxLon==minLon) 0.5 else (center.second-minLon)/(maxLon-minLon)
            val y=if(maxLat==minLat) 0.5 else (maxLat-center.first)/(maxLat-minLat)
            return Offset((28+x*(size.width-56)).toFloat(),(35+y*(size.height-70)).toFloat())
        }
        if(hazards) s.hazards.forEach { (cell,h) -> val p=pos(cell); drawRect(if(h.safeClaimTrusted) green.copy(alpha=0.35f) else signal.copy(alpha=0.3f),p-Offset(22f,22f),Size(44f,44f)); drawRect(ink,p-Offset(22f,22f),Size(44f,44f),style=Stroke(2f)) }
        if(responders) s.responders.forEach { drawCircle(accent,10f,pos(it.cell)+Offset(12f,0f)); drawCircle(ink,10f,pos(it.cell)+Offset(12f,0f),style=Stroke(2f)) }
        if(victims) s.incidents.forEach { drawCircle(urgencyColor(it.urgency),13f,pos(it.cell)-Offset(10f,0f)); drawCircle(ink,13f,pos(it.cell)-Offset(10f,0f),style=Stroke(2f)) }
        val paint=android.graphics.Paint().apply { color=ink.toArgb(); textSize=23f; typeface=android.graphics.Typeface.MONOSPACE }
        centers.keys.forEachIndexed { index,cell -> val p=pos(cell); drawContext.canvas.nativeCanvas.drawText("Area ${index+1}",p.x-26,p.y+34,paint) }
    }
    Panel {
        Label("LAYERS / LONG PRESS GRID TO REPORT")
        Toggle("Victims / large markers",victims) { victims=it }; Toggle("Responders / small markers",responders) { responders=it }; Toggle("Hazard observations",hazards) { hazards=it }
        Text("Partition boundaries cannot be inferred without other islands’ telemetry. Nearby reports can overlap on this overview.",fontSize=12.sp)
    }
    Action("REPORT HAZARD",Yellow,onClick=report)
    if(cells.isEmpty()) EmptyState("No location reports yet.","Allow location in a help request or your helper profile. The map will use those reports.")
    s.hazards.forEach { (cell,h) -> Panel {
        Label("REPORTED AREA / ${h.corroborations} INDEPENDENT REPORTS")
        Badge(if(h.trusted) "CORROBORATED HAZARD" else if(h.safeClaimTrusted) "CORROBORATED OBSERVATION" else "UNVERIFIED / CAUTION",if(h.safeClaimTrusted) Green else Yellow)
        Action("CONFIRM HAZARD",Yellow) { r.hazard("FLOOD",h.severity.coerceAtLeast(1),0,15,cell) }
        Action("REPORT AREA CLEAR",Paper) { r.hazard("SAFE",0,0,15,cell) }
    } }
}

@Composable fun TopologyScreen(s: CascadeState) {
    Headline("07 / MESH TOPOLOGY","SMALL LINKS.\nBIG REACH.")
    Panel(Blue) { Label("LOCAL ISLAND / OBSERVED PEERS",Paper); Text("${s.mesh.peers.size+1}",color=Paper,fontSize=78.sp,fontFamily=Display,fontWeight=FontWeight.Black); Text("The rest of the network may be partitioned. Peer presence does not reveal the complete radio graph.",color=Paper) }
    val ink=Ink; val paper=Paper; val accent=Blue; val green=Green; val yellow=Yellow; val signal=Signal
    Canvas(Modifier.fillMaxWidth().height(290.dp).hardPanel(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription="Local node with ${s.mesh.peers.size} observed mesh peers. Actual link routes and remote battery levels are unknown." }) {
        val center=Offset(size.width/2,size.height/2)
        val count=s.mesh.peers.size.coerceAtMost(16)
        repeat(count) { i ->
            val angle=(i.toDouble()/count*2*PI)-PI/2
            val p=center+Offset((cos(angle)*size.width*0.35).toFloat(),(sin(angle)*size.height*0.35).toFloat())
            drawLine(ink.copy(alpha=0.3f),center,p,2f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(8f,8f)))
            drawCircle(ink,13f,p); drawCircle(accent,19f,p,style=Stroke(3f))
        }
        drawCircle(green,27f,center)
        drawArc(accent,-90f,s.battery*3.6f,false,center-Offset(34f,34f),Size(68f,68f),style=Stroke(4f))
        val p=android.graphics.Paint().apply { color=ink.toArgb(); textSize=23f; typeface=android.graphics.Typeface.DEFAULT_BOLD }
        drawContext.canvas.nativeCanvas.drawText("YOU",center.x-23,center.y+8,p)
    }
    Panel {
        Label("YOUR BATTERY / ${s.battery}%")
        Text("Dotted lines mean presence, not verified direct links. Remote batteries, hop counts, relay load and remote island boundaries are unknown.")
        Label(if(s.saver) "SYNC DUTY / SAVER" else "SYNC DUTY / NORMAL")
        Text("Upstream bitchat controls BLE scan duty based on battery and app lifecycle. CASCADE saver also reduces application gossip.",fontSize=12.sp)
    }
}

@Composable fun SecurityScreen(s: CascadeState,r: CascadeRepository) {
    var peer by rememberSaveable { mutableStateOf("") }; var challenge by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }; var skills by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(false) }
    Headline("08 / TRUST & SECURITY","TRUST IS\nEARNED.","Signatures prove key ownership. They do not prove medical training, honest reporting, or a person’s identity.")
    Panel(Yellow) {
        Label("YOUR SIGNING FINGERPRINT",Ink); Text(s.identity.chunked(16).joinToString("\n"),fontFamily=Mono,fontSize=11.sp,color=Ink)
        Text("New identities start near zero. Young identities cannot bootstrap trust by vouching for one another.",color=Ink)
        Action("REQUEST VOUCH / SHOW CHALLENGE",Ink,Geo.valid(s.cell)) { r.requestVouch() }
    }
    Panel {
        Label("IN-PERSON PEER REVIEW")
        s.mesh.observations.filter { it.direct }.forEach { observation ->
            Action("SELECT NEARBY / ${observation.signingId.take(10)} / ${observation.rssi ?: "?"} DBM",Paper) { peer=observation.signingId }
        }
        Input(peer,"FULL PEER SIGNING ID",{ peer=it.trim().take(64) }); Input(challenge,"CHALLENGE SHOWN ON PEER DEVICE",{ challenge=it.take(8) })
        Input(group,"INDEPENDENT HOUSEHOLD / TEAM LABEL",{ group=it.take(40) })
        Input(skills,"SKILLS YOU CHECKED / MEDIC, BOAT…",{ skills=it.take(150) })
        Toggle("I met them and checked identity + skills",checked) { checked=it }
        Text("A challenge alone can be relayed. This review is a local human judgement. Radio proximity is not cryptographic proof.",fontSize=12.sp)
        Action("VOUCH FOR PEER",Green,checked && peer.length==64 && challenge.length==8 && group.isNotBlank()) { r.vouch(peer,challenge,skills.lowercase().split(',').map(String::trim).filter(String::isNotBlank).toSet(),group) }
        Action("BLOCK NODE",Signal,peer.length==64) { r.block(peer) }
        Action("FLAG DECOY / LOCAL BLOCK",Paper,peer.length==64) { r.block(peer) }
    }
    Label("SECURITY GATE / ${s.rejectionCount} REJECTIONS")
    if(s.securityLog.isEmpty()) Text("No rejected packets recorded in this session.",fontSize=14.sp)
    s.securityLog.take(15).forEach { Panel { Text(it,fontFamily=Mono,fontSize=12.sp) } }
    s.reviews.forEach { (id,review) -> Panel { Label("LOCAL REVIEW / ${id.take(12)}"); Text(review.capabilities.joinToString().ifEmpty { "Identity review only" }); Text("Independent group: ${review.group}",fontSize=12.sp) } }
}

@Composable fun HazardScreen(s: CascadeState,r: CascadeRepository,device: DeviceActions) {
    var type by rememberSaveable { mutableStateOf("FLOOD") }; var severity by rememberSaveable { mutableFloatStateOf(3f) }
    var depth by rememberSaveable { mutableFloatStateOf(60f) }; var expiry by rememberSaveable { mutableFloatStateOf(15f) }
    val cell=s.cell; var photo by remember { mutableStateOf<String?>(null) }
    Headline("09 / HAZARD REPORT","WHAT DID\nYOU SEE?", "First-hand observations help. Repeat reports from one identity count as one witness.")
    Panel {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("FLOOD","CURRENT","DEBRIS","ELECTRICAL","SAFE").forEach { kind ->
            OutlinedButton(onClick={type=kind},modifier=Modifier.heightIn(min=56.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(type==kind) Yellow else Color.Transparent,contentColor=if(type==kind) Ink else MaterialTheme.colorScheme.onSurface)) { Text(kind,fontFamily=Mono,fontSize=10.sp) }
        } }
        LocationCard(s,device)
        Label("SEVERITY / ${severity.toInt()} OF 5"); Slider(severity,{severity=it},valueRange=1f..5f,steps=3)
        Label("OBSERVED DEPTH / ${depth.toInt()} CM"); Slider(depth,{depth=it},valueRange=0f..300f)
        Label("EXPIRES IN / ${expiry.toInt()} MIN"); Slider(expiry,{expiry=it},valueRange=1f..60f)
        Action(if(photo==null) "ADD PHOTO / LOCAL EVIDENCE" else "PHOTO ATTACHED / LOCAL ONLY",Paper) { device.photo { photo=it } }
        photo?.let { PrivatePhoto(it) }
        Text("Hazard photos stay encrypted on this device. A SAFE observation cannot erase someone else’s current danger report.",fontSize=12.sp)
        Action("PUBLISH OBSERVATION",Yellow,Geo.valid(cell)) { r.hazard(type,severity.toInt(),depth.toInt(),expiry.toInt(),cell); photo?.let(r::saveHazardPhoto) }
        Action("CLEAR HAZARD / REPORT SAFE",Green,Geo.valid(cell)) { r.hazard("SAFE",0,0,expiry.toInt(),cell) }
    }
    Badge("MINIMUM 2 INDEPENDENT VERIFIED REPORTERS",Yellow)
    Text("Unknown routes remain unknown. Corroborated observations are evidence, not a guarantee that a route is safe.",fontSize=13.sp)
}

@Composable fun MergeScreen(s: CascadeState) {
    Headline("10 / PARTITION & MERGE","ISLANDS\nRECONNECT.")
    Panel(Green) { Label("AT THE DOOR IS PROTECTED",Ink); Text("Arrival is an irreversible safety latch.",color=Ink,fontSize=28.sp,fontWeight=FontWeight.Bold); Text("A merge never cancels someone already on site. If two responders arrived, both remain assigned until they complete or explicitly abort.",color=Ink) }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { Panel { Label("RETAINED"); Text(s.merge.retained.size.toString(),fontSize=36.sp,fontWeight=FontWeight.Black) } }
        Box(Modifier.weight(1f)) { Panel { Label("ORPHANED"); Text(s.merge.orphans.size.toString(),fontSize=36.sp,fontWeight=FontWeight.Black) } }
    }
    Panel { Label("DETERMINISTIC ORDER"); Text("1 / Keep all arrived responders\n2 / Earliest accepted Lamport clock\n3 / Lowest dispatch ID\n4 / Re-offer uncovered incidents",fontFamily=Mono,fontSize=12.sp,lineHeight=24.sp) }
    if(s.mergeLog.isEmpty()) EmptyState("No merges recorded.","A change in visible peers triggers reconciliation. Real island boundaries cannot be known from local discovery alone.")
    s.mergeLog.forEachIndexed { i,line -> Panel { Label("EVENT ${(i+1).toString().padStart(2,'0')}"); Text(line) } }
}

@Composable fun SimulationScreen(s: CascadeState,r: CascadeRepository) {
    var nodes by rememberSaveable { mutableFloatStateOf(100f) }
    Headline("11 / RED TEAM HOUR","BREAK IT.\nTHEN TRUST IT.","Synthetic traffic stays inside an isolated JVM simulation. It never enters the live Bluetooth mesh.")
    Panel(Pink) {
        Label("ATTACK SCENARIOS",Ink)
        Text("40 SYBIL NURSES\nFORGED CREDENTIALS\nGPS SPOOF + DECOYS\nBATTERY-DRAIN SPAM\nFLOOD-LURE REPORTS",color=Ink,fontFamily=Display,fontSize=25.sp,fontWeight=FontWeight.Black,lineHeight=30.sp)
    }
    Panel { Label("VIRTUAL NODES / ${nodes.toInt()}"); Slider(nodes,{nodes=it},valueRange=50f..200f,steps=2); Text("The same merge, planner, and security code used by the app runs against partitioned synthetic nodes.",fontSize=13.sp) }
    Action(if(s.simulationRunning) "RUNNING…" else "RUN SIMULATION →",Ink,!s.simulationRunning) { r.simulate(nodes.toInt()) }
    Action("SPAWN ATTACKER / RUN ATTACK SUITE",Pink,!s.simulationRunning) { r.simulate(nodes.toInt()) }
    if(s.simulation.isNotBlank()) Panel { Text(s.simulation,fontFamily=Mono,fontSize=10.sp,lineHeight=19.sp) }
}

@Composable fun SettingsScreen(s: CascadeState,r: CascadeRepository,device: DeviceActions) {
    var wipe by rememberSaveable { mutableStateOf(false) }
    Headline("12 / SETTINGS","YOUR NODE.\nYOUR RULES.")
    Panel {
        Label("LANGUAGE / LOCAL LEXICON")
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("EN","HI","HINGLISH").forEach { language -> Box(Modifier.weight(1f)) { Action(language,if(s.language==language) Green else Paper) { r.setting("language",language) } } } }
        Text("SOS parsing supports English, Hindi and Hinglish. Field controls use English; Hindi mode changes home actions and offline voice locale.",fontSize=12.sp)
        Toggle("Dark / hacker green",s.dark) { r.setting("dark",it.toString()) }
        Toggle("Battery saver",s.saver) { r.setting("saver",it.toString()) }
        Text("Location is set automatically with your permission. Exact request coordinates are encrypted for the accepted responder.",fontSize=12.sp)
        Label("PACKET TTL / ${s.ttlHours} HOURS")
        Slider(s.ttlHours.toFloat(),{r.setting("ttl",it.toInt().toString())},valueRange=1f..24f,steps=22)
        Text("BLE hop limit: ${s.hopLimit}. Lower limits reduce propagation and battery cost.",fontSize=12.sp)
        Slider(s.hopLimit.toFloat(),{r.setting("hops",it.toInt().toString())},valueRange=1f..7f,steps=5)
        Action("RELAY-ONLY MODE",Yellow) { r.setRole(Role.RELAY) }
        Role.entries.forEach { role -> Action("SWITCH ROLE / ${role.name}",if(s.role==role) Green else Paper) { r.setRole(role) } }
    }
    Action("BLUETOOTH + PERMISSIONS",Blue,onClick=device.permissions)
    Action("BATTERY OPTIMIZATION SETTINGS",Paper,onClick=device.batterySettings)
    Action("Share this app offline",Paper,onClick=device.shareApp)
    Action("EXPORT SANITIZED LOGS",Ink,onClick=device.export)
    Action("WIPE LOCAL DATA",Signal) { wipe=true }
    Panel { Label("resQ mesh / 0.3.0"); Text("Built on permissionlesstech / bitchat-android. GPL-3.0. BLE, relay, deduplication and Noise are retained from upstream.",fontSize=13.sp); Text("Development build. Simulator checks do not replace physical-device mesh validation or field evaluation.",fontSize=13.sp) }
    if(wipe) AlertDialog(onDismissRequest={wipe=false},title={Text("Wipe this node?")},text={Text("Deletes this device’s events, private data, and identity keys. Copies already relayed to other devices cannot be recalled. Creates a fresh local identity.")},confirmButton={TextButton(onClick={wipe=false;r.wipe()}){Text("WIPE DATA")}},dismissButton={TextButton(onClick={wipe=false}){Text("KEEP DATA")}})
}

@Composable fun LocationCard(s: CascadeState,device: DeviceActions) {
    LaunchedEffect(Unit) { device.location() }
    var fresh by remember(s.gps) { mutableStateOf(s.gps?.isFresh()==true) }
    LaunchedEffect(s.gps) { while(true) { fresh=s.gps?.isFresh()==true; kotlinx.coroutines.delay(1000) } }
    Label("YOUR LOCATION")
    Text(if(fresh) s.locationStatus else if(s.gps!=null) "Location needs refreshing" else s.locationStatus,fontSize=14.sp)
    Text("Your phone fills this in automatically. Exact coordinates are encrypted for the responder who accepts your request.",fontSize=12.sp)
    TextButton(onClick=device.location) { Text(if(fresh) "Refresh location" else "Enable location / retry") }
}
