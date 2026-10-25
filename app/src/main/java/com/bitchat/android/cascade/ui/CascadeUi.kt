@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.bitchat.android.cascade.ui

import androidx.compose.foundation.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.bitchat.android.cascade.DeviceActions
import com.bitchat.android.cascade.data.*
import org.cascade.domain.*
import org.cascade.pipeline.ClaimVerifier
import org.cascade.pipeline.Explainer

@Composable
fun CascadeUi(repository: CascadeRepository,device: DeviceActions,chatRequest: Pair<Long,String>?=null) {
    val s by repository.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf("COMMAND") }
    var detail by rememberSaveable { mutableStateOf("") }
    var chatPeer by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(chatRequest) { if(chatRequest!=null) { chatPeer=chatRequest.second;screen="CHAT" } }
    val scroll=rememberScrollState()
    LaunchedEffect(screen,s.onboarded) { scroll.scrollTo(0) }
    BackHandler(enabled=s.onboarded && screen!="COMMAND") { screen=if(screen=="DETAIL") "DISPATCH" else "COMMAND" }
    val snack=remember { SnackbarHostState() }
    val haptics=LocalHapticFeedback.current
    LaunchedEffect(s.dispatches.map { it.id to it.status }) { if(s.dispatches.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    LaunchedEffect(s.notice) { if(s.notice.isNotEmpty()) { snack.showSnackbar(s.notice); repository.dismissNotice() } }
    CascadeTheme(s.dark) {
        val view=androidx.compose.ui.platform.LocalView.current
        SideEffect { (view.context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window,view).isAppearanceLightStatusBars=!s.dark
            window.statusBarColor=if(s.dark) android.graphics.Color.rgb(11,18,14) else android.graphics.Color.WHITE
        } }
        Scaffold(containerColor=MaterialTheme.colorScheme.background,
            topBar={ StatusBar(s,device.permissions) { repository.setting("dark",(!s.dark).toString()) } },snackbarHost={ SnackbarHost(snack) },
            bottomBar={ if(s.onboarded) BottomBar(screen) { screen=it } }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
            HalftoneBackground(enabled=!s.saver,modifier=Modifier.matchParentSize())
            if(s.onboarded && screen=="CHAT") OfflineChat(repository,device,chatPeer) { screen="SETTINGS" }
            else Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(22.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                if(!s.ready) { Headline("INITIALIZING","CASCADE\nMESH"); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing encrypted local storage…") }
                else if(!s.onboarded) Onboarding(s,repository,device)
                else when(screen) {
                    "COMMAND" -> Command(s,repository,{ chatPeer=null;screen=it },device)
                    "SOS" -> SosScreen(s,repository,device)
                    "PROFILE" -> ProfileScreen(s,repository,device)
                    "DISPATCH" -> DispatchFeed(s,repository) { detail=it; screen="DETAIL" }
                    "DETAIL" -> DispatchDetail(s,repository,detail) { screen="MAP" }
                    "MAP" -> MapScreen(s,repository) { screen="HAZARD" }
                    "TOPOLOGY" -> TopologyScreen(s)
                    "SECURITY" -> SecurityScreen(s,repository)
                    "HAZARD" -> HazardScreen(s,repository,device)
                    "MERGE" -> MergeScreen(s)
                    "SIMULATION" -> SimulationScreen(s,repository)
                    "SETTINGS" -> SettingsScreen(s,repository,device)
                    "PEOPLE" -> PeopleScreen(s,repository) { peer -> chatPeer=peer;screen="CHAT" }
                    else -> MoreScreen { screen=it }
                }
                Spacer(Modifier.height(12.dp))
            }
            }
        }
    }
}

