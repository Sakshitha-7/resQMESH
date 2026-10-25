package com.bitchat.android.cascade.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.cascade.data.CascadeRepository
import com.bitchat.android.cascade.DeviceActions
import com.bitchat.android.ui.ChatViewModel

@Composable
fun OfflineChat(repository: CascadeRepository,device: DeviceActions,peer: String?,onSettings: ()->Unit) {
    val state by repository.state.collectAsState()
    val radio=repository.transport.radioForChat()
    if(radio==null || !state.mesh.running) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Offline chats",style=MaterialTheme.typography.headlineMedium)
            Text(state.mesh.problem ?: "Turn on Bluetooth to open nearby chats.")
            Button(onClick=device.permissions) { Text("Enable Bluetooth") }
        }
        return
    }
    val vm by repository.transport.offlineChat.collectAsState()
    val chat=vm
    if(chat==null) { CircularProgressIndicator(); return }
    LaunchedEffect(chat,peer) { if(peer!=null) { chat.startPrivateChat(peer);chat.showPrivateChatSheet(peer) } }
    com.bitchat.android.ui.theme.BitchatTheme(darkTheme=state.dark) {
        com.bitchat.android.ui.ChatScreen(chat,onResqSettings=onSettings)
    }
}
