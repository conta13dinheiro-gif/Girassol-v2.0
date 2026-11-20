package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class DomainBeta(val alpha: Double=1.5,val beta: Double=1.5) {
    val mean get()=alpha/(alpha+beta)
    fun valid()=alpha.isFinite() && beta.isFinite() && alpha>0 && beta>0 && alpha+beta<=2048
    fun observe(success: Boolean): DomainBeta {
        require(valid())
        val a=alpha+if(success) 1.0 else 0.0
        val b=beta+if(success) 0.0 else 2.2
        val scale=if(a+b>1024) .5 else 1.0
        // Keep a nonzero prior under indefinite streaming/rescaling; certainty cannot underflow.
        return DomainBeta((a*scale).coerceAtLeast(.5),(b*scale).coerceAtLeast(.5))
    }
}
enum class DunbarRing(val capacity: Int) { INTIMATE(5),SYMPATHY(15),FUNCTIONAL(50),COMMUNITY(150) }
object DunbarNetwork {
    fun strength(tie: SocialTie)=.4*abs(2*tie.affinity-1)+.35*tie.familiarity+.25*tie.trust
    fun ordered(outgoing: List<SocialTie>): List<SocialTie> {
        require(outgoing.size<=150 && outgoing.map { it.from }.distinct().size<=1)
        return outgoing.sortedWith(compareByDescending<SocialTie> { strength(it) }.thenBy { it.to })
    }
    fun ring(outgoing: List<SocialTie>,peer: String): DunbarRing? {
        val rank=ordered(outgoing).indexOfFirst { it.to==peer }
        return if(rank<0) null else DunbarRing.entries.first { rank<it.capacity }
    }
}
@Serializable enum class Deontic { OBRIGADO,PERMITIDO,PROIBIDO }
@Serializable enum class NormCondition { ALWAYS,TIMBER_LT,HYDRATION_LT,ENERGY_LT,PSI_GT }
/** Finite ADICO grammar; all conditions/actions/sanctions are interpreted by the real world writer. */
@Serializable data class AdicoRule(val attribute: String,val deontic: Deontic,val aim: Activity,val condition: NormCondition,
    val threshold: Double,val fine: Int,val quota: Int,val window: Int) {
    @kotlinx.serialization.Transient val id=Canonical.hash(statement())
    fun statement()="$attribute ${deontic.name} ${aim.name} WHEN ${condition.name} $threshold ELSE FINE $fine QUOTA $quota WINDOW $window"
    fun valid()=attribute.matches(Regex("ALL|LINEAGE:[\\p{L}\\p{N}_·–-]{1,64}")) && threshold.isFinite() && threshold in 0.0..1e9 &&
        fine in 0..3 && window in 1..1440 && (if(deontic==Deontic.PERMITIDO) quota in 1..50 else quota==0) &&
        (condition !in setOf(NormCondition.HYDRATION_LT,NormCondition.ENERGY_LT) || threshold<=1) &&
        (deontic!=Deontic.PROIBIDO || aim !in setOf(Activity.WATER,Activity.EAT,Activity.SLEEP))
    fun applies(world: WorldState,citizen: Citizen): Boolean = (attribute=="ALL" || attribute=="LINEAGE:${citizen.lineage}") && when(condition) {
        NormCondition.ALWAYS -> true
        NormCondition.TIMBER_LT -> (world.balances["market/timber"] ?: 0)<threshold
        NormCondition.HYDRATION_LT -> citizen.needs.hydration<threshold
        NormCondition.ENERGY_LT -> citizen.needs.energy<threshold
        NormCondition.PSI_GT -> (world.society?.pressure?.psi ?: 0.0)>threshold
    }
    companion object {
        fun parse(text: String): AdicoRule {
            require(text.length<=256 && text.none { it.isISOControl() })
            val p=text.trim().split(' ')
            require(p.size==13 && p[3]=="WHEN" && p[6]=="ELSE" && p[7]=="FINE" && p[9]=="QUOTA" && p[11]=="WINDOW")
            return AdicoRule(p[0],Deontic.valueOf(p[1]),Activity.valueOf(p[2]),NormCondition.valueOf(p[4]),p[5].toDouble(),p[8].toInt(),p[10].toInt(),p[12].toInt()).also { require(it.valid()) }
        }
    }
}
@Serializable data class SocialOpinion(val value: Double,val intrinsic: Double,val susceptibility: Double) {
    fun valid()=listOf(value,intrinsic).all { it.isFinite() && it in -1.0..1.0 } && susceptibility.isFinite() && susceptibility in 0.0..1.0
}
@Serializable data class NormUsage(val agent: String,val rule: String,val window: Long,val successes: Int=0,val violations: Int=0)
@Serializable data class NormSanction(val agent: String,val rule: String,val attempted: Activity,val admitted: Activity,val paid: Int,val level: Int,val tick: Long)
@Serializable data class SocialContact(val from: String,val peer: String,val domain: String,val tick: Long)
@Serializable data class PoliticalPressure(val population: Int,val capacity: Double,val gini: Double,val mmp: Double,val emp: Double,val fiscal: Double,val psi: Double) {
    fun valid()=population in 1..150 && listOf(capacity,gini,mmp,emp,fiscal,psi).all { it.isFinite() && it>=0 } &&
        capacity>=1 && gini in 0.0..1.0 && fiscal in .05..2.0 && abs(psi-mmp*(.35+gini)*emp/fiscal)<1e-8
}
@Serializable data class SocietyState(val rules: List<AdicoRule>,val opinions: Map<String,SocialOpinion>,val usage: List<NormUsage>,
    val sanctions: List<NormSanction>,val contacts: List<SocialContact>,val pressure: PoliticalPressure) {
    fun valid(world: WorldState): Boolean {
        val ids=world.agents.map { it.id }.toSet();val byRule=rules.associateBy { it.id }
        return ids.size in 1..150 && ids.size==world.agents.size && rules.size<=32 && byRule.size==rules.size && rules.all { it.valid() } && opinions.keys==ids && opinions.values.all { it.valid() } &&
            usage.size<=4800 && usage.map { it.agent to it.rule }.distinct().size==usage.size && usage.all {
                it.agent in ids && it.rule in byRule && it.window==world.tick/byRule.getValue(it.rule).window && it.successes in 0..1440 && it.violations in 0..1440 } &&
            sanctions.size<=150 && sanctions.map { it.agent }.distinct().size==sanctions.size && sanctions.all {
                it.agent in ids && it.rule in byRule && it.tick==world.tick && it.level in 1..3 && it.paid in 0..byRule.getValue(it.rule).fine*it.level && it.attempted!=it.admitted } &&
            contacts.size<=150 && contacts.map { it.from }.distinct().size==contacts.size && contacts.all { it.from in ids && it.peer in ids && it.from!=it.peer && it.domain in ProvenanceImmunity.DOMAINS && it.tick==world.tick } &&
            pressure.valid() && pressure==SocietyAdicoCliodynamicsEngine.measure(world) && pressure.population==ids.size && world.ties.size<=22500 && world.ties.map { it.from to it.to }.distinct().size==world.ties.size &&
            world.ties.groupingBy { it.from }.eachCount().values.all { it<=150 } && world.ties.all { it.from in ids && it.to in ids &&
                it.alpha.isFinite() && it.beta.isFinite() && it.alpha>0 && it.beta>0 && it.alpha+it.beta<=2048 &&
                it.familiarity.isFinite() && it.familiarity in 0.0..1.0 && it.domains.size<=10 && it.domains.all { (domain,beta) -> domain in ProvenanceImmunity.DOMAINS && beta.valid() } }
    }
}
data class InstitutionalDecision(val action: Activity,val violation: AdicoRule?=null)

