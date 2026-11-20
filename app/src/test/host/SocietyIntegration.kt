import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMind
import com.enhikyy.girassol.physics.NativeSpatial

/** Real normative admission precedes native routing; fines use conserved stock and replay exactly. */
object SocietyIntegration {
    @JvmStatic fun main(args: Array<String>) {
        val initial=WorldActor.genesis();val workshop=initial.rooms.first { it.id==Activity.BUILD.room }
        val balances=initial.balances.toMutableMap()
        check(WorldActor.transfer(balances,"market","environment","timber",balances.getValue("market/timber")-7))
        check(WorldActor.transfer(balances,"market","archive","paper",balances.getValue("market/paper")))
        val world=initial.copy(balances=balances,agents=initial.agents.mapIndexed { i,a -> if(i==0)
            a.copy(x=workshop.x,z=workshop.z,activity=Activity.BUILD,acting=true,needs=Needs(1.0,1.0,1.0,1.0,.01)) else a.copy(x=11.0,z=11.0,activity=Activity.SLEEP) })
        val cp=Checkpoint(world,0,WorldActor.GENESIS_HASH)
        val actor=WorldActor(cp,WorldActor.CURRENT_RULES_VERSION);val reader=WorldActor(cp)
        val startCoins=world.balances.getValue("agent0/coins")
        repeat(3) { iteration ->
            val before=actor.state
            val minds=before.agents.associate { it.id to (before.minds[it.id] ?: MindState.initial(before,it)) }
            val frame=CognitiveFrame(before.tick,Canonical.hash(Canonical.state(before)),minds,
                before.agents.associate { it.id to if(it.id=="agent0") Activity.BUILD else Activity.SLEEP },economyEnabled=true,societyEnabled=true)
            val admitted=actor.intentions(cognition=frame)
            check(admitted.getValue("agent0")==Activity.STUDY)
            val physics=NativeSpatial.advance(before,admitted)
            val transition=actor.step(cognition=frame,spatial=physics)
            check(transition.event.actions.first { it.agentId=="agent0" }.activity==Activity.STUDY)
            val sanctions=transition.state.society!!.sanctions
            val sanction=sanctions.single { it.agent=="agent0" }
            check(sanction.level==iteration+1 && sanction.paid==iteration+1)
            check(transition.event.transfers.any { it.from=="agent0" && it.to==DoubleEntryEconomyAndStigmergy.COMMONS && it.amount==iteration+1 })
            check(transition.event.institutions.size==if(iteration==0) 3 else 0)
            check(transition.state.initialTotals==world.initialTotals)
            check(WorldActor.violations(transition.state).isEmpty())
            reader.replay(Canonical.event(Canonical.event(transition.event)))
            check(reader.checkpoint()==actor.checkpoint())
        }
        check(actor.state.balances.getValue("agent0/coins")==startCoins-6)
        // Native decisions consume actual institutional state and remain reproducible.
        val first=NativeMind.evaluate(actor.state)
        check(first.applicable(actor.state) && first==NativeMind.evaluate(actor.state))
        println("Production society JNI: ADICO before native routing, graduated conserved fines, institution events, exact replay and deterministic cognition passed")
    }
}
