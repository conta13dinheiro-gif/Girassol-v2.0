package com.enhikyy.girassol.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Single owner, staged proposals from one immutable perception snapshot. No Android or wall clock. */
class WorldActor(checkpoint: Checkpoint = Checkpoint(genesis(), 0, GENESIS_HASH), private val rulesVersion: Int = CURRENT_RULES_VERSION) {
    var state: WorldState = checkpoint.state
        private set
    private var eventId = checkpoint.eventId
    private var previousHash = checkpoint.eventHash
    init { require(rulesVersion in 1..4); require(state.version == 1); require(violations(state).isEmpty()) }
    fun checkpoint() = Checkpoint(state, eventId, previousHash)

    @Synchronized fun step(neural: NeuralProposal? = null, cognition: CognitiveFrame? = null): Transition {
        val tick = state.tick + 1
        // 1: Runtime input boundary. Thermal policy changes pacing outside this logical core.
        // 2: Environment, spatial trail and metabolism.
        val base = state.copy(tick = tick, agents = state.agents.map { it.copy(needs = it.needs.metabolize()) }, trail = state.trail.map { it * .97 })
        // 3-7: Perception, bounded memory, appraisal, utility and intention hysteresis.
        val admitted = neural?.takeIf { rulesVersion >= 2 && it.applicable(state) && it.applicable(base) }
        val cognitiveInput=cognition?.snapshot()?.takeIf { rulesVersion>=4 && it.applicable(state) }
        val proposals = base.agents.associate { a -> a.id to
            (if (a.id == admitted?.agentId) admitted.activity
                else if(cognitiveInput!=null && a.acceptsNeuralActivity()) cognitiveInput.decisions.getValue(a.id) else choose(a, tick)) }
        // 8-9: Bounded typed neural input; arbitration below rechecks physical preconditions.
        val agents = base.agents.map { it.copy(acting = false) }.toMutableList()
        val balances = base.balances.toMutableMap()
        if (rulesVersion >= 2) {
            // Explicit returns from environmental sinks: no resource or currency is minted.
            if (tick % 2L == 0L) {
                transfer(balances, "environment", "market", "water", 1)
                transfer(balances, "environment", "market", "food", 1)
            }
            if (tick % 4L == 0L) transfer(balances, "environment", "market", "coffee", 1)
            if (tick % 6L == 0L) transfer(balances, "archive", "market", "paper", 1)
        }
        val ties = base.ties.toMutableList()
        val words = base.words.toMutableList()
        val dialogues = base.dialogues.toMutableList()
        val artifacts = base.artifacts.toMutableList()
        val rooms = base.rooms.toMutableList()
        val counts = base.actionCounts.toMutableMap()
        val trails = base.trail.toMutableList()
        val records = mutableListOf<ActionRecord>()
        val occupied = mutableMapOf<String, Int>()
        // 10: Rotating, deterministic arbitration prevents permanent first-agent priority.
        val order = agents.indices.map { (it + (tick % agents.size).toInt()) % agents.size }
        for (index in order) {
            var a = agents[index]
            val action = proposals.getValue(a.id)
            val room = base.rooms.first { it.id == action.room }
            val distance = hypot(room.x - a.x, room.z - a.z)
            if (distance > .35) {
                val factor = min(.45, distance) / distance
                a = a.copy(x = a.x + (room.x - a.x) * factor, z = a.z + (room.z - a.z) * factor, activity = action)
                records += ActionRecord(a.id, action, "MOVE")
            } else if ((occupied[room.id] ?: 0) < room.capacity) {
                // 11: All physical preconditions are checked again at execution time.
                occupied[room.id] = (occupied[room.id] ?: 0) + 1
                val before = a.needs
                var needs = before
                var success = true
                when (action) {
                    Activity.WATER -> { success = transfer(balances, "market", "environment", "water", 1); if (success) needs = needs.copy(hydration = .99) }
                    Activity.COFFEE -> { success = transfer(balances, "market", "environment", "coffee", 1); if (success) needs = needs.copy(energy = (needs.energy + .12).coerceAtMost(1.0)) }
                    Activity.EAT -> {
                        success = (balances["${a.id}/coins"] ?: 0) >= 1 && (balances["market/food"] ?: 0) >= 1
                        if (success) { check(transfer(balances, a.id, "market", "coins", 1)); check(transfer(balances, "market", "environment", "food", 1)); needs = needs.copy(glucose = .99) }
                    }
                    Activity.SLEEP -> needs = needs.copy(energy = (needs.energy + .1).coerceAtMost(1.0))
                    Activity.TALK -> {
                        val partner = base.agents.filter { it.id != a.id && hypot(it.x - a.x, it.z - a.z) <= 2.5 }.minByOrNull { it.id }
                        success = partner != null
                        if (partner != null) {
                            needs = needs.copy(social = (needs.social + .15).coerceAtMost(1.0))
                            val wi = words.indexOfFirst { it.lineage == a.lineage && it.concept == action }
                            val w = words[wi]; words[wi] = w.copy(uses = w.uses + 1)
                            val topics = a.memories.map { it.action }.filter { it != Activity.TALK }.distinct()
                            val topic = if (rulesVersion >= 2 && topics.isNotEmpty()) topics[Canonical.stream(state.seed, a.id, tick, "conversation").nextInt(topics.size)] else Activity.STUDY
                            val topicIndex = words.indexOfFirst { it.lineage == a.lineage && it.concept == topic }
                            val topicWord = words[topicIndex]
                            if (rulesVersion >= 2) words[topicIndex] = topicWord.copy(uses = topicWord.uses + 1)
                            val gloss = if (rulesVersion == 1) "Vamos conversar e compartilhar o que aprendemos." else when (topic) {
                                Activity.WATER -> "A fonte nos ajudou a recuperar as forças."
                                Activity.COFFEE -> "O café trouxe energia para continuar."
                                Activity.EAT -> "Compartilho o que descobri no mercado."
                                Activity.SLEEP -> "Descansar ajuda a começar um novo ciclo."
                                Activity.WRITE -> "Gostaria de contar o que escrevi."
                                Activity.STUDY -> "Vamos compartilhar o que aprendemos."
                                Activity.BUILD -> "Construímos novos espaços para a comunidade."
                                Activity.TALK -> "Vamos conversar um pouco."
                            }
                            dialogues += Dialogue(tick, a.name, partner.name, "${w.native} ${topicWord.native}.", gloss)
                            val ti = ties.indexOfFirst { it.from == a.id && it.to == partner.id }
                            if (ti >= 0) ties[ti] = ties[ti].copy(affinity = (ties[ti].affinity + .015).coerceAtMost(1.0))
                        }
                    }
                    Activity.STUDY -> { needs = needs.copy(curiosity = (needs.curiosity + .15).coerceAtMost(1.0)); a = a.copy(skill = a.skill.practice(tick)) }
                    Activity.WRITE -> {
                        success = transfer(balances, "market", "archive", "paper", 1)
                        if (success) {
                            needs = needs.copy(curiosity = (needs.curiosity + .25).coerceAtMost(1.0))
                            artifacts += Artifact(tick, a.name, "Caderno de ${a.name} · ${artifacts.size + 1}", "No ciclo $tick observei ${a.memories.takeLast(3).joinToString { it.action.label.lowercase() }.ifBlank { "a vida na colônia" }}. Compartilhar o conhecimento mantém nossa comunidade viva.", eventId + 1)
                            a = a.copy(skill = a.skill.practice(tick))
                        }
                    }
                    Activity.BUILD -> {
                        success = rooms.size < 10 && transfer(balances, "market", "structures", "timber", 4)
                        if (success) {
                            val n = rooms.size - 6
                            rooms += Room("annex_$n", "Anexo ${n + 1}", 2.0 + n * 2.0, 9.0, 1.7, 1.5, 2)
                            needs = needs.copy(curiosity = (needs.curiosity + .2).coerceAtMost(1.0))
                        }
                    }
                }
                if (success) {
                    // Wages are paid out of market coins after validated productive work.
                    if (rulesVersion >= 2 && action in setOf(Activity.WRITE, Activity.BUILD)) {
                        transfer(balances, "market", a.id, "coins", 1)
                    }
                    val reward = (needs.energy - before.energy) + (needs.hydration - before.hydration) + (needs.glucose - before.glucose) + (needs.social - before.social) + (needs.curiosity - before.curiosity)
                    val memory = Memory(tick, eventId + 1, action, reward, action == Activity.SLEEP)
                    a = a.copy(needs = needs, memories = (a.memories + memory).takeLast(32), acting = true)
                    counts[action.name] = (counts[action.name] ?: 0) + 1
                }
                records += ActionRecord(a.id, action, if (success) "OK" else "PRECONDITION_FAILED")
                a = a.copy(activity = action)
            } else records += ActionRecord(a.id, action, "RESERVED")
            val cell = a.x.toInt().coerceIn(0, 11) + 12 * a.z.toInt().coerceIn(0, 11)
            trails[cell] = (trails[cell] + .04).coerceAtMost(1.0)
            agents[index] = a
        }
        // 12: Bounded social/memory outputs; immutable publication follows validation.
        val next = base.copy(knowledge=base.knowledge+listOfNotNull(cognitiveInput?.lesson),evolution=cognitiveInput?.evolution ?: base.evolution,minds=cognitiveInput?.minds ?: base.minds, agents = agents.toList(), balances = balances.toMap(), ties = ties.toList(), words = words.toList(), rooms = rooms.toList(), dialogues = dialogues.takeLast(40), artifacts = artifacts.takeLast(24), actionCounts = counts.toMap(), trail = trails.toList())
        check(violations(next).isEmpty()) { violations(next).joinToString() }
        val raw = CanonicalEvent(version = if(cognitiveInput!=null) 4 else if (admitted != null) 3 else minOf(rulesVersion, 2), cognition=cognitiveInput, neural = admitted, eventId = eventId + 1, tick = tick, actions = records.toList(), stateHash = Canonical.hash(Canonical.state(next)), previousHash = previousHash)
        val event = raw.copy(hash = raw.computedHash())
        state = next; eventId = event.eventId; previousHash = event.hash
        return Transition(next, event)
    }

