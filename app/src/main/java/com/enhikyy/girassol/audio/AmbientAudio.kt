package com.enhikyy.girassol.audio

import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.plugins.PluginRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioStatus(val enabled: Boolean=false, val volume: Float=.35f, val active: Boolean=false,
                       val message: String="Som desativado")

/** Main-thread focus/lifecycle policy; stream operations are serialized on a separate thread. */
class AmbientAudio(context: Context, private val lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val app=context.applicationContext
    private val manager=app.getSystemService(AudioManager::class.java)
    private val prefs=app.getSharedPreferences("ambient_audio",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private var lease=AudioOutput.acquire()
    private var closed=false
    private var resumed=false
    private var running=false
    private var seed: Long?=null
    private var scene=AcousticScene()
    private var previousWorld: WorldState?=null
    private var focusHeld=false
    private var focusRequested=false
    private var focusBlocked=false
    private var duck=1f
    private var requested=false
    private var failed=false
    private var revision=0L
    private val mutable=MutableStateFlow(AudioStatus(prefs.getBoolean("enabled",false),
        prefs.getFloat("volume",.35f).let { if (it.isFinite()) it.coerceIn(0f,1f) else .35f }))
    val status=mutable.asStateFlow()
    private val focusListener=AudioManager.OnAudioFocusChangeListener { change ->
        if (!closed) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { focusHeld=true; duck=1f }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { focusHeld=true; duck=.2f }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusHeld=false
                AudioManager.AUDIOFOCUS_LOSS -> { focusHeld=false; focusBlocked=true; focusRequested=false }
            }
            reconcile()
        }
    }
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setOnAudioFocusChangeListener(focusListener,main).build()
    private val noisy=object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY) setEnabled(false)
        }
    }

    init {
        PluginRegistry.initialize(app)
        resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        app.registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(this)
    }
    fun setEnabled(value: Boolean) {
        if (closed) return
        abandon()
        mutable.value=mutable.value.copy(enabled=value)
        prefs.edit().putBoolean("enabled",value).apply()
        failed=false; focusBlocked=false
        reconcile()
    }
    fun setVolume(value: Float) {
        if (closed || !value.isFinite()) return
        val volume=value.coerceIn(0f,1f)
        mutable.value=mutable.value.copy(volume=volume)
        prefs.edit().putFloat("volume",volume).apply()
        reconcile()
    }
    fun update(world: WorldState?, isRunning: Boolean) {
        if (closed) return
        seed=world?.seed; running=isRunning
        scene=world?.let { AcousticScene.from(it,previousWorld) } ?: AcousticScene()
        previousWorld=world
        reconcile()
    }
    private fun reconcile() {
        if (closed) return
        val wants=mutable.value.enabled && mutable.value.volume>0f && resumed && running && seed!=null && !failed
        if (!wants) {
            stopStream(); abandon()
            if (!failed) mutable.value=mutable.value.copy(active=false,message=when {
                !mutable.value.enabled -> "Som desativado"
                mutable.value.volume==0f -> "Volume silenciado"
                !resumed -> "Som suspenso fora do aplicativo"
                !running -> "Som acompanha a pausa da biosfera"
                else -> "Aguardando a biosfera"
            })
            return
        }
        if (!focusRequested && !focusBlocked) {
            focusRequested=true
            focusHeld=manager.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) { focusBlocked=true; focusRequested=false }
        }
        if (!focusHeld) {
            stopStream(); mutable.value=mutable.value.copy(active=false,message="Som suspenso por outro aplicativo")
            return
        }
        val token=++revision
        val outputLease=lease
        val start=!requested
        requested=true
        val volume=mutable.value.volume*duck
        val observation=scene
        val worldSeed=seed!!
        AudioOutput.submit(outputLease) {
            val result=if (!NativeAudio.available) -1 else {
                NativeAudio.update(volume,observation.left,observation.right,observation.mastery,observation.social,observation.weather,observation.tension,observation.milestone,PluginRegistry.audioRootMultiplier())
                if (start) NativeAudio.start(worldSeed) else NativeAudio.update(volume,observation.left,observation.right,observation.mastery,observation.social,observation.weather,observation.tension,observation.milestone)
            }
            main.post {
                if (!closed && token==revision) {
                    if (result==0) mutable.value=mutable.value.copy(active=true,message="Ambiência nativa ativa")
                    else {
                        failed=true; stopStream(); abandon()
                        mutable.value=mutable.value.copy(active=false,message="Áudio indisponível. Desative e ative o som para tentar novamente.")
                    }
                }
            }
        }
    }
    private fun stopStream() {
        ++revision
        if (requested) { requested=false; AudioOutput.submit(lease) { if (NativeAudio.available) NativeAudio.stop() } }
        mutable.value=mutable.value.copy(active=false)
    }
    private fun abandon() {
        if (focusRequested) manager.abandonAudioFocusRequest(focus)
        focusRequested=false; focusHeld=false; duck=1f
    }
    override fun onResume(owner: LifecycleOwner) {
        // An older Activity can resume after a newer one has taken the singleton stream.
        lease=AudioOutput.acquire(); requested=false; ++revision
        resumed=true; focusBlocked=false; reconcile()
    }
    override fun onPause(owner: LifecycleOwner) { resumed=false; reconcile(); AudioOutput.release(lease) }
    override fun onDestroy(owner: LifecycleOwner) = close()
    fun close() {
        if (closed) return
        stopStream(); abandon(); closed=true
        app.unregisterReceiver(noisy); lifecycle.removeObserver(this)
        AudioOutput.release(lease)
        previousWorld=null
    }
}
