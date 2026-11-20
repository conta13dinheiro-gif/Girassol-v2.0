package com.enhikyy.girassol.cognition

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.runtime.BiosphereHub
import com.enhikyy.girassol.runtime.BiosphereService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal object CognitionWork {
    val lock = Mutex()
    val requests = AtomicLong()
}
data class CognitionStatus(val model: String? = null, val busy: Boolean = false, val automatic: Boolean = false,
    val message: String = "Importe um modelo GGUF de instruções para habilitar a cognição local.",val adapter: String?=null)

/** Foreground-only inference; the service remains the sole world writer. */
class CognitionController(context: Context, private val lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val files = ModelFiles(app)
    private val adapters=ModelFiles(app,"adapters")
    private var adapter: LocalModel?=null
    private val mutable = MutableStateFlow(CognitionStatus(busy = true))
    val status = mutable.asStateFlow()
    private var model: LocalModel? = null
    private var job: Job? = null
    private var automatic: Job? = null
    private var active = false
    private var request = 0L
    private var agentIndex = 0
    init {
        lifecycle.addObserver(this)
        job = scope.launch {
            try {
                model = withContext(Dispatchers.IO) { CognitionWork.lock.withLock { adapter=adapters.current(); files.current() } }
                mutable.value = mutable.value.copy(model = describe(), message = if (model == null) mutable.value.message else "Modelo disponível. Execute uma proposta ou ative o modo automático.")
            } catch (error: Exception) { if (error is CancellationException) throw error; report(error) }
            finally { finishOperation() }
        }
    }
    // Reconcile even if cancellation happens after the atomic install but before IO returns.
    private suspend fun finishOperation() = withContext(NonCancellable) {
        try {
            model = withContext(Dispatchers.IO) { CognitionWork.lock.withLock { adapter=adapters.current(); files.current() } }
            mutable.value = mutable.value.copy(model = describe(), busy = false,adapter=adapter?.let { "LoRA · ${it.bytes/1048576} MB · ${it.hash.take(12)}" })
        } catch (error: Exception) {
            model = null
            mutable.value = mutable.value.copy(model = null, busy = false, message = error.message ?: "Falha ao ler modelo instalado")
        }
    }
    private fun describe() = model?.let { "GGUF · ${it.bytes / 1048576} MB · ${it.hash.take(12)}" }
    private fun report(error: Exception) { mutable.value = mutable.value.copy(message = error.message ?: "Falha na cognição local") }
    fun install(uri: Uri) {
        if (mutable.value.busy) return
        setAutomatic(false)
        mutable.value = mutable.value.copy(busy = true, message = "Copiando e conferindo o GGUF…")
        job = scope.launch {
            try {
                model = withContext(Dispatchers.IO) { CognitionWork.lock.withLock { files.install(uri) } }
                mutable.value = mutable.value.copy(model = describe(), message = "Modelo importado. A compatibilidade será conferida ao executar.")
            } catch (error: Exception) { if (error is CancellationException) throw error; report(error) }
            finally { finishOperation() }
        }
    }
    fun remove() {
        if (mutable.value.busy) return
        setAutomatic(false)
        mutable.value = mutable.value.copy(busy = true)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { CognitionWork.lock.withLock { files.remove() } }
                model = null; mutable.value = CognitionStatus(message = "Modelo removido. O motor lógico continua disponível.")
            } catch (error: Exception) { if (error is CancellationException) throw error; report(error) }
            finally { finishOperation() }
        }
    }
    fun evaluate() {
        if (!active || mutable.value.busy || model == null) return
        val snapshot = BiosphereHub.state.value
        val world = snapshot.world ?: return
        if (!snapshot.running) { mutable.value = mutable.value.copy(message = "Retome a biosfera para enviar propostas."); return }
        val candidates = world.agents.filter { it.acceptsNeuralActivity() && world.minds[it.id]?.decision?.allowLlm!=false }
        if (candidates.isEmpty()) {
            mutable.value = mutable.value.copy(message = "Habitantes priorizando necessidades urgentes. Aguardando recuperação.")
            return
        }
        val installed = model ?: return
        val selectedAdapter=adapter
        mutable.value = mutable.value.copy(busy = true, message = "Avaliando necessidades no dispositivo…")
        job = scope.launch {
            val id = CognitionWork.requests.incrementAndGet()
            request = id
            // Cancellation invokes native cancellation immediately, even while a JNI call occupies IO.
            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { runCatching { NativeCognition.cancel(id) } }
            }
            try {
                val citizen = candidates[(agentIndex++).mod(candidates.size)]
                val action = withContext(Dispatchers.IO) {
                    CognitionWork.lock.withLock {
                        check(Process.is64Bit()) { "Inferência requer processo Android de 64 bits" }
                        val power = app.getSystemService(PowerManager::class.java)
                        val memory = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                        val tier = AllostaticThermalCoupling.fromPlatformThermalStatus(power.currentThermalStatus)
                        val budget = AllostaticThermalCoupling.assess(tier, memory.lowMemory, citizen.needs.energy)
                        check(budget.localLlmAllowed) { if (memory.lowMemory) "RAM indisponível para deliberação" else "Aguarde o aparelho esfriar para inferir" }
                        check(installed.bytes+(selectedAdapter?.bytes ?: 0) <= (memory.availMem-512L*1048576)/2) { "RAM livre insuficiente para este GGUF" }
                        NeuralProtocol.parse(NativeCognition.infer(installed.file.absolutePath,
                            NeuralProtocol.prompt(world, citizen).toByteArray(Charsets.UTF_8), id,selectedAdapter?.file?.absolutePath).toString(Charsets.UTF_8))
                    }
                }
                ensureActive()
                if (active && BiosphereHub.state.value.running) {
                    BiosphereService.propose(app, NeuralProposal(world.seed, world.tick, citizen.id, action,
                        Canonical.hash(Canonical.state(world)), installed.hash,selectedAdapter?.hash))
                    mutable.value = mutable.value.copy(message = "${citizen.name}: proposta ${action.label.lowercase()} enviada. O motor pode recusá-la por necessidade urgente ou validade.")
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; setAutomatic(false); report(error) }
            catch (_: LinkageError) { setAutomatic(false); mutable.value = mutable.value.copy(message = "Backend GGUF indisponível nesta arquitetura") }
            finally { cancellation.cancel(); request = 0; finishOperation() }
        }
    }
    fun installAdapter(uri: Uri) {
        if(mutable.value.busy) return
        setAutomatic(false)
        mutable.value=mutable.value.copy(busy=true,message="Conferindo adaptador LoRA…")
        job=scope.launch {
            try { adapter=withContext(Dispatchers.IO) { CognitionWork.lock.withLock { adapters.install(uri) } }; mutable.value=mutable.value.copy(message="Adaptador instalado. A compatibilidade será verificada na próxima deliberação.") }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { report(e) }
            finally { finishOperation() }
        }
    }
    fun removeAdapter() {
        if(mutable.value.busy) return
        setAutomatic(false); mutable.value=mutable.value.copy(busy=true)
        job=scope.launch {
            try { withContext(Dispatchers.IO) { CognitionWork.lock.withLock { adapters.remove() } }; adapter=null }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { report(e) }
            finally { finishOperation() }
        }
    }
    fun updateRuntime(running: Boolean, thermal: Int, availableMb: Long) {
        if (request > 0 && (!running || thermal >= PowerManager.THERMAL_STATUS_MODERATE || availableMb in 1..255)) {
            setAutomatic(false); cancel()
            mutable.value = mutable.value.copy(message = "Inferência interrompida: pausa, aquecimento ou pouca RAM")
        }
    }
    fun cancel() {
        val id = request
        if (id > 0) runCatching { NativeCognition.cancel(id) }
        if (job?.isActive == true) {
            job?.cancel()
            mutable.value = mutable.value.copy(message = "Operação cancelada")
        }
    }
    fun setAutomatic(enabled: Boolean) {
        automatic?.cancel(); automatic = null
        mutable.value = mutable.value.copy(automatic = enabled && active && model != null)
        if (mutable.value.automatic) automatic = scope.launch {
            while (isActive) { evaluate(); delay(15000) }
        }
    }
    override fun onResume(owner: LifecycleOwner) { active = true }
    override fun onPause(owner: LifecycleOwner) { active = false; setAutomatic(false); cancel() }
    override fun onDestroy(owner: LifecycleOwner) { cancel(); scope.cancel(); lifecycle.removeObserver(this) }
}
