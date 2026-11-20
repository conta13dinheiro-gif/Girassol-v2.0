package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.enhikyy.girassol.core.*

/** Checkpoint + event are one transaction. An invalid save is never silently replaced. */
class WorldStore(context: Context, name: String = activeName(context)) : SQLiteOpenHelper(context, name, null, 6, DatabaseErrorHandler {
    throw SQLiteDatabaseCorruptException("Banco corrompido; arquivo preservado para recuperação")
}) {
    private val app=context.applicationContext
    init { require(name.matches(Regex("girassol(?:-branch-[a-f0-9-]{36})?\\.db"))); setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, digest TEXT NOT NULL, event_id INTEGER NOT NULL, event_hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, tick INTEGER NOT NULL UNIQUE, payload TEXT NOT NULL)")
        createHistory(db)
        ResearchTables.create(db)
        GeaSchema.create(app, db)
        GeaSchema.createV6(app, db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..5 && newVersion==6)
        if(oldVersion<2) createHistory(db)
        if(oldVersion<3) ResearchTables.create(db) else if(oldVersion<4) ResearchTables.createVectors(db)
        if(oldVersion<5) GeaSchema.create(app, db)
        GeaSchema.createV6(app, db)
    }
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE milestones (id INTEGER PRIMARY KEY AUTOINCREMENT, tick INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, cause TEXT NOT NULL, agents TEXT NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL, event_hash TEXT NOT NULL)")
        db.execSQL("CREATE INDEX milestones_tick ON milestones(tick)")
    }

    fun recordRsiTrial(trial: RsiTrial) = GeaSchema.recordTrial(writableDatabase,trial)
    fun recoverRsiTrials() = GeaSchema.recoverTrials(writableDatabase)

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
                revision.cognitive?.let { change ->
                    db.rawQuery("SELECT payload FROM rsi_policy_trials WHERE source_event_id=? AND stage='VERIFIED'",arrayOf(revision.sourceTick.toString())).use { trials ->
                        var matched=false
                        while(trials.moveToNext()) {
                            val t=kotlinx.serialization.json.Json.decodeFromString<RsiTrial>(trials.getString(0))
                            if(t.candidate==change.candidate && t.baseline==change.baseline && t.trainingBaseline==change.trainingBaseline &&
                                t.trainingCandidate==change.trainingCandidate && t.validationBaseline==change.validationBaseline && t.validationCandidate==change.validationCandidate) matched=true
                        }
                        check(matched) { "RSI promotion lacks durable verified evidence" }
                    }
                }
                if(revision.sourceTick==0L) check(revision.sourceEventHash==WorldActor.GENESIS_HASH && revision.sourceStateHash==Canonical.hash(Canonical.state(WorldActor.genesis(previous.seed))))
                else db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(revision.sourceTick.toString())).use { source->
                    check(source.moveToFirst()) { "Origem da política ausente nesta linha" }
                    val e=Canonical.event(source.getString(0))
                    check(e.valid() && e.hash==revision.sourceEventHash && e.stateHash==revision.sourceStateHash) { "Proveniência evolutiva inválida" }
                }
            }
            val cognition=event.cognition
            if(cognition?.biologyEnabled==true) {
                val source=previous ?: load()?.state
                if(source!=null) check(cognition.applicable(source)) { "Biology acquisition lacks canonical source evidence" }
                else check(event.tick==1L && cognition.minds.values.all { it.symbioticModules.isEmpty() })
            }
            cognition?.lesson?.let { lesson ->
                val opinion=cognition.minds[lesson.researcher]?.memory?.opinions?.singleOrNull { it.claim==lesson.rowHash && it.root==lesson.acquisitionHash }
                if(cognition.immunityEnabled) check(opinion!=null) { "Missing immunity admission" }
                ResearchTables(db).assimilate(lesson,opinion)
            }
            db.insertOrThrow("events", null, ContentValues().apply { put("id", event.eventId); put("tick", event.tick); put("payload", Canonical.event(event)) })
            GeaSchema.record(db, transition, previous)
            db.insertWithOnConflict("checkpoint", null, ContentValues().apply {
                put("id", 1); put("payload", payload); put("digest", Canonical.hash(payload)); put("event_id", event.eventId); put("event_hash", event.hash)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
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
                    ResearchTables.fork(db,point)
                    GeaSchema.fork(db,point.eventId)
                    val payload=Canonical.state(point.state)
                    db.insertOrThrow("checkpoint",null,ContentValues().apply { put("id",1);put("payload",payload);put("digest",Canonical.hash(payload));put("event_id",point.eventId);put("event_hash",point.eventHash) })
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE origin") }
            check(destination.load()==point)
            destination.audit()
            return name
        } catch(e: Exception) { destination.close(); app.deleteDatabase(name); throw e }
        finally { destination.close() }
    }
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
            GeaSchema.audit(db)
            val checkpoint=load()
            val audit=JournalAudit()
            val symbiosis=SymbioticLedgerAudit()
            var opinions=emptyMap<String,List<ProvenancedOpinion>>()
            var learning=emptyMap<String,List<SkillLearningTrace>>()
            var skills=emptyMap<String,List<VoyagerSkill>>()
            db.rawQuery("SELECT id,tick,payload FROM events ORDER BY id",null).use { rows ->
                while (rows.moveToNext()) {
                    val event=Canonical.event(rows.getString(2))
                    audit.accept(rows.getLong(0),rows.getLong(1),event)
                    symbiosis.accept(event)
                    GeaSchema.auditEconomyEvent(db,event)
                    GeaSchema.auditInstitutionsEvent(db,event)
                    GeaSchema.auditBeliefsEvent(db,event,opinions)
                    GeaSchema.auditPedagogyEvent(db,event,learning,skills)
                    event.cognition?.let {
                        opinions=it.minds.mapValues { entry -> entry.value.memory.opinions }
                        learning=it.minds.mapValues { entry -> entry.value.memory.learning }
                        skills=it.minds.mapValues { entry -> entry.value.memory.skills }
                    }
                }
            }
            val count=audit.finish(checkpoint)
            ResearchTables(db).audit()
            db.setTransactionSuccessful()
            return count
        } finally { db.endTransaction() }
    }
}
