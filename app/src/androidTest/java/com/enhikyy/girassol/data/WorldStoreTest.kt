package com.enhikyy.girassol.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldStoreTest {
    @Test fun neuralEnvelopePersistsAndReopensWithoutModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("girassol.db")
        try {
            val actor = WorldActor()
            val w = actor.state
            val proposal = NeuralProposal(w.seed, w.tick, w.agents.last().id, Activity.STUDY,
                Canonical.hash(Canonical.state(w)), "a".repeat(64))
            val transition = actor.step(proposal)
            assertEquals(3, transition.event.version)
            WorldStore(context).use { it.commit(transition); assertEquals(1L, it.audit()) }
            WorldStore(context).use { assertEquals(actor.checkpoint(), it.load()); assertEquals(1L, it.audit()) }
        } finally { context.deleteDatabase("girassol.db") }
    }

    @Test fun auditDetectsDamagedMiddleWhileTailStillLoads() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("girassol.db")
        WorldStore(context).use { store ->
            assertEquals(0L,store.audit())
            val actor=WorldActor()
            repeat(12) { store.commit(actor.step()) }
            assertEquals(12L,store.audit())
            store.writableDatabase.execSQL("UPDATE events SET payload='{}' WHERE id=5")
            assertEquals(actor.checkpoint(),store.load())
            try { store.audit(); fail("Corrupt middle must fail audit") } catch (_: Exception) { }
            assertEquals(actor.checkpoint(),store.load())
        }
        context.deleteDatabase("girassol.db")
    }
    @Test fun checkpointSurvivesReopenAndDuplicateCannotReplaceIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("girassol.db")
        val actor = WorldActor()
        val store = WorldStore(context)
        assertNull(store.load())
        repeat(10) { store.commit(actor.step()) }
        store.close()
        val reopened = WorldStore(context)
        val cp = reopened.load()!!
        assertEquals(actor.checkpoint(), cp)
        val t = actor.step(); reopened.commit(t)
        try { reopened.commit(t); fail("Duplicate commit must fail") } catch (_: IllegalStateException) { }
        assertEquals(actor.checkpoint(), reopened.load())
        reopened.writableDatabase.execSQL("UPDATE checkpoint SET digest='invalid' WHERE id=1")
        try { reopened.load(); fail("Corruption must be detected") } catch (_: IllegalStateException) { }
        reopened.close()
        context.deleteDatabase("girassol.db")
    }

    @Test fun realEconomyProjectionsSurviveReopenAndMissingLedgerRowFailsAudit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-00000000-0000-0000-0000-000000000008.db"
        context.deleteDatabase(name)
        try {
            val actor=WorldActor(rulesVersion=WorldActor.CURRENT_RULES_VERSION)
            var actions=emptyList<ActionRecord>()
            WorldStore(context,name).use { store ->
                repeat(24) {
                    val frame=com.enhikyy.girassol.cognition.NativeMind.evaluate(actor.state,actions,List(8) { 0.0 },ThermalTier.NONE_OR_LIGHT)
                    val physics=com.enhikyy.girassol.physics.NativeSpatial.advance(actor.state,actor.intentions(cognition=frame))
                    val transition=actor.step(cognition=frame,spatial=physics)
                    store.commit(transition); actions=transition.event.actions
                }
                assertEquals(24L,store.audit())
                store.readableDatabase.rawQuery("SELECT count(*) FROM double_entry_ledger",null).use { rows ->
                    assertTrue(rows.moveToFirst());assertTrue(rows.getInt(0)>0)
                }
            }
            WorldStore(context,name).use { store ->
                assertEquals(actor.checkpoint(),store.load());assertEquals(24L,store.audit())
                store.writableDatabase.execSQL("DELETE FROM double_entry_ledger WHERE rowid=(SELECT rowid FROM double_entry_ledger LIMIT 1)")
                try { store.audit();fail("Missing projection must be detected") } catch (_: IllegalStateException) { }
                assertEquals(actor.checkpoint(),store.load())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun actualInstitutionsPersistAndAlteredSanctionFailsAudit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-00000000-0000-0000-0000-000000000006.db"
        context.deleteDatabase(name)
        try {
            val actor=WorldActor(rulesVersion=WorldActor.CURRENT_RULES_VERSION)
            var actions=emptyList<ActionRecord>()
            WorldStore(context,name).use { store ->
                repeat(10) {
                    val before=actor.state
                    val frame=com.enhikyy.girassol.cognition.NativeMind.evaluate(before,actions)
                    val physical=com.enhikyy.girassol.physics.NativeSpatial.advance(before,actor.intentions(cognition=frame))
                    val transition=actor.step(cognition=frame,spatial=physical)
                    store.commit(transition,before);actions=transition.event.actions
                }
                assertEquals(10L,store.audit())
                store.readableDatabase.rawQuery("SELECT count(*) FROM adico_institutions",null).use { rows -> assertTrue(rows.moveToFirst());assertEquals(3,rows.getInt(0)) }
            }
            WorldStore(context,name).use { store ->
                assertEquals(actor.checkpoint(),store.load());assertEquals(10L,store.audit())
                store.writableDatabase.execSQL("UPDATE adico_institutions SET or_else='FINE:3' WHERE rowid=(SELECT rowid FROM adico_institutions LIMIT 1)")
                try { store.audit();fail("Altered institutional sanction must fail audit") } catch(_: IllegalStateException) { }
                assertEquals(actor.checkpoint(),store.load())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun verifiedProgramsRollbackWithTheirEventAndDetectAlteration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-${java.util.UUID.randomUUID()}.db"
        try {
            val initial=WorldActor.genesis();val room=initial.rooms.first { it.id==Activity.STUDY.room }
            val stock=initial.balances.toMutableMap()
            check(WorldActor.transfer(stock,"market","environment","timber",stock.getValue("market/timber")))
            check(WorldActor.transfer(stock,"market","archive","paper",stock.getValue("market/paper")))
            val baseline=initial.copy(balances=stock,rooms=initial.rooms.map { if(it.id==Activity.STUDY.room) it.copy(capacity=2) else it },agents=initial.agents.mapIndexed { index,a -> if(index==0)
                a.copy(x=room.x,z=room.z,activity=Activity.STUDY,needs=Needs(1.0,1.0,1.0,1.0,.01)) else a.copy(x=11.0,z=11.0,activity=Activity.SLEEP) })
            val rule=AdicoRule.parse("LINEAGE:${baseline.agents[0].lineage} OBRIGADO STUDY WHEN ALWAYS 0 ELSE FINE 0 QUOTA 0 WINDOW 240")
            val society=SocietyState(listOf(rule),baseline.agents.associate { it.id to SocietyAdicoCliodynamicsEngine.initialOpinion(baseline,it) },emptyList(),emptyList(),emptyList(),SocietyAdicoCliodynamicsEngine.measure(baseline))
            val actor=WorldActor(Checkpoint(baseline.copy(society=society),0,WorldActor.GENESIS_HASH),8)
            var actions=emptyList<ActionRecord>();var injected=false
            WorldStore(context,name).use { store ->
                repeat(8) {
                    val before=actor.state
                    val raw=com.enhikyy.girassol.cognition.NativeMind.evaluate(before,actions)
                    val frame=raw.copy(decisions=before.agents.associate { it.id to if(it.id=="agent0") Activity.STUDY else Activity.SLEEP })
                    val spatial=com.enhikyy.girassol.physics.NativeSpatial.advance(before,actor.intentions(cognition=frame))
                    val transition=actor.step(cognition=frame,spatial=spatial)
                    if(!injected && transition.state.minds.values.any { it.memory.skills.isNotEmpty() }) {
                        val previous=store.load()
                        store.writableDatabase.execSQL("CREATE TRIGGER fail_skill BEFORE INSERT ON voyager_skill_library BEGIN SELECT RAISE(ABORT,'injected'); END")
                        try { store.commit(transition);fail("Program write failure must abort whole event") } catch(_: android.database.sqlite.SQLiteException) { }
                        assertEquals(previous,store.load())
                        store.writableDatabase.execSQL("DROP TRIGGER fail_skill");injected=true
                    }
                    store.commit(transition);actions=transition.event.actions
                }
                assertTrue(injected);assertEquals(8L,store.audit())
                store.readableDatabase.rawQuery("SELECT count(*) FROM voyager_skill_library",null).use { rows -> assertTrue(rows.moveToFirst());assertTrue(rows.getInt(0)>0) }
            }
            WorldStore(context,name).use { reopened ->
                assertEquals(actor.checkpoint(),reopened.load());assertEquals(8L,reopened.audit())
                reopened.writableDatabase.execSQL("UPDATE voyager_skill_library SET program='{}' WHERE event_id=(SELECT min(event_id) FROM voyager_skill_library)")
                try { reopened.audit();fail("Altered program must fail audit") } catch(_: IllegalStateException) { }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldCheckpointLoadsAndNewEventContinuesItsChain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        context.deleteDatabase("girassol.db")
        val statePayload = assets.open("legacy-v1-state.json").bufferedReader().use { it.readText() }
        val oldEventPayload = assets.open("legacy-v1-last-event.json").bufferedReader().use { it.readText().trim() }
        val event = Canonical.event(oldEventPayload)
        val store = WorldStore(context)
        val db = store.writableDatabase
        db.insertOrThrow("events", null, ContentValues().apply { put("id", event.eventId); put("tick", event.tick); put("payload", oldEventPayload) })
        db.insertOrThrow("checkpoint", null, ContentValues().apply { put("id", 1); put("payload", statePayload); put("digest", Canonical.hash(statePayload)); put("event_id", event.eventId); put("event_hash", event.hash) })
        val before = store.load()!!
        val next = WorldActor(before).step()
        assertEquals(2, next.event.version)
        assertEquals(event.hash, next.event.previousHash)
        store.commit(next)
        store.close()
        val reopened = WorldStore(context)
        assertEquals(next.state, reopened.load()!!.state)
        reopened.close()
        context.deleteDatabase("girassol.db")
    }
}
