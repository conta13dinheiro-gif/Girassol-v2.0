package com.enhikyy.girassol.runtime

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.enhikyy.girassol.MainActivity
import com.enhikyy.girassol.R
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.data.WorldStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Published snapshots only. The service thread owns the actor and SQLite connection. */
data class BiosphereUi(val world: WorldState? = null, val running: Boolean = false, val loading: Boolean = true,
    val neuralResult: String = "Nenhuma proposta neural processada", val speed: Int = 1, val thermal: Int = 0, val availableMb: Long = 0, val error: String? = null, val hash: String = "")
object BiosphereHub {
    internal val mutable = MutableStateFlow(BiosphereUi())
    val state = mutable.asStateFlow()
}
class BiosphereService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var store: WorldStore
    private lateinit var research: com.enhikyy.girassol.research.ResearchCoordinator
    private var actor: WorldActor? = null
    private var running = false
    private var speed = 1
    private val rsi=com.enhikyy.girassol.cognition.RsiCoordinator()
    private var lastActions: List<ActionRecord> = emptyList()
    private var pendingNeural: NeuralProposal? = null
    private var neuralResult = "Nenhuma proposta neural processada"
    private lateinit var wakeLock: PowerManager.WakeLock
    private val prefs by lazy { getSharedPreferences("runtime", MODE_PRIVATE) }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val power = getSystemService(PowerManager::class.java)
                val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
                val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 1000L)
                val proposal = pendingNeural
                pendingNeural = null
                val prior=actor!!.state
                if(thermal>0 || memory.lowMemory) rsi.cancel()
                val revision=rsi.take(prior)
                val mind=research.enrich(prior,com.enhikyy.girassol.cognition.NativeMind.evaluate(prior,lastActions).copy(evolution=revision))
                val candidate=WorldActor(actor!!.checkpoint(),7)
                val spatial=com.enhikyy.girassol.physics.NativeSpatial.advance(prior,candidate.intentions(proposal,mind))
                val transition = candidate.step(proposal,mind,spatial)
                if (proposal != null) neuralResult = if (transition.event.neural != null)
                    "Aceita no ciclo ${transition.state.tick}: ${proposal.agentId} · ${proposal.activity.label}"
                    else "Recusada no ciclo ${transition.state.tick}: necessidade urgente ou proposta inválida"
                store.commit(transition,prior)
                actor=candidate
                lastActions=transition.event.actions
                research.committed(transition)
                research.consider(candidate.checkpoint(),lastActions)
                rsi.consider(prior,transition,thermal==0 && !memory.lowMemory)
                BiosphereHub.mutable.value = BiosphereUi(world = transition.state, running = true, loading = false, neuralResult = neuralResult, speed = speed, thermal = thermal, availableMb = memory.availMem / 1048576, hash = transition.event.hash)
                if (transition.state.tick % 30 == 0L) getSystemService(NotificationManager::class.java).notify(10, notification())
                val delay = if (thermal >= PowerManager.THERMAL_STATUS_SEVERE || memory.lowMemory) 2000L else 500L / speed
                handler.postDelayed(this, delay)
            } catch (e: Exception) { fail(e) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("WorldActor").apply { start() }; handler = Handler(thread.looper)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Girassol:Biosphere")
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("biosphere", "Biosfera em execução", NotificationManager.IMPORTANCE_LOW))
        store = WorldStore(this)
        research=com.enhikyy.girassol.research.ResearchCoordinator(this,handler) { store }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(10, notification())
        handler.post {
            try {
                if (actor == null) { actor = store.load()?.let { WorldActor(it,7) } ?: WorldActor(rulesVersion=7); lastActions=store.lastActions() }
                research.refresh()
                when (intent?.action) {
                    ACTION_DATASET -> {
                        val id=checkNotNull(intent.getStringExtra("researchId"))
                        research.control(id,intent.getBooleanExtra("pinned",false),intent.getBooleanExtra("enabled",true))
                        if(prefs.getBoolean("running",true)) startLoop() else { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId) }
                    }
                    ACTION_RESEARCH -> {
                        research.cancel()
                        val previous=com.enhikyy.girassol.research.ResearchHub.state.value.settings
                        research.configure(previous.copy(automatic=intent.getBooleanExtra("automatic",previous.automatic),wifiOnly=intent.getBooleanExtra("wifi",previous.wifiOnly),quarantine=intent.getBooleanExtra("review",previous.quarantine),quotaMb=intent.getIntExtra("quota",previous.quotaMb)))
                        if(prefs.getBoolean("running",true)) startLoop() else { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId) }
                    }
                    ACTION_LOAD -> {
                        if (prefs.getBoolean("running", true)) startLoop()
                        else {
                            BiosphereHub.mutable.value = BiosphereUi(world = actor!!.state, running = false, loading = false, hash = actor!!.checkpoint().eventHash)
                            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                        }
                    }
                    ACTION_FORK -> {
                        val wasRunning=running
                        rsi.cancel(); research.cancel(); running=false; pendingNeural=null; handler.removeCallbacks(tick)
                        BiosphereHub.mutable.value=BiosphereHub.mutable.value.copy(running=false)
                        try {
                            val name=store.fork(intent.getLongExtra("moment",-1))
                            replaceWorld(name)
                            val point=actor!!.checkpoint()
                            neuralResult="Linha alternativa criada no ciclo ${point.state.tick}"
                            BiosphereHub.mutable.value=BiosphereUi(world=point.state,running=false,loading=false,neuralResult=neuralResult,hash=point.eventHash)
                        } catch(e: Exception) {
                            neuralResult="Bifurcação não criada: ${e.message}"
                            BiosphereHub.mutable.value=BiosphereHub.mutable.value.copy(neuralResult=neuralResult)
                        }
                        if(wasRunning) startLoop() else { if(wakeLock.isHeld) wakeLock.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                    }
                    ACTION_SWITCH -> {
                        val wasRunning=running
                        rsi.cancel(); research.cancel(); running=false; pendingNeural=null; handler.removeCallbacks(tick)
                        BiosphereHub.mutable.value=BiosphereHub.mutable.value.copy(running=false)
                        try {
                            val name=checkNotNull(intent.getStringExtra("database"))
                            require(name in databaseList())
                            replaceWorld(name)
                            BiosphereHub.mutable.value=BiosphereUi(world=actor!!.state,running=false,loading=false,hash=actor!!.checkpoint().eventHash)
                        } catch(e: Exception) {
                            BiosphereHub.mutable.value=BiosphereHub.mutable.value.copy(neuralResult="Não foi possível abrir a linha: ${e.message}")
                        }
                        if(wasRunning) startLoop() else { if(wakeLock.isHeld) wakeLock.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                    }
                    ACTION_IMPORT_DOCUMENT -> {
                        val uri=checkNotNull(intent.data) { "Arquivo de origem ausente" }
                        research.importDocument(uri) {
                            if(!prefs.getBoolean("running",false)) {
                                if(wakeLock.isHeld) wakeLock.release()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf(startId)
                            }
                        }
                        if(prefs.getBoolean("running",false)) startLoop()
                    }
                    ACTION_PROPOSAL -> {
                        if (running) {
                            val activity = intent.getStringExtra("activity")?.let { runCatching { com.enhikyy.girassol.core.Activity.valueOf(it) }.getOrNull() }
                            if (activity != null) pendingNeural = NeuralProposal(intent.getLongExtra("seed", -1),
                                intent.getLongExtra("sourceTick", -1), intent.getStringExtra("agentId") ?: "", activity,
                                intent.getStringExtra("sourceStateHash") ?: "", intent.getStringExtra("modelHash") ?: "",intent.getStringExtra("adapterHash"))
                        } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
                    }
                    ACTION_PAUSE -> {
                        pendingNeural = null; rsi.cancel(); research.cancel()
                        running = false; handler.removeCallbacks(tick); prefs.edit().putBoolean("running", false).apply()
                        if (wakeLock.isHeld) wakeLock.release()
                        BiosphereHub.mutable.value = BiosphereHub.mutable.value.copy(world = actor!!.state, running = false, loading = false)
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                    }
                    ACTION_SPEED -> {
                        speed = (speed % 4) * 2
                        if (speed == 0) speed = 1
                        BiosphereHub.mutable.value = BiosphereHub.mutable.value.copy(speed = speed)
                        if (!running) startLoop()
                    }
                    else -> startLoop()
                }
            } catch (e: Exception) { fail(e) }
        }
        return START_STICKY
    }
    private fun replaceWorld(name: String) {
        val replacement=WorldStore(this,name)
        var installed=false
        val oldName=WorldStore.activeName(this)
        try {
            val point=checkNotNull(replacement.load())
            replacement.audit()
            val next=WorldActor(point,7)
            val actions=replacement.lastActions()
            if(!prefs.edit().putString("world_database",name).commit()) {
                prefs.edit().putString("world_database",oldName).commit()
                error("Não foi possível salvar a linha ativa")
            }
            store.close(); store=replacement; actor=next; lastActions=actions; installed=true
        } finally { if(!installed) replacement.close() }
    }
    private fun startLoop() {
        if (running) return
        running = true; prefs.edit().putBoolean("running", true).apply()
        handler.removeCallbacks(tick); handler.post(tick)
    }
    private fun fail(e: Exception) {
        running = false; rsi.cancel(); research.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        BiosphereHub.mutable.value = BiosphereHub.mutable.value.copy(running = false, loading = false, error = e.message ?: "Falha ao abrir a biosfera")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, BiosphereService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "biosphere").setSmallIcon(R.drawable.ic_girassol).setContentTitle("Girassol · biosfera autônoma")
            .setContentText("Ciclo ${BiosphereHub.state.value.world?.tick ?: 0} · simulação local")
            .setContentIntent(open).addAction(0, "Pausar", pause).setOngoing(true).setSilent(true).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        rsi.close()
        research.close()
        handler.post { running = false; if (wakeLock.isHeld) wakeLock.release(); store.close(); thread.quitSafely() }
        super.onDestroy()
    }
    companion object {
        const val ACTION_IMPORT_DOCUMENT="com.enhikyy.girassol.IMPORT_DOCUMENT"
        fun importDocument(context: Context,uri: android.net.Uri) {
            context.startForegroundService(
                Intent(context,BiosphereService::class.java)
                    .setAction(ACTION_IMPORT_DOCUMENT)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        const val ACTION_DATASET="com.enhikyy.girassol.DATASET"
        fun datasetControl(context: Context,id: String,pinned: Boolean,enabled: Boolean) {
            context.startForegroundService(Intent(context,BiosphereService::class.java).setAction(ACTION_DATASET).putExtra("researchId",id).putExtra("pinned",pinned).putExtra("enabled",enabled))
        }
        const val ACTION_RESEARCH="com.enhikyy.girassol.RESEARCH"
        fun researchSettings(context: Context,settings: com.enhikyy.girassol.research.ResearchSettings) {
            require(settings.valid())
            context.startForegroundService(Intent(context,BiosphereService::class.java).setAction(ACTION_RESEARCH)
                .putExtra("automatic",settings.automatic).putExtra("wifi",settings.wifiOnly).putExtra("review",settings.quarantine).putExtra("quota",settings.quotaMb))
        }
        const val ACTION_PROPOSAL = "com.enhikyy.girassol.PROPOSAL"
        fun propose(context: Context, proposal: NeuralProposal) {
            context.startForegroundService(Intent(context, BiosphereService::class.java).setAction(ACTION_PROPOSAL)
                .putExtra("seed", proposal.seed).putExtra("sourceTick", proposal.sourceTick)
                .putExtra("agentId", proposal.agentId).putExtra("activity", proposal.activity.name)
                .putExtra("sourceStateHash", proposal.sourceStateHash).putExtra("modelHash", proposal.modelHash).putExtra("adapterHash",proposal.adapterHash))
        }
        const val ACTION_SWITCH="com.enhikyy.girassol.SWITCH"
        fun switchWorld(context: Context,database: String) {
            context.startForegroundService(Intent(context,BiosphereService::class.java).setAction(ACTION_SWITCH).putExtra("database",database))
        }
        const val ACTION_FORK="com.enhikyy.girassol.FORK"
        fun fork(context: Context,moment: Long) {
            context.startForegroundService(Intent(context,BiosphereService::class.java).setAction(ACTION_FORK).putExtra("moment",moment))
        }
        const val ACTION_LOAD = "com.enhikyy.girassol.LOAD"
        const val ACTION_PAUSE = "com.enhikyy.girassol.PAUSE"
        const val ACTION_SPEED = "com.enhikyy.girassol.SPEED"
        fun command(context: Context, action: String = "START") {
            context.startForegroundService(Intent(context, BiosphereService::class.java).setAction(action))
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.getSharedPreferences("runtime", Context.MODE_PRIVATE).getBoolean("running", false)) return
        try { BiosphereService.command(context) } catch (_: IllegalStateException) {
            // Some Android/OEM versions require reopening the app before starting foreground work.
            context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putBoolean("resumeRequired", true).apply()
        }
    }
}
