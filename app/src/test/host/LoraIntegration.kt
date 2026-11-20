import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.*
import com.enhikyy.girassol.physics.NativeSpatial
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object LoraIntegration {
    private fun direct(n: Int)=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
    private fun bytes(buffer: ByteBuffer)=ByteArray(buffer.capacity()).also { buffer.duplicate().apply { clear();get(it) } }
    @JvmStatic fun main(args: Array<String>) {
        val state=direct(2592);val input=direct(576);val output=direct(64)
        repeat(64) { input.putDouble(if(it%9==0) .1 else 0.0) };repeat(8) { input.putDouble((it+1)*.1) }
        check(GeaNativeBridge.loraStep(state,input,output,true,1337,-1,0.0))
        repeat(8) { check(abs(output.getDouble(it*8)-.1*(it+1)*.1)<1e-14) }
        repeat(70) { check(GeaNativeBridge.loraStep(state,input,output,false,0,it%8,.2)) }
        val original=bytes(state);val originalOut=bytes(output)
        fun rejected(call: ()->Unit) {
            var failed=false;try { call() } catch(_: IllegalArgumentException) { failed=true }
            check(failed && bytes(state).contentEquals(original) && bytes(output).contentEquals(originalOut))
        }
        rejected { GeaNativeBridge.loraStep(state,input,output,false,0,8,0.0) }
        rejected { GeaNativeBridge.loraStep(state,input,output,false,0,1,Double.NaN) }
        rejected { GeaNativeBridge.loraStep(state.asReadOnlyBuffer(),input,output,false,0,-1,0.0) }
        rejected { GeaNativeBridge.loraStep(state,input,output.asReadOnlyBuffer(),false,0,-1,0.0) }
        rejected { GeaNativeBridge.loraStep(state,ByteBuffer.allocate(576),output,false,0,-1,0.0) }
        val alias=state.duplicate();alias.limit(64);val slice=alias.slice()
        rejected { GeaNativeBridge.loraStep(state,input,slice,false,0,-1,0.0) }
        input.putDouble(0,Double.NaN);rejected { GeaNativeBridge.loraStep(state,input,output,false,0,-1,0.0) };input.putDouble(0,.1)
        val corrupt=direct(2592);corrupt.put(original);corrupt.put(0,0)
        rejected { GeaNativeBridge.loraStep(corrupt,input,output,false,0,-1,0.0) }
        // Actual work receipts, optimizer state in canonical JSON, real PIANO consumer and replay.
        val base=WorldActor.genesis();val room=base.rooms.first { it.id==Activity.STUDY.room }
        val prepared=base.copy(rooms=base.rooms.map { if(it.id==Activity.STUDY.room) it.copy(capacity=6) else it },agents=base.agents.mapIndexed { i,a -> if(i==0) a.copy(x=room.x,z=room.z,activity=Activity.STUDY,needs=Needs(1.0,1.0,1.0,1.0,.01)) else a.copy(x=11.0+i*.2,z=11.0,activity=Activity.SLEEP,needs=Needs(1.0,1.0,1.0,1.0,1.0)) })
        val rule=AdicoRule.parse("LINEAGE:${prepared.agents[0].lineage} OBRIGADO STUDY WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
        val world=prepared.copy(society=SocietyState(listOf(rule),prepared.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(prepared,it) },emptyList(),emptyList(),emptyList(),SocietyAdicoCliodynamicsEngine.measure(prepared)))
        val origin=Checkpoint(world,0,WorldActor.GENESIS_HASH);val actor=WorldActor(origin,8);val replay=WorldActor(origin);val audit=LoraLedgerAudit()
        var actions=emptyList<ActionRecord>();var updates=0;var consumed=0;var anchors=0L;var rejectedForgeries=0
        repeat(42) {
            val before=actor.state;val frozen=Canonical.state(before);val generated=NativeMind.evaluate(before,actions)
            val frame=generated.copy(decisions=before.agents.associate { it.id to if(it.id==before.agents[0].id) Activity.STUDY else Activity.SLEEP })
            check(frame.applicable(before) && generated==NativeMind.evaluate(Canonical.state(frozen),actions) && Canonical.state(before)==frozen)
            val paused=NativeMind.evaluate(before,actions,thermalTier=ThermalTier.MODERATE)
            for((id,mind) in frame.minds) {
                val old=before.minds[id]?.lora;val next=checkNotNull(mind.lora);check(next.valid(before.tick))
                if(old!=null) {
                    check(paused.minds.getValue(id).lora==old)
                    if(next.steps()>old.steps()) {
                        updates++;check(before.agents.first { it.id==id }.memories.any { it.tick==before.tick })
                        check(next.sourceHash==Canonical.hash(frozen) && next.baseHash==LoraAdapterState.baseHash(before.minds.getValue(id)))
                        check(!frame.copy(minds=frame.minds+(id to mind.copy(lora=next.copy(baseHash="f".repeat(64))))).applicable(before))
                    }
                }
                anchors=maxOf(anchors,next.steps()/32)
                if(mind.memory.ripple?.tick!=before.tick) {
                    val data=direct(576);mind.parameters.subList(184,248).forEach(data::putDouble);mind.hidden.forEach(data::putDouble)
                    val checkpoint=direct(2592);checkpoint.put(next.bytes());val values=direct(64)
                    check(GeaNativeBridge.loraStep(checkpoint,data,values,false,0,-1,0.0))
                    repeat(8) { check(abs(values.getDouble(it*8).coerceIn(-64.0,64.0)/8-mind.decision.nativeActionValues[it])<1e-12) }
                    consumed++
                }
            }
            val step=actor.step(cognition=frame,spatial=NativeSpatial.advance(before,actor.intentions(cognition=frame)))
            val trained=frame.minds.entries.firstOrNull { (id,mind) -> before.minds[id]?.lora?.let { old -> mind.lora!!.steps()>old.steps() }==true }
            if(trained!=null) {
                val (id,mind)=trained;val raw=step.event.copy(cognition=frame.copy(minds=frame.minds+(id to mind.copy(lora=mind.lora!!.copy(baseHash="f".repeat(64))))))
                val forged=raw.copy(hash=raw.computedHash());check(forged.valid())
                var rejected=false;try { audit.accept(forged) } catch(_: IllegalStateException) { rejected=true }
                check(rejected);rejectedForgeries++
            }
            audit.accept(step.event);replay.replay(Canonical.event(Canonical.event(step.event)));check(replay.checkpoint()==actor.checkpoint())
            // A restored checkpoint produces the exact same NEXT optimizer update, not just inference.
            val restored=Canonical.state(Canonical.state(actor.state))
            check(NativeMind.evaluate(restored,step.event.actions)==NativeMind.evaluate(actor.state,step.event.actions))
            actions=step.event.actions
        }
        check(updates>=32 && consumed>0 && anchors>=1 && rejectedForgeries>0) { "updates=$updates consumed=$consumed anchors=$anchors perAgent=${actor.state.minds.mapValues { it.value.lora?.steps() }} actions=$actions jobs=${actor.state.economy?.jobs}" }
        println("Production LoRA+ JNI: actual receipt updates=$updates, PIANO readouts=$consumed, EWC anchors=$anchors, rejected rehashed forgeries=$rejectedForgeries; thermal gate, immutable provenance, exact optimizer restore/live-shadow/replay and malformed direct buffers passed")
    }
}
