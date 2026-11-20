import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMind
import com.enhikyy.girassol.physics.NativeSpatial

/** Real native movement/work, outcome-driven FSRS/IRT, earned programs and exact canonical replay. */
object PedagogyIntegration {
    @JvmStatic fun main(args: Array<String>) {
        val initial=WorldActor.genesis();val library=initial.rooms.first { it.id==Activity.STUDY.room }
        val balances=initial.balances.toMutableMap()
        check(WorldActor.transfer(balances,"market","environment","timber",balances.getValue("market/timber")))
        check(WorldActor.transfer(balances,"market","archive","paper",balances.getValue("market/paper")))
        val prepared=initial.copy(balances=balances,rooms=initial.rooms.map { if(it.id==Activity.STUDY.room) it.copy(capacity=2) else it },agents=initial.agents.mapIndexed { i,a -> if(i==0)
            a.copy(x=library.x,z=library.z,activity=Activity.STUDY,needs=Needs(1.0,1.0,1.0,1.0,.01))
            else a.copy(x=11.0,z=11.0,activity=Activity.SLEEP) })
        val rule=AdicoRule.parse("LINEAGE:${prepared.agents[0].lineage} OBRIGADO STUDY WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
        val society=SocietyState(listOf(rule),prepared.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(prepared,it) },emptyList(),emptyList(),emptyList(),SocietyAdicoCliodynamicsEngine.measure(prepared))
        val world=prepared.copy(society=society)
        val checkpoint=Checkpoint(world,0,WorldActor.GENESIS_HASH)
        val actor=WorldActor(checkpoint,8);val reader=WorldActor(checkpoint)
        var actions=emptyList<ActionRecord>();var interpreted=0
        repeat(8) {
            val before=actor.state
            val generated=NativeMind.evaluate(before,actions)
            val frame=generated.copy(decisions=before.agents.associate { it.id to if(it.id=="agent0") Activity.STUDY else Activity.SLEEP })
            val physics=NativeSpatial.advance(before,actor.intentions(cognition=frame))
            val transition=actor.step(cognition=frame,spatial=physics)
            actions=transition.event.actions
            check(actions.first { it.agentId=="agent0" }.let { it.activity==Activity.STUDY && it.outcome=="OK" }) { "tick=${transition.state.tick} actual=$actions job=${transition.state.economy!!.jobs.first { it.agent=="agent0" }}" }
            val job=transition.state.economy!!.jobs.single { it.agent=="agent0" }
            val program=job.skill
            if(program!=null) {
                check(job.phase=="DONE" && job.programCounter==program.operators.size)
                check(job.toils==listOf("RESERVE","MOVE_TO","EXECUTE_WORK","DEPOSIT_LEDGER"))
                interpreted++
            }
            reader.replay(Canonical.event(Canonical.event(transition.event)))
            check(reader.checkpoint()==actor.checkpoint())
        }
        val bank=actor.state.minds.getValue("agent0").memory
        check(bank.valid(actor.state.tick))
        val trace=bank.learningFor(Activity.STUDY)
        check(trace.fsrs!!.modelVersion==45 && trace.item!!.observations>=6 && trace.verifiedExecutions>=6)
        val skill=bank.skills.single { it.action==Activity.STUDY }
        check(skill.executions>=3 && skill.knowledge>=.75 && skill.proofs.size==3 && interpreted>=3)
        check(skill.proofs.all { proof -> proof.stateHash.length==64 && proof.tick<actor.state.tick })
        check(NativeMind.evaluate(actor.state,actions)==NativeMind.evaluate(actor.state,actions))
        println("Production pedagogy JNI: actual job receipts train FSRS4.5/IRT/BKT, verified work programs execute, bounded provenance and exact replay passed; interpreted=$interpreted")
    }
}
