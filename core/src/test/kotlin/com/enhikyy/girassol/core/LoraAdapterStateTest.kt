package com.enhikyy.girassol.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LoraAdapterStateTest {
    private fun wire(): ByteArray=ByteBuffer.allocate(2592).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x3141524c);putShort(1);putShort(8);putShort(8);putShort(4);putInt(0);putLong(0);putLong(0)
    }.array()
    @Test fun malformedOptimizerCannotEnterCanonicalState() {
        val bytes=wire();val valid=LoraAdapterState(TsetlinState.encode(bytes));assertTrue(valid.valid(0))
        for(change in listOf<(ByteBuffer)->Unit>({it.putInt(0,0)}, {it.putLong(16,-1)}, {it.putLong(24,1)},
            {it.putDouble(32+32*8,.1)}, {it.putDouble(32+128*8,-.1)}, {it.putDouble(32,Double.NaN)})) {
            val copy=bytes.copyOf();change(ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN));assertFalse(LoraAdapterState(TsetlinState.encode(copy)).valid(2))
        }
        assertFalse(valid.copy(hex=valid.hex.dropLast(2)).valid(0));assertFalse(valid.copy(trainedTick=0).valid(0))
    }
    @Test fun historicalEncodingOmitsAdapterAndActivatedWorldCannotDiscardIt() {
        val initial=WorldActor.genesis();assertFalse(Canonical.state(initial).contains("lora"))
        val mind=MindState.initial(initial,initial.agents[0]).copy(lora=LoraAdapterState(TsetlinState.encode(wire())))
        val world=initial.copy(minds=mapOf(initial.agents[0].id to mind))
        assertEquals(world,Canonical.state(Canonical.state(world)))
        assertThrows(IllegalArgumentException::class.java) { WorldActor(Checkpoint(world,0,WorldActor.GENESIS_HASH),8).step() }
    }
    @Test fun trainingRequiresAnEarlierInitializedOptimizerAndGenuineSourceReceipt() {
        val initial=WorldActor.genesis();val agent=initial.agents[0];val old=MindState.initial(initial,agent).copy(lora=LoraAdapterState(TsetlinState.encode(wire())))
        val receipt=Memory(1,1,agent.activity,.1)
        val world=initial.copy(tick=1,minds=mapOf(agent.id to old),agents=initial.agents.map { if(it.id==agent.id) it.copy(memories=listOf(receipt)) else it })
        val bytes=wire();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16,1)
        val hash=Canonical.hash(Canonical.state(world));val state=LoraAdapterState(TsetlinState.encode(bytes),1,hash,LoraAdapterState.baseHash(old))
        assertTrue(LoraAdapterState.transition(world,agent.id,state,hash))
        assertFalse(LoraAdapterState.transition(world,agent.id,state.copy(sourceHash="f".repeat(64)),hash))
        assertFalse(LoraAdapterState.transition(world,agent.id,state.copy(baseHash="f".repeat(64)),hash))
        assertFalse(LoraAdapterState.transition(world.copy(agents=initial.agents),agent.id,state,hash))
        assertFalse(LoraAdapterState.transition(world.copy(minds=emptyMap()),agent.id,state,hash))
        assertFalse(LoraAdapterState.transition(world,agent.id,null,hash))
    }
}
