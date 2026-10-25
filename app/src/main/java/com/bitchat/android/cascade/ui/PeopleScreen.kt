package com.bitchat.android.cascade.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.cascade.data.*

@Composable fun PeopleScreen(s: CascadeState,r: CascadeRepository,onFullChat: (String)->Unit) {
    var selected by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable(selected) { mutableStateOf(s.contacts.firstOrNull { it.peer==selected }?.name.orEmpty()) }
    var draft by rememberSaveable(selected) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val peers=(s.mesh.peers+s.contacts.map { it.peer }+s.chats.map { it.peer }).distinct()
        .sortedWith(compareByDescending<String> { it in s.mesh.peers }.thenBy { p->s.contacts.firstOrNull { it.peer==p }?.name ?: p })
    val messages=s.chats.filter { it.peer==selected }
    var pending by remember(selected) { mutableStateOf<String?>(null) }
    val initialCount=remember(selected) { messages.size }
    LaunchedEffect(messages.size) {
        if(messages.size>initialCount && messages.lastOrNull()?.outgoing==true && messages.lastOrNull()?.text==pending) { if(draft.trim()==pending) draft=""; pending=null }
    }
    Headline("PEOPLE / ${s.mesh.peers.size} NEARBY","Stay in touch", "Save a name for a nearby person and message them privately. Both phones need a working mesh connection.")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide=maxWidth>=640.dp
        Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            if(wide || selected.isEmpty()) Column(if(wide) Modifier.width(240.dp) else Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Label("${s.contacts.size} SAVED CONTACTS")
                if(peers.isEmpty()) EmptyState("No people nearby yet",s.mesh.problem ?: "Keep Bluetooth on and open resQ mesh on another phone. People appear here automatically.")
                peers.forEach { peer ->
                    val contact=s.contacts.firstOrNull { it.peer==peer }
                    val last=s.chats.lastOrNull { it.peer==peer }
                    Surface(onClick={selected=peer},shape=RoundedCornerShape(18.dp),color=if(peer==selected) Green else Paper,modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text((contact?.name?.take(1) ?: "?").uppercase(),Modifier.background(Green,RoundedCornerShape(14.dp)).padding(12.dp),color=Blue)
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(contact?.name ?: "Nearby ${peer.take(6)}",fontSize=16.sp)
                                Text(if(peer in s.mesh.peers) "Nearby · tap to chat" else "Offline · saved on this phone",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(last!=null) Text(last.text.take(48),maxLines=1,fontSize=12.sp)
                            }
                        }
                    }
                }
            }
            if(selected.isNotEmpty()) Column(if(wide) Modifier.weight(1f) else Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(!wide) TextButton(onClick={selected=""}) { Text("← All people") }
                Text(s.contacts.firstOrNull { it.peer==selected }?.name ?: "Nearby ${selected.take(6)}",fontSize=24.sp)
                Label(if(selected in s.mesh.peers) "NEARBY · PRIVATE CHAT" else "OFFLINE · RECONNECT TO SEND")
                TextButton(onClick={saving=!saving}) { Text(if(s.contacts.any { it.peer==selected }) "Edit saved contact" else "Save contact") }
                if(saving) Panel {
                    Input(name,"Name on this phone",{name=it.take(40)})
                    Action("Save contact",enabled=name.isNotBlank()) { r.saveContact(selected,name);saving=false }
                    if(s.contacts.any { it.peer==selected }) TextButton(onClick={r.removeContact(selected);saving=false}) { Text("Remove saved contact") }
                }
                Text("Encrypted between phones. Saved names are local; compare identities in person before trusting someone.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(messages.isEmpty()) Text("Say hello to start this conversation.",Modifier.padding(vertical=24.dp),fontSize=14.sp)
                    messages.forEach { message ->
                        Column(Modifier.align(if(message.outgoing) Alignment.End else Alignment.Start).widthIn(max=280.dp).background(if(message.outgoing) Green else Paper,RoundedCornerShape(16.dp)).padding(14.dp)) {
                            Text(message.text,fontSize=15.sp)
                            Text(message.status,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                        }
                    }
                }
                Action("Chat with files and voice notes",Paper) { onFullChat(selected) }
                Input(draft,"Message",{draft=it.take(1000)},2)
                Action("Send privately",enabled=draft.isNotBlank() && selected in s.mesh.peers && s.mesh.running) {pending=draft.trim();r.sendChat(selected,draft)}
                Text("Latest 500 messages are stored encrypted on this phone. “Queued” means delivery is not yet confirmed.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
