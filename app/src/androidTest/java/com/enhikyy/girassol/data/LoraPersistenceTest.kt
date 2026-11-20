package com.enhikyy.girassol.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoraPersistenceTest {
    @Test fun nativeOptimizerReopensResumesAndAuditRejectsMissingOrRehashedCheckpoint() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="girassol-branch-00000000-0000-0000-0000-000000000011.db"
        context.deleteDatabase(name)
        try {
            val actor=WorldActor(rulesVersion=4);var actions=emptyList<ActionRecord>()
            WorldStore(context,name).use { store ->
                repeat(12) {
                    val before=actor.state;val frame=NativeMind.evaluate(before,actions)
                    val transition=actor.step(cognition=frame);actions=transition.event.actions
                    // Exercise DAO's default previous=null: unchanged adapters must not be duplicated.
                    store.commit(transition)
                }
                assertEquals(12L,store.audit())
            }
            val expected=NativeMind.evaluate(actor.state,actions)
            WorldStore(context,name).use { store ->
                val restored=checkNotNull(store.load());assertEquals(actor.checkpoint(),restored)
                assertEquals(expected,NativeMind.evaluate(restored.state,actions))
                val db=store.writableDatabase
                db.rawQuery("SELECT event_id,agent_id,adapter FROM lora_adapter_checkpoints ORDER BY event_id DESC LIMIT 1",null).use { row ->
                    assertTrue(row.moveToFirst());val event=row.getLong(0);val agent=row.getString(1);val original=row.getBlob(2)
                    val damaged=original.copyOf();damaged[32]=(damaged[32].toInt() xor 1).toByte()
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest(damaged).joinToString("") { "%02x".format(it) }
                    db.execSQL("UPDATE lora_adapter_checkpoints SET adapter=?,digest=? WHERE event_id=? AND agent_id=?",arrayOf(damaged,digest,event,agent))
                    var rejected=false;try { store.audit() } catch(_: IllegalStateException) { rejected=true };assertTrue(rejected)
                    val originalDigest=java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
                    db.execSQL("UPDATE lora_adapter_checkpoints SET adapter=?,digest=? WHERE event_id=? AND agent_id=?",arrayOf(original,originalDigest,event,agent))
                    assertEquals(12L,store.audit())
                    db.execSQL("DELETE FROM lora_adapter_checkpoints WHERE event_id=? AND agent_id=?",arrayOf(event,agent))
                    rejected=false;try { store.audit() } catch(_: IllegalStateException) { rejected=true };assertTrue(rejected)
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
