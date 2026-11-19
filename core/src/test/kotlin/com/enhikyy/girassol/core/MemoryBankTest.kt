package com.enhikyy.girassol.core
import org.junit.Assert.*
import org.junit.Test

class MemoryBankTest {
    @Test fun failureAndSuccessAreRecordedOnceWithLocationAndNormalizedVector() {
        val w=WorldActor.genesis().copy(tick=20)
        val a=w.agents.first().copy(activity=Activity.STUDY,memories=listOf(Memory(20,20,Activity.STUDY,.15)))
        val bank=MemoryBank().observe(w,a,ActionRecord(a.id,a.activity,"OK"))
        assertEquals(1,bank.episodes.size); assertEquals(a.x,bank.episodes.single().x,0.0)
        assertTrue(bank.valid(20)); assertEquals(bank,bank.observe(w,a,ActionRecord(a.id,a.activity,"OK")))
        val failed=MemoryBank().observe(w,a.copy(memories=emptyList()),ActionRecord(a.id,a.activity,"PRECONDITION_FAILED"))
        assertTrue(failed.episodes.single().reward<0); assertTrue(failed.valid(20))
    }
    @Test fun sleepDistillsBeforeForgettingAndKeepsConsolidatedKnowledge() {
        val w=WorldActor.genesis(); val a=w.agents.first()
        val old=Episode(1,1,Activity.BUILD,-.15,a.x,a.z,-.3,.2,MemoryBank.embedding(a))
        val bank=MemoryBank(episodes=listOf(old)).observe(w.copy(tick=30000),a.copy(activity=Activity.SLEEP,acting=true),null)
        assertTrue(bank.episodes.isEmpty()); assertEquals(-.15,bank.reflections.single().expectedReward,1e-10)
        assertEquals(1L,bank.reflections.single().sourceEvent); assertTrue(bank.valid(30000))
    }
    @Test fun onlyValidatedTechniquesAreSharedAndTheirProvenanceSurvivesSerialization() {
        val w=WorldActor.genesis().copy(tick=12); val a=w.agents.first().copy(activity=Activity.STUDY,
            memories=(1L..3L).map { Memory(if(it==3L) 12 else it,it,Activity.STUDY,.15) })
        val teacher=MemoryBank().observe(w,a,ActionRecord(a.id,a.activity,"OK"))
        assertEquals(3,teacher.procedures.single().successes)
        val student=MemoryBank().learnFrom(a.id,teacher,12)
        assertEquals(a.id,student.procedures.single().teacher)
        val mind=MindState.initial(w,a).copy(memory=student)
        val saved=w.copy(minds=mapOf(a.id to mind))
        assertEquals(saved,Canonical.state(Canonical.state(saved)))
    }
    @Test fun malformedBankCannotEnterCanonicalFrame() {
        val w=WorldActor.genesis().copy(tick=1); val a=w.agents.first()
        val invalid=MemoryBank(episodes=listOf(Episode(1,1,Activity.STUDY,.1,a.x,a.z,0.0,.5,List(16) { 0f })))
        assertFalse(MindState.initial(w,a).copy(memory=invalid).valid(1))
    }
    @Test fun episodicVectorsDoNotAliasPublishedFrames() {
        val w=WorldActor.genesis().copy(tick=1); val a=w.agents.first()
        val embedding=MemoryBank.embedding(a).toMutableList()
        val memory=MemoryBank(episodes=listOf(Episode(1,1,a.activity,.1,a.x,a.z,.2,.3,embedding)))
        val actor=WorldActor(Checkpoint(w,1,WorldActor.GENESIS_HASH),4)
        val frame=CognitiveFrame(1,Canonical.hash(Canonical.state(w)),w.agents.associate { it.id to MindState.initial(w,it).copy(memory=if(it.id==a.id) memory else MemoryBank()) },w.agents.associate { it.id to Activity.STUDY })
        val t=actor.step(cognition=frame); val bytes=Canonical.event(t.event); val state=Canonical.state(t.state)
        embedding[0]=Float.NaN
        assertEquals(bytes,Canonical.event(t.event)); assertEquals(state,Canonical.state(t.state)); assertTrue(t.event.valid())
    }

}
