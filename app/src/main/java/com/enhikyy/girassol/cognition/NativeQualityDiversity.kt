package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One bounded pool per caller thread; archive ownership is immutable and belongs to each world. */
internal object NativeQualityDiversity {
    data class Candidate(val citizen: Citizen,val mind: MindState,val reward: Double,val neuralValues: List<Double>)
    private class Pool {
        val state=ByteBuffer.allocateDirect(QualityDiversityArchive.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val input=ByteBuffer.allocateDirect(150*QualityDiversityArchive.CANDIDATE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val zeroes=ByteArray(QualityDiversityArchive.WIRE_BYTES)
    }
    private val buffers=ThreadLocal.withInitial { Pool() }
    fun update(world: WorldState,candidates: List<Candidate>): QualityDiversityArchive? {
        if(candidates.isEmpty()) return world.qualityDiversity
        require(candidates.size<=150 && world.tick>0)
        val pool=checkNotNull(buffers.get());val input=pool.input;input.clear()
        candidates.forEach { c ->
            require(c.mind.valid(world.tick) && c.citizen.genome.generation>0 && c.neuralValues.size==8 && c.neuralValues.all { it.isFinite() && it in -8.0..8.0 })
            val trajectory=BehavioralDescriptor.trajectory(world,c.citizen,c.mind.memory,c.reward)
            val id=java.lang.Long.parseUnsignedLong(Canonical.hash(c.citizen.id).take(16),16)
            input.putLong(id).putInt(c.citizen.genome.generation).putInt(0)
            input.putDouble(BehavioralDescriptor.fitness(c.citizen,c.reward)).putDouble(BehavioralDescriptor.cooperativeAxis(trajectory))
            trajectory.forEach(input::putDouble);c.neuralValues.forEach(input::putDouble)
        }
        input.flip()
        val exact=input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val previous=world.qualityDiversity
        if(previous!=null) previous.view().writeWire(pool.state,pool.zeroes)
        else { pool.state.clear();pool.state.put(pool.zeroes);pool.state.clear() }
        check(GeaNativeBridge.qdUpdate(pool.state,exact,candidates.size,world.tick,previous==null))
        return QualityDiversityArchive.fromWire(pool.state)
    }
}