@Composable private fun StatusBar(s: CascadeState,onBluetooth: ()->Unit,onTheme: ()->Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(horizontal=18.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("rQ",Modifier.background(Green,RoundedCornerShape(12.dp)).padding(10.dp),color=Blue,fontFamily=Mono,fontSize=17.sp,fontWeight=FontWeight.Medium)
                Column { Text("resQ mesh",color=Ink,fontFamily=Display,fontSize=21.sp,fontWeight=FontWeight.Medium); Text("HELP IS CLOSER",color=MaterialTheme.colorScheme.onSurfaceVariant,fontFamily=Mono,fontSize=8.sp) }
            }
            IconButton(onClick=onBluetooth) { Icon(Icons.Outlined.Bluetooth,contentDescription="Turn on Bluetooth or reconnect",tint=if(s.mesh.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick=onTheme,modifier=Modifier.semantics { contentDescription=if(s.dark) "Switch to light theme" else "Switch to dark theme" }) { Text(if(s.dark) "LIGHT" else "DARK",fontFamily=Mono,fontSize=10.sp) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Label(if(s.mesh.running && s.mesh.peers.isNotEmpty()) "MESH ONLINE" else "MESH OFFLINE",Blue)
            Label("${s.battery}% PWR",Ink)
            Label(s.role.name,Ink)
        }
        Text(s.mesh.problem ?: if(!s.mesh.running) "RADIO STOPPED / ENABLE BLUETOOTH" else if(s.mesh.peers.isEmpty()) "Bluetooth active · waiting for nearby devices" else "${s.mesh.peers.size} nearby devices · internet not required",color=MaterialTheme.colorScheme.onSurfaceVariant,fontFamily=Mono,fontSize=9.sp)
    }
}
@Composable private fun BottomBar(screen: String,onNavigate: (String)->Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("COMMAND" to "Home","DISPATCH" to "Requests","MAP" to "Map","PEOPLE" to "People","MORE" to "More").forEach { (key,label) ->
            Column(Modifier.weight(1f).heightIn(min=64.dp).background(if(screen==key) Green else MaterialTheme.colorScheme.surface,RoundedCornerShape(20.dp)).clickable { onNavigate(key) }.padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Icon(when(key) { "COMMAND"->Icons.Outlined.Home; "DISPATCH"->Icons.Outlined.Assignment; "MAP"->Icons.Outlined.Map; "PEOPLE"->Icons.Outlined.People; else->Icons.Outlined.MoreHoriz },contentDescription=null,tint=if(screen==key) Blue else Ink,modifier=Modifier.size(23.dp))
                Text(label,color=if(screen==key) Blue else Ink,fontFamily=Display,fontSize=11.sp,fontWeight=FontWeight.Medium)
            }
        }
    }
}
@Composable private fun Onboarding(s: CascadeState,r: CascadeRepository,device: DeviceActions) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var role by rememberSaveable { mutableStateOf(Role.RELAY) }
    Headline("FIELD GUIDE / 0${step+1} OF 04",when(step) { 0->"WHEN TOWERS\nGO DOWN."; 1->"TURN YOUR\nRADIO ON."; 2->"PICK YOUR\nPLACE."; else->"YOU ARE\nA NODE." })
    Panel(if(step==0) Yellow else MaterialTheme.colorScheme.surface) {
        Label(when(step) { 0->"NO SERVER. NO SIGNAL REQUIRED."; 1->"BLUETOOTH + NEARBY DEVICES"; 2->"EVERY ROLE MATTERS"; else->"YOUR LOCAL SIGNING IDENTITY" })
        when(step) {
            0 -> { Text("A flood can cut the network. It cannot cut every connection.",fontFamily=Display,fontSize=27.sp,fontWeight=FontWeight.Bold); Text("Send an SOS, offer your skills, or relay a neighbour’s message. Nearby phones carry signed packets between islands."); Text("Delivery depends on nearby devices. An SOS is not a confirmed rescue.",fontSize=13.sp) }
            1 -> { Text("Bluetooth discovers nearby phones. Android also requires location permission for discovery. Notification permission keeps the relay visible."); Action("GRANT PERMISSIONS",Blue,onClick=device.permissions); Action("BATTERY OPTIMIZATION",Ink,onClick=device.batterySettings); Text("Location is filled automatically when you request or offer help. Exact coordinates are encrypted for the accepted responder.",fontSize=13.sp) }
            2 -> Role.entries.forEach { option -> Action("${if(role==option) "✓ " else ""}${when(option) { Role.VICTIM->"I may need help"; Role.RESPONDER->"I can offer help"; Role.RELAY->"Relay nearby messages" }}",if(role==option) Green else Paper) { role=option } }
            else -> { Text(s.identity.chunked(16).joinToString("\n"),fontFamily=Mono,fontSize=12.sp); Text("Stored on this device. Compare the full identity in person before sharing trust or verifying responder skills."); Badge("KEYS STORED ENCRYPTED",Green) }
        }
    }
    Action(if(step<3) "CONTINUE →" else "Get started",Blue) { if(step<3) step++ else { r.onboard(role); device.permissions() } }
    if(step>0) Action("← BACK",Paper) { step-- }
}
@Composable private fun Command(s: CascadeState,r: CascadeRepository,navigate: (String)->Unit,device: DeviceActions) {
    val haptic=LocalHapticFeedback.current
    Headline("HOME",if(s.language=="HI") "एक दूसरे\nके साथ।" else "How can we\nhelp today?")
    Action(if(s.language=="HI") "SOS / मदद चाहिए" else "Request help / SOS",Blue) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); navigate("SOS") }
    Action(if(s.language=="HI") "मैं मदद कर सकता हूँ →" else "Offer help",Green) { r.setRole(Role.RESPONDER); navigate("PROFILE") }
    Action("Nearby devices · ${s.mesh.peers.size}",Paper) { navigate("PEOPLE") }
    Action("Offline chats",Paper) { navigate("CHAT") }
    Panel {
        Label("NEARBY REQUESTS")
        Text("${s.incidents.size} active calls for help",color=Ink,fontFamily=Display,fontSize=24.sp,fontWeight=FontWeight.Medium)
        Text(if(s.incidents.isEmpty()) "No reports received yet. Connect nearby devices to exchange requests." else "These requests are known to this device. More may arrive when phones reconnect.",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
    }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        MetricBox("NEARBY",s.mesh.peers.size,Yellow,Modifier.weight(1f)); MetricBox("HELPERS",s.responders.count { it.available },Green,Modifier.weight(1f)); MetricBox("HAZARDS",s.hazards.size,Pink,Modifier.weight(1f))
    }
    if(!s.mesh.running) Action("Connect nearby devices",Ink,onClick=device.permissions)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Label("↳ MESH PULSE"); Label("LOCAL EVENT LOG") }
    Panel {
        if(s.events.isEmpty()) Text("Listening for the first event…",fontFamily=Mono,fontSize=12.sp)
        s.events.take(5).forEachIndexed { i,event -> Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { Label("0${i+1}",Blue); Text(event,fontSize=13.sp) } }
    }
    Action("Report a danger",Yellow) { navigate("HAZARD") }
}
@Composable private fun MetricBox(label: String,value: Int,color: Color,modifier: Modifier) {
    Column(modifier.hardPanel(color).padding(10.dp)) { Text(value.toString().padStart(2,'0'),color=Ink,fontFamily=Display,fontSize=31.sp,fontWeight=FontWeight.Black); Text(label,color=Ink,fontFamily=Mono,fontSize=9.sp) }
}

