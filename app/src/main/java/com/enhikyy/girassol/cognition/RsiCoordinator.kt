package com.enhikyy.girassol.cognition

import android.os.Process
import com.enhikyy.girassol.core.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RsiStatus(val running: Boolean=false,val evaluated: Int=0,val total: Int=0,val message: String="Aguardando uma descoberta ou crise.",val sourceTick: Long?=null)
internal object RsiHub { val mutable=MutableStateFlow(RsiStatus()); val state=mutable.asStateFlow() }
internal class RsiCoordinator(private val record: (RsiTrial)->Unit) : AutoCloseable {
    private val generation=AtomicLong()
    private val executor=Executors.newSingleThreadExecutor { task->Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() },"Girassol-Shadow-Replay") }
    private var future: Future<*>?=null
    @Volatile private var pending: PolicyRevision?=null
    private var queued: Transition?=null
    private var finished=CountDownLatch(0)
    private var closed=false
    @Synchronized fun consider(previous: WorldState,transition: Transition,allow: Boolean) {
        if(closed) return
        val causes=Milestones.detect(previous,transition).filter { it.kind in setOf("knowledge","crisis","architecture","culture") }
        if(causes.isNotEmpty()) queued=transition
        if(!allow || future?.isDone==false || pending!=null) return
        val trigger=queued ?: return
        queued=null
        val token=generation.get(); val origin=Checkpoint(trigger.state,trigger.event.eventId,trigger.event.hash)
        RsiHub.mutable.value=RsiStatus(true,message="Comparando políticas em mundos isolados.",sourceTick=origin.state.tick)
        val completion=CountDownLatch(1); finished=completion
        future=executor.submit {
            try {
                val result=ShadowReplay.search(origin,trigger.event.actions,{ generation.get()!=token }, { done,total->
                    synchronized(this@RsiCoordinator) { if(generation.get()==token) RsiHub.mutable.value=RsiHub.mutable.value.copy(evaluated=done,total=total) }
                },record)
                synchronized(this@RsiCoordinator) { if(generation.get()==token) {
                    pending=result.revision
                    RsiHub.mutable.value=RsiHub.mutable.value.copy(running=false,evaluated=result.evaluated,message=result.message)
                } }
            } catch(_: CancellationException) { /* Cancellation is published by the owner. */ }
            catch(e: Exception) { synchronized(this@RsiCoordinator) { if(generation.get()==token) RsiHub.mutable.value=RsiHub.mutable.value.copy(running=false,message="Experimento interrompido: ${e.message}") } }
            finally { completion.countDown() }
        }
    }
    @Synchronized fun take(world: WorldState): PolicyRevision? { val value=pending; pending=null; return value?.takeIf { it.rulesVersion==WorldActor.CURRENT_RULES_VERSION && it.applicable(world) } }
    fun cancel() {
        val completion=synchronized(this) {
            generation.incrementAndGet(); pending=null; queued=null
            RsiHub.mutable.value=RsiHub.mutable.value.copy(running=false,message="Experimento suspenso; nenhuma alteração aplicada.")
            finished
        }
        // Cooperative cancellation closes the current durable proposal before its database can close.
        check(completion.await(30,TimeUnit.SECONDS)) { "RSI worker has not released its timeline" }
    }
    override fun close() { synchronized(this) { closed=true }; cancel(); executor.shutdownNow() }
}
