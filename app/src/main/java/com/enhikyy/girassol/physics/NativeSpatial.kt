package com.enhikyy.girassol.physics

import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.render.NativeCityMesh
import com.enhikyy.girassol.render.NativeProjection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only the world writer / isolated replay worker calls physics; the UI consumes recorded paths. */
internal object NativeSpatial {
    private const val SUBSTEPS=30
    private const val STRIDE=(8+(SUBSTEPS+1)*3)*8
    private external fun nativeStep(rooms: ByteBuffer,count: Int,bodies: ByteBuffer,people: Int,seed: Long,tick: Long,result: ByteBuffer): Boolean
    private class Buffers(val count: Int) {
        val input=direct(Math.multiplyExact(count,56))
        val output=direct(Math.multiplyExact(count,STRIDE))
    }
    private val buffers=ThreadLocal<Buffers>()
    fun advance(world: WorldState,intentions: Map<String,Activity>): SpatialFrame {
        check(NativeProjection.available)
        val people=world.agents
        require(people.size in 1..150 && world.rooms.size in 1..192)
        val b=buffers.get()?.takeIf { it.count==people.size } ?: Buffers(people.size).also(buffers::set)
        b.input.clear();b.output.clear()
        people.forEach { a->
            val prior=world.spatial[a.id]
            b.input.putDouble(a.x).putDouble(prior?.position?.y ?: 0.0).putDouble(a.z)
                .putDouble(prior?.verticalVelocity ?: 0.0).putDouble(world.rooms.indexOfFirst { it.id==SmartTerrain.target(world,a,intentions.getValue(a.id)).id }.toDouble())
                .putDouble(kotlin.math.sin(prior?.heading ?: 0.0)*minOf(.9,(prior?.distance ?: 0.0)/.5))
                .putDouble(kotlin.math.cos(prior?.heading ?: 0.0)*minOf(.9,(prior?.distance ?: 0.0)/.5))
        }
        check(nativeStep(NativeCityMesh.roomBuffer(world.rooms),world.rooms.size,b.input,people.size,world.seed,world.tick+1,b.output)) { "Falha no solver espacial" }
        val poses=people.mapIndexed { i,a->
            val start=i*STRIDE
            fun value(j: Int)=b.output.getDouble(start+j*8)
            val route=List(SUBSTEPS+1) { j-> SpatialPoint(value(8+j*3),value(9+j*3),value(10+j*3)) }
            // Keep every fixed-step corner. Interpolating an endpoint chord could cross a wall visually.
            val flags=value(7).toInt()
            a.id to SpatialPose(SpatialPoint(value(0),value(1),value(2)),value(3),value(4),value(5),
                if(value(5)>1e-6) value(6) else world.spatial[a.id]?.heading ?: 0.0,(flags and 1)!=0,route,(flags and 2)!=0)
        }.toMap()
        return SpatialFrame(world.tick,Canonical.hash(Canonical.state(world)),world.seed,intentions=intentions.toMap(),poses=poses)
            .also { check(it.applicable(world,intentions)) }
    }
    private fun direct(bytes: Int)=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
