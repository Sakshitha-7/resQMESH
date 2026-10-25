package com.bitchat.android.cascade

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bitchat.android.R
import com.bitchat.android.cascade.data.CascadeRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

@AndroidEntryPoint
class CascadeService : Service() {
    private val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    @Inject lateinit var repository: CascadeRepository
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cascade_mesh","Offline emergency mesh",NotificationManager.IMPORTANCE_LOW))
        val launch=PendingIntent.getActivity(this,0,Intent(this,CascadeActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(204,NotificationCompat.Builder(this,"cascade_mesh").setSmallIcon(R.drawable.ic_cascade)
            .setContentTitle("resQ mesh").setContentText("Starting nearby discovery…").setOngoing(true).setContentIntent(launch).build())
        serviceScope.launch { repository.transport.status.collect { status ->
            val online=status.running && status.peers.isNotEmpty()
            manager.notify(204,NotificationCompat.Builder(this@CascadeService,"cascade_mesh").setSmallIcon(R.drawable.ic_cascade)
                .setContentTitle(if(online) "resQ mesh · Mesh online" else "resQ mesh · Mesh offline")
                .setContentText(status.problem ?: if(online) "${status.peers.size} nearby devices · no internet required" else "Waiting for nearby devices")
                .setOngoing(true).setContentIntent(launch).build())
        } }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        repository.startTransport()
        return START_STICKY
    }
    override fun onDestroy() { serviceScope.cancel(); repository.stopTransport(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder?=null
}
