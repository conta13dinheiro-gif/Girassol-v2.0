package com.enhikyy.girassol.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TemporalWorldStoreTest {
    @Test fun snapshotsForkAnExactVerifiedPrefixWithoutChangingTheSource() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${UUID.randomUUID()}.db"
        var branch: String?=null
        try {
            WorldStore(context,name).use { source->
                val actor=WorldActor()
                repeat(12) { val previous=actor.state; source.commit(actor.step(),previous) }
                val original=source.load()
                val first=source.moments().last()
                val historical=source.moment(first.id)
                assertEquals(1L,historical.state.tick)
                branch=source.fork(first.id)
                WorldStore(context,branch!!).use { copy->
                    assertEquals(historical,copy.load()); assertEquals(1L,copy.audit())
                    val alternative=WorldActor(historical)
                    copy.commit(alternative.step(),historical.state)
                    assertEquals(2L,copy.audit())
                }
                assertEquals(original,source.load()); assertEquals(12L,source.audit())
                source.writableDatabase.execSQL("UPDATE milestones SET payload='{}' WHERE id=?",arrayOf(first.id))
                try { source.moment(first.id); fail("Corrupt historical payload must fail") } catch(_: IllegalStateException) { }
            }
        } finally { context.deleteDatabase(name); branch?.let(context::deleteDatabase) }
    }
    @Test fun schemaOneMigratesAdditivelyAndKeepsTheExistingJournalBytes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${UUID.randomUUID()}.db"
        val t=WorldActor().step(); val payload=Canonical.state(t.state); val event=Canonical.event(t.event)
        try {
            val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
                db.execSQL("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,digest TEXT NOT NULL,event_id INTEGER NOT NULL,event_hash TEXT NOT NULL)")
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY,tick INTEGER NOT NULL UNIQUE,payload TEXT NOT NULL)")
                db.insertOrThrow("events",null,ContentValues().apply { put("id",1);put("tick",1);put("payload",event) })
                db.insertOrThrow("checkpoint",null,ContentValues().apply { put("id",1);put("payload",payload);put("digest",t.event.stateHash);put("event_id",1);put("event_hash",t.event.hash) })
                db.version=1
            }
            WorldStore(context,name).use { store->
                assertEquals(Checkpoint(t.state,1,t.event.hash),store.load()); assertEquals(1L,store.audit())
                store.readableDatabase.rawQuery("SELECT payload FROM events WHERE id=1",null).use { assertTrue(it.moveToFirst()); assertEquals(event,it.getString(0)) }
                assertTrue(store.moments().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun policyPromotionRequiresTheExactSourceEventInThisJournal() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${UUID.randomUUID()}.db"
        try { WorldStore(context,name).use { store->
            val first=WorldActor().step(); store.commit(first,WorldActor.genesis())
            val cp=checkNotNull(store.load()); val w=cp.state
            val baseline=ReplayMetrics(.1,.6,.5,.4); val better=ReplayMetrics(.4,.8,.7,.6)
            val good=PolicyRevision(w.seed,w.tick,Canonical.hash(Canonical.state(w)),cp.eventHash,
                List(8) { 0.0 },List(8) { .1 },baseline,better,baseline,better,256,23)
            fun transition(revision: PolicyRevision): Transition = WorldActor(cp,4).step(cognition=CognitiveFrame(
                w.tick,Canonical.hash(Canonical.state(w)),w.agents.associate { it.id to MindState.initial(w,it) },
                w.agents.associate { it.id to Activity.STUDY },revision))
            try { store.commit(transition(good.copy(sourceEventHash="f".repeat(64))),w); fail("foreign provenance accepted") }
            catch(_: IllegalStateException) { }
            assertEquals(cp,store.load()); assertEquals(1L,store.audit())
            store.commit(transition(good),w); assertEquals(good,store.load()!!.state.evolution); assertEquals(2L,store.audit())
        } } finally { context.deleteDatabase(name) }
    }

}
