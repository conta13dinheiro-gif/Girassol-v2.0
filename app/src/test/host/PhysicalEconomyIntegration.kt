import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.physics.NativeSpatial

/** Production C++ solver must navigate to the actual constructed room, including expiry fallback. */
object PhysicalEconomyIntegration {
    @JvmStatic fun main(args: Array<String>) {
        val initial=WorldActor.genesis()
        val workshop=initial.rooms.first { it.id==Activity.BUILD.room }
        val balances=initial.balances.toMutableMap()
        check(WorldActor.transfer(balances,"market","archive","paper",balances.getValue("market/paper")))
        val world=initial.copy(balances=balances,agents=initial.agents.mapIndexed { i,a ->
            if(i==0) a.copy(x=workshop.x,z=workshop.z,activity=Activity.BUILD,needs=Needs(1.0,1.0,1.0,1.0,.01))
            else a.copy(x=11.0,z=11.0,activity=Activity.SLEEP)
        })
        val actor=WorldActor(Checkpoint(world,0,WorldActor.GENESIS_HASH),WorldActor.CURRENT_RULES_VERSION)
        val reader=WorldActor(Checkpoint(world,0,WorldActor.GENESIS_HASH))
        var construction: Transition?=null
        for(attempt in 0 until 32) {
            val current=actor.state
            val input=CognitiveFrame(current.tick,Canonical.hash(Canonical.state(current)),current.agents.associate { it.id to (current.minds[it.id] ?: MindState.initial(current,it)) },
                current.agents.associate { it.id to if(it.id=="agent0") Activity.BUILD else Activity.SLEEP },economyEnabled=true)
            val physical=NativeSpatial.advance(current,actor.intentions(cognition=input))
            val transition=actor.step(cognition=input,spatial=physical)
            reader.replay(Canonical.event(Canonical.event(transition.event)))
            check(actor.checkpoint()==reader.checkpoint())
            if(transition.event.actions.any { it.agentId=="agent0" && it.activity==Activity.BUILD && it.outcome=="OK" }) {
                construction=transition;break
            }
        }
        val transition=checkNotNull(construction) { "Builder never reached its physical work site" }
        check(transition.event.constructed.size==1 && transition.state.economy!!.tools.single().uses==31)
        val annex=transition.state.rooms.single { it.id==transition.event.constructed.single().room }
        val activity=Activity.entries.first { SmartTerrain.supports(annex,it) }
        var stationed=transition.state.copy(agents=transition.state.agents.map { if(it.id=="agent0") it.copy(x=annex.x,z=annex.z) else it },spatial=emptyMap())
        var reached=false
        for(attempt in 0 until 32) {
            val citizen=stationed.agents[0]
            check(SmartTerrain.target(stationed,citizen,activity).id==annex.id)
            val intentions=stationed.agents.associate { it.id to if(it.id=="agent0") activity else Activity.SLEEP }
            val result=NativeSpatial.advance(stationed,intentions)
            check(result.applicable(stationed,intentions))
            if(result.poses.getValue("agent0").reached) { reached=true;break }
            stationed=stationed.copy(tick=stationed.tick+1,spatial=result.poses,agents=stationed.agents.map { a ->
                val position=result.poses.getValue(a.id).position;a.copy(x=position.x,z=position.z)
            })
        }
        check(reached) { "Native solver never reached constructed annex" }
        val citizen=stationed.agents[0]
        val expired=stationed.copy(tick=stationed.economy!!.niches.single().expires-1)
        check(SmartTerrain.target(expired,citizen,activity).id==activity.room)
        println("Production JNI construction/tool/ledger/replay and actual annex navigation passed")
    }
}
