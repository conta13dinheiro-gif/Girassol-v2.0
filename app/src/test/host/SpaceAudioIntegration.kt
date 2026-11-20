import com.enhikyy.girassol.audio.*
import com.enhikyy.girassol.cognition.GeaNativeBridge
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.physics.NativeSpatial
import com.enhikyy.girassol.render.NativeCityMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Exercises the actual consumer and JNI buffers, including rejection before output mutation. */
object SpaceAudioIntegration {
    private fun direct(n: Int)=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
    private fun bytes(b: ByteBuffer)=ByteArray(b.capacity()) { b.get(it) }
    private fun rejected(block: ()->Unit) {
        var rejected=false
        try { block() } catch(_: IllegalArgumentException) { rejected=true }
        check(rejected) { "Invalid acoustic input accepted" }
    }
    @JvmStatic fun main(args: Array<String>) {
        GeaNativeBridge.requireAvailable()
        val state=direct(544);val mono=direct(8192);val stereo=direct(16384)
        repeat(2048) { mono.putFloat(it*4,(sin(it*.071)*.5).toFloat()) }
        fun call(s: ByteBuffer=state,m: ByteBuffer=mono,o: ByteBuffer=stereo,n: Int=2048,r: Int=48000,a: Double=PI/2,reset: Boolean=false) =
            GeaNativeBridge.hrtfProcess(s,m,o,n,r,a,0.0,0.0,reset)
        check(call(reset=true))
        val priorState=bytes(state);val priorAudio=bytes(stereo)
        for(action in listOf<()->Unit>(
            {call(n=2049)}, {call(n=0)}, {call(r=7999)}, {call(a=Double.NaN)},
            {call(s=state.asReadOnlyBuffer())}, {call(o=stereo.asReadOnlyBuffer())},
            {call(m=ByteBuffer.allocate(8192))}, {call(s=direct(543))}, {call(r=44100)}
        )) {
            rejected(action);check(bytes(state).contentEquals(priorState));check(bytes(stereo).contentEquals(priorAudio))
        }
        mono.putFloat(4,Float.NaN);rejected {call()};check(bytes(state).contentEquals(priorState));check(bytes(stereo).contentEquals(priorAudio));mono.putFloat(4,(sin(.071)*.5).toFloat())
        state.putInt(0,0);val corrupt=bytes(state);rejected {call()};check(bytes(state).contentEquals(corrupt));check(bytes(stereo).contentEquals(priorAudio))
        check(call(reset=true));check(call(m=mono.asReadOnlyBuffer()))
        val aliased=direct(16384);val input=aliased.duplicate();input.limit(8192)
        rejected {call(m=input.slice(),o=aliased)}
        val samples=FloatArray(2048) { (sin(it*.071)*.5).toFloat() };val parameters=AcousticParameters(PI/2,0.0,0.0)
        val complete=NativeSpatialAudio().process(samples,0,2048,48000,parameters).copyOf()
        val stream=NativeSpatialAudio();val chunks=stream.process(samples,0,257,48000,parameters).copyOf(514)+stream.process(samples,257,1791,48000,parameters).copyOf(3582)
        check(complete.contentEquals(chunks))
        val changed=stream.process(samples,0,2048,44100,parameters).copyOf()
        check(changed.contentEquals(NativeSpatialAudio().process(samples,0,2048,44100,parameters)))
        val world=WorldActor.genesis();val citizen=world.agents.first()
        val trace=NativeSpatialAudio.trace(world,citizen.id,ListenerPose(citizen.x,1.3,citizen.z+5,0.0))
        check(abs(trace.azimuth)<1e-12 && abs(trace.distance-5)<1e-12 && trace.occlusion in 0.0..1.0)
        val turned=NativeSpatialAudio.trace(world,citizen.id,ListenerPose(citizen.x,1.3,citizen.z+5,PI/2))
        check(abs(turned.azimuth-PI/2)<1e-12 && abs(turned.distance-5)<1e-12)
        val roomBuffer=NativeCityMesh.roomBuffer(world.rooms);val endpoints=direct(56);val result=direct(24)
        repeat(7) { endpoints.putDouble(it*8,0.0) }
        val resultBefore=bytes(result)
        rejected {GeaNativeBridge.acousticTrace(roomBuffer,193,world.seed,endpoints,result)}
        rejected {GeaNativeBridge.acousticTrace(roomBuffer,world.rooms.size,world.seed,endpoints,result.asReadOnlyBuffer())}
        endpoints.putDouble(0,Double.NaN)
        rejected {GeaNativeBridge.acousticTrace(roomBuffer,world.rooms.size,world.seed,endpoints,result)}
        check(bytes(result).contentEquals(resultBefore))
        val intentions=world.agents.associate {it.id to it.activity}
        val spatial=NativeSpatial.advance(world,intentions)
        check(spatial.applicable(world,intentions) && spatial==NativeSpatial.advance(world,intentions))
        val method=NativeSpatial::class.java.getDeclaredMethod("nativeStep",ByteBuffer::class.java,Int::class.javaPrimitiveType,ByteBuffer::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,ByteBuffer::class.java)
        method.isAccessible=true
        val bodies=direct(56);val output=direct(808)
        fun nav(count: Int=world.rooms.size,people: Int=1,o: ByteBuffer=output)=method.invoke(NativeSpatial,roomBuffer,count,bodies,people,world.seed,world.tick+1,o) as Boolean
        check(!nav(people=151) && !nav(count=193) && !nav(o=output.asReadOnlyBuffer()))
        bodies.putDouble(5*8,1.0);check(!nav());check(bytes(output).all {it==0.toByte()})
        println("Production space/audio JNI: consumer chunk/rate equivalence, HRTF invalid/corrupt/readonly/alias atomicity, real city ray/distance/listener yaw, Recast+ORCA actual spatial frame and bounded JNI rejection passed")
    }
}
