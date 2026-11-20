import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMind
import com.enhikyy.girassol.physics.NativeSpatial
import kotlin.math.abs

/** Acquisitions require actual earlier native work receipts. No skill/practice evidence is seeded. */
object BiologyIntegration {
    @JvmStatic fun main(args: Array<String>) {
        val base=WorldActor.genesis()
        val rooms=base.rooms.map { when(it.id) {
            Activity.TALK.room -> it.copy(x=4.5,z=2.6,depth=2.6,capacity=6)
            Activity.STUDY.room -> it.copy(capacity=6)
            else -> it
        } }
        val salon=rooms.first { it.id==Activity.TALK.room };val library=rooms.first { it.id==Activity.STUDY.room }
        val recipient=base.agents[0].id;val teacher=base.agents[1].id
        val pair=setOf(recipient,teacher)
        val prepared=base.copy(rooms=rooms,agents=base.agents.mapIndexed { i,a -> when(i) {
            0 -> a.copy(x=salon.x,z=salon.z,activity=Activity.TALK,needs=Needs(1.0,1.0,1.0,1.0,1.0))
            1 -> a.copy(x=library.x,z=library.z,activity=Activity.STUDY,needs=Needs(1.0,1.0,1.0,1.0,.01))
            else -> a.copy(x=11.0+i*.1,z=11.0,activity=Activity.SLEEP)
        } },ties=base.ties.map { if(it.from in pair && it.to in pair) it.copy(affinity=.96,domains=mapOf("LOGIC" to DomainBeta(20.0,1.0))) else it })
        val rules=listOf(0 to Activity.TALK,1 to Activity.STUDY,2 to Activity.SLEEP).map { (index,action) ->
            AdicoRule.parse("LINEAGE:${prepared.agents[index].lineage} OBRIGADO ${action.name} WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
        }
        val initial=prepared.copy(society=SocietyState(rules,prepared.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(prepared,it) },emptyList(),emptyList(),emptyList(),SocietyAdicoCliodynamicsEngine.measure(prepared)))
        check(WorldActor.violations(initial).isEmpty()) { WorldActor.violations(initial) }
        val origin=Checkpoint(initial,0,WorldActor.GENESIS_HASH);val actor=WorldActor(origin,8);val reader=WorldActor(origin)
        val ledger=SymbioticLedgerAudit()
        var actions=emptyList<ActionRecord>();var acquired=0;var trials=0;var births=0
        repeat(32) {
            val before=actor.state;val frozen=Canonical.state(before)
            val frame=NativeMind.evaluate(before,actions)
            check(frame.applicable(before));check(frame==NativeMind.evaluate(before,actions));check(Canonical.state(before)==frozen)
            val old=before.minds[recipient];val next=frame.minds.getValue(recipient)
            val added=next.symbioticModules.filter { it !in old?.symbioticModules.orEmpty() }
            if(added.isNotEmpty()) {
                val record=added.single();check(old!=null && record.donor==teacher && record.action==Activity.STUDY)
                val donor=before.minds.getValue(teacher);val start=184+Activity.STUDY.ordinal*8
                repeat(8) { i -> val expected=(1-.25*record.trust)*old.parameters[start+i]+.25*record.trust*donor.parameters[start+i]
                    check(abs(next.parameters[start+i]-expected)<1e-12) { "Native readout lost actual fusion" } }
                check(old.memory.learningFor(Activity.STUDY).verifiedExecutions==next.memory.learningFor(Activity.STUDY).verifiedExecutions)
                check(donor.memory.learningFor(Activity.STUDY).verifiedExecutions>=3)
                check(record.sourceHash==Canonical.hash(frozen))
                for(corrupt in listOf(record.copy(sourceHash="f".repeat(64)),record.copy(beforeHash="f".repeat(64)),record.copy(donorBlockHash="f".repeat(64)),record.copy(afterHash="f".repeat(64))))
                    check(!frame.copy(minds=frame.minds+(recipient to next.copy(symbioticModules=listOf(corrupt)))).applicable(before))
                val citizen=before.agents.first { it.id==recipient }
                check(PedagogyAndDiploidEvolutionEngine.symbiogenesis(before,citizen,old,"FAILED",record.sourceHash)==old)
                val noContact=before.copy(society=before.society!!.copy(contacts=emptyList()))
                check(PedagogyAndDiploidEvolutionEngine.symbiogenesis(noContact,citizen,old,"OK",record.sourceHash)==old)
                val noTrust=before.copy(ties=before.ties.map { if(it.from==recipient && it.to==teacher) it.copy(domains=mapOf("LOGIC" to DomainBeta(1.0,20.0))) else it })
                check(PedagogyAndDiploidEvolutionEngine.symbiogenesis(noTrust,citizen,old,"OK",record.sourceHash)==old)
                acquired++
            }
            val transition=actor.step(cognition=frame,spatial=NativeSpatial.advance(before,actor.intentions(cognition=frame)))
            if(added.isNotEmpty()) {
                val badMind=next.copy(symbioticModules=listOf(added.single().copy(afterHash="f".repeat(64))))
                val raw=transition.event.copy(cognition=frame.copy(minds=frame.minds+(recipient to badMind)))
                val forged=raw.copy(hash=raw.computedHash())
                var rejected=false
                try { ledger.accept(forged) } catch(_: IllegalStateException) { rejected=true }
                check(rejected) { "Rehashed forged fusion accepted" }
            }
            ledger.accept(transition.event)
            actions=transition.event.actions
            val biology=transition.state.biology!!;check(biology.valid(transition.state))
            check(biology.semantics==PopulationSemantics.measure(transition.state))
            trials+=biology.selections.size
            val born=transition.state.genealogy.filter { it.tick==transition.state.tick };births+=born.size
            born.forEach { record ->
                val parents=listOf(record.parentOne,record.parentTwo).map { id -> before.agents.single { it.id==id } }
                val child=transition.state.agents.single { it.id==record.child };check(child.id.length==70)
                for(selector in listOf<(Genome)->Locus>({it.curiosity},{it.sociability})) {
                    val gene=selector(child.genome);val mother=selector(parents[0].genome);val father=selector(parents[1].genome)
                    check((gene.maternal to gene.dominanceA) in listOf(mother.maternal to mother.dominanceA,mother.paternal to mother.dominanceB))
                    check((gene.paternal to gene.dominanceB) in listOf(father.maternal to father.dominanceA,father.paternal to father.dominanceB))
                }
            }
            before.agents.forEach { original -> check(transition.state.agents.single { it.id==original.id }.genome==original.genome) }
            reader.replay(Canonical.event(Canonical.event(transition.event)));check(reader.checkpoint()==actor.checkpoint())
        }
        check(acquired==1 && trials>0 && births>0) { "acquired=$acquired trials=$trials births=$births mind=${actor.state.minds.getValue(teacher).memory.learningFor(Activity.STUDY)}" }
        check(actor.state.agents.size<=150 && WorldActor.CURRENT_RULES_VERSION==8)
        println("Production biology JNI: actual practice -> qualified donor -> functional native readout fusion, authenticated provenance, Baldwin admissions, exact v8 allele/dominance segregation and canonical replay passed; acquisitions=$acquired trials=$trials births=$births")
    }
}
