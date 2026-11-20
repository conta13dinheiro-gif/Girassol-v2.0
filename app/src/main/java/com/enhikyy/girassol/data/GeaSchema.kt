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
        "bkt_fsrs_mastery", "voyager_skill_library", "rsi_policy_trials")

    fun create(context: Context, db: SQLiteDatabase) {
        context.assets.open("database/gea-v5.sql").bufferedReader().use { reader ->
            reader.readText().split(';').map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
        }
    }

    fun createV6(context: Context,db: SQLiteDatabase) {
        context.assets.open("database/gea-v6.sql").bufferedReader().use { reader ->
            reader.readText().split("-- @statement").map(String::trim).filter(String::isNotEmpty).forEach { db.execSQL(it) }
        }
    }

    fun recordTrial(db: SQLiteDatabase,trial: RsiTrial) {
        require(trial.valid() && trial.sourceTick>0)
        db.beginTransaction()
        try {
            db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(trial.sourceTick.toString())).use { source ->
                check(source.moveToFirst()) { "RSI source is absent from this timeline" }
                val event=Canonical.event(source.getString(0))
                check(event.valid() && event.hash==trial.sourceEventHash && event.stateHash==trial.sourceStateHash)
            }
            if(trial.stage!="PROPOSED") db.rawQuery("SELECT payload FROM rsi_policy_trials WHERE trial_id=? AND stage='PROPOSED'",arrayOf(trial.id)).use { row ->
                check(row.moveToFirst())
                val proposed=Json.decodeFromString<RsiTrial>(row.getString(0))
                check(sameProposal(proposed,trial))
            }
            if(trial.stage=="VERIFIED") db.rawQuery("SELECT payload FROM rsi_policy_trials WHERE trial_id=? AND stage='TRAINED'",arrayOf(trial.id)).use { row ->
                check(row.moveToFirst())
                val trained=Json.decodeFromString<RsiTrial>(row.getString(0))
                check(trained.trainingBaseline==trial.trainingBaseline && trained.trainingCandidate==trial.trainingCandidate)
            }
            val committedTick=db.rawQuery("SELECT event_id FROM checkpoint WHERE id=1",null).use { tip -> check(tip.moveToFirst()); tip.getLong(0) }
            check(committedTick>=trial.sourceTick)
            val payload=Json.encodeToString(trial)
            require(payload.length<=16384)
            db.insertOrThrow("rsi_policy_trials",null,ContentValues().apply {
                put("event_id",committedTick); put("source_event_id",trial.sourceTick); put("trial_id",trial.id); put("stage",trial.stage)
                put("rules_version",trial.rulesVersion); put("payload",payload); put("digest",Canonical.hash(payload))
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun sameProposal(a: RsiTrial,b: RsiTrial) = a.id==b.id && a.seed==b.seed && a.sourceTick==b.sourceTick &&
        a.sourceStateHash==b.sourceStateHash && a.sourceEventHash==b.sourceEventHash && a.rulesVersion==b.rulesVersion &&
        a.ordinal==b.ordinal && a.baseline==b.baseline && a.candidate==b.candidate

    /** A process death leaves a durable proposal, never a fabricated promotion. */
    fun recoverTrials(db: SQLiteDatabase) {
        while(true) {
            val abandoned=mutableListOf<RsiTrial>()
            db.rawQuery("SELECT p.payload FROM rsi_policy_trials p WHERE p.stage='PROPOSED' AND NOT EXISTS (SELECT 1 FROM rsi_policy_trials t WHERE t.trial_id=p.trial_id AND t.stage NOT IN ('PROPOSED','TRAINED')) LIMIT 20",null).use { rows ->
                while(rows.moveToNext()) abandoned+=Json.decodeFromString<RsiTrial>(rows.getString(0))
            }
            if(abandoned.isEmpty()) return
            abandoned.forEach { recordTrial(db,it.copy(stage="CANCELLED",reason="RECOVERED_INTERRUPTION")) }
        }
    }

    /** Derived records commit with their canonical event, never before it. */
    fun record(db: SQLiteDatabase, transition: Transition, previous: WorldState?) {
        val event = transition.event
        event.transfers.forEachIndexed { index,entry ->
            check(entry.valid())
            db.insertOrThrow("double_entry_ledger",null,ContentValues().apply {
                put("event_id",event.eventId); put("entry_id",index); put("debit_account",entry.to)
                put("credit_account",entry.from); put("resource",entry.resource); put("amount",entry.amount)
            })
        }
        event.constructed.forEach { structure ->
            db.insertOrThrow("ecological_niche_structures",null,ContentValues().apply {
                put("event_id",event.eventId); put("structure_id",structure.room); put("owner_id",structure.builder)
                put("expires_tick",structure.expires); put("payload",Json.encodeToString(structure))
            })
        }
        event.cognition?.evolution?.let { revision ->
            val payload = Json.encodeToString(revision)
            db.insertOrThrow("rsi_promotion_ledger", null, ContentValues().apply {
                put("event_id", event.eventId); put("rules_version", revision.rulesVersion)
                if (revision.sourceTick == 0L) {
                    putNull("source_event_id")
                } else {
                    put("source_event_id", revision.sourceTick)
                }
                put("payload", payload); put("digest", Canonical.hash(payload))
            })
        }
        val before=previous ?: if(transition.state.minds.values.any { it.memory.opinions.isNotEmpty() }) {
            db.rawQuery("SELECT payload,digest FROM checkpoint WHERE id=1",null).use { rows ->
                if(rows.moveToFirst()) {
                    val text=rows.getString(0);check(Canonical.hash(text)==rows.getString(1));Canonical.state(text)
                } else null
            }
        } else null
        for ((agent, mind) in transition.state.minds) {
            for(opinion in mind.memory.opinions.filter { it !in before?.minds?.get(agent)?.memory?.opinions.orEmpty() }) {
                verifyOpinion(db,event,agent,opinion)
                val discounted=opinion.discounted()
                db.insertOrThrow("subjective_beliefs",null,ContentValues().apply {
                    put("event_id",event.eventId);put("agent_id",agent);put("claim_id",opinion.claim);put("source_id",opinion.root)
                    put("belief",discounted.belief);put("disbelief",discounted.disbelief);put("uncertainty",discounted.uncertainty);put("base_rate",discounted.baseRate)
                })
            }
            if(mind.tsetlin.hex.isNotEmpty() && mind.tsetlin.hex != previous?.minds?.get(agent)?.tsetlin?.hex) {
                val bytes=mind.tsetlin.bytes()
                db.insertOrThrow("tsetlin_automata_blobs",null,ContentValues().apply {
                    put("event_id",event.eventId);put("agent_id",agent);put("format_version",1);put("automata",bytes)
                    put("digest",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                })
            }
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

    fun auditEconomyEvent(db: SQLiteDatabase,event: CanonicalEvent) {
        db.rawQuery("SELECT entry_id,debit_account,credit_account,resource,amount FROM double_entry_ledger WHERE event_id=? ORDER BY entry_id",arrayOf(event.eventId.toString())).use { rows ->
            var count=0
            while(rows.moveToNext()) {
                check(count<event.transfers.size && rows.getInt(0)==count)
                val expected=event.transfers[count++]
                check(expected.to==rows.getString(1) && expected.from==rows.getString(2) && expected.resource==rows.getString(3) && expected.amount==rows.getInt(4))
            }
            check(count==event.transfers.size) { "Missing double-entry evidence" }
        }
        db.rawQuery("SELECT structure_id,owner_id,expires_tick,payload FROM ecological_niche_structures WHERE event_id=? ORDER BY structure_id",arrayOf(event.eventId.toString())).use { rows ->
            val expected=event.constructed.sortedBy { it.room }; var count=0
            while(rows.moveToNext()) {
                check(count<expected.size)
                val structure=expected[count++]
                check(structure.room==rows.getString(0) && structure.builder==rows.getString(1) && structure.expires==rows.getLong(2) && structure==Json.decodeFromString<EcologicalNicheStructure>(rows.getString(3)))
            }
            check(count==expected.size) { "Missing ecological infrastructure evidence" }
        }
    }

    private fun verifyOpinion(db: SQLiteDatabase,event: CanonicalEvent,agent: String,opinion: ProvenancedOpinion) {
        check(ProvenanceImmunity.valid(listOf(opinion),event.tick) && opinion.receivedTick<event.tick)
        if(opinion.sourceTick==0L) check(opinion.sourceEventHash==WorldActor.GENESIS_HASH)
        else db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(opinion.sourceTick.toString())).use { rows ->
            check(rows.moveToFirst());check(Canonical.event(rows.getString(0)).hash==opinion.sourceEventHash)
        }
        val lesson=event.cognition?.lesson
        if(lesson!=null && lesson.researcher==agent && lesson.rowHash==opinion.claim && lesson.acquisitionHash==opinion.root) {
            check(opinion.sourceAgent==lesson.verifier && opinion.sourceTick==lesson.originTick && opinion.sourceEventHash==lesson.originEventHash)
            // ResearchTables.assimilate already checked original dataset, exact row and reviewer confidence in this transaction.
        } else {
            db.rawQuery("SELECT b.agent_id,e.payload FROM subjective_beliefs b JOIN events e ON e.id=b.event_id WHERE b.claim_id=? AND b.source_id=? AND b.event_id<? ORDER BY b.event_id LIMIT 1",arrayOf(opinion.claim,opinion.root,event.eventId.toString())).use { rows ->
                check(rows.moveToFirst()) { "Opinion lacks an original committed admission" }
                val original=Canonical.event(rows.getString(1)).cognition?.minds?.get(rows.getString(0))?.memory?.opinions?.single { it.claim==opinion.claim && it.root==opinion.root }
                check(original?.copy(trust=opinion.trust,receivedTick=opinion.receivedTick)==opinion) { "Relayed opinion changed its origin" }
            }
        }
    }
    fun auditBeliefsEvent(db: SQLiteDatabase,event: CanonicalEvent,prior: Map<String,List<ProvenancedOpinion>>) {
        val expected=event.cognition?.minds.orEmpty().flatMap { (agent,mind) ->
            mind.memory.opinions.filter { it !in prior[agent].orEmpty() }.map { agent to it }
        }.sortedWith(compareBy<Pair<String,ProvenancedOpinion>> { it.first }.thenBy { it.second.claim }.thenBy { it.second.root })
        db.rawQuery("SELECT agent_id,claim_id,source_id,belief,disbelief,uncertainty,base_rate FROM subjective_beliefs WHERE event_id=? ORDER BY agent_id,claim_id,source_id",arrayOf(event.eventId.toString())).use { rows ->
            var count=0
            while(rows.moveToNext()) {
                check(count<expected.size)
                val (agent,opinion)=expected[count++]
                check(agent==rows.getString(0) && opinion.claim==rows.getString(1) && opinion.root==rows.getString(2))
                val value=opinion.discounted()
                check(listOf(value.belief,value.disbelief,value.uncertainty,value.baseRate).mapIndexed { i,x -> kotlin.math.abs(x-rows.getDouble(i+3))<1e-10 }.all { it })
                verifyOpinion(db,event,agent,opinion)
            }
            check(count==expected.size) { "Missing subjective belief evidence" }
        }
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
        db.rawQuery("SELECT t.automata,t.digest,t.agent_id,t.format_version,e.payload FROM tsetlin_automata_blobs t JOIN events e ON e.id=t.event_id", null).use { rows ->
            while (rows.moveToNext()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rows.getBlob(0))
                    .joinToString("") { "%02x".format(it) }
                check(digest == rows.getString(1)) { "Tsetlin blob digest mismatch" }
                val recorded=Canonical.event(rows.getString(4)).cognition?.minds?.get(rows.getString(2))?.tsetlin
                check(rows.getInt(3)==1 && recorded!=null && recorded.bytes().contentEquals(rows.getBlob(0))) {
                    "Tsetlin blob differs from canonical event"
                }
            }
        }
        db.rawQuery("SELECT t.payload,t.digest,t.event_id,t.trial_id,t.stage,t.rules_version,e.payload,p.payload,t.source_event_id FROM rsi_policy_trials t JOIN events e ON e.id=t.source_event_id JOIN rsi_policy_trials p ON p.trial_id=t.trial_id AND p.stage='PROPOSED'",null).use { rows ->
            while(rows.moveToNext()) {
                val payload=rows.getString(0)
                check(Canonical.hash(payload)==rows.getString(1))
                val trial=Json.decodeFromString<RsiTrial>(payload)
                val event=Canonical.event(rows.getString(6))
                check(trial.valid() && trial.sourceTick<=rows.getLong(2) && trial.sourceTick==rows.getLong(8) && trial.id==rows.getString(3) && trial.stage==rows.getString(4) && trial.rulesVersion==rows.getInt(5))
                check(event.valid() && event.hash==trial.sourceEventHash && event.stateHash==trial.sourceStateHash)
                check(sameProposal(Json.decodeFromString<RsiTrial>(rows.getString(7)),trial))
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
