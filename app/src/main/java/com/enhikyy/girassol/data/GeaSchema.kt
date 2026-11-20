package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.enhikyy.girassol.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Schema migration participates in SQLiteOpenHelper's transaction. No journal is rewritten. */
internal object GeaSchema {
    val tables = listOf("rsi_promotion_ledger", "subjective_beliefs", "ecological_niche_structures",
        "tsetlin_automata_blobs", "adico_institutions", "double_entry_ledger",
        "bkt_fsrs_mastery", "voyager_skill_library")

    fun create(context: Context, db: SQLiteDatabase) {
        context.assets.open("database/gea-v5.sql").bufferedReader().use { reader ->
            reader.readText().split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
        }
    }

    /** Derived records commit with their canonical event, never before it. */
    fun record(db: SQLiteDatabase, transition: Transition, previous: WorldState?) {
        val event = transition.event
        event.cognition?.evolution?.let { revision ->
            val payload = Json.encodeToString(revision)
            db.insertOrThrow("rsi_promotion_ledger", null, ContentValues().apply {
                put("event_id", event.eventId); put("rules_version", revision.rulesVersion)
                if (revision.sourceTick == 0L) putNull("source_event_id") else put("source_event_id", revision.sourceTick)
                put("payload", payload); put("digest", Canonical.hash(payload))
            })
        }
        for ((agent, mind) in transition.state.minds) {
            val old = previous?.minds?.get(agent)?.memory?.learning.orEmpty().associateBy { it.action }
            for (trace in mind.memory.learning) {
                if (old[trace.action] == trace) continue
                check(trace.valid(event.tick))
                db.insertOrThrow("bkt_fsrs_mastery", null, ContentValues().apply {
                    put("event_id", event.eventId); put("agent_id", agent); put("skill_id", trace.action.name)
                    put("knowledge", trace.knowledgeProbability); put("difficulty", trace.difficulty)
                    put("stability", trace.stabilityTicks); put("theta", trace.theta)
                    put("reviews", trace.reviews); put("last_review_tick", trace.lastReviewTick)
                })
            }
        }
    }

    fun fork(db: SQLiteDatabase, eventId: Long) {
        for (table in tables) db.execSQL("INSERT INTO $table SELECT * FROM origin.$table WHERE event_id<=?", arrayOf(eventId))
    }

    fun audit(db: SQLiteDatabase) {
        db.rawQuery("SELECT r.payload,r.digest,r.rules_version,r.source_event_id,e.payload FROM rsi_promotion_ledger r JOIN events e ON e.id=r.event_id", null).use { rows ->
            while (rows.moveToNext()) {
                val payload = rows.getString(0)
                check(Canonical.hash(payload) == rows.getString(1)) { "RSI ledger digest mismatch" }
                val revision = Json.decodeFromString<PolicyRevision>(payload)
                check(revision == Canonical.event(rows.getString(4)).cognition?.evolution)
                check(revision.rulesVersion == rows.getInt(2))
                check(if (revision.sourceTick == 0L) rows.isNull(3) else !rows.isNull(3) && revision.sourceTick == rows.getLong(3))
            }
        }
        db.rawQuery("SELECT belief,disbelief,uncertainty,base_rate FROM subjective_beliefs", null).use { rows ->
            while (rows.moveToNext()) {
                SubjectiveOpinion(rows.getDouble(0), rows.getDouble(1), rows.getDouble(2), rows.getDouble(3))
            }
        }
        db.rawQuery(
            "SELECT 1 FROM subjective_beliefs b LEFT JOIN staging_knowledge s ON s.id=b.claim_id AND s.acquisition_hash=b.source_id WHERE s.id IS NULL LIMIT 1",
            null
        ).use { rows -> check(!rows.moveToFirst()) { "Subjective belief has no staged evidence provenance" } }
        db.rawQuery("SELECT automata,digest FROM tsetlin_automata_blobs", null).use { rows ->
            while (rows.moveToNext()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rows.getBlob(0))
                    .joinToString("") { "%02x".format(it) }
                check(digest == rows.getString(1)) { "Tsetlin blob digest mismatch" }
            }
        }
        db.rawQuery("PRAGMA quick_check", null).use {
            check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext()) { "SQLite integrity failure" }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use {
            check(!it.moveToFirst()) { "SQLite provenance reference failure" }
        }
    }
}
