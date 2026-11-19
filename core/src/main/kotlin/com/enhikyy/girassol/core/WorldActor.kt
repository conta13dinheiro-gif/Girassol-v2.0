package com.enhikyy.girassol.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Single owner, staged proposals from one immutable perception snapshot. No Android or wall clock. */
class WorldActor(checkpoint: Checkpoint = Checkpoint(genesis(), 0, GENESIS_HASH), private val rulesVersion: Int = 2) {
    var state: WorldState = checkpoint.state
        private set
    private var eventId = checkpoint.eventId
    private var previousHash = checkpoint.eventHash
    init { require(rulesVersion in 1..7); require(state.version == 1); require(violations(state).isEmpty()) }
    fun checkpoint() = Checkpoint(state, eventId, previousHash)

    /** Same admitted intentions used by the native planner and by execution. */
    @Synchronized fun intentions(neural: NeuralProposal? = null,cognition: CognitiveFrame? = null): Map<String,Activity> {
        val base=state.copy(tick=state.tick+1,agents=state.agents.map { it.copy(needs=it.needs.metabolize()) })
        val admitted=neural?.takeIf { rulesVersion>=2 && it.applicable(state) }
        val input=cognition?.takeIf { rulesVersion>=4 && it.applicable(state) }
        return base.agents.associate { a->a.id to (if(a.id==admitted?.agentId) admitted.activity
            else if(input!=null && a.acceptsNeuralActivity()) { val proposed=input.decisions.getValue(a.id); if(rulesVersion>=6) UtilityGoapPlanner.choose(base,a,proposed) else proposed } else choose(a,base.tick,base)) }
    }

