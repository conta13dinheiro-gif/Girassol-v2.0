package com.enhikyy.girassol.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray

/** Five producers publish once into a tick-local bottleneck. Fixed ordering at the
 * barrier keeps live/shadow deterministic even when producers complete out of order.
 * Two workers, ten queued tasks; overflow executes on the caller to bound memory.
 */
object PianoWorkspace {
    private val workers=ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(10),
        { task -> Thread(task,"Girassol-PIANO").apply { isDaemon=true } },ThreadPoolExecutor.CallerRunsPolicy())
    fun aggregate(vararg modules: () -> List<Double>): List<Double> {
        require(modules.size==5)
        val shared=AtomicReferenceArray<List<Double>>(5)
        val pending=modules.mapIndexed { index, module -> workers.submit(Callable {
            val values=module()
            require(values.size==8 && values.all(Double::isFinite))
            shared.set(index,values.toList())
        }) }
        try {
            pending.forEach { it.get() }
            return List(8) { action -> (0..4).sumOf { shared.get(it)[action] } }
        } finally { pending.forEach { if(!it.isDone) it.cancel(true) } }
    }
}
