package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Recorded input, never executable code. Model output is parsed separately from its provenance. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class NeuralProposal(
    val seed: Long, val sourceTick: Long, val agentId: String, val activity: Activity,
    val sourceStateHash: String, val modelHash: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val adapterHash: String?=null
) {
    fun applicable(world: WorldState): Boolean {
        if (seed != world.seed || sourceTick < 0 || sourceTick > world.tick || world.tick - sourceTick > 512) return false
        if (!sourceStateHash.matches(Regex("[a-f0-9]{64}")) || !modelHash.matches(Regex("[a-f0-9]{64}"))) return false
        if(adapterHash!=null && !adapterHash.matches(Regex("[a-f0-9]{64}"))) return false
        val agent = world.agents.firstOrNull { it.id == agentId } ?: return false
        // Critical needs and sleep completion always keep the existing utility policy.
        return agent.acceptsNeuralActivity()
    }
}

fun Citizen.acceptsNeuralActivity(): Boolean = needs.hydration >= .6 && needs.glucose >= .6 && needs.energy >= .6 &&
    !(activity == Activity.SLEEP && needs.energy < .9)

object NeuralProtocol {
    @Serializable private data class Answer(val activity: Activity)
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    fun parse(output: String): Activity {
        require(output.length <= 256) { "Saída neural excede o limite" }
        return json.decodeFromString<Answer>(output).activity
    }
    fun prompt(world: WorldState, citizen: Citizen): String {
        require(world.agents.any { it.id == citizen.id })
        val n = citizen.needs
        // No citizen-written text enters the prompt, only bounded enums/numbers.
        fun pct(value: Double) = (value * 100).toInt()
        return "Choose one next activity for a simulated citizen. Return only JSON {\"activity\":\"STUDY\"}. " +
            "Allowed: WATER, COFFEE, EAT, SLEEP, TALK, WRITE, STUDY, BUILD. " +
            "Needs satisfied (0=urgent, 100=full): energy=${pct(n.energy)}, water=${pct(n.hydration)}, " +
            "food=${pct(n.glucose)}, social=${pct(n.social)}, curiosity=${pct(n.curiosity)}. " +
            "Current=${citizen.activity.name}. Skill=${pct(citizen.skill.mastery)}. " +
            "Rooms=${world.rooms.size}. Building consumes timber and uses a generated parcel; writing consumes paper. " +
            "Timber=${world.balances["market/timber"] ?: 0}; paper=${world.balances["market/paper"] ?: 0}. " +
            "Recent actions=${citizen.memories.takeLast(3).joinToString(",") { it.action.name }}. " +
            "Prefer unmet needs and varied productive activity."
    }
}
