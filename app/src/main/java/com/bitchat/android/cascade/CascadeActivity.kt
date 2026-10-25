package com.bitchat.android.cascade

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.cascade.data.CascadeRepository
import com.bitchat.android.cascade.ui.CascadeUi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.cascade.crypto.PacketCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

data class DeviceActions(
    val permissions: () -> Unit,
    val location: () -> Unit,
    val shareApp: () -> Unit,
    val voice: ((String) -> Unit) -> Unit,
    val photo: ((String) -> Unit) -> Unit,
    val export: () -> Unit,
    val batterySettings: () -> Unit
)

@AndroidEntryPoint
class CascadeActivity : ComponentActivity() {
    @Inject lateinit var repository: CascadeRepository
    private val chatRequest=androidx.compose.runtime.mutableStateOf<Pair<Long,String>?>(null)
    private var voiceResult: ((String)->Unit)?=null
    private var photoResult: ((String)->Unit)?=null
    private var locationJob: Job?=null
    private val radioReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context,intent:Intent) {
            when(intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> startIfPermitted()
                android.bluetooth.BluetoothAdapter.STATE_OFF -> repository.stopTransport()
            }
        }
    }
    private val gpsPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.any { it }) locate() else repository.locationStatus("Location permission denied · allow it in app Settings")
    }
    private var speech: SpeechRecognizer?=null
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startIfPermitted() }
    private val enableBluetooth=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startIfPermitted() }
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) startVoice() else repository.notifyUser("Microphone permission denied. You can type your SOS.")
    }
    private val picker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) io.launch {
            runCatching {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
                require(bounds.outWidth>0 && bounds.outHeight>0)
                val options=BitmapFactory.Options().apply { inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/384).coerceAtLeast(1) }
                val source=contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it,null,options) } ?: error("Image unreadable")
                val ratio=256f/maxOf(source.width,source.height)
                val bitmap=Bitmap.createScaledBitmap(source,(source.width*ratio).toInt().coerceAtLeast(1),(source.height*ratio).toInt().coerceAtLeast(1),true)
                val bytes=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,35,bytes)
                require(bytes.size()<=12000)
                val encoded=PacketCrypto.b64(bytes.toByteArray())
                withContext(Dispatchers.Main) { photoResult?.invoke(encoded); repository.notifyUser("Photo compressed; metadata removed. Shared only with the accepted responder.") }
            }.onFailure { repository.notifyUser("Photo could not fit the 12 KB BLE limit. Choose a simpler image.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptChatIntent(intent)
        ContextCompat.registerReceiver(this,radioReceiver,android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),ContextCompat.RECEIVER_EXPORTED)
        setContent { CascadeUi(repository,DeviceActions(
            permissions={ requestMeshPermissions() },
            location={ requestLocation() },
            shareApp={ shareApp() },
            voice={ callback -> voiceResult=callback; microphone.launch(Manifest.permission.RECORD_AUDIO) },
            photo={ callback -> photoResult=callback; picker.launch("image/*") },
            export={ export() },
            batterySettings={ startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        ),chatRequest.value) }
        lifecycleScope.launch { val initial=repository.state.first { it.ready }; if(initial.onboarded) startIfPermitted() }
    }
    private fun acceptChatIntent(intent: Intent) {
        if(intent.getBooleanExtra(com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT,false)) {
            intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID)?.let { peer ->
                if(peer.length in 1..128) chatRequest.value=System.nanoTime() to peer
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);acceptChatIntent(intent) }
    private fun requestLocation() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            gpsPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        } else locate()
    }
    @Suppress("MissingPermission")
    private fun locate() {
        locationJob?.cancel()
        locationJob=lifecycleScope.launch {
            repository.locationStatus("Finding your location…")
            val manager=getSystemService(android.location.LocationManager::class.java)
            val fine=ContextCompat.checkSelfPermission(this@CascadeActivity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val providers=listOfNotNull(if(fine) android.location.LocationManager.GPS_PROVIDER else null,android.location.LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            if(providers.isEmpty()) { repository.locationStatus("Location is off · turn it on in Quick Settings"); return@launch }
            try {
                val fix=withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<android.location.Location> { continuation ->
                        val listener=object : android.location.LocationListener {
                            override fun onLocationChanged(location: android.location.Location) {
                                val age=android.os.SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos
                                if(age !in 0..120_000_000_000L || !location.hasAccuracy() || location.accuracy>5000f) return
                                if(continuation.isActive) continuation.resume(location) { _,_,_ -> }
                                manager.removeUpdates(this)
                            }
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) = Unit
                        }
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                        try { providers.forEach { manager.requestLocationUpdates(it,1000L,0f,listener,android.os.Looper.getMainLooper()) } }
                        catch(error: Exception) { manager.removeUpdates(listener); if(continuation.isActive) continuation.resumeWith(Result.failure(error)) }
                    }
                }
                if(fix==null) repository.locationStatus("No location fix yet · move outdoors and retry")
                else repository.acceptLocation(com.bitchat.android.cascade.data.GpsFix(fix.latitude,fix.longitude,fix.accuracy,fix.elapsedRealtimeNanos/1_000_000))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { repository.locationStatus("Location unavailable · review app permissions and retry") }
        }
    }
    override fun onStop() { locationJob?.cancel(); super.onStop() }
    private fun requiredPermissions() = buildList {
        addAll(com.bitchat.android.cascade.transport.RadioReadiness.permissions())
        if(Build.VERSION.SDK_INT>=33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun requestMeshPermissions() {
        if(com.bitchat.android.cascade.transport.RadioReadiness.missing(this).isNotEmpty()) {
            permissions.launch(requiredPermissions().toTypedArray()); return
        }
        val adapter=getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if(adapter!=null && !adapter.isEnabled) {
            runCatching { enableBluetooth.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { repository.notifyUser("Turn on Bluetooth in Quick Settings, then tap Connect.") }
            return
        }
        startIfPermitted()
    }
    private fun startIfPermitted() {
        val required=requiredPermissions().filter { it!=Manifest.permission.POST_NOTIFICATIONS }
        if(required.all { ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED }) {
            runCatching { ContextCompat.startForegroundService(this,Intent(this,CascadeService::class.java)) }
                .onFailure { repository.notifyUser("Android could not start the Bluetooth service (${it.javaClass.simpleName}). Reopen the app and tap Connect.") }
        } else repository.notifyUser(com.bitchat.android.cascade.transport.RadioReadiness.problem(this) ?: "Review app permissions, then tap Connect.")
    }
    override fun onResume() {
        super.onResume()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) repository.clearLocation()
        if(repository.state.value.ready && repository.state.value.onboarded) startIfPermitted()
    }
    private fun startVoice() {
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            repository.notifyUser("Offline speech recognition is not installed. Type your SOS or install an offline language pack later."); return
        }
        speech?.destroy()
        speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { repository.notifyUser("Listening offline…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { repository.notifyUser("Offline voice input unavailable ($error). Try typing.") }
                override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { voiceResult?.invoke(it) } }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int,params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,if(repository.state.value.language=="EN") "en-IN" else "hi-IN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true))
        }
    }
    private fun shareApp() { lifecycleScope.launch {
        try {
            val file=withContext(Dispatchers.IO) {
                val directory=File(cacheDir,"exports").apply { mkdirs() }
                File(directory,"resq-mesh.apk").also { File(applicationInfo.sourceDir).copyTo(it,overwrite=true) }
            }
            val uri=FileProvider.getUriForFile(this@CascadeActivity,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share resQ mesh offline"))
        } catch(error:Exception) { repository.notifyUser("Could not share the app. Check free storage and try again.") }
    } }
    private fun export() { io.launch {
        val directory=File(cacheDir,"exports").apply { mkdirs() }
        val file=File(directory,"cascade-sanitized-log.txt").apply { writeText(repository.exportLog()) }
        withContext(Dispatchers.Main) {
            val uri=FileProvider.getUriForFile(this@CascadeActivity,"$packageName.files",file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Export sanitized log"))
        }
    } }
    override fun onDestroy() { unregisterReceiver(radioReceiver); speech?.destroy(); io.cancel(); super.onDestroy() }
}