@Composable private fun SosScreen(s: CascadeState,r: CascadeRepository,device: DeviceActions) {
    var text by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableIntStateOf(1) }; var urgency by rememberSaveable { mutableFloatStateOf(4f) }
    var photo by remember { mutableStateOf<String?>(null) }; var translated by rememberSaveable { mutableStateOf(false) }
    val parsed=remember(text) { ClaimVerifier().parse(text.take(800)) }
    val mine=s.incidents.filter { it.owner==s.identity }
    Headline("REQUEST HELP","What do\nyou need?","English, Hindi, or Hinglish. Health text stays on your device until a responder accepts.")
    Panel {
        Input(text,"WHAT DO YOU NEED?",{ text=it.take(800) },4)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("Medical","Trapped","Water rising","Need boat","Elderly / child","Medication").forEach { tag ->
                OutlinedButton(onClick={ text=(text+" "+tag).trim().take(800) },modifier=Modifier.heightIn(min=56.dp)) { Text(tag.uppercase(),fontFamily=Mono,fontSize=10.sp) }
            }
        }
        Action("Speak your request",Ink) { device.voice { text=it.take(800) } }
        Action(if(photo==null) "ADD PHOTO / ENCRYPTED" else "REPLACE PHOTO / ATTACHED",Paper) { device.photo { photo=it } }
        Action("Review understood needs",Paper) { translated=!translated }
        if(translated) Text("${parsed.need} / ${when(parsed.need) { Need.MEDICAL->"चिकित्सा"; Need.BOAT->"नाव"; Need.RESCUE->"बचाव"; Need.FOOD->"खाना / पानी"; Need.POWER->"बिजली"; else->"सहायता" }} · confidence ${(parsed.confidence*100).toInt()}%\nLocal keyword interpretation; review the urgency yourself.",fontSize=13.sp)
        Label("PEOPLE / $count")
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Action("−",Paper) { count=(count-1).coerceAtLeast(1) } }
            Box(Modifier.weight(1f)) { Action("+",Paper) { count=(count+1).coerceAtMost(100) } }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Label("URGENCY"); Badge("${urgency.toInt()} / 5",urgencyColor(urgency.toInt())) }
        Slider(value=urgency,onValueChange={ urgency=it },valueRange=1f..5f,steps=3,modifier=Modifier.semantics { contentDescription="SOS urgency from one to five" })
        LocationCard(s,device)
        Action("Send help request",Signal,text.isNotBlank() && s.gps?.isFresh()==true) {
            r.sendLocatedSos(text,urgency.toInt(),count,photo)
        }
    }
    mine.forEach { incident ->
        val dispatch=s.dispatches.firstOrNull { it.incident==incident.id && it.id in s.merge.retained }
        Panel(Yellow) {
            Label("YOUR SOS / ${incident.id.take(8)}",Ink)
            Text(when { dispatch?.arrived==true->"RESPONDER AT THE DOOR"; dispatch?.acceptedAt!=null->"RESPONDER ACCEPTED"; dispatch!=null->"DIRECT OFFER PENDING"; else->"BROADCAST QUEUED" },color=Ink,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("✓ Signed and saved\n${if(s.mesh.peers.isNotEmpty()) "•" else "○"} Relay available; delivery unconfirmed\n○ Claim verification needs evidence\n${if(dispatch!=null) "✓" else "○"} Responder offered\n${if(dispatch?.status==DispatchStatus.EN_ROUTE) "✓" else "○"} En route\n${if(dispatch?.arrived==true) "✓" else "○"} Arrived",color=Ink,fontFamily=Mono,fontSize=11.sp,lineHeight=23.sp)
            Action("I’M SAFE NOW",Green) { r.clearSos(incident.id) }
            Action("CANCEL SOS",Paper) { r.clearSos(incident.id) }
        }
    }
}

