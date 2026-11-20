package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.enhikyy.girassol.core.*
import java.security.MessageDigest
import kotlin.math.abs

/** Checkpoint + event are one transaction. An invalid save is never silently replaced. */
class WorldStore(context: Context, name: String = activeName(context)) : SQLiteOpenHelper(context, name, null, 6) {
    private val app=context.applicationContext
    init { require(name.matches(Regex("girassol(?:-branch-[a-f0-9-]{36})?\\.db"))); setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA synchronous=FULL")
        // Keep the per-connection page cache small and checkpoint WAL frequently.
        // WAL files are still durable; these are bounds, not data-retention policies.
        db.execSQL("PRAGMA cache_size=-2048")
        db.execSQL("PRAGMA wal_autocheckpoint=256")
        db.execSQL("PRAGMA journal_size_limit=4194304")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, digest TEXT NOT NULL, event_id INTEGER NOT NULL, event_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, tick INTEGER NOT NULL UNIQUE, payload TEXT NOT NULL)")
        createHistory(db)
        createCognitiveGrs(db)
        createRsiPromotionLedger(db)
        ResearchTables.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..5 && newVersion==6)
        if(oldVersion<2) createHistory(db)
        if(oldVersion<3) ResearchTables.create(db)
        else {
            val hasVectors = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='knowledge_vectors'", null).use { it.moveToFirst() }
            if(!hasVectors) ResearchTables.createVectors(db)
        }
        if(oldVersion<5) createCognitiveGrs(db)
        if(oldVersion<6) createRsiPromotionLedger(db)
    }
    private fun createCognitiveGrs(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cognitive_grs (id INTEGER PRIMARY KEY CHECK(id=1), tick INTEGER NOT NULL, payload BLOB NOT NULL, digest TEXT NOT NULL, agent_hash TEXT NOT NULL)")
    }
    private fun createRsiPromotionLedger(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rsi_promotion_ledger (event_id INTEGER NOT NULL, tick INTEGER NOT NULL, citizen_id TEXT NOT NULL, gate_passed INTEGER NOT NULL CHECK(gate_passed IN (0,1)), gate_score REAL NOT NULL CHECK(gate_score BETWEEN 0 AND 1), promotion_count INTEGER NOT NULL CHECK(promotion_count >= 0), veto_count INTEGER NOT NULL CHECK(veto_count >= 0), event_hash TEXT NOT NULL, PRIMARY KEY(event_id,citizen_id))")
        db.execSQL("CREATE INDEX rsi_ledger_tick ON rsi_promotion_ledger(tick)")
    }
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE milestones (id INTEGER PRIMARY KEY AUTOINCREMENT, tick INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, cause TEXT NOT NULL, agents TEXT NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL, event_hash TEXT NOT NULL)")
        db.execSQL("CREATE INDEX milestones_tick ON milestones(tick)")
    }

    fun load(): Checkpoint? {
        val db = readableDatabase
        db.rawQuery("SELECT payload,digest,event_id,event_hash FROM checkpoint WHERE id=1", null).use { c ->
            if (!c.moveToFirst()) {
                db.rawQuery("SELECT COUNT(*) FROM events", null).use { events -> events.moveToFirst(); check(events.getLong(0) == 0L) { "Checkpoint ausente em mundo existente" } }
                return null
            }
            val payload = c.getString(0)
            check(Canonical.hash(payload) == c.getString(1)) { "Checkpoint danificado; os dados foram preservados" }
            val state = Canonical.state(payload)
            check(WorldActor.violations(state).isEmpty()) { "Checkpoint viola os contratos do mundo" }
            validateCognitiveGrs(db, state)
            val id = c.getLong(2); val hash = c.getString(3)
            check(id == state.tick && id > 0)
            db.rawQuery("SELECT payload FROM events WHERE id=?", arrayOf(id.toString())).use { tail ->
                check(tail.moveToFirst()) { "Evento do checkpoint ausente" }
                val e = Canonical.event(tail.getString(0))
                JournalAudit.validateTail(Checkpoint(state,id,hash),e)
            }
            return Checkpoint(state, id, hash)
        }
    }
    fun commit(transition: Transition, previous: WorldState? = null) {
        JournalAudit.validateTransition(transition)
        val event = transition.event
        val payload = Canonical.state(transition.state)
        check(event.valid() && event.stateHash == Canonical.hash(payload) && transition.state.tick == event.tick)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT event_id,event_hash,digest FROM checkpoint WHERE id=1", null).use { c ->
                if (c.moveToFirst()) {
                    check(event.eventId == c.getLong(0) + 1 && event.previousHash == c.getString(1))
                    if(previous!=null) check(previous.tick==event.tick-1 && Canonical.hash(Canonical.state(previous))==c.getString(2))
                }
                else check(event.eventId == 1L && event.previousHash == WorldActor.GENESIS_HASH)
            }
            transition.event.cognition?.evolution?.let { revision->
                check(previous!=null && revision.applicable(previous))
                if(revision.sourceTick==0L) check(revision.sourceEventHash==WorldActor.GENESIS_HASH && revision.sourceStateHash==Canonical.hash(Canonical.state(WorldActor.genesis(previous.seed))))
                else db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(revision.sourceTick.toString())).use { source->
                    check(source.moveToFirst()) { "Origem da política ausente nesta linha" }
                    val e=Canonical.event(source.getString(0))
                    check(e.valid() && e.hash==revision.sourceEventHash && e.stateHash==revision.sourceStateHash) { "Proveniência evolutiva inválida" }
                }
            }
            event.cognition?.lesson?.let { ResearchTables(db).assimilate(it) }
            db.insertOrThrow("events", null, ContentValues().apply { put("id", event.eventId); put("tick", event.tick); put("payload", Canonical.event(event)) })
            db.insertWithOnConflict("checkpoint", null, ContentValues().apply {
                put("id", 1); put("payload", payload); put("digest", Canonical.hash(payload)); put("event_id", event.eventId); put("event_hash", event.hash)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            persistCognitiveGrs(db, transition.state)
            persistRsiPromotionLedger(db, event)
            val first=db.rawQuery("SELECT 1 FROM milestones LIMIT 1",null).use { !it.moveToFirst() }
            val before=if(first) null else previous
            val causes=if(before!=null || first) Milestones.detect(before,transition) else emptyList()
            causes.forEach { m->
                db.insertOrThrow("milestones",null,ContentValues().apply {
                    put("tick",event.tick); put("kind",m.kind); put("title",m.title); put("cause",m.cause)
                    put("agents",m.agents.joinToString(",")); put("payload",payload); put("digest",event.stateHash); put("event_hash",event.hash)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    data class Moment(val id: Long,val tick: Long,val kind: String,val title: String,val cause: String,val agents: List<String>)
    fun moments(): List<Moment> = readableDatabase.rawQuery("SELECT id,tick,kind,title,cause,agents FROM milestones ORDER BY tick DESC,id DESC",null).use { c->
        buildList { while(c.moveToNext()) add(Moment(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5).split(',').filter { it.isNotEmpty() })) }
    }
    fun moment(id: Long): Checkpoint {
        require(id>0)
        return readableDatabase.rawQuery("SELECT tick,payload,digest,event_hash FROM milestones WHERE id=?",arrayOf(id.toString())).use { c->
            check(c.moveToFirst()) { "Marco não encontrado" }
            val payload=c.getString(1); check(Canonical.hash(payload)==c.getString(2)) { "Snapshot histórico danificado" }
            val w=Canonical.state(payload)
            check(w.tick==c.getLong(0) && WorldActor.violations(w).isEmpty())
            val cp=Checkpoint(w,w.tick,c.getString(3))
            readableDatabase.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(cp.eventId.toString())).use { e->
                check(e.moveToFirst()) { "Evento histórico ausente" }; JournalAudit.validateTail(cp,Canonical.event(e.getString(0)))
            }
            cp
        }
    }
    /** Copies the verified prefix into another database. The source journal is retained intact. */
    fun fork(id: Long): String {
        val point=moment(id)
        val name="girassol-branch-${java.util.UUID.randomUUID()}.db"
        val destination=WorldStore(app,name)
        try {
            val db=destination.writableDatabase
            check(app.getDatabasePath(name).parentFile!!.usableSpace > app.getDatabasePath(databaseName!!).length()+64L*1024*1024) { "Espaço insuficiente para bifurcar" }
            db.execSQL("ATTACH DATABASE ? AS origin",arrayOf(app.getDatabasePath(databaseName!!).absolutePath))
            try {
                db.beginTransaction()
                try {
                    db.execSQL("INSERT INTO events SELECT * FROM origin.events WHERE id<=?",arrayOf(point.eventId))
                    db.execSQL("INSERT INTO milestones SELECT * FROM origin.milestones WHERE tick<=?",arrayOf(point.state.tick))
                    db.execSQL("INSERT INTO rsi_promotion_ledger SELECT * FROM origin.rsi_promotion_ledger WHERE event_id<=?",arrayOf(point.eventId))
                    ResearchTables.fork(db,point)
                    val payload=Canonical.state(point.state)
                    db.insertOrThrow("checkpoint",null,ContentValues().apply { put("id",1);put("payload",payload);put("digest",Canonical.hash(payload));put("event_id",point.eventId);put("event_hash",point.eventHash) })
                    persistCognitiveGrs(db, point.state)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE origin") }
            check(destination.load()==point)
            destination.audit()
            return name
        } catch(e: Exception) { destination.close(); app.deleteDatabase(name); throw e }
        finally { destination.close() }
    }
    private fun persistCognitiveGrs(db: SQLiteDatabase, state: WorldState) {
        require(state.agents.isNotEmpty() && state.agents.size <= 4096)
        val values = state.agents.flatMap { citizen ->
            val mind = state.minds[citizen.id]
            val trace = mind?.nativeEngineTrace ?: List(8) { 0.0 }
            val extended = mind?.nativeExtendedState ?: List(52) { 0.0 }
            require(trace.size == 8 && extended.size == 52)
            val cell = citizen.x.toInt().coerceIn(0,11) + 12 * citizen.z.toInt().coerceIn(0,11)
            val strongest = state.ties.asSequence().filter { it.from == citizen.id }.maxByOrNull { it.affinity }
            val ring = strongest?.let { state.civic.rings["${citizen.id}/${it.to}"] }
            val ringCode = when (ring) { "CLOSE_5" -> 1.0; "SYMPATHY_15" -> 2.0; "AFFINITY_50" -> 3.0; "NETWORK_150" -> 4.0; else -> 0.0 }
            val civicValues = listOf(
                state.civic.opinions[citizen.id] ?: 0.0,
                ringCode,
                state.civic.triadBalance,
                state.civic.psi,
                state.civic.pheromones[cell],
                state.civic.learning[citizen.id]?.knowledgeProbability ?: 0.0,
                state.civic.learningTheta2pl[citizen.id] ?: 0.0,
                state.civic.auroraAxisX
            )
            trace + extended + civicValues
        }.map { value ->
            require(value.isFinite())
            value.toFloat()
        }
        val encoded = GirassolRecordCodec.encode(
            GrsMatrix(state.agents.size, 68, values),
            GrsEncoding.FLOAT32
        )
        val idsHash = Canonical.hash(state.agents.joinToString("\u0000") { it.id })
        db.insertWithOnConflict("cognitive_grs", null, ContentValues().apply {
            put("id", 1)
            put("tick", state.tick)
            put("payload", encoded)
            put("digest", sha256(encoded))
            put("agent_hash", idsHash)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    private fun validateCognitiveGrs(db: SQLiteDatabase, state: WorldState) {
        db.rawQuery("SELECT tick,payload,digest,agent_hash FROM cognitive_grs WHERE id=1", null).use { c ->
            // Databases created before schema v5 are accepted; the next successful commit backfills the sidecar.
            if (!c.moveToFirst()) return
            val bytes = c.getBlob(1)
            check(c.getLong(0) == state.tick) { "GRS fora de sincronia com o checkpoint WAL" }
            check(sha256(bytes) == c.getString(2)) { "Digest do sidecar GRS inválido" }
            check(Canonical.hash(state.agents.joinToString("\u0000") { it.id }) == c.getString(3)) {
                "Ordem de cidadãos do sidecar GRS divergente"
            }
            val matrix = GirassolRecordCodec.decode(bytes)
            check(matrix.rows == state.agents.size && matrix.columns in setOf(8, 60, 68)) { "Dimensões do sidecar GRS inválidas" }
            state.agents.forEachIndexed { row, citizen ->
                val mind = state.minds[citizen.id]
                val trace = mind?.nativeEngineTrace ?: List(8) { 0.0 }
                val extended = mind?.nativeExtendedState ?: List(52) { 0.0 }
                repeat(8) { column ->
                    check(abs(matrix[row, column].toDouble() - trace[column].toFloat().toDouble()) <= 1e-6) {
                        "Trace cognitivo GRS diverge do checkpoint para ${citizen.id}"
                    }
                }
                if (matrix.columns >= 60) repeat(52) { column ->
                    check(abs(matrix[row, column + 8].toDouble() - extended[column].toFloat().toDouble()) <= 1e-5) {
                        "Estado estendido GRS diverge do checkpoint para ${citizen.id}"
                    }
                }
                if (matrix.columns == 68) {
                    val cell = citizen.x.toInt().coerceIn(0,11) + 12 * citizen.z.toInt().coerceIn(0,11)
                    val strongest = state.ties.asSequence().filter { it.from == citizen.id }.maxByOrNull { it.affinity }
                    val ring = strongest?.let { state.civic.rings["${citizen.id}/${it.to}"] }
                    val ringCode = when (ring) { "CLOSE_5" -> 1.0; "SYMPATHY_15" -> 2.0; "AFFINITY_50" -> 3.0; "NETWORK_150" -> 4.0; else -> 0.0 }
                    val civicValues = listOf(
                        state.civic.opinions[citizen.id] ?: 0.0, ringCode, state.civic.triadBalance,
                        state.civic.psi, state.civic.pheromones[cell],
                        state.civic.learning[citizen.id]?.knowledgeProbability ?: 0.0,
                        state.civic.learningTheta2pl[citizen.id] ?: 0.0, state.civic.auroraAxisX
                    )
                    civicValues.forEachIndexed { column, value ->
                        check(abs(matrix[row, column + 60].toDouble() - value.toFloat().toDouble()) <= 1e-5) {
                            "Estado cívico GRS diverge do checkpoint para ${citizen.id}"
                        }
                    }
                }
            }
        }
    }

    private fun persistRsiPromotionLedger(db: SQLiteDatabase, event: CanonicalEvent) {
        event.cognition?.minds?.forEach { (citizenId, mind) ->
            val state = mind.nativeExtendedState
            require(state.size == 52 && state.all { it.isFinite() })
            val gate = state[50].coerceIn(0.0, 1.0)
            db.insertOrThrow("rsi_promotion_ledger", null, ContentValues().apply {
                put("event_id", event.eventId)
                put("tick", event.tick)
                put("citizen_id", citizenId)
                put("gate_passed", if (gate >= .75) 1 else 0)
                put("gate_score", gate)
                put("promotion_count", state[48].toLong())
                put("veto_count", state[49].toLong())
                put("event_hash", event.hash)
            })
        }
    }

    private fun validateRsiPromotionLedger(db: SQLiteDatabase) {
        db.rawQuery(
            "SELECT l.event_id,l.tick,l.citizen_id,l.gate_passed,l.gate_score,l.promotion_count,l.veto_count,l.event_hash,e.payload FROM rsi_promotion_ledger l JOIN events e ON e.id=l.event_id ORDER BY l.event_id,l.citizen_id",
            null
        ).use { rows ->
            while (rows.moveToNext()) {
                val event = Canonical.event(rows.getString(8))
                check(event.valid() && event.eventId == rows.getLong(0) && event.tick == rows.getLong(1) &&
                    event.hash == rows.getString(7)) { "Proveniência do ledger RSI inválida" }
                val mind = event.cognition?.minds?.get(rows.getString(2))
                    ?: error("Registro RSI sem estado cognitivo no evento")
                val state = mind.nativeExtendedState
                check(state.size == 52 &&
                    (if (state[50] >= .75) 1 else 0) == rows.getInt(3) &&
                    abs(state[50] - rows.getDouble(4)) <= 1e-6 &&
                    state[48].toLong() == rows.getLong(5) &&
                    state[49].toLong() == rows.getLong(6)) { "Ledger RSI diverge do evento canônico" }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun research() = ResearchTables(writableDatabase)
    fun lastActions(): List<ActionRecord> = readableDatabase.rawQuery("SELECT payload FROM events ORDER BY id DESC LIMIT 1",null).use { if(it.moveToFirst()) Canonical.event(it.getString(0)).actions else emptyList() }
    companion object {
        fun activeName(context: Context): String = context.getSharedPreferences("runtime",Context.MODE_PRIVATE).getString("world_database","girassol.db") ?: "girassol.db"
    }

    /** One stable SQLite transaction; no rows are removed or rewritten. Call off the UI thread. */
    fun audit(): Long {
        val db=readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val checkpoint=load()
            val audit=JournalAudit()
            db.rawQuery("SELECT id,tick,payload FROM events ORDER BY id",null).use { rows ->
                while (rows.moveToNext()) audit.accept(rows.getLong(0),rows.getLong(1),Canonical.event(rows.getString(2)))
            }
            val count=audit.finish(checkpoint)
            ResearchTables(db).audit()
            validateRsiPromotionLedger(db)
            db.setTransactionSuccessful()
            return count
        } finally { db.endTransaction() }
    }
}