class SocietyAdicoCliodynamicsEngine(private val prior: WorldState) {
    init { require(prior.agents.size in 1..150) }
    private val tick=prior.tick+1
    val rules=prior.society?.rules ?: foundingRules()
    private val usage=prior.society?.usage.orEmpty().filter { it.window==tick/rules.first { rule -> rule.id==it.rule }.window }
        .associateBy { it.agent to it.rule }.toMutableMap()
    private val sanctions=mutableListOf<NormSanction>()
    fun sanction(agent: String,attempted: Activity,decision: InstitutionalDecision,balances: Map<String,Int>,transfer: (String,String,String,Int)->Boolean) {
        val rule=decision.violation ?: return
        val old=usage[agent to rule.id] ?: NormUsage(agent,rule.id,tick/rule.window)
        val count=(old.violations+1).coerceAtMost(1440);val level=count.coerceAtMost(3)
        val paid=minOf(rule.fine*level,balances["$agent/coins"] ?: 0)
        if(paid>0) check(transfer(agent,DoubleEntryEconomyAndStigmergy.COMMONS,"coins",paid))
        usage[agent to rule.id]=old.copy(violations=count)
        sanctions+=NormSanction(agent,rule.id,attempted,decision.action,paid,level,tick)
        check(sanctions.size<=150)
    }
    data class Settlement(val state: SocietyState,val ties: List<SocialTie>)
    fun settle(before: WorldState,world: WorldState,records: List<ActionRecord>,contacts: List<SocialContact>,lesson: KnowledgeLesson?): Settlement {
        for(record in records.filter { it.outcome=="OK" }) {
            val citizen=before.agents.first { it.id==record.agentId }
            for(rule in rules.filter { it.aim==record.activity && it.applies(before,citizen) }) {
                val key=citizen.id to rule.id;val old=usage[key] ?: NormUsage(citizen.id,rule.id,tick/rule.window)
                usage[key]=old.copy(successes=(old.successes+1).coerceAtMost(1440))
            }
        }
        val citizens=world.agents.associateBy { it.id };val outcomes=records.associateBy { it.agentId }
        val ties=world.ties.map { tie -> tie.copy(familiarity=(tie.familiarity*exp(-.0005)).coerceIn(0.0,1.0),domains=tie.domains.toMap()) }.toMutableList()
        val indexes=ties.mapIndexed { i,t -> (t.from to t.to) to i }.toMap()
        val byOwner=world.ties.groupBy { it.from }
        val ownerIndexes=ties.indices.groupBy { ties[it].from }
        for(citizen in world.agents) {
            val witnesses=DunbarNetwork.ordered(byOwner[citizen.id].orEmpty()).filter { tie -> citizens[tie.to]?.let { hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }==true }.take(5)
            for(tie in witnesses) {
                val index=indexes.getValue(tie.from to tie.to);var next=ties[index].copy(familiarity=(ties[index].familiarity+.02).coerceAtMost(1.0))
                val outcome=outcomes[tie.to]
                if(outcome!=null && outcome.outcome in setOf("OK","PRECONDITION_FAILED")) {
                    val success=outcome.outcome=="OK";val domain=domain(outcome.activity)
                    val general=DomainBeta(next.alpha,next.beta).observe(success)
                    next=next.copy(alpha=general.alpha,beta=general.beta,domains=next.domains+(domain to (next.domains[domain] ?: DomainBeta()).observe(success)))
                }
                ties[index]=next
            }
        }
        lesson?.let { evidence -> indexes[evidence.researcher to evidence.verifier]?.let { index ->
            val tie=ties[index]
            ties[index]=tie.copy(domains=tie.domains+(evidence.domain to (tie.domains[evidence.domain] ?: DomainBeta()).observe(true)))
        } }
        val old=prior.society?.opinions.orEmpty()
        val snapshot=world.agents.associate { citizen -> citizen.id to (old[citizen.id] ?: initialOpinion(world,citizen)) }
        val opinions=snapshot.toMutableMap()
        for(contact in contacts.sortedBy { it.from }) {
            val source=snapshot.getValue(contact.peer)
            val own=snapshot.getValue(contact.from)
            val tie=indexes[contact.from to contact.peer]?.let { ties[it] }
            opinions[contact.from]=own.copy(value=hybrid(own,source.value,tie?.domains?.get(contact.domain)?.mean ?: tie?.trust ?: .5))
        }
        // One bounded, deterministically selected Heider triad per owner, in signed coordinates.
        for(citizen in world.agents) {
            val neighbors=DunbarNetwork.ordered(ownerIndexes[citizen.id].orEmpty().map { ties[it] }).take(50)
            if(neighbors.size<2) continue
            val rng=Canonical.stream(world.seed,citizen.id,tick,"heider-triad-v1")
            val first=rng.nextInt(neighbors.size);val second=(first+1+rng.nextInt(neighbors.size-1))%neighbors.size
            val ij=neighbors[first];val ik=neighbors[second]
            val jk=indexes[ij.to to ik.to]?.let { ties[it] } ?: continue
            val index=indexes.getValue(ik.from to ik.to)
            ties[index]=ties[index].copy(affinity=balancedAffinity(ij.affinity,jk.affinity,ties[index].affinity))
        }
        val pressure=measure(world)
        return Settlement(SocietyState(rules.toList(),opinions.toSortedMap(),usage.values.sortedWith(compareBy<NormUsage> { it.agent }.thenBy { it.rule }),sanctions.toList(),contacts.toList(),pressure),ties.toList())
    }
    companion object {
        fun foundingRules()=listOf(
            "ALL PROIBIDO BUILD WHEN TIMBER_LT 8 ELSE FINE 1 QUOTA 0 WINDOW 240",
            "ALL PERMITIDO WRITE WHEN ALWAYS 0 ELSE FINE 1 QUOTA 8 WINDOW 240",
            "ALL OBRIGADO WATER WHEN HYDRATION_LT 0.30 ELSE FINE 1 QUOTA 0 WINDOW 240"
        ).map(AdicoRule::parse).sortedBy { it.id }
        fun admit(world: WorldState,citizen: Citizen,proposed: Activity,rules: List<AdicoRule> = world.society?.rules ?: foundingRules()): InstitutionalDecision {
            require(rules.size<=32)
            // Physiological survival precedes ordinary institutional restrictions.
            val critical=listOf(citizen.needs.hydration to Activity.WATER,citizen.needs.glucose to Activity.EAT,citizen.needs.energy to Activity.SLEEP).minBy { it.first }
            if(critical.first<.18) return InstitutionalDecision(critical.second)
            val applicable=rules.filter { it.applies(world,citizen) }.sortedBy { it.id }
            val required=applicable.firstOrNull { it.deontic==Deontic.OBRIGADO && it.aim!=proposed }
            if(required!=null) return InstitutionalDecision(required.aim,required)
            fun forbidden(action: Activity)=applicable.firstOrNull { rule -> rule.aim==action && (rule.deontic==Deontic.PROIBIDO ||
                (rule.deontic==Deontic.PERMITIDO && (world.society?.usage?.firstOrNull { it.agent==citizen.id && it.rule==rule.id && it.window==world.tick/rule.window }?.successes ?: 0)>=rule.quota)) }
            val violation=forbidden(proposed) ?: return InstitutionalDecision(proposed)
            // Necessary rest is the safe resolution of mutually restrictive ordinary permissions.
            val fallback=listOf(Activity.STUDY,Activity.WATER,Activity.EAT,Activity.SLEEP).firstOrNull { forbidden(it)==null } ?: Activity.SLEEP
            return InstitutionalDecision(fallback,violation)
        }
        fun partner(world: WorldState,citizen: Citizen,outgoing: List<SocialTie>): Citizen? {
            val ranks=DunbarNetwork.ordered(outgoing).mapIndexed { i,t -> t.to to i }.toMap()
            val nearby=world.agents.filter { it.id!=citizen.id && it.id in ranks && hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }
                .sortedWith(compareBy<Citizen> { ranks.getValue(it.id) }.thenBy { it.id }).take(5)
            if(nearby.isEmpty()) return null
            return nearby[Canonical.stream(world.seed,citizen.id,world.tick,"dunbar-contact-v1").nextInt(nearby.size)]
        }
        fun domain(action: Activity)=when(action) {
            Activity.WATER -> "ECOLOGY";Activity.EAT -> "AGRICULTURE";Activity.SLEEP,Activity.COFFEE -> "MEDICINE"
            Activity.TALK -> "GOVERNANCE";Activity.STUDY -> "LOGIC";Activity.WRITE -> "LANGUAGE";Activity.BUILD -> "ENGINEERING"
        }
        fun initialOpinion(world: WorldState,citizen: Citizen)=SocialOpinion(2*citizen.genome.sociability.express()-1,2*citizen.genome.sociability.express()-1,
            ((world.minds[citizen.id]?.personality?.get(0) ?: .4)+.25).coerceIn(.1,.9))
        fun hybrid(own: SocialOpinion,peer: Double,trust: Double,epsilon: Double=.45,mu: Double=.25): Double {
            require(own.valid() && peer.isFinite() && peer in -1.0..1.0 && trust.isFinite() && trust in 0.0..1.0 && epsilon.isFinite() && epsilon>0 && mu.isFinite() && mu in 0.0.. .5)
            val difference=peer-own.value
            val dw=own.value+if(abs(difference)<epsilon) mu*trust*difference else 0.0
            return (own.susceptibility*dw+(1-own.susceptibility)*own.intrinsic).coerceIn(-1.0,1.0)
        }
        fun balancedAffinity(ij: Double,jk: Double,ik: Double): Double {
            require(listOf(ij,jk,ik).all { it.isFinite() && it in 0.0..1.0 })
            val a=2*ij-1;val b=2*jk-1;val c=2*ik-1
            return if(a*b*c<0) ((c+.03*sign(a*b)).coerceIn(-1.0,1.0)+1)/2 else ik
        }
        fun measure(world: WorldState)=pressure(world.agents.size,world.rooms.sumOf { it.capacity }.coerceAtLeast(1).toDouble(),
            world.agents.map { (world.balances["${it.id}/coins"] ?: 0).toDouble() },world.agents.count { it.skill.mastery>=.75 },maxOf(1,ceil(world.agents.size/50.0).toInt()),
            (world.balances["${DoubleEntryEconomyAndStigmergy.COMMONS}/coins"] ?: 0).toDouble())
        fun pressure(population: Int,capacity: Double,wealth: List<Double>,aspirants: Int,slots: Int,treasury: Double): PoliticalPressure {
            require(population in 1..150 && wealth.size==population && capacity.isFinite() && capacity>=1 &&
                wealth.all { it.isFinite() && it>=0 } && aspirants in 0..population && slots>=1 && treasury.isFinite() && treasury>=0)
            val ordered=wealth.sorted();val total=ordered.sum()
            require(total.isFinite())
            val gini=if(total<=1e-9) 0.0 else (ordered.mapIndexed { i,v -> (2.0*(i+1)-population-1)*v }.sum()/(population*total)).coerceIn(0.0,1.0)
            val mmp=population/capacity;val emp=aspirants.toDouble()/slots;val fiscal=(treasury/(population*10.0)).coerceIn(.05,2.0)
            return PoliticalPressure(population,capacity,gini,mmp,emp,fiscal,mmp*(.35+gini)*emp/fiscal)
        }
    }
}
