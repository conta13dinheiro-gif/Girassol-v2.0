package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.ln

object Canonical {
    private val json = Json { encodeDefaults = true; allowStructuredMapKeys = false }
    fun state(value: WorldState): String = json.encodeToString(value)
    fun state(value: String): WorldState = json.decodeFromString(value)
    fun event(value: CanonicalEvent): String = json.encodeToString(value)
    fun event(value: String): CanonicalEvent = json.decodeFromString(value)
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun stream(seed: Long, id: String, tick: Long, name: String): DeterministicRng {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$seed:${id.length}:$id:$tick:${name.length}:$name".toByteArray(Charsets.UTF_8))
        return DeterministicRng(ByteBuffer.wrap(bytes).long)
    }
}

class DeterministicRng(private var state: Long) {
    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    fun nextDouble(): Double = (nextLong() ushr 11) * 1.1102230246251565E-16
    fun nextInt(bound: Int): Int { require(bound > 0); return ((nextLong() ushr 1) % bound).toInt() }
}

@Serializable enum class Activity(val label: String, val room: String) {
    WATER("Beber água", "cafe"), COFFEE("Tomar café", "cafe"), EAT("Fazer compras", "market"),
    SLEEP("Dormir", "bedroom"), TALK("Conversar", "salon"), WRITE("Escrever", "library"),
    STUDY("Estudar", "library"), BUILD("Construir", "workshop")
}
@Serializable data class Needs(val energy: Double = .8, val hydration: Double = .8, val glucose: Double = .8, val social: Double = .7, val curiosity: Double = .6) {
    fun metabolize() = copy(energy = (energy - .002).coerceAtLeast(0.0), hydration = (hydration - .003).coerceAtLeast(0.0), glucose = (glucose - .002).coerceAtLeast(0.0), social = (social - .002).coerceAtLeast(0.0), curiosity = (curiosity - .001).coerceAtLeast(0.0))
    fun valid() = listOf(energy, hydration, glucose, social, curiosity).all { it.isFinite() && it in 0.0..1.0 }
}
@Serializable data class Locus(val maternal: Double, val paternal: Double, val dominanceA: Double, val dominanceB: Double) {
    init { require(listOf(maternal, paternal, dominanceA, dominanceB).all { it.isFinite() && it in 0.0..1.0 }) }
    fun express() = if (dominanceA + dominanceB == 0.0) (maternal + paternal) / 2 else (maternal * dominanceA + paternal * dominanceB) / (dominanceA + dominanceB)
}
@Serializable data class Genome(val curiosity: Locus, val sociability: Locus, val generation: Int = 1)
@Serializable data class Memory(val tick: Long, val eventId: Long, val action: Activity, val reward: Double, val semantic: Boolean = false)
@Serializable data class Skill(val mastery: Double = .2, val successes: Int = 0, val lastPractice: Long = 0) {
    fun practice(tick: Long): Skill {
        val posterior = mastery * .9 / (mastery * .9 + (1 - mastery) * .2)
        return Skill((posterior + (1 - posterior) * .08).coerceIn(0.0, 1.0), successes + 1, tick)
    }
    val shareable get() = successes >= 3
}
@Serializable data class Citizen(val id: String, val name: String, val lineage: String, val color: Long, val genome: Genome,
    val x: Double, val z: Double, val needs: Needs, val activity: Activity = Activity.TALK,
    val acting: Boolean = false, val memories: List<Memory> = emptyList(), val skill: Skill = Skill())
@Serializable data class Room(val id: String, val name: String, val x: Double, val z: Double, val width: Double, val depth: Double, val capacity: Int)
@Serializable data class SocialTie(val from: String, val to: String, val affinity: Double = .45, val alpha: Double = 1.0, val beta: Double = 1.0) {
    val trust get() = alpha / (alpha + beta)
}
@Serializable data class Word(val lineage: String, val concept: Activity, val native: String, val uses: Int = 0)
@Serializable data class Dialogue(val tick: Long, val speaker: String, val listener: String, val native: String, val portuguese: String)
@Serializable data class Artifact(val tick: Long, val author: String, val title: String, val text: String, val sourceEventId: Long)
@Serializable data class ActionRecord(val agentId: String, val activity: Activity, val outcome: String)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class WorldState(val version: Int = 1, val seed: Long, val tick: Long = 0,
    val agents: List<Citizen>, val rooms: List<Room>, val balances: Map<String, Int>,
    val initialTotals: Map<String, Int>, val ties: List<SocialTie>, val words: List<Word>,
    val dialogues: List<Dialogue> = emptyList(), val artifacts: List<Artifact> = emptyList(),
    val actionCounts: Map<String, Int> = emptyMap(), val trail: List<Double> = List(144) { 0.0 },
    @EncodeDefault(EncodeDefault.Mode.NEVER) val minds: Map<String,MindState> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evolution: PolicyRevision? = null)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CanonicalEvent(val version: Int = 1, val eventId: Long, val tick: Long,
    val actions: List<ActionRecord>, val stateHash: String, val previousHash: String, val hash: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val neural: NeuralProposal? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cognition: CognitiveFrame? = null) {
    fun computedHash(): String = Canonical.hash(Canonical.event(copy(hash = "")))
    fun valid() = version in 1..4 && (when(version) { 1,2 -> neural==null && cognition==null; 3 -> neural!=null && cognition==null; else -> cognition!=null }) && hash == computedHash()
}
data class Transition(val state: WorldState, val event: CanonicalEvent)
data class Checkpoint(val state: WorldState, val eventId: Long, val eventHash: String)
data class Telemetry(val entropy: Double, val gini: Double, val socialCohesion: Double, val stress: Double)

fun WorldState.telemetry(): Telemetry {
    val counts = Activity.entries.map { actionCounts[it.name] ?: 0 }
    val total = counts.sum().toDouble()
    val entropy = if (total == 0.0) 0.0 else -counts.filter { it > 0 }.sumOf { val p = it / total; p * ln(p) } / ln(Activity.entries.size.toDouble())
    val wealth = agents.map { balances["${it.id}/coins"] ?: 0 }.sorted()
    val sum = wealth.sum().toDouble()
    val gini = if (sum == 0.0) 0.0 else wealth.mapIndexed { i, v -> (2.0 * (i + 1) - wealth.size - 1) * v }.sum() / (wealth.size * sum)
    val cohesion = ties.map { it.affinity }.average().takeIf { it.isFinite() } ?: 0.0
    val scarcity = 1 - agents.map { (it.needs.energy + it.needs.hydration + it.needs.glucose) / 3 }.average()
    return Telemetry(entropy, gini, cohesion, (scarcity * (1 - cohesion)).coerceIn(0.0, 1.0))
}
