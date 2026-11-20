package com.enhikyy.girassol.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeaMigrationTest {
    @Test fun corruptDatabaseIsPreservedInsteadOfRecreated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "girassol-branch-00000000-0000-0000-0000-000000000005.db"
        context.deleteDatabase(name)
        try {
            WorldStore(context, name).use { it.commit(WorldActor().step()) }
            val file = context.getDatabasePath(name)
            java.io.RandomAccessFile(file, "rw").use { it.write("CORRUPTED HEADER!".toByteArray()) }
            val damaged = file.readBytes()
            var rejected = false
            try {
                WorldStore(context, name).use { it.writableDatabase }
            } catch (_: android.database.sqlite.SQLiteException) { rejected = true }
            assertTrue("Corruption must be surfaced", rejected)
            assertArrayEquals(damaged, file.readBytes())
        } finally { context.deleteDatabase(name) }
    }

    @Test fun committedBalanceChangesProduceBalancedDoubleEntryRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "girassol-branch-00000000-0000-0000-0000-000000000006.db"
        context.deleteDatabase(name)
        try {
            val actor = WorldActor()
            WorldStore(context, name).use { store ->
                repeat(80) {
                    val previous = actor.state
                    val transition = actor.step()
                    store.commit(transition, previous)

                    val expected = linkedMapOf<Pair<String, String>, Long>()
                    val keys = previous.balances.keys + transition.state.balances.keys
                    keys.forEach { key ->
                        val separator = key.lastIndexOf('/')
                        val account = key.substring(0, separator)
                        val resource = key.substring(separator + 1)
                        val delta = (transition.state.balances[key] ?: 0).toLong() -
                            (previous.balances[key] ?: 0).toLong()
                        if (delta != 0L) expected[account to resource] = delta
                    }
                    val actual = linkedMapOf<Pair<String, String>, Long>()
                    store.readableDatabase.rawQuery(
                        "SELECT debit_account,credit_account,resource,amount FROM double_entry_ledger WHERE event_id=?",
                        arrayOf(transition.event.eventId.toString())
                    ).use { rows ->
                        while (rows.moveToNext()) {
                            val debit = rows.getString(0)
                            val credit = rows.getString(1)
                            val resource = rows.getString(2)
                            val amount = rows.getLong(3)
                            assertTrue(amount > 0L)
                            assertNotEquals(debit, credit)
                            actual[debit to resource] = (actual[debit to resource] ?: 0L) - amount
                            actual[credit to resource] = (actual[credit to resource] ?: 0L) + amount
                        }
                    }
                    assertEquals("Ledger must reproduce exact per-account resource deltas", expected, actual)
                }
                assertEquals(80L, store.audit())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun validatedVoyagerSkillsArePersistedWithCanonicalProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "girassol-branch-00000000-0000-0000-0000-000000000007.db"
        context.deleteDatabase(name)
        try {
            val actor = WorldActor(rulesVersion = 4)
            WorldStore(context, name).use { store ->
                repeat(3) {
                    val previous = actor.state
                    val transition = actor.step()
                    store.commit(transition, previous)
                }
                val previous = actor.state
                val minds = previous.agents.associate { citizen ->
                    val initial = MindState.initial(previous, citizen)
                    if (citizen.id != previous.agents.first().id) citizen.id to initial
                    else {
                        val bank = MemoryBank(
                            procedures = listOf(Procedure(Activity.STUDY, 3, 0.4, previous.tick, previous.tick)),
                            learning = listOf(
                                SkillLearningTrace(
                                    action = Activity.STUDY,
                                    knowledgeProbability = 0.80,
                                    reviews = 3,
                                    lastReviewTick = previous.tick
                                )
                            ),
                            reflectedTick = previous.tick
                        )
                        citizen.id to initial.copy(memory = bank)
                    }
                }
                val cognition = CognitiveFrame(
                    sourceTick = previous.tick,
                    sourceHash = Canonical.hash(Canonical.state(previous)),
                    minds = minds,
                    decisions = previous.agents.associate { it.id to it.activity }
                )
                val transition = actor.step(cognition = cognition)
                store.commit(transition, previous)

                store.readableDatabase.rawQuery(
                    "SELECT agent_id,skill_id,executions,knowledge FROM voyager_skill_library WHERE event_id=?",
                    arrayOf(transition.event.eventId.toString())
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(previous.agents.first().id, rows.getString(0))
                    assertEquals(Activity.STUDY.name, rows.getString(1))
                    assertEquals(3, rows.getInt(2))
                    assertEquals(0.80, rows.getDouble(3), 1e-9)
                    assertFalse(rows.moveToNext())
                }
                store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM ecological_niche_structures WHERE event_id=? AND structure_id LIKE 'aurora-cell-%'",
                    arrayOf(transition.event.eventId.toString())
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertTrue("At least one behavioral niche should be archived", rows.getLong(0) > 0L)
                }
                store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM ecological_niche_structures WHERE event_id=? AND structure_id='aurora-projector'",
                    arrayOf(transition.event.eventId.toString())
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(1L, rows.getLong(0))
                }
                store.readableDatabase.rawQuery(
                    "SELECT COUNT(*),COUNT(DISTINCT deontic) FROM adico_institutions WHERE event_id=?",
                    arrayOf(transition.event.eventId.toString())
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("The civic rule catalog should contain seven deduplicated rules", 7L, rows.getLong(0))
                    assertEquals(3L, rows.getLong(1))
                }
                assertEquals(4L, store.audit())
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun upgradesEveryHistoricalSchemaWithoutChangingEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in 1..4) {
            val name = "girassol-branch-00000000-0000-0000-0000-00000000000$version.db"
            context.deleteDatabase(name)
            try {
                val actor = WorldActor()
                WorldStore(context, name).use { store -> store.commit(actor.step()) }
                // Reconstruct each historical schema, retaining a real checkpoint and journal.
                SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    GeaSchema.tables.forEach { db.execSQL("DROP TABLE $it") }
                    if (version < 4) db.execSQL("DROP TABLE knowledge_vectors")
                    if (version < 3) listOf("epistemic_nodes", "research_intents", "external_knowledge_journal", "staging_knowledge", "installed_datasets").forEach { db.execSQL("DROP TABLE $it") }
                    if (version < 2) db.execSQL("DROP TABLE milestones")
                    db.version = version
                }
                WorldStore(context, name).use { store ->
                    assertEquals(actor.checkpoint(), store.load())
                    assertEquals(1L, store.audit())
                    assertEquals(5, store.readableDatabase.version)
                    GeaSchema.tables.forEach { table ->
                        store.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { assertTrue(it.moveToFirst()) }
                    }
                    store.readableDatabase.rawQuery("PRAGMA foreign_keys", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                    store.commit(actor.step())
                    assertEquals(2L, store.audit())
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
