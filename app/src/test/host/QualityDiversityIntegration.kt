import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.*
import com.enhikyy.girassol.physics.NativeSpatial
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object QualityDiversityIntegration {
    private fun candidate(): ByteBuffer {
        val data=ByteBuffer.allocateDirect(224).order(ByteOrder.LITTLE_ENDIAN)
        data.putLong(1).putInt(1).putInt(0).putDouble(.5).putDouble(.5)
        repeat(16) { data.putDouble(.5) };repeat(8) { data.putDouble(it/8.0) };data.clear();return data
    }
    private fun bytes(data: ByteBuffer)=ByteArray(data.capacity()) { data.get(it) }
    private fun boundary() {
        val output=ByteBuffer.allocateDirect(QualityDiversityArchive.WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val input=candidate()
        check(GeaNativeBridge.qdUpdate(output,input,1,1,true))
        val saved=bytes(output)
        fun invalid(block: ()->Unit) {
            try { block();error("invalid QD input accepted") } catch(expected: IllegalArgumentException) { }
            check(saved.contentEquals(bytes(output))) { "invalid input mutated caller state" }
        }
        invalid { GeaNativeBridge.qdUpdate(output,input,0,2,false) }
        invalid { GeaNativeBridge.qdUpdate(output,input,151,2,false) }
        invalid { GeaNativeBridge.qdUpdate(output,input,1,1,false) }
        invalid { GeaNativeBridge.qdUpdate(output,ByteBuffer.allocate(224),1,2,false) }
        invalid { GeaNativeBridge.qdUpdate(output.asReadOnlyBuffer(),input,1,2,false) }
        input.putDouble(16,Double.NaN);invalid { GeaNativeBridge.qdUpdate(output,input,1,2,false) };input.putDouble(16,.5)
        input.putInt(12,1);invalid { GeaNativeBridge.qdUpdate(output,input,1,2,false) };input.putInt(12,0)
        val corrupt=ByteBuffer.allocateDirect(output.capacity());corrupt.put(saved);corrupt.put(0,0)
        try { GeaNativeBridge.qdUpdate(corrupt,input,1,2,false);error("corrupt state accepted") } catch(expected: IllegalArgumentException) { }
        val threads=Executors.newFixedThreadPool(2)
        try {
            val results=threads.invokeAll(List(2) { Callable {
                val state=ByteBuffer.allocateDirect(output.capacity())
                check(GeaNativeBridge.qdUpdate(state,candidate(),1,1,true));QualityDiversityArchive.fromWire(state)
            } }).map { it.get() }
            check(results[0]==results[1])
        } finally { threads.shutdownNow() }
        check(saved.contentEquals(bytes(output)))
    }
    @JvmStatic fun main(args: Array<String>) {
        boundary()
        val initial=WorldActor.genesis();val room=initial.rooms.first { it.id==Activity.STUDY.room }
        val world=initial.copy(rooms=initial.rooms.map { if(it.id==room.id) it.copy(capacity=initial.agents.size) else it },agents=initial.agents.map {
            it.copy(x=room.x,z=room.z,activity=Activity.STUDY,needs=Needs(1.0,1.0,1.0,1.0,.01))
        })
        val rule=AdicoRule.parse("ALL OBRIGADO STUDY WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
        val initialized=world.copy(society=SocietyState(listOf(rule),world.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(world,it) },emptyList(),emptyList(),emptyList(),SocietyAdicoCliodynamicsEngine.measure(world)))
        val origin=Checkpoint(initialized,0,WorldActor.GENESIS_HASH);val actor=WorldActor(origin,8);val reader=WorldActor(origin)
        var actions=emptyList<ActionRecord>();var samples=0L;var expectedSamples=0L
        repeat(10) {
            val before=actor.state
            expectedSamples+=actions.count { record -> record.outcome=="OK" && before.economy?.jobs?.any { it.agent==record.agentId && it.phase=="DONE" && it.receiptTick==before.tick }==true }
            val frame=NativeMind.evaluate(before,actions)
            check(frame==NativeMind.evaluate(before,actions))
            val physics=NativeSpatial.advance(before,actor.intentions(cognition=frame))
            val next=actor.step(cognition=frame,spatial=physics);actions=next.event.actions
            next.state.qualityDiversity?.let { archive ->
                check(archive.valid(next.state.tick));val view=archive.view();check(view.samples==expectedSamples && view.samples>=samples && view.occupied in 1..512);samples=view.samples
            }
            reader.replay(Canonical.event(Canonical.event(next.event)));check(reader.checkpoint()==actor.checkpoint())
        }
        check(samples==expectedSamples && samples>=9) { "samples=$samples expected=$expectedSamples actions=$actions" }
        val settled=actor.state;val first=settled.agents[0];val mind=settled.minds.getValue(first.id)
        // Synthetic extreme policies isolate the actual archive consumer; all other world inputs match.
        fun archived(target: Activity,sign: Double): QualityDiversityArchive {
            val output=ByteBuffer.allocateDirect(QualityDiversityArchive.WIRE_BYTES)
            val input=candidate()
            repeat(8) { input.putDouble(160+it*8,if(it==target.ordinal) sign*8 else -sign*8) }
            check(GeaNativeBridge.qdUpdate(output,input,1,settled.tick-1,true))
            return QualityDiversityArchive.fromWire(output)
        }
        var influenced=false
        for(target in Activity.entries) {
            val up=NativeMind.evaluate(settled.copy(qualityDiversity=archived(target,1.0)),actions)
            val down=NativeMind.evaluate(settled.copy(qualityDiversity=archived(target,-1.0)),actions)
            if(up.decisions!=down.decisions || up.minds.any { (id,state) -> state.decision.topThree!=down.minds.getValue(id).decision.topThree }) { influenced=true;break }
        }
        check(influenced) { "archive failed to influence real workspace ordering" }
        val legacy=mind.copy(behaviors=List(300) { List(6) { .5 } },noveltyAdmissions=null)
        val migration=NativeMind.evaluate(settled.copy(minds=settled.minds+mapOf(first.id to legacy)),actions).minds.getValue(first.id)
        check(migration.behaviors.size<=256 && migration.discoveredBehaviors>=300)
        println("Production QD JNI: transactional invalid inputs, native isolation, real settled producer, occupied-niche policy consumer, bounded novelty count and exact replay passed; samples=$samples")
    }
}