@Composable private fun ProfileScreen(s: CascadeState,r: CascadeRepository,device: DeviceActions) {
    var capabilities by rememberSaveable { mutableStateOf(s.capabilities.joinToString(", ")) }
    var capacity by rememberSaveable { mutableFloatStateOf(s.capacity.toFloat()) }; var radius by rememberSaveable { mutableFloatStateOf(s.radius.toFloat()) }
    var available by rememberSaveable { mutableStateOf(s.available) }
    Headline("OFFER HELP","How can\nyou help?")
    Panel(Green) { Label("SKILLS BECOME A LIFELINE",Ink); Text("A boat. A rope. A pair of hands.",color=Ink,fontSize=27.sp,fontWeight=FontWeight.Bold) }
    Panel {
        Input(capabilities,"CAPABILITIES / COMMA SEPARATED",{ capabilities=it.take(200) })
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) { listOf("boat","medic","dialysis","vehicle","rope","generator","food").forEach { tag ->
            OutlinedButton(onClick={ capabilities=(capabilities.split(',').map(String::trim).filter(String::isNotBlank)+tag).distinct().joinToString(", ") },modifier=Modifier.heightIn(min=56.dp)) { Text(tag.uppercase(),fontFamily=Mono,fontSize=10.sp) }
        } }
        LocationCard(s,device)
        Label("PHYSICAL CAPACITY / ${capacity.toInt()} PEOPLE"); Slider(capacity,{capacity=it},valueRange=1f..20f,steps=18)
        Label("RESPONSE RADIUS / ${radius.toInt()} M"); Slider(radius,{radius=it},valueRange=250f..10000f)
        Toggle("Available for dispatch",available) { available=it }
        Toggle("Battery saver",s.saver) { r.setting("saver",it.toString()) }
        Text("Beacons expire after 3 minutes without refresh. Medical skills are self-declared until another person checks them in person.",fontSize=13.sp)
        Action("Save and share availability",Blue,s.gps?.isFresh()==true) { r.profile(capabilities,capacity.toInt(),radius.toInt(),available) }
    }
}

