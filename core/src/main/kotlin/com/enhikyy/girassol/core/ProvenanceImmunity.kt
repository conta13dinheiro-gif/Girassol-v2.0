package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable

/** Immutable source identity survives social relays. Trust belongs to recipient/source/domain. */
@Serializable data class ProvenancedOpinion(val claim: String,val root: String,val sourceGroup: String,val domain: String,
    val sourceAgent: String,val sourceTick: Long,val sourceEventHash: String,val raw: SubjectiveOpinion,val trust: Double,val receivedTick: Long) {
    fun discounted() = raw.discountByTrust(trust)
}
data class CognitiveEvidence(val claim: String,val root: String,val sourceGroup: String,val domain: String,val sourceAgent: String,
    val confidence: Int,val sourceTick: Long,val sourceEventHash: String) {
    fun valid(world: WorldState): Boolean = listOf(claim,root,sourceGroup,sourceEventHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        domain in ProvenanceImmunity.DOMAINS && sourceAgent in world.agents.map { it.id } && confidence in 90..100 && sourceTick in 0..world.tick
}
object ProvenanceImmunity {
    val DOMAINS=setOf("LOGIC","LANGUAGE","ECOLOGY","PHYSICS","AGRICULTURE","ENGINEERING","ETHICS","MUSIC","MEDICINE","GOVERNANCE")
    const val MAX_CLAIMS=32
    const val MAX_SOURCES=8
    /** A factorized tensor: interpersonal Beta trust times source competence in the requested domain. */
    fun trust(world: WorldState,recipient: String,source: String,domain: String): Double {
        require(domain in DOMAINS)
        if(recipient==source) return 1.0
        val tie=world.ties.firstOrNull { it.from==recipient && it.to==source }
        val relation=tie?.domains?.get(domain)?.mean ?: tie?.trust ?: .5
        val actions=when(domain) {
            "LANGUAGE","MUSIC" -> listOf(Activity.WRITE,Activity.STUDY)
            "AGRICULTURE","ECOLOGY" -> listOf(Activity.EAT,Activity.WATER)
            "MEDICINE" -> listOf(Activity.SLEEP,Activity.COFFEE)
            "ENGINEERING","PHYSICS" -> listOf(Activity.BUILD,Activity.STUDY)
            "GOVERNANCE","ETHICS" -> listOf(Activity.TALK)
            else -> listOf(Activity.STUDY)
        }
        val bank=world.minds[source]?.memory
        val competence=actions.map { bank?.learningFor(it)?.knowledgeProbability ?: .2 }.average()
        if(!relation.isFinite() || relation !in 0.0..1.0 || !competence.isFinite()) return 0.0
        return (relation*(.5+.5*competence)).coerceIn(0.0,1.0)
    }
    fun valid(items: List<ProvenancedOpinion>,tick: Long): Boolean = items.size<=MAX_CLAIMS*MAX_SOURCES &&
        items.map { it.claim to it.root }.distinct().size==items.size && items.map { it.claim }.distinct().size<=MAX_CLAIMS &&
        items.groupingBy { it.claim }.eachCount().values.all { it<=MAX_SOURCES } && items.all {
            listOf(it.claim,it.root,it.sourceGroup,it.sourceEventHash).all { h -> h.matches(Regex("[a-f0-9]{64}")) } &&
                it.domain in DOMAINS && it.sourceAgent.isNotBlank() && it.sourceAgent.length<=128 && it.sourceTick in 0..it.receivedTick &&
                it.receivedTick<=tick && it.trust.isFinite() && it.trust in 0.0..1.0 }

    fun fuse(items: List<ProvenancedOpinion>,claim: String): SubjectiveOpinion? {
        val matching=items.filter { it.claim==claim }.distinct()
        require(matching.size<=MAX_SOURCES && matching.map { it.root }.distinct().size==matching.size) { "Conflicting or excess source identities" }
        val sources=matching.sortedBy { it.root }
        if(sources.isEmpty()) return null
        // Shared dataset OR originating checkpoint implies dependence. Transitive closure also
        // prevents a bridge source from making two correlated groups appear independent.
        val remaining=sources.toMutableList()
        val correlated=mutableListOf<List<ProvenancedOpinion>>()
        while(remaining.isNotEmpty()) {
            val group=mutableListOf(remaining.removeAt(0))
            do {
                val connected=remaining.filter { p -> group.any { it.sourceGroup==p.sourceGroup || it.sourceEventHash==p.sourceEventHash } }
                group+=connected;remaining.removeAll(connected.toSet())
            } while(connected.isNotEmpty())
            correlated+=group.sortedBy { it.root }
        }
        val groups=correlated.map { group ->
            var opinion=group.first().discounted();var weight=group.first().trust.coerceAtLeast(1e-9)
            for(item in group.drop(1)) {
                val nextWeight=item.trust.coerceAtLeast(1e-9)
                opinion=SubjectiveOpinion.averagingFuse(opinion,item.discounted(),weight,nextWeight);weight+=nextWeight
            }
            opinion
        }
        return groups.drop(1).fold(groups.first()) { a,b -> SubjectiveOpinion.cumulativeFuseIndependent(a,b) }
    }
    /** Refuses duplicate roots and uncertainty/trust failures before any native learning. */
    fun admit(world: WorldState,recipient: String,bank: MemoryBank,evidence: CognitiveEvidence): MemoryBank? {
        require(evidence.valid(world) && recipient in world.agents.map { it.id })
        if(bank.opinions.any { it.claim==evidence.claim && it.root==evidence.root }) return null
        // Acquisition identities already learned by this world cannot be laundered through eviction/relays.
        if(world.knowledge.any { it.rowHash==evidence.claim && it.acquisitionHash==evidence.root }) return null
        val trust=trust(world,recipient,evidence.sourceAgent,evidence.domain)
        val record=ProvenancedOpinion(evidence.claim,evidence.root,evidence.sourceGroup,evidence.domain,evidence.sourceAgent,
            evidence.sourceTick,evidence.sourceEventHash,SubjectiveOpinion(evidence.confidence/100.0,0.0,1-evidence.confidence/100.0),trust,world.tick)
        val sources=(bank.opinions.filterNot { it.claim==record.claim }+(bank.opinions.filter { it.claim==record.claim }+record)
            .sortedWith(compareByDescending<ProvenancedOpinion> { it.receivedTick }.thenBy { it.root }).take(MAX_SOURCES))
        val retainedClaims=sources.groupBy { it.claim }.entries.sortedWith(compareByDescending<Map.Entry<String,List<ProvenancedOpinion>>> { it.value.maxOf { p -> p.receivedTick } }.thenBy { it.key }).take(MAX_CLAIMS).map { it.key }.toSet()
        val retained=sources.filter { it.claim in retainedClaims }.sortedWith(compareBy<ProvenancedOpinion> { it.claim }.thenBy { it.root })
        if(retained.none { it.claim==record.claim && it.root==record.root }) return null
        val opinion=fuse(retained,record.claim) ?: return null
        if(trust<.2 || opinion.projectedProbability()<.62 || opinion.uncertainty>.8) return null
        return bank.copy(opinions=retained)
    }
    /** Social copies keep raw evidence/source group; only the recipient's direct trust is applied. */
    fun learnFrom(world: WorldState,recipient: String,bank: MemoryBank,teacher: MemoryBank): MemoryBank {
        var next=bank
        for(item in teacher.opinions) {
            if(next.opinions.any { it.claim==item.claim && it.root==item.root }) continue
            // Already accepted source record, not a new acquisition or an independent confirmation.
            val trust=trust(world,recipient,item.sourceAgent,item.domain)
            if(trust<.2) continue
            val record=item.copy(trust=trust,receivedTick=world.tick)
            val same=(next.opinions.filter { it.claim==item.claim }+record).sortedWith(compareByDescending<ProvenancedOpinion> { it.receivedTick }.thenBy { it.root }).take(MAX_SOURCES)
            val all=next.opinions.filterNot { it.claim==item.claim }+same
            val claims=all.groupBy { it.claim }.entries.sortedWith(compareByDescending<Map.Entry<String,List<ProvenancedOpinion>>> { it.value.maxOf { p -> p.receivedTick } }.thenBy { it.key }).take(MAX_CLAIMS).map { it.key }.toSet()
            next=next.copy(opinions=all.filter { it.claim in claims }.sortedWith(compareBy<ProvenancedOpinion> { it.claim }.thenBy { it.root }))
        }
        return next
    }
}
