package com.bitchat.android.cascade

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var sosRingtone: Ringtone?=null
    private var sosAlarmJob: Job?=null
    private var sosAudioFocusRequest: AudioFocusRequest?=null
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
        // Subscribe to incoming SOS alerts from mesh peers and sound the alarm
        lifecycleScope.launch {
            repository.sosAlertFlow.collect { senderIdentity ->
                soundSosAlarm()
            }
        }
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
    override fun onDestroy() { unregisterReceiver(radioReceiver); speech?.destroy(); io.cancel(); stopSosAlarm(); super.onDestroy() }

    /**
     * Sounds the system alarm ringtone at full ALARM-stream volume for up to 8 seconds.
     * Falls back to a strong SOS vibration pattern (... --- ...) if the ringtone cannot play
     * or the ringer is in silent/vibrate mode. Both sound and vibration are applied when possible.
     */
    private fun soundSosAlarm() {
        stopSosAlarm()  // cancel any previously playing alarm first

        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager

        // ── Vibration (always: works even in silent mode) ──────────────────────
        // SOS morse pattern: ... (short short short) --- (long long long) ... (short short short)
        // Format: [delay, on, off, on, off, ...] in ms
        val sosMorse = longArrayOf(
            0L,
            /* S */ 100, 100, 100, 100, 100, 200,
            /* O */ 300, 300, 300, 300, 300, 200,
            /* S */ 100, 100, 100, 100, 100, 500
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)?.defaultVibrator
                vm?.vibrate(VibrationEffect.createWaveform(sosMorse, -1))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createWaveform(sosMorse, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(sosMorse, -1)
                }
            }
        } // vibration failure is non-critical

        // ── Audio ringtone ─────────────────────────────────────────────────────
        runCatching {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return@runCatching

            // Request audio focus on the ALARM stream so we override DnD / media.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                sosAudioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            }

            val ringtone = RingtoneManager.getRingtone(applicationContext, uri) ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                // Raise alarm stream to max regardless of ringer mode.
                audioManager?.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
                ringtone.volume = 1.0f
                ringtone.isLooping = false
            }
            sosRingtone = ringtone
            ringtone.play()
        }.onFailure {
            // Sound failed — vibration above is the fallback; notify as a safety net.
            repository.notifyUser("⚡ SOS Alert received! (Alarm audio unavailable: ${it.message})")
        }

        // Auto-stop after 8 seconds.
        sosAlarmJob = lifecycleScope.launch {
            delay(8_000L)
            stopSosAlarm()
        }
    }

    private fun stopSosAlarm() {
        sosAlarmJob?.cancel(); sosAlarmJob = null
        sosRingtone?.stop(); sosRingtone = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sosAudioFocusRequest?.let {
                (getSystemService(AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(it)
            }
        }
        sosAudioFocusRequest = null
        // Cancel any active vibration.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
        }
    }
}
