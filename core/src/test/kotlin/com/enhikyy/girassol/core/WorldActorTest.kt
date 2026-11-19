package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class WorldActorTest {
    @Test fun identicalSeedAndInputsProduceIdenticalEvents() {
        val a = WorldActor(); val b = WorldActor()
        repeat(300) { assertEquals(a.step(), b.step()) }
        assertNotEquals(Canonical.state(a.state), Canonical.state(WorldActor.genesis(10)))
    }
    @Test fun streamsDoNotDependOnOtherAgentsDraws() {
        val expected = Canonical.stream(42, "A", 30, "decision").nextLong()
        repeat(50) { Canonical.stream(42, "B", it.toLong(), "birth").nextLong() }
        assertEquals(expected, Canonical.stream(42, "A", 30, "decision").nextLong())
        assertNotEquals(expected, Canonical.stream(42, "A", 30, "genome").nextLong())
    }
    @Test fun serializationAndCheckpointResumePreserveFuture() {
        val actor = WorldActor()
        repeat(111) { actor.step() }
        val cp = actor.checkpoint()
        val restored = WorldActor(cp.copy(state = Canonical.state(Canonical.state(cp.state))))
        repeat(150) { assertEquals(actor.step(), restored.step()) }
    }
    @Test fun replayIsIdempotentAndRejectsTamperingWithoutMutation() {
        val a = WorldActor(); val b = WorldActor()
        repeat(100) { val t = a.step(); b.replay(t.event); b.replay(t.event); assertEquals(a.checkpoint(), b.checkpoint()) }
        val before = b.checkpoint()
        val next = a.step().event
        assertThrows(IllegalArgumentException::class.java) { b.replay(next.copy(stateHash = "tampered")) }
        assertEquals(before, b.checkpoint())
        val falseEvent = next.copy(stateHash = "different").let { it.copy(hash = it.computedHash()) }
        assertThrows(IllegalArgumentException::class.java) { b.replay(falseEvent) }
        assertEquals(before, b.checkpoint())
    }
    @Test fun closedEconomyRejectsInvalidAndOverflowingTransfersAtomically() {
        val b = mutableMapOf("a/coins" to 10, "b/coins" to 5)
        val before = b.toMap()
        for (amount in listOf(-1, 0, 11, Int.MAX_VALUE)) assertFalse(WorldActor.transfer(b, "a", "b", "coins", amount))
        assertFalse(WorldActor.transfer(b, "a", "a", "coins", 1))
        assertEquals(before, b)
        assertTrue(WorldActor.transfer(b, "a", "b", "coins", 7))
        assertEquals(15, b.values.sum())
        val overflow = mutableMapOf("a/coins" to 10, "b/coins" to Int.MAX_VALUE)
        assertFalse(WorldActor.transfer(overflow, "a", "b", "coins", 1))
        assertEquals(10, overflow["a/coins"])
    }
    @Test fun longRunPreservesConservationMemoryBoundsAndRealActivity() {
        val a = WorldActor()
        val counts = mutableMapOf<Activity, Int>()
        repeat(2000) {
            val t = a.step()
            assertTrue(WorldActor.violations(t.state).isEmpty())
            assertTrue(t.event.valid())
            t.event.actions.filter { it.outcome == "OK" }.forEach { counts[it.activity] = (counts[it.activity] ?: 0) + 1 }
        }
        assertTrue(a.state.agents.all { it.memories.size <= 32 })
        assertTrue(a.state.dialogues.isNotEmpty())
        assertTrue(a.state.artifacts.isNotEmpty())
        assertTrue(a.state.rooms.size > 6)
        assertEquals(Activity.entries.toSet(), counts.keys)
    }
    @Test fun movementDoesNotApplyDistantResourceEffects() {
        val initial = WorldActor.genesis()
        val agents = initial.agents.map { it.copy(x = 11.0, z = 11.0, needs = it.needs.copy(hydration = .1), activity = Activity.WATER) }
        val a = WorldActor(Checkpoint(initial.copy(agents = agents), 0, WorldActor.GENESIS_HASH))
        val t = a.step()
        assertTrue(t.event.actions.all { it.outcome == "MOVE" })
        assertEquals(initial.balances, t.state.balances)
        assertTrue(t.state.agents.all { it.needs.hydration < .1 })
    }
    @Test fun exclusiveLibraryAllowsOnlyOneAgentEachTick() {
        val initial = WorldActor.genesis()
        val room = initial.rooms.first { it.id == "library" }
        val agents = initial.agents.map { it.copy(x = room.x, z = room.z, activity = Activity.WRITE, needs = Needs()) }
        val a = WorldActor(Checkpoint(initial.copy(agents = agents), 0, WorldActor.GENESIS_HASH))
        val t = a.step()
        assertEquals(1, t.event.actions.count { it.outcome == "OK" && it.activity == Activity.WRITE })
        assertTrue(t.event.actions.any { it.outcome == "RESERVED" })
        assertEquals(1, t.state.artifacts.size)
    }
    @Test fun eventHashCoversActionsTickStateAndPreviousHash() {
        val e = WorldActor().step().event
        assertTrue(e.valid())
        assertFalse(e.copy(tick = 9).valid())
        assertFalse(e.copy(previousHash = "x").valid())
        assertFalse(e.copy(actions = emptyList()).valid())
        assertEquals(e, Canonical.event(Canonical.event(e)))
    }
    @Test fun snapshotsAlreadyPublishedRemainUnchanged() {
        val a = WorldActor(); val old = a.state; val payload = Canonical.state(old)
        repeat(20) { a.step() }
        assertEquals(payload, Canonical.state(old))
        assertEquals(0L, old.tick)
    }
    @Test fun skillMustHaveThreeValidatedPracticesBeforeSharing() {
        var skill = Skill()
        repeat(2) { skill = skill.practice(it.toLong()); assertFalse(skill.shareable) }
        skill = skill.practice(3)
        assertTrue(skill.shareable)
        assertTrue(skill.mastery > .2 && skill.mastery <= 1)
    }
    @Test fun dominanceDoesNotUseLearnedMastery() {
        val locus = Locus(.2, .8, .25, .75)
        assertEquals(.65, locus.express(), .000001)
        val genome = WorldActor.genesis().agents.first().genome
        val a = WorldActor(); repeat(100) { a.step() }
        assertEquals(genome, a.state.agents.first().genome)
    }
    @Test fun goapPlansToEarnCoinsBeforePurchasingFood() {
        val base = WorldActor.genesis()
        val hungry = base.agents.first().copy(
            needs = Needs(energy = 1.0, hydration = 1.0, glucose = .1, social = 1.0, curiosity = 1.0)
        )
        val world = base.copy(
            agents = base.agents.map { if (it.id == hungry.id) hungry else it },
            balances = base.balances + ("${hungry.id}/coins" to 0)
        )
        val before = Canonical.state(world)
        assertEquals(Activity.WRITE, UtilityGoapPlanner.choose(world, hungry, Activity.STUDY))
        assertEquals(before, Canonical.state(world))
    }

    @Test fun goapAvoidsAnUnavailableResourceAndUsesAnotherValidGoal() {
        val base = WorldActor.genesis()
        val citizen = base.agents.first().copy(
            needs = Needs(energy = 1.0, hydration = .1, glucose = 1.0, social = .1, curiosity = 1.0)
        )
        val world = base.copy(
            agents = base.agents.map { if (it.id == citizen.id) citizen else it },
            balances = base.balances + mapOf(
                "market/water" to 0,
                "market/food" to 0,
                "${citizen.id}/coins" to 0
            )
        )
        assertEquals(Activity.TALK, UtilityGoapPlanner.choose(world, citizen, Activity.WATER))
    }

    @Test fun versionSixGoapEventsReplayWithoutChangingHistoricalRules() {
        val origin = WorldActor.genesis()
        val a = WorldActor(Checkpoint(origin, 0, WorldActor.GENESIS_HASH), 6)
        val b = WorldActor(Checkpoint(origin, 0, WorldActor.GENESIS_HASH), 6)
        val intents = a.intentions()
        val poses = origin.agents.associate { agent ->
            val point = SpatialPoint(agent.x, 0.0, agent.z)
            agent.id to SpatialPose(point, 0.0, 0.0, 0.0, 0.0, false, listOf(point))
        }
        val frame = SpatialFrame(
            sourceTick = origin.tick,
            sourceHash = Canonical.hash(Canonical.state(origin)),
            seed = origin.seed,
            intentions = intents,
            poses = poses
        )
        val transition = a.step(spatial = frame)
        assertEquals(6, transition.event.version)
        assertTrue(transition.event.valid())
        b.replay(transition.event)
        assertEquals(a.checkpoint(), b.checkpoint())
    }

    @Test fun modularDistrictGrowthContinuesBeyondTenAndSixtyFourRooms() {
        var rooms = WorldActor.genesis().rooms
        repeat(256) {
            val next = ProceduralDistricts.nextAnnex(rooms, 42L)
            assertTrue(rooms.none { prior ->
                val overlapsX = kotlin.math.abs(prior.x - next.x) < (prior.width + next.width) / 2.0
                val overlapsZ = kotlin.math.abs(prior.z - next.z) < (prior.depth + next.depth) / 2.0
                overlapsX && overlapsZ
            })
            assertTrue(rooms.none { it.id == next.id })
            rooms = rooms + next
        }
        assertEquals(262, rooms.size)
        val districtNames = rooms.map { it.name.substringBeforeLast(' ') }.toSet()
        assertTrue(districtNames.containsAll(setOf(
            "Moradia modular", "Estufa agrícola", "Reciclagem e reúso",
            "Oficina de energia", "Biblioteca de pesquisa", "Ágora cívica"
        )))
    }

    @Test fun reciprocalTrustCreatesDeterministicDescendantAndReplaysGenealogy() {
        val initial = WorldActor.genesis()
        val room = initial.rooms.first { it.id == "salon" }
        val parents = initial.agents.take(2).mapIndexed { index, citizen ->
            citizen.copy(
                x = room.x + index * .4,
                z = room.z,
                needs = Needs(energy = .9, hydration = .9, glucose = .9, social = .9, curiosity = .9),
                activity = Activity.TALK,
                acting = true
            )
        }
        val pair = setOf(parents[0].id, parents[1].id)
        val state = initial.copy(
            agents = initial.agents.map { old -> parents.firstOrNull { it.id == old.id } ?: old },
            ties = initial.ties.map { tie ->
                if (tie.from in pair && tie.to in pair) tie.copy(affinity = .91) else tie
            }
        )
        val actorA = WorldActor(Checkpoint(state, 0, WorldActor.GENESIS_HASH), 6)
        val actorB = WorldActor(Checkpoint(state, 0, WorldActor.GENESIS_HASH), 6)
        val first = parents[0]
        val proposal = NeuralProposal(
            seed = state.seed,
            sourceTick = state.tick,
            agentId = first.id,
            activity = Activity.TALK,
            sourceStateHash = Canonical.hash(Canonical.state(state)),
            modelHash = "a".repeat(64)
        )
        val intentions = actorA.intentions(proposal)
        val poses = state.agents.associate { citizen ->
            val point = SpatialPoint(citizen.x, 0.0, citizen.z)
            val destination = state.rooms.first { it.id == intentions.getValue(citizen.id).room }
            val reached = kotlin.math.abs(citizen.x - destination.x) <= destination.width / 2.0 &&
                kotlin.math.abs(citizen.z - destination.z) <= destination.depth / 2.0
            citizen.id to SpatialPose(
                position = point, verticalVelocity = 0.0, surface = 0.0,
                distance = 0.0, heading = 0.0, reached = reached,
                route = listOf(point)
            )
        }
        val frame = SpatialFrame(
            sourceTick = state.tick,
            sourceHash = Canonical.hash(Canonical.state(state)),
            seed = state.seed,
            intentions = intentions,
            poses = poses
        )
        val transition = actorA.step(neural = proposal, spatial = frame)
        assertEquals(1, transition.state.genealogy.size)
        val record = transition.state.genealogy.single()
        val child = transition.state.agents.single { it.id == record.child }
        assertEquals(parents[0].id, record.parentOne)
        assertEquals(parents[1].id, record.parentTwo)
        assertEquals(2, child.genome.generation)
        assertTrue(child.lineage.contains(parents[0].lineage))
        assertTrue(child.lineage.contains(parents[1].lineage))
        assertEquals(0, transition.state.balances["${child.id}/coins"])
        assertEquals(Activity.entries.size, transition.state.words.count { it.lineage == child.lineage })
        assertTrue(record.valid(transition.state.tick, transition.state.agents))
        assertEquals(transition, run {
            actorB.replay(transition.event)
            Transition(actorB.state, transition.event)
        })
        val restored = WorldActor(
            Checkpoint(Canonical.state(Canonical.state(actorA.checkpoint().state)), actorA.checkpoint().eventId, actorA.checkpoint().eventHash),
            6
        )
        assertEquals(actorA.state.genealogy, restored.state.genealogy)
        assertEquals(Canonical.state(actorA.state), Canonical.state(restored.state))
    }

    @Test fun rulesSevenPersistsAnokhinIgnitionKuramotoRitualAndReplays() {
        val origin = WorldActor.genesis()
        val agora = Room("annex_test_agora", "Ágora cívica", 5.5, 5.0, 12.0, 12.0, 6)
        val world = origin.copy(rooms = origin.rooms + agora)
        val actor = WorldActor(Checkpoint(world, 0, WorldActor.GENESIS_HASH), 7)
        val minds = world.agents.associate { citizen ->
            val initial = MindState.initial(world, citizen)
            citizen.id to initial.copy(
                psyche = initial.psyche.copy(
                    expectedSensory = List(16) { .5 },
                    lastPredictionError = .85,
                    ignitionIndex = .95,
                    initialized = true
                )
            )
        }
        val cognitive = CognitiveFrame(
            sourceTick = world.tick,
            sourceHash = Canonical.hash(Canonical.state(world)),
            minds = minds,
            decisions = world.agents.associate { it.id to Activity.TALK },
            socialSynchrony = .97,
            innerSpeech = mapOf(world.agents.first().id to "Preciso comparar a previsão antes de agir.")
        )
        assertTrue(cognitive.applicable(world))
        val intentions = actor.intentions(cognition = cognitive)
        val poses = world.agents.associate { citizen ->
            val point = SpatialPoint(citizen.x, 0.0, citizen.z)
            citizen.id to SpatialPose(
                position = point, verticalVelocity = 0.0, surface = 0.0,
                distance = 0.0, heading = 0.0, reached = false,
                route = listOf(point)
            )
        }
        val spatial = SpatialFrame(
            sourceTick = world.tick,
            sourceHash = Canonical.hash(Canonical.state(world)),
            seed = world.seed,
            intentions = intentions,
            poses = poses
        )
        val transition = actor.step(cognition = cognitive, spatial = spatial)
        assertEquals(7, transition.event.version)
        assertTrue(transition.event.valid())
        assertEquals(.97, transition.state.socialSynchrony, 1e-9)
        assertTrue(transition.state.artifacts.any { it.title == "Fala interna" && it.author == world.agents.first().id })
        assertTrue(transition.state.artifacts.any { it.title == "Ignição global GNW" })
        assertTrue(transition.state.artifacts.any { it.title == "Ritual de fase na Ágora" })
        assertTrue(transition.state.dialogues.any { it.portuguese.contains("ritual") })
        assertTrue(transition.state.minds.values.all { it.psyche.valid() })

        val reader = WorldActor(Checkpoint(world, 0, WorldActor.GENESIS_HASH), 7)
        reader.replay(transition.event)
        assertEquals(actor.checkpoint(), reader.checkpoint())
        assertEquals(Canonical.state(actor.state), Canonical.state(reader.state))
    }

}
