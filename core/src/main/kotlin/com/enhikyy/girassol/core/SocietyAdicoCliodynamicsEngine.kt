package com.enhikyy.girassol.core
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

enum class DunbarRing { INNER_5, CLOSE_15, COMMUNITY_50, PUBLIC_150, OUTER }
enum class AdicoDeontic { MUST, MAY, MUST_NOT }
data class DunbarRelationship(val otherAgentId: String, val rank: Int, val ring: DunbarRing, val trust: Double)
data class AdicoRuleDecision(val attributes: String, val deontic: AdicoDeontic, val aim: Activity, val condition: String, val objectOrElse: String, val conditionMet: Boolean, val utility: Double)
class SocialGraphIndex internal constructor(
    internal val outgoingBySource: Map<String, List<SocialTie>>,
    internal val directedEdges: Map<Pair<String, String>, SocialTie>,
    val cliodynamicsPsi: Double
)

data class SocietyCognitionResult(
    val relationships: List<DunbarRelationship>, val generalDomainTrust: Double,
    val deffuantOpinion: Double, val friedkinJohnsenOpinion: Double, val heiderFrustration: Double,
    val cliodynamicsPsi: Double, val adicoDecisions: List<AdicoRuleDecision>,
    val utilityByActivity: Map<Activity, Double>
)

/** Deterministic social-policy projection; WorldActor remains the sole canonical state writer. */
object SocietyAdicoCliodynamicsEngine {
    fun buildGraphIndex(world: WorldState): SocialGraphIndex = SocialGraphIndex(
        outgoingBySource = world.ties.groupBy { it.from },
        directedEdges = world.ties.associateBy { it.from to it.to },
        cliodynamicsPsi = cliodynamicsPsi(world)
    )

    fun evaluate(world: WorldState, citizen: Citizen): SocietyCognitionResult =
        evaluate(world, citizen, buildGraphIndex(world))

