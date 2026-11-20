package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Immutable sparse representation of the fixed native 8x8x8 archive; canonical event/SQLite state. */
@Serializable data class QualityDiversityArchive(val binary: String) {
    @Transient @Volatile private var cached: QdArchiveView?=null
    fun view(): QdArchiveView = cached ?: QdArchiveView.decode(binary).also { cached=it }
    fun valid(now: Long): Boolean = try { view().tick in 1..now } catch(_: Exception) { false }
    companion object {
        const val CAPACITY=512
        const val HEADER_BYTES=412
        const val CELL_BYTES=245
        const val WIRE_BYTES=HEADER_BYTES+CAPACITY*CELL_BYTES
        const val CANDIDATE_BYTES=224
        private val alphabet="0123456789abcdef"
        fun fromWire(wire: ByteBuffer): QualityDiversityArchive {
            require(wire.capacity()==WIRE_BYTES)
            val source=wire.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val count=source.getInt(24);require(count in 1..CAPACITY)
            val bytes=ByteArray(HEADER_BYTES+count*(CELL_BYTES+2))
            source.position(0);source.get(bytes,0,HEADER_BYTES)
            var offset=HEADER_BYTES
            repeat(CAPACITY) { key ->
                val start=HEADER_BYTES+key*CELL_BYTES
                val occupied=source.get(start).toInt()
                require(occupied in 0..1)
                if(occupied==1) {
                    require(offset+CELL_BYTES+2<=bytes.size)
                    bytes[offset++]=(key and 255).toByte();bytes[offset++]=(key ushr 8).toByte()
                    source.position(start);source.get(bytes,offset,CELL_BYTES);offset+=CELL_BYTES
                } else repeat(CELL_BYTES) { require(source.get(start+it).toInt()==0) }
            }
            require(offset==bytes.size)
            val text=CharArray(bytes.size*2)
            bytes.forEachIndexed { i,b -> val n=b.toInt() and 255;text[i*2]=alphabet[n ushr 4];text[i*2+1]=alphabet[n and 15] }
            return QualityDiversityArchive(String(text)).also { it.view() }
        }
    }
}