    @Synchronized fun step(neural: NeuralProposal? = null, cognition: CognitiveFrame? = null, spatial: SpatialFrame? = null): Transition {
        val tick = state.tick + 1
        // 1: Runtime input boundary. Thermal policy changes pacing outside this logical core.
        // 2: Environment, spatial trail and metabolism.
        val base = state.copy(tick = tick, agents = state.agents.map { it.copy(needs = it.needs.metabolize()) }, trail = state.trail.map { it * .97 })
        // 3-7: Perception, bounded memory, appraisal, utility and intention hysteresis.
        val admitted = neural?.takeIf { rulesVersion >= 2 && it.applicable(state) }
        val cognitiveInput=cognition?.snapshot()?.takeIf { rulesVersion>=4 && it.applicable(state) }
        val proposals = intentions(neural,cognitiveInput)
        val physics=spatial?.snapshot()?.takeIf { rulesVersion>=5 && it.applicable(state,proposals) }
        require(rulesVersion<5 || physics!=null) { "Resultado físico ausente ou incompatível" }
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
        val genealogy = base.genealogy.toMutableList()
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
            val pose=physics?.poses?.get(a.id)
            if(pose!=null) a=a.copy(x=pose.position.x,z=pose.position.z)
            val distance = hypot(room.x - a.x, room.z - a.z)
            if (if(pose!=null) !pose.reached else distance > .35) {
                val factor = if(distance>0) min(.45, distance) / distance else 0.0
                a = if(pose==null) a.copy(x = a.x + (room.x - a.x) * factor, z = a.z + (room.z - a.z) * factor, activity = action)
                    else a.copy(activity=action)
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
                            if (rulesVersion >= 6) createDescendant(state.seed,tick,a,partner,agents,balances,ties,words,genealogy)
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
                        success = (rulesVersion >= 6 || rooms.size < 10) && transfer(balances, "market", "structures", "timber", 4)
                        if (success) {
                            if (rulesVersion >= 6) rooms += ProceduralDistricts.nextAnnex(rooms,state.seed)
                            else {
                                val n = rooms.size - 6
                                rooms += Room("annex_$n", "Anexo ${n + 1}", 2.0 + n * 2.0, 9.0, 1.7, 1.5, 2)
                            }
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
        if (rulesVersion >= 7 && cognitiveInput != null) {
            cognitiveInput.innerSpeech.toSortedMap().entries.take(4).forEach { (citizenId, thought) ->
                val recent = artifacts.any {
                    it.author == citizenId && it.title == "Fala interna" && tick - it.tick < 30
                }
                if (!recent) artifacts += Artifact(tick,citizenId,"Fala interna",thought,eventId+1)
            }
            cognitiveInput.minds.entries
                .filter { it.value.psyche.ignitionIndex > .65 }
                .sortedBy { it.key }
                .take(4)
                .forEach { (citizenId, mind) ->
                    val recent = artifacts.any {
                        it.author == citizenId && it.title == "Ignição global GNW" && tick - it.tick < 30
                    }
                    if (!recent) {
                        val emotion = "P=${"%.2f".format(Locale.ROOT,mind.psyche.pleasure)} A=${"%.2f".format(Locale.ROOT,mind.psyche.arousal)} D=${"%.2f".format(Locale.ROOT,mind.psyche.dominance)}"
                        artifacts += Artifact(
                            tick, citizenId, "Ignição global GNW",
                            "Erro preditivo Anokhin=${"%.3f".format(Locale.ROOT,mind.psyche.lastPredictionError)}; índice de ignição=${"%.3f".format(Locale.ROOT,mind.psyche.ignitionIndex)}; PAD $emotion.",
                            eventId + 1
                        )
                    }
                }

            val agora = rooms.firstOrNull { it.name.startsWith("Ágora cívica") }
            if (cognitiveInput.socialSynchrony >= .90 && agora != null) {
                val participants = agents.filter {
                    kotlin.math.abs(it.x - agora.x) <= agora.width / 2.0 &&
                        kotlin.math.abs(it.z - agora.z) <= agora.depth / 2.0
                }.sortedBy { it.id }
                val recentRitual = artifacts.lastOrNull { it.title == "Ritual de fase na Ágora" }
                if (participants.size >= 4 && (recentRitual == null || tick - recentRitual.tick >= 240)) {
                    val names = participants.map { it.name }
                    artifacts += Artifact(
                        tick, "biosfera", "Ritual de fase na Ágora",
                        "Sincronização Kuramoto R=${"%.3f".format(Locale.ROOT,cognitiveInput.socialSynchrony)} reuniu ${names.joinToString(", ")} na Ágora.",
                        eventId + 1
                    )
                    val first = participants.first()
                    val second = participants.last()
                    val nativeWord = words.firstOrNull { it.lineage == first.lineage && it.concept == Activity.TALK }?.native ?: "va-ri"
                    dialogues += Dialogue(
                        tick, first.id, second.id, nativeWord,
                        "A fase coletiva encontrou um ritmo comum; ${names.size} habitantes participaram do ritual."
                    )
                }
            }
        }
        // 12: Bounded social/memory outputs; immutable publication follows validation.
        val next = base.copy(socialSynchrony=if(rulesVersion>=7) cognitiveInput?.socialSynchrony ?: 0.0 else base.socialSynchrony,spatial=physics?.poses ?: base.spatial,knowledge=base.knowledge+listOfNotNull(cognitiveInput?.lesson),evolution=cognitiveInput?.evolution ?: base.evolution,minds=cognitiveInput?.minds ?: base.minds, agents = agents.toList(), balances = balances.toMap(), ties = ties.toList(), words = words.toList(), rooms = rooms.toList(), dialogues = dialogues.takeLast(40), artifacts = artifacts.takeLast(24), actionCounts = counts.toMap(), trail = trails.toList(), genealogy = genealogy.toList())
        check(violations(next).isEmpty()) { violations(next).joinToString() }
        val raw = CanonicalEvent(version = if(rulesVersion>=7 && physics!=null && cognitiveInput!=null) 7 else if(physics!=null) { if(rulesVersion>=6) 6 else 5 } else if(cognitiveInput!=null) 4 else if (admitted != null) 3 else minOf(rulesVersion, 2), spatial=physics,cognition=cognitiveInput, neural = admitted, eventId = eventId + 1, tick = tick, actions = records.toList(), stateHash = Canonical.hash(Canonical.state(next)), previousHash = previousHash)
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
        val result = shadow.step(event.neural,event.cognition,event.spatial)
        require(result.event == event) { "Replay divergente" }
        state = result.state; eventId = event.eventId; previousHash = event.hash
    }

    /**
     * A descendant is created only after reciprocal social trust is established.
     * Pair order, inherited loci, name and location all derive from the canonical
     * world seed and event tick, so replay reproduces the exact family record.
     */
    private fun createDescendant(
        seed: Long,
        tick: Long,
        current: Citizen,
        partner: Citizen,
        agents: MutableList<Citizen>,
        balances: MutableMap<String, Int>,
        ties: MutableList<SocialTie>,
        words: MutableList<Word>,
        genealogy: MutableList<GenealogyRecord>
    ) {
        if (current.id >= partner.id) return
        val firstTie = ties.firstOrNull { it.from == current.id && it.to == partner.id } ?: return
        val secondTie = ties.firstOrNull { it.from == partner.id && it.to == current.id } ?: return
        if (minOf(firstTie.affinity, secondTie.affinity) < .90) return
        if (genealogy.any { it.parentOne == current.id && it.parentTwo == partner.id && tick - it.tick < 1440 }) return

        val generation = Math.addExact(maxOf(current.genome.generation, partner.genome.generation), 1)
        val childId = "child_${tick}_${current.id}_${partner.id}"
        if (agents.any { it.id == childId }) return
        val rng = Canonical.stream(seed, childId, tick, "inheritance")

        fun inherit(one: Locus, two: Locus): Locus = Locus(
            maternal = one.express(),
            paternal = two.express(),
            dominanceA = rng.nextDouble(),
            dominanceB = rng.nextDouble()
        )
        val genome = Genome(
            curiosity = inherit(current.genome.curiosity, partner.genome.curiosity),
            sociability = inherit(current.genome.sociability, partner.genome.sociability),
            generation = generation
        )
        val red = (((current.color shr 16) and 255) + ((partner.color shr 16) and 255)) / 2
        val green = (((current.color shr 8) and 255) + ((partner.color shr 8) and 255)) / 2
        val blue = ((current.color and 255) + (partner.color and 255)) / 2
        val color = 0xff000000L or (red shl 16) or (green shl 8) or blue
        val syllables = listOf("va", "ri", "na", "sol", "ka", "tha", "lu", "mi", "ae", "ron")
        val lineage = "${current.lineage}·${partner.lineage}".take(48)
        val child = Citizen(
            id = childId,
            name = "Descendente ${genealogy.size + 1}",
            lineage = lineage,
            color = color,
            genome = genome,
            x = (current.x + partner.x) / 2.0 + (rng.nextDouble() - .5) * .18,
            z = (current.z + partner.z) / 2.0 + (rng.nextDouble() - .5) * .18,
            needs = Needs(energy = .85, hydration = .85, glucose = .85, social = .65, curiosity = .7),
            activity = Activity.SLEEP,
            skill = listOf(current.skill, partner.skill).filter { it.shareable }
                .maxByOrNull { it.mastery }?.copy(lastPractice = tick) ?: Skill()
        )

        val existing = agents.toList()
        agents += child
        balances["$childId/coins"] = 0
        existing.forEach { citizen ->
            val affinity = if (citizen.id == current.id || citizen.id == partner.id) .72 else .42
            ties += SocialTie(citizen.id, childId, affinity)
            ties += SocialTie(childId, citizen.id, affinity)
        }
        Activity.entries.forEach { activity ->
            val left = words.first { it.lineage == current.lineage && it.concept == activity }.native
            val right = words.first { it.lineage == partner.lineage && it.concept == activity }.native
            val symbol = buildString {
                append(left.first())
                append(right.first())
                repeat(2) { append(syllables[rng.nextInt(syllables.size)]) }
            }
            words += Word(lineage, activity, symbol)
        }
        genealogy += GenealogyRecord(current.id, partner.id, childId, tick, Canonical.genomeHash(genome))
    }

    private fun choose(a: Citizen, tick: Long, world: WorldState): Activity {
        val fallback = chooseLegacy(a, tick, world)
        return if (rulesVersion >= 6) UtilityGoapPlanner.choose(world, a, fallback) else fallback
    }

    private fun chooseLegacy(a: Citizen, tick: Long, world: WorldState): Activity {
        val n = a.needs
        if (n.hydration < .42) return Activity.WATER
        if (n.glucose < .4) {
            // A citizen without coins can earn a wage by writing before visiting the market.
            if (rulesVersion >= 2 && (world.balances["${a.id}/coins"] ?: 0) == 0 &&
                (world.balances["market/coins"] ?: 0) > 0 &&
                ((world.balances["market/paper"] ?: 0) + (world.balances["archive/paper"] ?: 0)) > 0) return Activity.WRITE
            return Activity.EAT
        }
        if (n.energy < .38 || (a.activity == Activity.SLEEP && n.energy < .9)) return Activity.SLEEP
        if (!a.acting && tick % 16 != a.id.last().code % 16L) return a.activity
        val rng = Canonical.stream(world.seed, a.id, tick, "decision")
        val scores = listOf(
            Activity.TALK to ((1 - n.social) * 2 + a.genome.sociability.express() * .3),
            Activity.STUDY to ((1 - n.curiosity) * 1.6 + rng.nextDouble() * .3),
            Activity.WRITE to ((1 - n.curiosity) * 1.7 + rng.nextDouble() * .4),
            Activity.COFFEE to ((1 - n.energy) * 1.4 + rng.nextDouble() * .3),
            Activity.BUILD to (if (rulesVersion >= 6 || world.rooms.size < 10) .5 + rng.nextDouble() * .8 else -1.0)
        )
        return scores.maxBy { it.second + if (it.first == a.activity) .12 else 0.0 }.first
    }

    companion object {
        const val CURRENT_RULES_VERSION = 7
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
            if(!s.socialSynchrony.isFinite() || s.socialSynchrony !in 0.0..1.0) add("Sincronização social inválida")
            if(s.spatial.keys.any { id->s.agents.none { it.id==id } } || s.spatial.values.any { !it.valid() }) add("Estado espacial inválido")
            if (s.agents.isEmpty() || s.agents.map { it.id }.distinct().size != s.agents.size) add("Identidades inválidas")
            if(s.knowledge.any { !it.valid(s.tick) || s.agents.none { a->a.id==it.researcher } || s.agents.none { a->a.id==it.verifier } } || s.knowledge.map { it.id }.distinct().size!=s.knowledge.size) add("Proveniência de conhecimento inválida")
            if(s.evolution?.valid(s.seed,s.tick)==false) add("Política evolutiva inválida")
            if(s.minds.keys.any { id->s.agents.none { it.id==id } } || s.minds.values.any { !it.valid(s.tick) }) add("Estado cognitivo inválido")
            if(s.genealogy.map { it.child }.distinct().size!=s.genealogy.size || s.genealogy.any { !it.valid(s.tick,s.agents) }) add("Genealogia inválida")
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