    fun evaluate(world: WorldState, citizen: Citizen, graph: SocialGraphIndex): SocietyCognitionResult {
    fun classifyDunbarRing(rank: Int): DunbarRing {
        require(rank > 0)
        return when {
            rank <= 5 -> DunbarRing.INNER_5
            rank <= 15 -> DunbarRing.CLOSE_15
            rank <= 50 -> DunbarRing.COMMUNITY_50
            rank <= 150 -> DunbarRing.PUBLIC_150
            else -> DunbarRing.OUTER
        }
    }

    fun deffuantStep(currentOpinion: Double, peerOpinion: Double, trust: Double, confidenceBoundEpsilon: Double = 0.45, convergenceMu: Double = 0.25): Double {
        require(currentOpinion.isFinite() && currentOpinion in -1.0..1.0)
        require(peerOpinion.isFinite() && peerOpinion in -1.0..1.0)
        require(trust.isFinite() && trust in 0.0..1.0)
        require(confidenceBoundEpsilon.isFinite() && confidenceBoundEpsilon in 0.0..2.0)
        require(convergenceMu.isFinite() && convergenceMu in 0.0..1.0)
        val difference = peerOpinion - currentOpinion
        return if (abs(difference) <= confidenceBoundEpsilon) (currentOpinion + convergenceMu * trust * difference).coerceIn(-1.0, 1.0) else currentOpinion
    }

    fun friedkinJohnson(sociallyInfluencedOpinion: Double, intrinsicOpinion: Double, susceptibility: Double): Double {
        require(sociallyInfluencedOpinion.isFinite() && sociallyInfluencedOpinion in -1.0..1.0)
        require(intrinsicOpinion.isFinite() && intrinsicOpinion in -1.0..1.0)
        require(susceptibility.isFinite() && susceptibility in 0.0..1.0)
        return (susceptibility * sociallyInfluencedOpinion + (1.0 - susceptibility) * intrinsicOpinion).coerceIn(-1.0, 1.0)
    }

        require(world.agents.any { it.id == citizen.id }) { "Citizen is not part of this world" }
        val outgoing = graph.outgoingBySource[citizen.id].orEmpty()
            .sortedWith(compareByDescending<SocialTie> { betaTrust(it) }.thenBy { it.to })
        val relationships = outgoing.mapIndexed { index, tie -> DunbarRelationship(tie.to, index + 1, classifyDunbarRing(index + 1), betaTrust(tie)) }
        val trust = outgoing.map(::betaTrust).average().let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.5 }
        val intrinsic = (citizen.genome.sociability.express() * 2.0 - 1.0).coerceIn(-1.0, 1.0)
        var deffuant = outgoing.map { it.affinity * 2.0 - 1.0 }.average().takeIf { it.isFinite() } ?: intrinsic
        for (tie in outgoing) deffuant = deffuantStep(deffuant, tie.affinity * 2.0 - 1.0, betaTrust(tie))
        val susceptibility = (0.25 + 0.5 * citizen.genome.curiosity.express()).coerceIn(0.0, 1.0)
        val fjOpinion = friedkinJohnson(deffuant, intrinsic, susceptibility)
        val heider = heiderFrustration(graph, citizen.id)
        val psi = graph.cliodynamicsPsi
        val peerPresent = world.agents.any { it.id != citizen.id && hypot(it.x - citizen.x, it.z - citizen.z) <= 2.5 }
        fun stock(name: String) = (world.balances["market/$name"] ?: 0).coerceAtLeast(0)
        val coins = (world.balances[citizen.id + "/coins"] ?: 0).coerceAtLeast(0)
        val rules = listOf(
            Rule(AdicoDeontic.MUST, Activity.WATER, "hydration below 0.18", "restore homeostasis", citizen.needs.hydration < 0.18),
            Rule(AdicoDeontic.MUST, Activity.EAT, "glucose below 0.18 and food and coins available", "restore nutrition", citizen.needs.glucose < 0.18 && coins > 0 && stock("food") > 0),
            Rule(AdicoDeontic.MUST, Activity.SLEEP, "energy below 0.12", "restore energy", citizen.needs.energy < 0.12),
            Rule(AdicoDeontic.MUST_NOT, Activity.BUILD, "communal timber below four units", "preserve shared construction stock", stock("timber") < 4),
            Rule(AdicoDeontic.MUST_NOT, Activity.WRITE, "communal paper unavailable", "preserve archive stock", stock("paper") <= 0),
            Rule(AdicoDeontic.MUST_NOT, Activity.TALK, "no peer in interaction range", "avoid empty social transaction", !peerPresent),
            Rule(AdicoDeontic.MAY, Activity.TALK, "peer in interaction range", "permit reciprocal conversation", peerPresent)
        )
        val decisions = rules.map { rule ->
            val utility = if (!rule.conditionMet) 0.0 else when (rule.deontic) {
                AdicoDeontic.MUST -> 0.30
                AdicoDeontic.MAY -> 0.035
                AdicoDeontic.MUST_NOT -> -0.45
            }
            AdicoRuleDecision(
                attributes = "citizen:" + citizen.id + "; institution:biosphere-commons",
                deontic = rule.deontic, aim = rule.aim, condition = rule.condition,
                objectOrElse = rule.objectOrElse, conditionMet = rule.conditionMet, utility = utility
            )
        }
        val utilities = Activity.entries.associateWith { activity ->
            val social = when (activity) {
                Activity.TALK -> (1.0 - citizen.needs.social) * 0.16 + fjOpinion * 0.035 + trust * 0.04 - heider * 0.05
                Activity.BUILD -> -psi.coerceAtMost(5.0) * 0.008
                Activity.STUDY, Activity.WRITE -> maxOf(0.0, fjOpinion) * 0.012
                else -> 0.0
            }
            social + decisions.filter { it.aim == activity }.sumOf { it.utility }
        }
        return SocietyCognitionResult(relationships, trust, deffuant, fjOpinion, heider, psi, decisions, utilities)
    }

    private data class Rule(val deontic: AdicoDeontic, val aim: Activity, val condition: String, val objectOrElse: String, val conditionMet: Boolean)
    private fun betaTrust(tie: SocialTie): Double {
        val alpha = tie.alpha.coerceAtLeast(1e-9)
        val beta = tie.beta.coerceAtLeast(1e-9)
        return (alpha / (alpha + beta)).coerceIn(0.0, 1.0)
    }
    private fun heiderFrustration(graph: SocialGraphIndex, source: String): Double {
        val first = graph.outgoingBySource[source].orEmpty()
        if (first.isEmpty()) return 0.0
        var total = 0
        var imbalanced = 0
        for (ij in first) {
            for (jk in graph.outgoingBySource[ij.to].orEmpty()) {
                if (jk.to == source) continue
                val ik = graph.directedEdges[source to jk.to] ?: continue
                total++
                if (ij.affinity * jk.affinity * ik.affinity < 0.0) imbalanced++
            }
        }
        return if (total == 0) 0.0 else imbalanced.toDouble() / total
    }
    private fun cliodynamicsPsi(world: WorldState): Double {
        val population = world.agents.size
        if (population == 0) return 0.0
        val capacity = world.rooms.sumOf { it.capacity }.coerceAtLeast(1)
        val wealth = world.agents.map { (world.balances[it.id + "/coins"] ?: 0).coerceAtLeast(0).toDouble() }.sorted()
        val sum = wealth.sum()
        val gini = if (sum <= 1e-9 || wealth.size <= 1) 0.0 else {
            val weighted = wealth.mapIndexed { index, value -> (2.0 * (index + 1) - wealth.size - 1) * value }.sum()
            (weighted / (wealth.size * sum)).coerceIn(0.0, 1.0)
        }
        // A measurable sociability+curiosity proxy, not a claimed political identity.
        val aspirants = world.agents.count { it.genome.sociability.express() >= 0.70 && it.genome.curiosity.express() >= 0.70 }
        val leadershipSlots = ceil(population / 10.0).toInt().coerceAtLeast(1)
        val treasury = (world.balances["market/coins"] ?: 0).coerceAtLeast(0).toDouble()
        val mmp = population.toDouble() / capacity
        val emp = aspirants.toDouble() / leadershipSlots
        val sfd = (treasury / (population * 10.0)).coerceIn(0.05, 2.0)
        return ((mmp * (0.35 + gini) * emp) / sfd).takeIf { it.isFinite() }?.coerceIn(0.0, 20.0) ?: 20.0
    }
}
