import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMind
import com.enhikyy.girassol.cognition.NativeMemory

/** Uses production JNI, real native parameter changes, canonical events and the live admission path. */
object MemoryIntegration {
    private fun sleeping(): WorldState {
        val base=WorldActor.genesis().copy(tick=40)
        val agents=base.agents.map { it.copy(activity=Activity.SLEEP,acting=true) }
        val minds=agents.associate { citizen ->
            val episodes=(1L..16L).map { id ->
                val action=Activity.entries[(id%8).toInt()]
                Episode(id,id,action,if(id%3L==0L) -.3 else .6,citizen.x,citizen.z,.5,.8,MemoryBank.embedding(citizen,action,.6))
            }
            citizen.id to MindState.initial(base,citizen).copy(memory=MemoryBank(episodes=episodes))
        }
        return base.copy(agents=agents,minds=minds)
    }
    @JvmStatic fun main(args: Array<String>) {
        val world=sleeping()
        val first=NativeMind.evaluate(world)
        check(first.applicable(world))
        check(first==NativeMind.evaluate(world)) { "SWR is not deterministic" }
        val replayed=first.minds.values.filter { it.memory.ripple!=null }
        check(replayed.size==2 && replayed.sumOf { it.memory.ripple!!.pulses }<=24)
        for(mind in replayed) {
            val trace=mind.memory.ripple!!
            check(trace.before!=trace.after && trace.pulses in 1..12)
            check(mind.memory.semantic.isNotEmpty() && mind.memory.valid(world.tick))
        }
        val hot=NativeMind.evaluate(world,thermalTier=ThermalTier.MODERATE)
        check(hot.minds.values.all { it.memory.ripple==null })
        val gated=world.copy(minds=first.minds)
        val second=NativeMind.evaluate(gated)
        for(id in first.minds.keys) if(first.minds.getValue(id).memory.ripple!=null) {
            check(second.minds.getValue(id).memory.ripple==first.minds.getValue(id).memory.ripple)
            check(second.minds.getValue(id).memory.semantic==first.minds.getValue(id).memory.semantic)
        }
        val cp=Checkpoint(world,world.tick,Canonical.hash("synthetic-origin"))
        val writer=WorldActor(cp,WorldActor.CURRENT_RULES_VERSION);val reader=WorldActor(cp)
        val physics=com.enhikyy.girassol.physics.NativeSpatial.advance(world,writer.intentions(cognition=first))
        val transition=writer.step(cognition=first,spatial=physics)
        reader.replay(Canonical.event(Canonical.event(transition.event)))
        check(reader.checkpoint()==writer.checkpoint())
        // Associative expansion reaches an episode outside native top-1 before bounded reranking.
        val citizen=world.agents.first()
        val query=MemoryBank.embedding(citizen)
        val a=Episode(1,1,citizen.activity,.5,citizen.x,citizen.z,.5,.8,query)
        val b=a.copy(tick=2,eventId=2)
        val bank=MemoryBank(episodes=listOf(a,b))
        val baseline=NativeMemory.recall(bank,citizen,40,1).single().episode.eventId
        val other=if(baseline==1L) 2L else 1L
        val linked=bank.copy(associations=listOf(AssociativeLink(other,baseline,1.0)),workingMemory=WorkingMemoryState(listOf(WorkingMemorySlot(baseline,1.0,40))))
        check(NativeMemory.recall(linked,citizen,40,1).single().episode.eventId==other)
        check(NativeMemory.recall(bank,citizen,40,1).single().episode.eventId==baseline) { "Pooled buffer leaked previous query" }
        // Trust gates the same native study operation used by research/live and isolated experiments.
        val initial=WorldActor.genesis()
        val e=CognitiveEvidence(Canonical.hash("claim"),Canonical.hash("acquisition"),Canonical.hash("dataset"),"LOGIC","agent1",95,0,WorldActor.GENESIS_HASH)
        val old=MindState.initial(initial,initial.agents[0])
        val distrusted=initial.copy(ties=initial.ties.filterNot { it.from=="agent0" && it.to=="agent1" }+SocialTie("agent0","agent1",alpha=1.0,beta=99.0))
        check(NativeMind.assimilate(distrusted,"agent0",old,e,"verified energy observation")==null)
        val trusted=initial.copy(ties=initial.ties.filterNot { it.from=="agent0" && it.to=="agent1" }+SocialTie("agent0","agent1",alpha=99.0,beta=1.0))
        val admitted=checkNotNull(NativeMind.assimilate(trusted,"agent0",old,e,"verified energy observation"))
        check(admitted.parameters!=old.parameters && admitted.memory.opinions.single().root==e.root)
        check(NativeMind.assimilate(trusted,"agent0",admitted,e,"verified energy observation")==null)
        println("Production JNI memory: bounded deterministic SWR changes weights, thermal/cooldown gates, exact replay, associative expansion/pool reuse and provenance-gated study passed")
    }
}