    /** Replaying an already applied envelope is a no-op; divergence never changes this actor. */
    @Synchronized fun replay(event: CanonicalEvent) {
        require(event.valid()) { "Hash de evento inválido" }
        if (event.eventId == eventId) { require(event.hash == previousHash); return }
        require(event.eventId == eventId + 1 && event.previousHash == previousHash && event.tick == state.tick + 1)
        val shadow = WorldActor(checkpoint(), event.version)
        val result = shadow.step(event.neural,event.cognition)
        require(result.event == event) { "Replay divergente" }
        state = result.state; eventId = event.eventId; previousHash = event.hash
    }

    private fun choose(a: Citizen, tick: Long): Activity {
        val n = a.needs
        if (n.hydration < .42) return Activity.WATER
        if (n.glucose < .4) {
            // A citizen without coins can earn a wage by writing before visiting the market.
            if (rulesVersion >= 2 && (state.balances["${a.id}/coins"] ?: 0) == 0 &&
                (state.balances["market/coins"] ?: 0) > 0 &&
                ((state.balances["market/paper"] ?: 0) + (state.balances["archive/paper"] ?: 0)) > 0) return Activity.WRITE
            return Activity.EAT
        }
        if (n.energy < .38 || (a.activity == Activity.SLEEP && n.energy < .9)) return Activity.SLEEP
        if (!a.acting && tick % 16 != a.id.last().code % 16L) return a.activity
        val rng = Canonical.stream(state.seed, a.id, tick, "decision")
        val scores = listOf(
            Activity.TALK to ((1 - n.social) * 2 + a.genome.sociability.express() * .3),
            Activity.STUDY to ((1 - n.curiosity) * 1.6 + rng.nextDouble() * .3),
            Activity.WRITE to ((1 - n.curiosity) * 1.7 + rng.nextDouble() * .4),
            Activity.COFFEE to ((1 - n.energy) * 1.4 + rng.nextDouble() * .3),
            Activity.BUILD to (if (state.rooms.size < 10) .5 + rng.nextDouble() * .8 else -1.0)
        )
        return scores.maxBy { it.second + if (it.first == a.activity) .12 else 0.0 }.first
    }