/** Validated read-only view: no mutable byte/vector is exposed to other worlds or policy workers. */
class QdArchiveView private constructor(private val bytes: ByteArray,private val offsets: IntArray,
    val samples: Long,val tick: Long) {
    val occupied get()=offsets.size
    val coverage get()=occupied.toDouble()/QualityDiversityArchive.CAPACITY
    private fun buffer()=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun policy(index: Int): List<Double> {
        require(index in offsets.indices)
        val data=buffer();val start=offsets[index]+3+156
        return List(8) { data.getDouble(start+it*8) }
    }
    fun meanFitness(): Double { val data=buffer();return offsets.sumOf { data.getDouble(it+3+12) }/occupied }
    /** Uniform occupied-niche selection is performed by the caller's deterministic RNG. */
    fun actionBias(index: Int): List<Double> = policy(index).map { it*.015 }
    fun writeWire(target: ByteBuffer,zeroes: ByteArray) {
        require(target.isDirect && !target.isReadOnly && target.capacity()==QualityDiversityArchive.WIRE_BYTES && zeroes.size==target.capacity())
        target.clear();target.put(zeroes);target.position(0);target.put(bytes,0,QualityDiversityArchive.HEADER_BYTES)
        val data=buffer()
        offsets.forEach { offset ->
            val key=data.getShort(offset).toInt() and 65535
            target.position(QualityDiversityArchive.HEADER_BYTES+key*QualityDiversityArchive.CELL_BYTES)
            target.put(bytes,offset+2,QualityDiversityArchive.CELL_BYTES)
        }
        target.clear()
    }
    companion object {
        internal fun decode(text: String): QdArchiveView {
            val h=QualityDiversityArchive.HEADER_BYTES;val c=QualityDiversityArchive.CELL_BYTES
            require(text.length in (h+c+2)*2..(h+512*(c+2))*2 && text.length%2==0)
            val bytes=ByteArray(text.length/2)
            for(i in bytes.indices) {
                val one=text[i*2];val two=text[i*2+1]
                require(one in '0'..'9' || one in 'a'..'f');require(two in '0'..'9' || two in 'a'..'f')
                bytes[i]=((one.digitToInt(16) shl 4) or two.digitToInt(16)).toByte()
            }
            val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(data.int==0x31445147 && data.int==1)
            val samples=data.long;val tick=data.long;val count=data.int
            require(samples>0 && tick>0 && count in 1..512 && samples>=count && bytes.size==h+count*(c+2))
            val mean=DoubleArray(16) { data.double };val first=DoubleArray(16) { data.double };val second=DoubleArray(16) { data.double }
            require(mean.all { it.isFinite() && it in 0.0..1.0 } && first.all(Double::isFinite) && second.all(Double::isFinite))
            fun dot(a: DoubleArray,b: DoubleArray)=a.indices.sumOf { a[it]*b[it] }
            require(abs(dot(first,first)-1)<1e-8 && abs(dot(second,second)-1)<1e-8 && abs(dot(first,second))<1e-8)
            var previous=-1
            val offsets=IntArray(count) {
                val offset=data.position();val key=data.short.toInt() and 65535
                require(key in 0..511 && key>previous);previous=key
                require(data.get().toInt()==1);data.long
                require(data.int>0)
                val fitness=data.double;val explicit=data.double
                require(fitness.isFinite() && fitness in -1.0..1.0 && explicit.isFinite() && explicit in 0.0..1.0)
                val trajectory=DoubleArray(16) { data.double };require(trajectory.all { it.isFinite() && it in 0.0..1.0 })
                repeat(8) { val x=data.double;require(x.isFinite() && x in -8.0..8.0) }
                val descriptor=DoubleArray(3) { data.double };require(descriptor.all { it.isFinite() && it in 0.0..1.0 })
                val centered=DoubleArray(16) { trajectory[it]-mean[it] }
                val expected=doubleArrayOf(explicit,1/(1+exp(-dot(first,centered))),1/(1+exp(-dot(second,centered))))
                require(expected.indices.all { abs(expected[it]-descriptor[it])<1e-10 })
                val computed=descriptor.fold(0) { acc,x -> acc*8+min(7,(x*8).toInt()) };require(key==computed)
                offset
            }
            require(!data.hasRemaining())
            return QdArchiveView(bytes,offsets,samples,tick)
        }
    }
}

/** Measured finite trajectory: last32 outcomes, five current needs, x/z and reward. */
object BehavioralDescriptor {
    fun trajectory(world: WorldState,citizen: Citizen,memory: MemoryBank,reward: Double): List<Double> {
        require(reward.isFinite() && reward in -5.0..5.0)
        val recent=memory.episodes.takeLast(32)
        val frequencies=Activity.entries.map { action -> recent.count { it.action==action }.toDouble()/recent.size.coerceAtLeast(1) }
        val n=citizen.needs
        val values=frequencies+listOf(n.energy,n.hydration,n.glucose,n.social,n.curiosity,
            ((citizen.x+24)/48).coerceIn(0.0,1.0),((citizen.z+24)/48).coerceIn(0.0,1.0),(tanh(reward/.2)+1)/2)
        require(world.agents.any { it.id==citizen.id } && values.size==16 && values.all { it.isFinite() && it in 0.0..1.0 })
        return values
    }
    fun cooperativeAxis(trajectory: List<Double>): Double {
        require(trajectory.size==16)
        return listOf(Activity.TALK,Activity.BUILD,Activity.WRITE).sumOf { trajectory[it.ordinal] }.coerceIn(0.0,1.0)
    }
    fun fitness(citizen: Citizen,reward: Double): Double {
        require(reward.isFinite() && reward in -5.0..5.0)
        val n=citizen.needs
        return (.5*listOf(n.energy,n.hydration,n.glucose,n.social,n.curiosity).average()+.3*tanh(reward/.2)+.2).coerceIn(-1.0,1.0)
    }
}
