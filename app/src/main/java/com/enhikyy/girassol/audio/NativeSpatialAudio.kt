package com.enhikyy.girassol.audio

import com.enhikyy.girassol.cognition.GeaNativeBridge
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.render.NativeCityMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ListenerPose(val x: Double=5.5,val y: Double=1.4,val z: Double=4.5,val yaw: Double=0.0)
internal object SpatialListener { @Volatile var pose=ListenerPose() }
internal data class AcousticParameters(val azimuth: Double,val distance: Double,val occlusion: Double)

/** One bounded stream per speech worker. Native state and audio buffers are reused for every chunk. */
internal class NativeSpatialAudio {
    private val state=direct(544)
    private val mono=direct(8192)
    private val stereo=direct(16384)
    val output=FloatArray(4096)
    private var rate=0
    fun process(samples: FloatArray,offset: Int,frames: Int,sampleRate: Int,parameters: AcousticParameters): FloatArray {
        require(offset>=0 && frames in 1..2048 && offset<=samples.size-frames)
        mono.clear();repeat(frames) { mono.putFloat(samples[offset+it]) }
        GeaNativeBridge.requireAvailable()
        check(GeaNativeBridge.hrtfProcess(state,mono,stereo,frames,sampleRate,parameters.azimuth,parameters.distance,parameters.occlusion,rate!=sampleRate))
        rate=sampleRate
        repeat(frames*2) { output[it]=stereo.getFloat(it*4) };return output
    }
    companion object {
        private fun direct(bytes: Int)=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        fun trace(world: WorldState,source: String,listener: ListenerPose=SpatialListener.pose): AcousticParameters {
            val citizen=world.agents.single { it.id==source }
            val input=direct(56);val output=direct(24)
            listOf(citizen.x,(world.spatial[source]?.position?.y ?: 0.0)+1.3,citizen.z,listener.x,listener.y,listener.z,listener.yaw).forEach(input::putDouble)
            GeaNativeBridge.requireAvailable()
            check(GeaNativeBridge.acousticTrace(NativeCityMesh.roomBuffer(world.rooms),world.rooms.size,world.seed,input,output))
            return AcousticParameters(output.getDouble(0),output.getDouble(8),output.getDouble(16))
        }
    }
}
