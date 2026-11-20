package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One caller-owned 3.2KiB workspace per world thread; no persistent native handles. */
internal object NativeLora {
    private class Workspace {
        val state: ByteBuffer=ByteBuffer.allocateDirect(LoraAdapterState.BYTES)
        val sample: ByteBuffer=ByteBuffer.allocateDirect(576).order(ByteOrder.nativeOrder())
        val output: ByteBuffer=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
    }
    private val local=ThreadLocal.withInitial { Workspace() }
    data class Result(val state: LoraAdapterState,val scores: List<Double>)
    fun evaluate(world: WorldState,citizen: Citizen,prior: MindState,current: MindState,tier: ThermalTier,sourceHash: String): Result {
        val buffers=checkNotNull(local.get())
        fun sample(mind: MindState) {
            buffers.sample.clear();mind.parameters.subList(184,248).forEach(buffers.sample::putDouble);mind.hidden.forEach(buffers.sample::putDouble)
        }
        val old=prior.lora
        if(old!=null) { require(old.valid(world.tick));buffers.state.clear();buffers.state.put(old.bytes()) }
        sample(prior)
        val seed=Canonical.stream(world.seed,citizen.id,0,"lora-readout-v1").nextLong()
        if(old==null) check(GeaNativeBridge.loraStep(buffers.state,buffers.sample,buffers.output,true,seed,-1,0.0))
        val receipt=citizen.memories.lastOrNull { it.tick==world.tick }
        val train=old!=null && old.steps()<LoraAdapterState.MAX_STEPS && tier==ThermalTier.NONE_OR_LIGHT &&
            receipt!=null && receipt.tick>old.trainedTick && receipt.eventId==world.tick && receipt.action==citizen.activity &&
            receipt.reward.isFinite() && receipt.reward in -1.0..1.0 && prior.decision.nativeActionValues.size==8
        if(train) check(GeaNativeBridge.loraStep(buffers.state,buffers.sample,buffers.output,false,0,receipt!!.action.ordinal,receipt.reward))
        val bytes=ByteArray(LoraAdapterState.BYTES);buffers.state.clear();buffers.state.get(bytes)
        val checkpoint=if(train) LoraAdapterState(TsetlinState.encode(bytes),world.tick,sourceHash,LoraAdapterState.baseHash(prior))
            else old ?: LoraAdapterState(TsetlinState.encode(bytes))
        // Base plasticity and SWR keep their existing ownership. Adapt the CURRENT readout.
        sample(current);check(GeaNativeBridge.loraStep(buffers.state,buffers.sample,buffers.output,false,0,-1,0.0))
        val scores=List(8) { buffers.output.getDouble(it*8).coerceIn(-64.0,64.0) }
        return Result(checkpoint,scores)
    }
}
