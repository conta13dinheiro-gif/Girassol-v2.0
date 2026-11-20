package com.enhikyy.girassol.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.research.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Accessed only from the world writer or a separate read-only UI connection. */
class ResearchTables(private val db: SQLiteDatabase) {
    fun mastery(): Map<Domain,Double> = db.rawQuery("SELECT domain,mastery FROM epistemic_nodes",null).use { c->
        buildMap { while(c.moveToNext()) put(Domain.valueOf(c.getString(0)),c.getDouble(1)) }
    }
    fun entries(): List<ResearchEntry> = db.rawQuery("SELECT payload FROM research_intents ORDER BY tick DESC,id DESC LIMIT 100",null).use { c->
        buildList { while(c.moveToNext()) add(ResearchCodec.decode<ResearchEntry>(c.getString(0))) }
    }
    fun entry(id: String): ResearchEntry? = db.rawQuery("SELECT payload FROM research_intents WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) ResearchCodec.decode(it.getString(0)) else null }
    fun save(entry: ResearchEntry) {
        db.insertWithOnConflict("research_intents",null,ContentValues().apply {
            put("id",entry.intent.id);put("tick",entry.intent.tick);put("payload",ResearchCodec.encode(entry))
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    fun installedKeys(): Set<DatasetKey> = db.rawQuery("SELECT repo,config,split,query_hash FROM installed_datasets",null).use { c->
        buildSet { while(c.moveToNext()) add(DatasetKey(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) }
    }
    fun installed(key: DatasetKey): Boolean = db.rawQuery("SELECT 1 FROM installed_datasets WHERE repo=? AND config=? AND split=? AND query_hash=?",arrayOf(key.repo,key.config,key.split,key.queryHash)).use { it.moveToFirst() }
    fun control(id: String,pinned: Boolean,enabled: Boolean) {
        val current=checkNotNull(entry(id))
        save(current.copy(pinned=pinned,enabled=enabled,message=if(enabled) "Estudo disponível para os habitantes." else "Estudo suspenso. Diário e pesos já aprendidos preservados."))
    }
    fun acquire(intent: DatasetResearchIntent,batch: DatasetBatch,point: Checkpoint,quota: Long): List<KnowledgeCandidate> {
        check(entry(intent.id)?.enabled!=false)
        require(batch.queryHash==intent.queryHash && intent.tick<=point.state.tick && point.state.agents.any { it.id==intent.discoverer })
        check(!installed(DatasetKey(batch.repo.id,batch.subset.config,batch.subset.split,intent.queryHash)))
        val previous=db.rawQuery("SELECT hash FROM external_knowledge_journal ORDER BY seq DESC LIMIT 1",null).use { if(it.moveToFirst()) it.getString(0) else WorldActor.GENESIS_HASH }
        val sealed=KnowledgeJournal.seal(batch,point.state.tick,point.eventHash,previous)
        val envelope=ResearchCodec.encode(sealed.envelope)
        require(batch.rows==DatasetText.rows(batch.raw,batch.endpoint.toHttpUrl().queryParameter("offset")?.toLongOrNull() ?: 0L))
        val candidates=batch.rows.distinctBy { it.digest }.map { row->
            require(sha256(row.text.toByteArray())==row.digest)
            KnowledgeCandidate(sha256("${sealed.hash}:${row.index}:${row.digest}".toByteArray()),sealed.hash,intent,batch.repo.id,batch.repo.license,row)
        }
        // Include text/index/metadata overhead; immutable evidence is never silently pruned.
        val incoming=sealed.compressed.size.toLong()+envelope.toByteArray().size+candidates.sumOf { ResearchCodec.encode(it).toByteArray().size.toLong() }+65536
        QuotaLru.victims(emptyList(),bytes(),incoming,quota)
        db.beginTransaction()
        try {
            verifyPoint(point)
            db.insertOrThrow("external_knowledge_journal",null,ContentValues().apply {
                put("hash",sealed.hash);put("tick",point.state.tick);put("envelope",envelope);put("compressed",sealed.compressed)
            })
            candidates.forEach { c->db.insertOrThrow("staging_knowledge",null,ContentValues().apply {
                put("id",c.id);put("acquisition_hash",sealed.hash);put("intent_id",intent.id);put("payload",ResearchCodec.encode(c));put("status","QUARANTINED");put("tick",point.state.tick)
            }) }
            db.insertOrThrow("installed_datasets",null,ContentValues().apply { put("repo",batch.repo.id);put("config",batch.subset.config);put("split",batch.subset.split);put("query_hash",intent.queryHash);put("intent_id",intent.id);put("tick",point.state.tick) })
            save(ResearchEntry(intent,ResearchPhase.QUARANTINED,"Aguardando duas revisões e um experimento isolado.",batch.repo.id,candidates.size))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return candidates
    }
    fun bytes(): Long = db.rawQuery("SELECT COALESCE(SUM(length(compressed)+length(envelope)),0) FROM external_knowledge_journal",null).use { it.moveToFirst(); it.getLong(0) }+
        db.rawQuery("SELECT COALESCE(SUM(length(payload)),0) FROM staging_knowledge",null).use { it.moveToFirst();it.getLong(0) }+
        db.rawQuery("SELECT COALESCE(SUM(length(embedding)),0) FROM knowledge_vectors",null).use { it.moveToFirst();it.getLong(0) }+
        db.rawQuery("SELECT COALESCE(SUM(length(payload)),0) FROM research_intents",null).use { it.moveToFirst();it.getLong(0) }
    fun candidates(limit: Int=100): List<KnowledgeCandidate> = db.rawQuery("SELECT payload FROM staging_knowledge WHERE status='QUARANTINED' ORDER BY tick,id",null).use { c->
        buildList { while(size<limit && c.moveToNext()) { val row=ResearchCodec.decode<KnowledgeCandidate>(c.getString(0));if(entry(row.intent.id)?.enabled!=false) add(row) } }
    }
    fun snippets(intent: String): List<Pair<KnowledgeCandidate,String>> = db.rawQuery("SELECT payload,status FROM staging_knowledge WHERE intent_id=? ORDER BY id LIMIT 100",arrayOf(intent)).use { c->
        buildList { while(c.moveToNext()) add(ResearchCodec.decode<KnowledgeCandidate>(c.getString(0)) to c.getString(1)) }
    }
    fun review(candidate: KnowledgeCandidate,tick: Long,approved: Boolean,quota: Long=Long.MAX_VALUE) {
        verifyRow(candidate)
        val original=readCandidate(candidate.id)
        check(candidate.copy(researcher=null,verifier=null,embedding=emptyList(),embeddingModel="")==original.copy(researcher=null,verifier=null,embedding=emptyList(),embeddingModel=""))
        val researcher=candidate.researcher;val verifier=candidate.verifier
        if(approved) check(researcher!=null && verifier!=null && EpistemicQuarantine.approved(candidate.row,researcher,verifier))
        if(approved) {
            check(candidate.embeddingModel.matches(Regex("[a-f0-9]{64}")) && candidate.embedding.size in 1..32768 && candidate.embedding.all { it.isFinite() })
            check(kotlin.math.abs(candidate.embedding.sumOf { it.toDouble()*it }-1.0)<.01)
        }
        QuotaLru.victims(emptyList(),bytes(),(ResearchCodec.encode(candidate).toByteArray().size-ResearchCodec.encode(original).toByteArray().size).coerceAtLeast(0).toLong()+candidate.embedding.size*4L,quota)
        db.update("staging_knowledge",ContentValues().apply { put("payload",ResearchCodec.encode(candidate));put("review_tick",tick);put("status",if(approved) "VALIDATED" else "REJECTED") },"id=? AND status='QUARANTINED'",arrayOf(candidate.id)).also { check(it==1) }
    }
    fun rejectExperiment(id: String,tick: Long) {
        db.update("staging_knowledge",ContentValues().apply { put("status","REPLAY_REJECTED");put("review_tick",tick) },"id=? AND status='VALIDATED'",arrayOf(id))
    }
    fun approvedFor(agent: String,known: Set<String>): KnowledgeCandidate? = db.rawQuery("SELECT payload FROM staging_knowledge WHERE status='VALIDATED' ORDER BY tick,id",null).use { c->
        var result: KnowledgeCandidate?=null
        while(c.moveToNext() && result==null) { val row=ResearchCodec.decode<KnowledgeCandidate>(c.getString(0));if(row.intent.discoverer==agent && row.id !in known && entry(row.intent.id)?.enabled!=false) result=row }
        result
    }
    private fun readCandidate(id: String): KnowledgeCandidate = db.rawQuery("SELECT payload FROM staging_knowledge WHERE id=?",arrayOf(id)).use { check(it.moveToFirst());ResearchCodec.decode(it.getString(0)) }
    /** Part of the world transaction: no live weights without matching verified evidence. */
    fun assimilate(lesson: KnowledgeLesson, eventId: Long) {
        verifyPointHash(lesson.originTick,lesson.originEventHash)
        if(lesson.originTick>0) db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(lesson.originTick.toString())).use { source->check(source.moveToFirst());check(Canonical.event(source.getString(0)).stateHash==lesson.originStateHash) }
        db.rawQuery("SELECT tick FROM events WHERE id=?",arrayOf(eventId.toString())).use { current ->
            check(current.moveToFirst() && current.getLong(0)==lesson.tick+1) { "Evento de assimilação ausente ou fora de sequência" }
        }
        val c=readCandidate(lesson.id)
        verifyRow(c)
        check(c.acquisitionHash==lesson.acquisitionHash && c.row.digest==lesson.rowHash && c.intent.domain.name==lesson.domain && c.intent.discoverer==lesson.researcher)
        val first=checkNotNull(c.researcher); val second=checkNotNull(c.verifier)
        check(EpistemicQuarantine.approved(c.row,first,second) && first.agent==lesson.researcher && second.agent==lesson.verifier && minOf(first.confidence,second.confidence)==lesson.confidence)
        val witnesses=listOf(first,second).map { review ->
            EpistemicWitness(
                claimHash=c.row.digest,
                sourceEventHash=c.acquisitionHash,
                sourceAgentId=review.agent,
                opinion=SubjectiveOpinion.fromBeta(
                    alpha=1.0+review.confidence,
                    beta=1.0+(100-review.confidence),
                    baseRate=0.5
                )
            )
        }
        val fused=checkNotNull(EpistemicImmunityEngine.fuseCorrelated(witnesses))
        db.insertOrThrow("subjective_beliefs",null,ContentValues().apply {
            put("event_id",eventId); put("agent_id",lesson.researcher); put("claim_id",c.row.digest)
            put("source_id",c.acquisitionHash); put("belief",fused.opinion.belief)
            put("disbelief",fused.opinion.disbelief); put("uncertainty",fused.opinion.uncertainty)
            put("base_rate",fused.opinion.baseRate)
        })
        db.update("staging_knowledge",ContentValues().apply { put("status","LEARNED");put("applied_tick",lesson.tick+1) },"id=? AND status='VALIDATED' AND review_tick<=?",arrayOf(c.id,lesson.tick.toString())).also { check(it==1) }
        if(c.embedding.isNotEmpty()) {
            val vector=java.nio.ByteBuffer.allocate(c.embedding.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            c.embedding.forEach(vector::putFloat)
            db.insertOrThrow("knowledge_vectors",null,ContentValues().apply { put("id",c.id);put("model_hash",c.embeddingModel);put("dimensions",c.embedding.size);put("embedding",vector.array());put("tick",lesson.tick+1) })
        }
        db.execSQL("UPDATE epistemic_nodes SET mastery=MIN(1.0,mastery+?) WHERE domain=?",arrayOf(.02*lesson.confidence/100,lesson.domain))
        entry(c.intent.id)?.let { save(it.copy(phase=ResearchPhase.LEARNED,message="Trecho incorporado durante estudo na biblioteca.",learned=it.learned+1)) }
    }
    /** Bounded HNSW shards cover all compatible learned records without loading the whole library. */
    fun similar(space: String,query: List<Float>,check: ()->Unit): List<Pair<KnowledgeCandidate,Float>> {
        var after="";val best=mutableListOf<Pair<String,Float>>()
        val shardSize=minOf(128,2*1024*1024/query.size.coerceAtLeast(1))
        require(shardSize>0 && query.isNotEmpty())
        while(true) {
            check()
            val shard=db.rawQuery("SELECT id,embedding FROM knowledge_vectors WHERE model_hash=? AND dimensions=? AND id>? ORDER BY id LIMIT ?",arrayOf(space,query.size.toString(),after,shardSize.toString())).use { cursor->
                buildList { while(cursor.moveToNext()) {
                    val buffer=java.nio.ByteBuffer.wrap(cursor.getBlob(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    require(buffer.remaining()==query.size*4)
                    add(cursor.getString(0) to List(query.size) { buffer.float })
                } }
            }
            if(shard.isEmpty()) break
            best+=com.enhikyy.girassol.cognition.NativeSemanticMemory.search(query,shard)
            best.sortByDescending { it.second };while(best.size>8) best.removeAt(best.lastIndex)
            after=shard.last().first
        }
        return best.map { readCandidate(it.first) to it.second }
    }
    private fun verifyRow(candidate: KnowledgeCandidate) {
        db.rawQuery("SELECT envelope,compressed FROM external_knowledge_journal WHERE hash=?",arrayOf(candidate.acquisitionHash)).use { source->
            check(source.moveToFirst())
            val envelope=ResearchCodec.decode<AcquisitionEnvelope>(source.getString(0))
            val raw=KnowledgeJournal.restore(SealedAcquisition(envelope,source.getBlob(1),candidate.acquisitionHash))
            val offset=envelope.endpoint.toHttpUrl().queryParameter("offset")?.toLongOrNull() ?: 0L
            check(candidate.repo==envelope.repo && candidate.license==envelope.license && candidate.intent.queryHash==envelope.queryHash && DatasetText.rows(raw,offset).any { it==candidate.row }) { "Trecho não pertence à aquisição imutável" }
        }
    }
    fun restore(hash: String): ByteArray = db.rawQuery("SELECT envelope,compressed FROM external_knowledge_journal WHERE hash=?",arrayOf(hash)).use { c->
        check(c.moveToFirst());KnowledgeJournal.restore(SealedAcquisition(ResearchCodec.decode(c.getString(0)),c.getBlob(1),hash))
    }
    fun audit() {
        var previous=WorldActor.GENESIS_HASH
        db.rawQuery("SELECT hash,envelope,compressed FROM external_knowledge_journal ORDER BY seq",null).use { c->while(c.moveToNext()) {
            val envelope=ResearchCodec.decode<AcquisitionEnvelope>(c.getString(1));check(envelope.previousHash==previous)
            KnowledgeJournal.restore(SealedAcquisition(envelope,c.getBlob(2),c.getString(0)))
            verifyPointHash(envelope.tick,envelope.eventHash);previous=c.getString(0)
        } }
    }
    private fun verifyPoint(point: Checkpoint) {
        verifyPointHash(point.state.tick,point.eventHash)
        db.rawQuery("SELECT event_id,event_hash FROM checkpoint WHERE id=1",null).use { tail->
            if(tail.moveToFirst()) check(tail.getLong(0)==point.eventId && tail.getString(1)==point.eventHash) else check(point.eventId==0L)
        }
    }
    private fun verifyPointHash(tick: Long,hash: String) {
        if(tick==0L) check(hash==WorldActor.GENESIS_HASH)
        else db.rawQuery("SELECT payload FROM events WHERE id=?",arrayOf(tick.toString())).use { c-> check(c.moveToFirst());val event=Canonical.event(c.getString(0));check(event.valid() && event.hash==hash) }
    }
    companion object {
        fun createVectors(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE knowledge_vectors(id TEXT PRIMARY KEY,model_hash TEXT NOT NULL,dimensions INTEGER NOT NULL,embedding BLOB NOT NULL,tick INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX knowledge_vector_space ON knowledge_vectors(model_hash,dimensions,id)")
        }
        fun create(db: SQLiteDatabase) {
            createVectors(db)
            db.execSQL("CREATE TABLE epistemic_nodes(domain TEXT PRIMARY KEY,mastery REAL NOT NULL CHECK(mastery BETWEEN 0 AND 1),prerequisites TEXT NOT NULL)")
            Domain.entries.forEach { d->db.execSQL("INSERT INTO epistemic_nodes VALUES(?,0,?)",arrayOf(d.name,d.prerequisites.joinToString(",") { it.name })) }
            db.execSQL("CREATE TABLE research_intents(id TEXT PRIMARY KEY,tick INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE external_knowledge_journal(seq INTEGER PRIMARY KEY AUTOINCREMENT,hash TEXT UNIQUE NOT NULL,tick INTEGER NOT NULL,envelope TEXT NOT NULL,compressed BLOB NOT NULL)")
            db.execSQL("CREATE TRIGGER knowledge_no_update BEFORE UPDATE ON external_knowledge_journal BEGIN SELECT RAISE(ABORT,'immutable acquisition'); END")
            db.execSQL("CREATE TRIGGER knowledge_no_delete BEFORE DELETE ON external_knowledge_journal BEGIN SELECT RAISE(ABORT,'immutable acquisition'); END")
            db.execSQL("CREATE TABLE staging_knowledge(id TEXT PRIMARY KEY,acquisition_hash TEXT NOT NULL,intent_id TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,tick INTEGER NOT NULL,review_tick INTEGER,applied_tick INTEGER)")
            db.execSQL("CREATE INDEX staging_status ON staging_knowledge(status,tick)")
            db.execSQL("CREATE TABLE installed_datasets(repo TEXT NOT NULL,config TEXT NOT NULL,split TEXT NOT NULL,query_hash TEXT NOT NULL,intent_id TEXT NOT NULL,tick INTEGER NOT NULL,PRIMARY KEY(repo,config,split,query_hash))")
        }
        fun fork(db: SQLiteDatabase,point: Checkpoint) {
            db.execSQL("INSERT INTO knowledge_vectors SELECT * FROM origin.knowledge_vectors WHERE tick<=?",arrayOf(point.state.tick))
            db.execSQL("INSERT INTO research_intents SELECT * FROM origin.research_intents WHERE tick<=?",arrayOf(point.state.tick))
            db.execSQL("INSERT INTO external_knowledge_journal SELECT * FROM origin.external_knowledge_journal WHERE tick<=?",arrayOf(point.state.tick))
            db.execSQL("INSERT INTO staging_knowledge SELECT id,acquisition_hash,intent_id,payload,CASE WHEN review_tick>? THEN 'QUARANTINED' WHEN applied_tick>? THEN 'VALIDATED' ELSE status END,tick,CASE WHEN review_tick<=? THEN review_tick ELSE NULL END,CASE WHEN applied_tick<=? THEN applied_tick ELSE NULL END FROM origin.staging_knowledge WHERE tick<=?",Array(5) { point.state.tick })
            db.execSQL("INSERT INTO installed_datasets SELECT * FROM origin.installed_datasets WHERE tick<=?",arrayOf(point.state.tick))
            db.rawQuery("SELECT id,payload FROM staging_knowledge WHERE review_tick IS NULL",null).use { rows->
                val reset=buildList { while(rows.moveToNext()) add(rows.getString(0) to ResearchCodec.decode<KnowledgeCandidate>(rows.getString(1)).copy(researcher=null,verifier=null,embedding=emptyList(),embeddingModel="")) }
                reset.forEach { (id,candidate)->db.update("staging_knowledge",ContentValues().apply { put("payload",ResearchCodec.encode(candidate)) },"id=?",arrayOf(id)) }
            }
            val tables=ResearchTables(db)
            tables.entries().forEach { e->tables.save(e.copy(phase=ResearchPhase.QUARANTINED,message="Evidência preservada nesta linha temporal.",learned=point.state.knowledge.count { k->tables.snippets(e.intent.id).any { it.first.id==k.id } })) }
            point.state.knowledge.groupBy { it.domain }.forEach { (domain,lessons)->db.execSQL("UPDATE epistemic_nodes SET mastery=? WHERE domain=?",arrayOf(lessons.sumOf { .02*it.confidence/100 }.coerceAtMost(1.0),domain)) }
        }
    }
}