    companion object {
        const val CURRENT_RULES_VERSION = 2
        val GENESIS_HASH = "0".repeat(64)
        fun genesis(seed: Long = 20261119): WorldState {
            val rooms = listOf(Room("salon", "Sala solar", 3.0, 3.0, 3.8, 2.6, 6), Room("bedroom", "Dormitório Aurora", 8.0, 2.5, 3.8, 2.5, 3), Room("cafe", "Café & fonte", 2.5, 6.5, 3.0, 2.0, 2), Room("market", "Mercado", 8.5, 6.5, 3.0, 2.0, 2), Room("library", "Biblioteca", 5.5, .6, 3.0, 1.4, 1), Room("workshop", "Oficina", 5.4, 9.0, 2.3, 1.8, 1))
            val names = listOf("Ayla", "Kael", "Lira", "Orin", "Soren", "Nima")
            val colors = listOf(0xFFE9C46A, 0xFF83B9C4, 0xFFC8A1CE, 0xFF9DBE8F, 0xFFDD9471, 0xFFAAC9AE)
            val agents = names.mapIndexed { i, name ->
                val rng = Canonical.stream(seed, name, 0, "genome")
                fun locus() = Locus(.3 + rng.nextDouble() * .6, .3 + rng.nextDouble() * .6, rng.nextDouble(), rng.nextDouble())
                Citizen("agent$i", name, listOf("Sol–Vael", "Kora–Thal", "Nima–Ril")[i % 3], colors[i], Genome(locus(), locus()), 2.0 + i * 1.2, 4.5, Needs(energy = .45 + i * .07, hydration = .35 + i * .08, curiosity = .3 + i * .08), activity = Activity.entries[i])
            }
            val balances = linkedMapOf<String, Int>()
            mapOf("water" to 5000, "food" to 5000, "coffee" to 1500, "timber" to 200, "paper" to 2000, "coins" to 500).forEach { (k,v) -> balances["market/$k"] = v }
            agents.forEach { balances["${it.id}/coins"] = 24 }
            val totals = balances.entries.groupBy { it.key.substringAfter('/') }.mapValues { (_,v) -> v.sumOf { it.value } }
            val words = listOf("Sol–Vael", "Kora–Thal", "Nima–Ril").flatMap { lineage -> Activity.entries.map { concept ->
                val rng = Canonical.stream(seed, lineage, 0, concept.name)
                val syllables = listOf("va", "ri", "na", "sol", "ka", "tha", "lu", "mi", "ae", "ron")
                Word(lineage, concept, syllables[rng.nextInt(syllables.size)] + syllables[rng.nextInt(syllables.size)] + syllables[rng.nextInt(syllables.size)])
            } }
            return WorldState(seed = seed, agents = agents, rooms = rooms, balances = balances, initialTotals = totals,
                ties = agents.flatMap { a -> agents.filter { it.id != a.id }.map { b -> SocialTie(a.id, b.id, if (a.lineage == b.lineage) .65 else .4) } }, words = words)
        }
        fun transfer(balances: MutableMap<String, Int>, from: String, to: String, item: String, amount: Int): Boolean {
            if (amount <= 0 || from == to) return false
            val source = "$from/$item"; val dest = "$to/$item"
            val sourceValue = balances[source] ?: 0; val destValue = balances[dest] ?: 0
            if (sourceValue < amount || destValue > Int.MAX_VALUE - amount) return false
            balances[source] = sourceValue - amount; balances[dest] = destValue + amount
            return true
        }
        fun violations(s: WorldState): List<String> = buildList {
            if (s.agents.isEmpty() || s.agents.map { it.id }.distinct().size != s.agents.size) add("Identidades inválidas")
            if(s.knowledge.any { !it.valid(s.tick) || s.agents.none { a->a.id==it.researcher } || s.agents.none { a->a.id==it.verifier } } || s.knowledge.map { it.id }.distinct().size!=s.knowledge.size) add("Proveniência de conhecimento inválida")
            if(s.evolution?.valid(s.seed,s.tick)==false) add("Política evolutiva inválida")
            if(s.minds.keys.any { id->s.agents.none { it.id==id } } || s.minds.values.any { !it.valid(s.tick) }) add("Estado cognitivo inválido")
            if (s.balances.values.any { it < 0 }) add("Estoque negativo")
            val totals = s.balances.entries.groupBy { it.key.substringAfter('/') }.mapValues { (_,v) -> v.sumOf { it.value.toLong() } }
            if (totals != s.initialTotals.mapValues { it.value.toLong() }) add("Conservação violada")
            if (s.agents.any { !it.needs.valid() || !it.x.isFinite() || !it.z.isFinite() }) add("Homeostase inválida")
            if (s.agents.any { a -> a.memories.size > 32 || a.memories.any { it.eventId <= 0 || it.tick > s.tick } }) add("Memória inválida")
            if (s.ties.any { it.from == it.to || !it.affinity.isFinite() || it.affinity !in 0.0..1.0 }) add("Vínculo inválido")
            val t = s.telemetry()
            if (listOf(t.entropy, t.gini, t.socialCohesion, t.stress).any { !it.isFinite() || it < -1e-9 || it > 1.00000001 }) add("Telemetria inválida")
            if (s.trail.size != 144 || s.trail.any { !it.isFinite() || it !in 0.0..1.0 }) add("Grade inválida")
        }
    }
}
