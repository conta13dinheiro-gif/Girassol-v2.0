package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class AssociativeMemoryTest {
    private val initial=WorldActor.genesis()
    private fun episode(id: Long,action: Activity=Activity.STUDY,reward: Double=.5)=initial.agents.first().let {
        Episode(id,id,action,reward,it.x,it.z,.5,.8,MemoryBank.embedding(it,action,reward))
    }
    private fun evidence(world: WorldState,root: String="root")=CognitiveEvidence(Canonical.hash("claim"),Canonical.hash(root),Canonical.hash("dataset"),
        "LOGIC",world.agents[1].id,95,0,WorldActor.GENESIS_HASH)
    private fun trusted(): WorldState=initial.copy(ties=initial.ties.filterNot { it.from=="agent0" && it.to=="agent1" }+SocialTie("agent0","agent1",alpha=99.0,beta=1.0))
    @Test fun averagingIsNormalizedVacuousSafeAndDoesNotCountCopiesAsIndependent() {
        val vacuous=SubjectiveOpinion(0.0,0.0,1.0)
        assertEquals(vacuous,SubjectiveOpinion.averagingFuse(vacuous,vacuous))
        val first=SubjectiveOpinion(.6,.1,.3)
        val second=SubjectiveOpinion(.1,.5,.4)
        val combined=SubjectiveOpinion.averagingFuse(first,second,3.0,1.0)
        assertEquals((3*.6*.4+.1*.3)/(3*.4+.3),combined.belief,1e-12)
        assertEquals((3+1)*.3*.4/(3*.4+.3),combined.uncertainty,1e-12)
        assertEquals(first,SubjectiveOpinion.averagingFuse(first,first))
        assertEquals(vacuous,SubjectiveOpinion.averagingFuse(SubjectiveOpinion(1.0,0.0,0.0),SubjectiveOpinion(0.0,1.0,0.0)))
    }
    @Test fun dependenceIncludesDatasetAndSharedOriginWithTransitiveClosure() {
        val raw=SubjectiveOpinion(.7,0.0,.3)
        fun opinion(root: String,group: String,event: String)=ProvenancedOpinion(Canonical.hash("claim"),Canonical.hash(root),Canonical.hash(group),"LOGIC","agent1",0,Canonical.hash(event),raw,.8,0)
        val a=opinion("a","group1","event1")
        val b=opinion("b","group1","event2")
        val c=opinion("c","group2","event2")
        val fused=ProvenanceImmunity.fuse(listOf(a,b,c),a.claim)!!
        assertEquals(a.discounted().uncertainty,fused.uncertainty,1e-12)
        assertEquals(fused,ProvenanceImmunity.fuse(listOf(c,a,b),a.claim))
        val independent=c.copy(sourceEventHash=Canonical.hash("different"))
        assertTrue(ProvenanceImmunity.fuse(listOf(a,independent),a.claim)!!.uncertainty<fused.uncertainty)
        assertEquals(ProvenanceImmunity.fuse(listOf(a),a.claim),ProvenanceImmunity.fuse(listOf(a,a),a.claim))
    }
    @Test fun admissionRejectsLowTrustAndDuplicateRootsBeforeLearning() {
        val w=trusted();val e=evidence(w)
        val bank=ProvenanceImmunity.admit(w,"agent0",MemoryBank(),e)!!
        assertTrue(bank.valid(0));assertEquals(1,bank.opinions.size)
        assertNull(ProvenanceImmunity.admit(w,"agent0",bank,e))
        val negative=w.copy(ties=listOf(SocialTie("agent0","agent1",alpha=1.0,beta=99.0)))
        assertNull(ProvenanceImmunity.admit(negative,"agent0",MemoryBank(),e))
    }
    @Test fun socialCopiesPreserveRootRawAndDirectRecipientTrust() {
        val w=trusted();val e=evidence(w)
        val original=ProvenanceImmunity.admit(w,"agent0",MemoryBank(),e)!!
        val recipient="agent2"
        val related=w.copy(ties=w.ties+SocialTie(recipient,"agent1",alpha=19.0,beta=1.0))
        val copied=ProvenanceImmunity.learnFrom(related,recipient,MemoryBank(),original)
        val record=copied.opinions.single()
        assertEquals(original.opinions.single().copy(trust=ProvenanceImmunity.trust(related,recipient,"agent1","LOGIC")),record)
        assertEquals(copied,ProvenanceImmunity.learnFrom(related,recipient,copied,original))
        assertTrue(copied.valid(0))
    }
    @Test fun claimsAndRootsHaveHardRetentionBounds() {
        val w=trusted().copy(tick=300)
        var bank=MemoryBank()
        for(claim in 0..35) for(source in 0..10) {
            val e=evidence(w,"root-$source").copy(claim=Canonical.hash("claim-$claim"))
            bank=ProvenanceImmunity.admit(w,"agent0",bank,e) ?: bank
        }
        assertEquals(32,bank.opinions.map { it.claim }.distinct().size)
        assertEquals(256,bank.opinions.size)
        assertTrue(bank.valid(w.tick))
    }
    @Test fun neighborhoodsAreDeterministicPruneStaleEdgesAndBoundDegrees() {
        var bank=MemoryBank()
        for(id in 1L..512L) bank=AssociativeCognitiveMemory.connect(bank.copy(episodes=bank.episodes+episode(id)))
        assertTrue(bank.valid(512));assertTrue(bank.associations.size<=2048)
        assertTrue(bank.associations.groupingBy { it.sourceEvent }.eachCount().values.all { it<=4 })
        assertEquals(bank,AssociativeCognitiveMemory.connect(bank))
        val reduced=AssociativeCognitiveMemory.connect(bank.copy(episodes=bank.episodes.takeLast(30)))
        assertTrue(reduced.valid(512));assertTrue(reduced.associations.all { it.sourceEvent>482 && it.targetEvent>482 })
    }
    @Test fun activeNeighborsPrimeRecallAndDecay() {
        val bank=AssociativeCognitiveMemory.connect(MemoryBank(episodes=listOf(episode(1),episode(2))))
            .copy(workingMemory=WorkingMemoryState(listOf(WorkingMemorySlot(2,1.0,2))))
        assertTrue(AssociativeCognitiveMemory.spreadingActivation(bank,1,2)>.99)
        assertEquals(0.0,AssociativeCognitiveMemory.spreadingActivation(bank,1,1000),0.0)
    }
    @Test fun replayLearnsSemanticsOncePerSourceAndPersistsAllLayers() {
        val episodes=listOf(episode(1,reward=.2),episode(2,reward=.8))
        val bank=AssociativeCognitiveMemory.consolidate(MemoryBank(episodes=episodes),episodes,2,Canonical.hash("before"),Canonical.hash("after"))
        assertEquals(2,bank.semantic.single().observations);assertEquals(.5,bank.semantic.single().expectedReward,1e-12)
        val twice=AssociativeCognitiveMemory.consolidate(bank,episodes,3,Canonical.hash("before2"),Canonical.hash("after2"))
        assertEquals(bank.semantic,twice.semantic)
        assertTrue(twice.valid(3))
        val world=initial.copy(tick=3,minds=mapOf("agent0" to MindState.initial(initial,initial.agents[0]).copy(memory=twice)))
        assertEquals(world,Canonical.state(Canonical.state(world)))
        val historical=Canonical.state(initial)
        assertFalse(historical.contains("associations"));assertFalse(historical.contains("opinions"));assertFalse(historical.contains("ripple"))
        assertFalse(twice.copy(associations=listOf(AssociativeLink(1,999,.9))).valid(3))
    }
}
