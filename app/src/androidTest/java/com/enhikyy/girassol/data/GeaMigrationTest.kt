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
                }
                val db = store.readableDatabase
                db.rawQuery(
                    "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM double_entry_ledger",
                    null
                ).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertTrue("Resource movements should be journaled", rows.getLong(0) > 0L)
                    assertTrue(rows.getLong(1) > 0L)
                }
                db.rawQuery(
                    "SELECT 1 FROM double_entry_ledger WHERE amount<=0 OR debit_account=credit_account LIMIT 1",
                    null
                ).use { assertFalse("Every posting must have distinct positive counterparties", it.moveToFirst()) }
                db.rawQuery(
                    "SELECT event_id,resource,SUM(amount) FROM double_entry_ledger GROUP BY event_id,resource",
                    null
                ).use { rows ->
                    while (rows.moveToNext()) assertTrue(rows.getLong(2) > 0L)
                }
                assertEquals(80L, store.audit())
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
