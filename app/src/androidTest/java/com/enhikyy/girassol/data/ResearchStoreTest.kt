package com.enhikyy.girassol.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.research.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ResearchStoreTest {
    @Test fun acquisitionsAreImmutableBoundToTheJournalAndForkWithoutFutureReviews() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${UUID.randomUUID()}.db";var branch: String?=null
        try { WorldStore(context,name).use { store->
            val actor=WorldActor();store.commit(actor.step(),WorldActor.genesis())
            val point=actor.checkpoint();val who=point.state.agents[0].id
            val intent=EpistemicCurriculumEngine().intent(point.state.seed,point.state.tick,who,Domain.LOGIC,"Impasses de raciocínio",emptyMap())
            val row=DatasetRow(0,"text: An isolated physical system conserves energy.",sha256("text: An isolated physical system conserves energy.".toByteArray()))
            val raw="""{"rows":[{"row":{"text":"An isolated physical system conserves energy."}}]}""".toByteArray()
            val batch=DatasetBatch(DatasetRepo("test/science","abc",1,"cc-by-4.0",listOf("question-answering"),1.0),DatasetSplit("default","train"),intent.queryHash,"https://datasets-server.huggingface.co/rows",0,sha256(raw),raw,listOf(row))
            val tables=store.research();tables.save(ResearchEntry(intent,ResearchPhase.SEARCHING,"search"))
            val candidate=tables.acquire(intent,batch,point,256L*1048576).single()
            assertArrayEquals(raw,tables.restore(candidate.acquisitionHash));assertEquals(10,tables.mastery().size)
            repeat(3) { val before=actor.state;store.commit(actor.step(),before) }
            val first=KnowledgeReview(who,"a".repeat(64),row.digest,Verdict.APPROVE,95)
            tables.review(candidate.copy(researcher=first,verifier=first.copy(agent=point.state.agents[1].id),embedding=listOf(1f,0f),embeddingModel="f".repeat(64)),actor.state.tick,true)
            branch=store.fork(store.moments().last().id)
            WorldStore(context,branch!!).use { copy->
                val historical=copy.research().candidates().single()
                assertNull(historical.researcher);assertNull(historical.verifier)
                assertArrayEquals(raw,copy.research().restore(historical.acquisitionHash));assertEquals(1L,copy.audit())
            }
            try { store.writableDatabase.execSQL("DELETE FROM external_knowledge_journal");fail("immutable evidence deleted") } catch(_: android.database.sqlite.SQLiteException) { }
            tables.audit()
        } } finally { context.deleteDatabase(name);branch?.let(context::deleteDatabase) }
    }
    @Test fun realAcquisitionImmunityAndBeliefProjectionAreAtomicAndAudited() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${UUID.randomUUID()}.db"
        try { WorldStore(context,name).use { store ->
            val actor=WorldActor(rulesVersion=WorldActor.CURRENT_RULES_VERSION)
            // Use actual simulation movement/work rather than injecting a physical study flag.
            for(attempt in 0 until 120) {
                val world=actor.state
                if(world.agents.first().let { it.activity==Activity.STUDY && it.acting }) break
                val frame=CognitiveFrame(world.tick,Canonical.hash(Canonical.state(world)),world.agents.associate { it.id to (world.minds[it.id] ?: MindState.initial(world,it)) },
                    world.agents.associate { it.id to Activity.STUDY })
                val physics=com.enhikyy.girassol.physics.NativeSpatial.advance(world,actor.intentions(cognition=frame))
                store.commit(actor.step(cognition=frame,spatial=physics),world)
            }
            val point=actor.checkpoint();val world=point.state;val who=world.agents[0].id
            assertTrue(world.agents[0].acting && world.agents[0].activity==Activity.STUDY)
            val intent=EpistemicCurriculumEngine().intent(world.seed,world.tick,who,Domain.LOGIC,"Evidence test",emptyMap())
            val text="text: An isolated physical system conserves energy."
            val row=DatasetRow(0,text,sha256(text.toByteArray()))
            val raw="""{"rows":[{"row":{"text":"An isolated physical system conserves energy."}}]}""".toByteArray()
            val batch=DatasetBatch(DatasetRepo("test/science","abc",1,"cc-by-4.0",listOf("question-answering"),1.0),DatasetSplit("default","train"),intent.queryHash,"https://datasets-server.huggingface.co/rows",0,sha256(raw),raw,listOf(row))
            val tables=store.research();tables.save(ResearchEntry(intent,ResearchPhase.SEARCHING,"search"))
            val pending=tables.acquire(intent,batch,point,256L*1048576).single()
            val review=KnowledgeReview(who,"a".repeat(64),row.digest,Verdict.APPROVE,95)
            val candidate=pending.copy(researcher=review,verifier=review.copy(agent=world.agents[1].id),embedding=listOf(1f,0f),embeddingModel="f".repeat(64))
            tables.review(candidate,world.tick,true)
            val old=world.minds.getValue(who)
            val learned=checkNotNull(com.enhikyy.girassol.cognition.NativeMind.assimilate(world,who,old,candidate.cognitiveEvidence(world.tick,point.eventHash),text))
            val metrics=ReplayMetrics(.2,.8,.6,.7)
            val lesson=KnowledgeLesson(candidate.id,candidate.acquisitionHash,row.digest,"LOGIC",who,world.agents[1].id,95,world.tick,metrics,metrics,world.tick,Canonical.hash(Canonical.state(world)),point.eventHash)
            val frame=CognitiveFrame(world.tick,Canonical.hash(Canonical.state(world)),world.minds+(who to learned),world.agents.associate { it.id to Activity.STUDY },lesson=lesson,immunityEnabled=true)
            assertTrue(frame.applicable(world))
            val physics=com.enhikyy.girassol.physics.NativeSpatial.advance(world,actor.intentions(cognition=frame))
            val transition=actor.step(cognition=frame,spatial=physics)
            // Force an actual projection write failure after ResearchTables has updated staging.
            store.writableDatabase.execSQL("CREATE TRIGGER deny_belief BEFORE INSERT ON subjective_beliefs BEGIN SELECT RAISE(ABORT,'test write rejection'); END")
            try { store.commit(transition,world);fail("Belief failure must roll back event and acquisition status") } catch(_: android.database.sqlite.SQLiteException) { }
            assertEquals(point,store.load())
            assertNotNull(tables.approvedFor(who,emptySet()))
            store.writableDatabase.execSQL("DROP TRIGGER deny_belief")
            store.commit(transition,world)
            assertEquals(transition.state.tick,store.audit())
            store.readableDatabase.rawQuery("SELECT belief,disbelief,uncertainty FROM subjective_beliefs",null).use { rows ->
                assertTrue(rows.moveToFirst());val opinion=learned.memory.opinions.single().discounted()
                assertEquals(opinion.belief,rows.getDouble(0),1e-12);assertEquals(opinion.uncertainty,rows.getDouble(2),1e-12)
                assertFalse(rows.moveToNext())
            }
            store.writableDatabase.execSQL("DELETE FROM subjective_beliefs")
            try { store.audit();fail("Missing belief projection must be detected") } catch(_: IllegalStateException) { }
        } } finally { context.deleteDatabase(name) }
    }

}
