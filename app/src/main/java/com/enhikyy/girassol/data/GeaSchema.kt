package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.enhikyy.girassol.core.*
import kotlinx.serialization.decodeFromString
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
        recordDoubleEntryLedger(db, event.eventId, previous?.balances, transition.state.balances)
        recordAuroraArchive(db, event, transition.state)
        event.cognition?.evolution?.let { revision ->
            val payload = Json.encodeToString(revision)
            db.insertOrThrow("rsi_promotion_ledger", null, ContentValues().apply {
                put("event_id", event.eventId); put("rules_version", revision.rulesVersion)
                if (revision.sourceTick == 0L) putNull("source_event_id") else put("source_event_id", revision.sourceTick)
                put("payload", payload); put("digest", Canonical.hash(payload))
            })
        }
        for ((agent, mind) in transition.state.minds) {
            val oldMemory = previous?.minds?.get(agent)?.memory
            val old = oldMemory?.learning.orEmpty().associateBy { it.action }
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

            val oldProcedures = oldMemory?.procedures.orEmpty().associateBy { it.action }
            for (procedure in mind.memory.procedures) {
                if (oldProcedures[procedure.action] == procedure) continue
                val trace = mind.memory.learning.firstOrNull { it.action == procedure.action } ?: continue
                if (procedure.successes < 3 || trace.knowledgeProbability < 0.75) continue
                val preconditions = when (procedure.action) {
                    Activity.WATER -> listOf("hydration<0.42", "market/water>=1")
                    Activity.COFFEE -> listOf("market/coffee>=1")
                    Activity.EAT -> listOf("personal_coins>=1", "market/food>=1")
                    Activity.SLEEP -> listOf("energy<0.38")
                    Activity.TALK -> listOf("peer_distance<=2.5")
                    Activity.WRITE -> listOf("market/paper>=1")
                    Activity.STUDY -> listOf("library_access=true")
                    Activity.BUILD -> listOf("market/timber>=4", "build_capacity_available=true")
                }
                val program = Json.encodeToString(mapOf(
                    "version" to "1",
                    "operation" to "perform_activity",
                    "activity" to procedure.action.name,
                    "room" to procedure.action.room
                ))
                db.insertOrThrow("voyager_skill_library", null, ContentValues().apply {
                    put("event_id", event.eventId); put("agent_id", agent)
                    put("skill_id", procedure.action.name); put("executions", procedure.successes)
                    put("knowledge", trace.knowledgeProbability)
                    put("preconditions", Json.encodeToString(preconditions))
                    put("program", program)
                })
            }
        }
        val reflexKey = com.enhikyy.girassol.cognition.TsetlinReflexEngine.persistenceKey
        val previousDigest = db.rawQuery(
            "SELECT digest FROM tsetlin_automata_blobs WHERE agent_id=? ORDER BY event_id DESC LIMIT 1",
            arrayOf(reflexKey)
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (previousDigest == null || event.tick % 30L == 0L) {
            val blob = com.enhikyy.girassol.cognition.TsetlinReflexEngine.snapshot()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                .joinToString("") { "%02x".format(it) }
            if (digest != previousDigest) {
                db.insertOrThrow("tsetlin_automata_blobs", null, ContentValues().apply {
                    put("event_id", event.eventId); put("agent_id", reflexKey); put("format_version", 1)
                    put("automata", blob); put("digest", digest)
                })
            }
        }
    }


    /**
     * Projects committed cognitive features into a persistent 3D MAP-Elites archive.
     * Oja state is checkpointed every 30 ticks; only improved cells add event rows.
     */
    private fun recordAuroraArchive(
        db: SQLiteDatabase,
        event: CanonicalEvent,
        world: WorldState
    ) {
        if (world.minds.isEmpty()) return
        val tick = event.tick
        val ttl = 86_400L
        val expiry = Math.addExact(tick, ttl)

        var projector = db.rawQuery(
            "SELECT payload,expires_tick FROM ecological_niche_structures " +
                "WHERE structure_id='aurora-projector' ORDER BY event_id DESC LIMIT 1",
            null
        ).use { rows ->
            if (rows.moveToFirst() && rows.getLong(1) >= tick) {
                Json.decodeFromString<AuroraOjaProjectorState>(rows.getString(0))
                    .also { check(it.valid()) { "Stored AURORA projector is invalid" } }
            } else {
                AuroraOjaProjectorState.create(inputDimensions = 6, seed = world.seed)
            }
        }

        val elites = db.rawQuery(
            "SELECT n.payload FROM ecological_niche_structures n " +
                "WHERE n.structure_id LIKE 'aurora-cell-%' AND n.expires_tick>=? " +
                "AND n.event_id=(SELECT MAX(p.event_id) FROM ecological_niche_structures p " +
                "WHERE p.structure_id=n.structure_id) ORDER BY n.structure_id",
            arrayOf(tick.toString())
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) add(Json.decodeFromString<AuroraCandidate>(rows.getString(0)))
            }
        }
        var archive = AuroraMapElitesState(binsPerAxis = 8, genomeDimensions = 8, elites = elites)
        check(archive.valid()) { "Stored AURORA archive violates niche invariants" }

        val semanticEntropy = SemanticCollapseGuard.normalizedPopulationEntropy(world.actionCounts)
        for (citizen in world.agents.sortedBy { it.id }) {
            val mind = world.minds[citizen.id] ?: continue
            val features = listOf(
                mind.personality[0],
                mind.personality[2],
                citizen.skill.mastery,
                mind.novelty,
                citizen.needs.social,
                citizen.needs.energy
            )
            val descriptor = projector.project(features)
            val actual = listOf(
                citizen.needs.energy,
                citizen.needs.hydration,
                citizen.needs.glucose,
                citizen.needs.social,
                citizen.needs.curiosity
            )
            val prediction = mind.psyche.expectedSensory
            val brier = if (mind.psyche.initialized && prediction.size >= 5) {
                (0 until 5).map { index ->
                    val error = prediction[index].coerceIn(0.0, 1.0) - actual[index]
                    error * error
                }.average().coerceIn(0.0, 1.0)
            } else 1.0
            val harmony = world.ties.asSequence().filter { it.from == citizen.id }
                .map { it.affinity.coerceIn(0.0, 1.0) }.average()
                .let { if (it.isFinite()) it else 0.5 }
            val fitness = ReplayMetrics(
                innovation = mind.novelty.coerceIn(0.0, 1.0),
                resilience = minOf(citizen.needs.energy, citizen.needs.hydration, citizen.needs.glucose),
                harmony = harmony,
                energyEfficiency = citizen.needs.energy,
                brierScore = brier,
                semanticEntropy = semanticEntropy
            )
            val genome = listOf(
                citizen.genome.curiosity.maternal,
                citizen.genome.curiosity.paternal,
                citizen.genome.sociability.maternal,
                citizen.genome.sociability.paternal,
                citizen.genome.symbiosis.maternal,
                citizen.genome.symbiosis.paternal,
                citizen.genome.symbiosis.dominanceA,
                citizen.genome.symbiosis.dominanceB
            )
            val candidate = AuroraCandidate(
                id = "aurora-" + Canonical.hash("\${citizen.id}:\${event.eventId}".toByteArray()).take(24),
                eventId = event.eventId,
                sourceEventHash = event.hash,
                descriptor = descriptor,
                genome = genome,
                metrics = fitness,
                generation = citizen.genome.generation.toLong()
            )
            val update = AuroraMapElites.insert(archive, candidate)
            if (update.disposition == AuroraInsertDisposition.INSERTED ||
                update.disposition == AuroraInsertDisposition.REPLACED
            ) {
                archive = update.state
                val cell = update.cell
                db.insertOrThrow("ecological_niche_structures", null, ContentValues().apply {
                    put("event_id", event.eventId)
                    put("structure_id", "aurora-cell-\${cell.first}-\${cell.second}-\${cell.third}")
                    put("owner_id", citizen.id)
                    put("expires_tick", expiry)
                    put("payload", Json.encodeToString(candidate))
                })
            }
            if (tick % 30L == 0L) projector = projector.observe(features)
        }

        val projectorAlreadyRecorded = db.rawQuery(
            "SELECT 1 FROM ecological_niche_structures WHERE event_id=? AND structure_id='aurora-projector'",
            arrayOf(event.eventId.toString())
        ).use { it.moveToFirst() }
        if (projectorAlreadyRecorded) return
        if (tick % 30L == 0L || db.rawQuery(
                "SELECT 1 FROM ecological_niche_structures WHERE structure_id='aurora-projector' LIMIT 1",
                null
            ).use { !it.moveToFirst() }
        ) {
            db.insertOrThrow("ecological_niche_structures", null, ContentValues().apply {
                put("event_id", event.eventId)
                put("structure_id", "aurora-projector")
                put("owner_id", "aurora-system")
                put("expires_tick", expiry)
                put("payload", Json.encodeToString(projector))
            })
        }
    }

    /**
     * Materializes balanced postings from the net account deltas of a committed
     * transition. Each resource is balanced independently; matching is stable
     * by account name and cannot mint or destroy units.
     */
    private fun recordDoubleEntryLedger(
        db: SQLiteDatabase,
        eventId: Long,
        before: Map<String, Int>?,
        after: Map<String, Int>
    ) {
        if (before == null) return
        val keys = (before.keys + after.keys).toSortedSet()
        val byResource = linkedMapOf<String, MutableList<Pair<String, Long>>>()
        for (key in keys) {
            val separator = key.lastIndexOf('/')
            check(separator > 0 && separator < key.lastIndex) { "Invalid balance key: $key" }
            val account = key.substring(0, separator)
            val resource = key.substring(separator + 1)
            val delta = (after[key] ?: 0).toLong() - (before[key] ?: 0).toLong()
            if (delta != 0L) byResource.getOrPut(resource) { mutableListOf() }.add(account to delta)
        }

        var entryId = 0
        for ((resource, movements) in byResource.toSortedMap()) {
            val debits = movements.filter { it.second < 0L }
                .map { it.first to -it.second }.sortedBy { it.first }.toMutableList()
            val credits = movements.filter { it.second > 0L }
                .sortedBy { it.first }.toMutableList()
            val debitTotal = debits.sumOf { it.second }
            val creditTotal = credits.sumOf { it.second }
            check(debitTotal == creditTotal) {
                "Unbalanced resource movement for $resource at event $eventId: $debitTotal != $creditTotal"
            }

            var debitIndex = 0
            var creditIndex = 0
            while (debitIndex < debits.size && creditIndex < credits.size) {
                val (debitAccount, debitRemaining) = debits[debitIndex]
                val (creditAccount, creditRemaining) = credits[creditIndex]
                val amount = minOf(debitRemaining, creditRemaining)
                check(amount in 1L..Int.MAX_VALUE.toLong())
                check(debitAccount != creditAccount)
                db.insertOrThrow("double_entry_ledger", null, ContentValues().apply {
                    put("event_id", eventId)
                    put("entry_id", entryId++)
                    put("debit_account", debitAccount)
                    put("credit_account", creditAccount)
                    put("resource", resource)
                    put("amount", amount.toInt())
                })
                if (debitRemaining == amount) debitIndex++
                else debits[debitIndex] = debitAccount to (debitRemaining - amount)
                if (creditRemaining == amount) creditIndex++
                else credits[creditIndex] = creditAccount to (creditRemaining - amount)
            }
            check(debitIndex == debits.size && creditIndex == credits.size) {
                "Ledger residual for $resource at event $eventId"
            }
        }
    }

    fun restoreTsetlin(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT automata,digest FROM tsetlin_automata_blobs WHERE agent_id=? ORDER BY event_id DESC LIMIT 1",
            arrayOf(com.enhikyy.girassol.cognition.TsetlinReflexEngine.persistenceKey)
        ).use { rows ->
            if (!rows.moveToFirst()) {
                com.enhikyy.girassol.cognition.TsetlinReflexEngine.reset()
                return
            }
            val blob = rows.getBlob(0)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                .joinToString("") { "%02x".format(it) }
            check(digest == rows.getString(1)) { "Tsetlin snapshot digest mismatch" }
            com.enhikyy.girassol.cognition.TsetlinReflexEngine.restore(blob)
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
        db.rawQuery(
            "SELECT 1 FROM double_entry_ledger WHERE amount<=0 OR debit_account=credit_account LIMIT 1",
            null
        ).use { rows ->
            check(!rows.moveToFirst()) { "Invalid double-entry posting" }
        }

        db.rawQuery(
            "SELECT n.event_id,n.structure_id,n.owner_id,n.expires_tick,n.payload,e.payload " +
                "FROM ecological_niche_structures n JOIN events e ON e.id=n.event_id " +
                "WHERE n.structure_id='aurora-projector' OR n.structure_id LIKE 'aurora-cell-%'",
            null
        ).use { rows ->
            while (rows.moveToNext()) {
                val eventId = rows.getLong(0)
                val structureId = rows.getString(1)
                val owner = rows.getString(2)
                val expires = rows.getLong(3)
                val event = Canonical.event(rows.getString(5))
                check(event.eventId == eventId && expires >= event.tick)
                when {
                    structureId == "aurora-projector" -> {
                        check(owner == "aurora-system")
                        val projector = Json.decodeFromString<AuroraOjaProjectorState>(rows.getString(4))
                        check(projector.valid())
                    }
                    structureId.startsWith("aurora-cell-") -> {
                        check(owner.isNotBlank())
                        val candidate = Json.decodeFromString<AuroraCandidate>(rows.getString(4))
                        check(candidate.valid(8) && candidate.eventId == eventId && candidate.sourceEventHash == event.hash)
                        val cell = AuroraMapElites.cellOf(candidate.descriptor, 8)
                        check(structureId == "aurora-cell-${cell.first}-${cell.second}-${cell.third}")
                    }
                }
            }
        }
        db.rawQuery(
            "SELECT event_id,resource,SUM(amount) FROM double_entry_ledger GROUP BY event_id,resource ORDER BY event_id,resource",
            null
        ).use { rows ->
            while (rows.moveToNext()) check(rows.getLong(2) > 0L) { "Empty double-entry resource batch" }
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
        db.rawQuery(
            "SELECT v.agent_id,v.skill_id,v.executions,v.knowledge,v.preconditions,v.program,e.payload " +
                "FROM voyager_skill_library v JOIN events e ON e.id=v.event_id",
            null
        ).use { rows ->
            while (rows.moveToNext()) {
                val action = Activity.valueOf(rows.getString(1))
                val conditions = Json.decodeFromString<List<String>>(rows.getString(4))
                val program = Json.decodeFromString<Map<String, String>>(rows.getString(5))
                check(rows.getInt(2) >= 3 && rows.getDouble(3) in 0.75..1.0)
                check(conditions.isNotEmpty() && conditions.all { it.isNotBlank() })
                check(program["version"] == "1" && program["operation"] == "perform_activity")
                check(program["activity"] == action.name && program["room"] == action.room)
                val event = Canonical.event(rows.getString(6))
                val mind = event.cognition?.minds?.get(rows.getString(0))
                    ?: error("Voyager skill has no canonical cognitive evidence")
                val procedure = mind.memory.procedures.firstOrNull { it.action == action }
                    ?: error("Voyager skill is absent from canonical memory")
                val trace = mind.memory.learning.firstOrNull { it.action == action }
                    ?: error("Voyager skill has no BKT evidence")
                check(procedure.successes == rows.getInt(2))
                check(trace.knowledgeProbability == rows.getDouble(3) && trace.knowledgeProbability >= 0.75)
            }
        }
        db.rawQuery("SELECT format_version,automata,digest FROM tsetlin_automata_blobs", null).use { rows ->
            while (rows.moveToNext()) {
                val blob = rows.getBlob(1)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                    .joinToString("") { "%02x".format(it) }
                check(digest == rows.getString(2)) { "Tsetlin blob digest mismatch" }
                check(rows.getInt(0) == 1 && blob.size >= 32 &&
                    blob[0] == 'G'.code.toByte() && blob[1] == 'R'.code.toByte() &&
                    blob[2] == 'S'.code.toByte() && blob[3] == 'T'.code.toByte()) {
                    "Tsetlin blob format mismatch"
                }
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
