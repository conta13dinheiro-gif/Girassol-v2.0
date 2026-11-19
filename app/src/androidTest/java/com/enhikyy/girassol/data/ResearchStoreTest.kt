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
}