@Composable private fun DispatchFeed(s: CascadeState,r: CascadeRepository,open: (String)->Unit) {
    Headline("HELP REQUESTS","Your help\nrequests","Direct offers expire after 60 seconds. Unanswered offers fall back to the next eligible responder.")
    val mine=s.dispatches.filter { it.responder==s.identity && !it.terminal && it.id in s.merge.retained }
    if(mine.isEmpty()) EmptyState("Standing by.","No direct offers for this device. Allow location, publish your responder profile, and keep Bluetooth active.")
    mine.forEach { d ->
        val incident=s.incidents.find { it.id==d.incident }
        Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Badge("U${incident?.urgency ?: 3}",urgencyColor(incident?.urgency ?: 3)); Label(d.status.name) }
            Text(incident?.required?.joinToString(" + ")?.uppercase()?.ifEmpty { "ASSISTANCE NEEDED" } ?: "ASSISTANCE NEEDED",fontSize=28.sp,fontWeight=FontWeight.Bold)
            if(incident!=null) {
                val distance=if(Geo.valid(s.cell)) Geo.distanceMeters(s.cell,incident.cell) else 0
                Text(Explainer().explain(incident,distance,s.hazards[incident.cell]?.penalty ?: 400),fontSize=14.sp)
                Label("REPORT TRUST / ${(incident.trust*100).toInt()}%")
                LinearProgressIndicator(progress={ incident.trust.toFloat() },modifier=Modifier.fillMaxWidth(),color=Blue)
                Text("Travel and battery estimates use approximate areas. Check the route on the ground.",fontSize=12.sp)
            }
            if(d.status==DispatchStatus.OFFER) {
                Action("ACCEPT →",Green) { r.dispatch(d.id,DispatchStatus.ACCEPT) }
                Action("DECLINE",Paper) { r.dispatch(d.id,DispatchStatus.DECLINE) }
                Action("NEED INFO",Yellow) { r.dispatch(d.id,DispatchStatus.INFO) }
            }
            Action("View request",Blue) { open(d.id) }
        }
    }
    Label("LOCAL RANKING / ${s.assignments.size} POTENTIAL MATCHES")
    s.assignments.take(8).forEach { a -> Panel { Label("U${a.incident.urgency} / SCORE ${a.score}"); Text(a.explanation); Label("MODELED BATTERY COST / ${a.cost.estimatedBatteryUnits} UNITS"); Text("Only the incident owner issues the signed offer.",fontSize=12.sp) } }
}

@Composable private fun DispatchDetail(s: CascadeState,r: CascadeRepository,id: String,openMap: ()->Unit) {
    val d=s.dispatches.find { it.id==id }
    Headline("REQUEST DETAILS","Your next\nsteps")
    if(d==null) { EmptyState("Dispatch unavailable.","This dispatch is not in the local event log."); return }
    val details=s.privateDetails[id]
    Panel {
        Badge(d.status.name,if(d.arrived) Green else Yellow)
        if(d.arrived) Text("AT THE DOOR / PROTECTED",fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text(if(details!=null) "ENCRYPTED DETAILS UNLOCKED" else "PRIVATE DETAILS LOCKED / WAITING FOR ACCEPTANCE OR DELIVERY",fontFamily=Mono,fontSize=11.sp)
        if(details!=null) {
            Text(details.text,fontSize=22.sp,fontWeight=FontWeight.Bold)
            Text("Medical flags: ${details.flags.joinToString().ifEmpty { "none parsed" }}")
            if(details.latitude!=null) Text("Location: ${details.latitude}, ${details.longitude}",fontFamily=Mono,fontSize=12.sp)
            details.photo?.let { PrivatePhoto(it) }
        }
        Text("No trusted navigation route is available from sparse reports. An empty cell is unknown, not safe.",fontSize=14.sp)
        Action("OPEN OFFLINE MAP",Blue,onClick=openMap)
        if(!d.terminal && d.id in s.merge.retained) {
            if(d.status==DispatchStatus.OFFER) Action("ACCEPT",Green) { r.dispatch(id,DispatchStatus.ACCEPT) }
            if(d.acceptedAt!=null) {
                Action("I’m on my way",Blue) { r.dispatch(id,DispatchStatus.EN_ROUTE) }
                Action("I’ve arrived",Green) { r.dispatch(id,DispatchStatus.ARRIVED) }
                Action("Ask for backup",Yellow) { r.dispatch(id,DispatchStatus.BACKUP) }
                Action("Stop / it’s unsafe",Signal) { r.dispatch(id,DispatchStatus.ABORT) }
                Action("Mark as complete",Ink) { r.dispatch(id,DispatchStatus.COMPLETE) }
            }
        }
        if(id in s.merge.superseded) Text("Superseded after merge. Check the merge log before moving.",color=Blue)
    }
}

@Composable private fun MoreScreen(navigate: (String)->Unit) {
    Headline("FIELD TOOLS","Tools and\nsettings")
    listOf("CHAT" to "Offline chats and groups","PROFILE" to "Your helper profile","TOPOLOGY" to "Nearby network","SECURITY" to "Trust and security","HAZARD" to "Report a danger","MERGE" to "Reconnection history","SIMULATION" to "Test the network","SETTINGS" to "SETTINGS").forEachIndexed { i,(key,label) ->
        Action(label,if(i==5) Pink else if(i==2) Yellow else Paper) { navigate(key) }
    }
}
