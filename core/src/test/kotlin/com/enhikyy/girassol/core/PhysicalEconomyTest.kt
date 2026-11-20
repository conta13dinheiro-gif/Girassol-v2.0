package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class PhysicalEconomyTest {
    private fun world(action: Activity): WorldState {
        val w=WorldActor.genesis()
        val room=w.rooms.first { it.id==action.room }
        return w.copy(agents=w.agents.mapIndexed { i,a -> if(i==0) a.copy(x=room.x,z=room.z,activity=action,
            needs=when(action) { Activity.EAT -> Needs(glucose=.02); Activity.WATER -> Needs(hydration=.02)
                Activity.WRITE,Activity.BUILD -> Needs(energy=1.0,hydration=1.0,glucose=1.0,social=1.0,curiosity=.01)
                else -> Needs() }) else a.copy(x=11.0,z=11.0,activity=Activity.SLEEP) })
    }
    private fun step(w: WorldState,action: Activity): Transition {
        val frame=CognitiveFrame(w.tick,Canonical.hash(Canonical.state(w)),w.agents.associate { it.id to (w.minds[it.id] ?: MindState.initial(w,it)) },
            w.agents.associate { it.id to if(it.id=="agent0") action else Activity.SLEEP },economyEnabled=true)
        val actor=WorldActor(Checkpoint(w,w.tick,WorldActor.GENESIS_HASH),WorldActor.CURRENT_RULES_VERSION)
        val intentions=actor.intentions(cognition=frame)
        val poses=w.agents.associate { a ->
            val room=SmartTerrain.target(w,a,intentions.getValue(a.id))
            val point=SpatialPoint(a.x,0.0,a.z)
            a.id to SpatialPose(point,0.0,0.0,0.0,0.0,kotlin.math.abs(a.x-room.x)<room.width/2 && kotlin.math.abs(a.z-room.z)<room.depth/2,listOf(point))
        }
        return actor.step(cognition=frame,spatial=SpatialFrame(w.tick,Canonical.hash(Canonical.state(w)),w.seed,intentions=intentions,poses=poses))
    }
    private fun assertJournal(before: WorldState,t: Transition) {
        val stock=before.balances.toMutableMap()
        for(entry in t.event.transfers) {
            assertTrue(entry.valid()); assertTrue(WorldActor.transfer(stock,entry.from,entry.to,entry.resource,entry.amount))
        }
        for(key in stock.keys+t.state.balances.keys) assertEquals(key,stock[key] ?: 0,t.state.balances[key] ?: 0)
        assertTrue(WorldActor.violations(t.state).toString(),WorldActor.violations(t.state).isEmpty())
        assertEquals(before.initialTotals,t.state.initialTotals)
    }
    @Test fun commonsActuallyFeedsInsolventCitizenWithoutMintingMoney() {
        var w=world(Activity.EAT)
        val balances=w.balances.toMutableMap()
        val commons=DoubleEntryEconomyAndStigmergy.COMMONS
        assertTrue(WorldActor.transfer(balances,"agent0",commons,"coins",balances.getValue("agent0/coins")))
        assertTrue(WorldActor.transfer(balances,"market",commons,"food",balances.getValue("market/food")))
        w=w.copy(balances=balances,economy=EconomyState())
        val t=step(w,Activity.EAT)
        assertEquals("OK",t.event.actions.first { it.agentId=="agent0" }.outcome)
        assertEquals(0,t.state.balances.getValue("agent0/coins")); assertEquals(.99,t.state.agents[0].needs.glucose,0.0)
        assertTrue(t.event.transfers.any { it.from==commons && it.resource=="food" })
        assertTrue(t.event.transfers.any { it.from==commons && it.resource=="coins" })
        assertJournal(w,t)
        val reader=WorldActor(Checkpoint(w,0,WorldActor.GENESIS_HASH));reader.replay(Canonical.event(Canonical.event(t.event)))
        assertEquals(t.state,reader.state)
    }
    @Test fun buildingConsumesHammerAndWoodProducesAuditableInfrastructureAndExpires() {
        var w=world(Activity.BUILD)
        val balances=w.balances.toMutableMap()
        WorldActor.transfer(balances,"market","archive","paper",balances.getValue("market/paper"))
        w=w.copy(balances=balances)
        val t=step(w,Activity.BUILD)
        assertEquals("OK",t.event.actions.first { it.agentId=="agent0" }.outcome)
        assertEquals(7,t.state.rooms.size)
        val economy=t.state.economy!!
        assertEquals(31,economy.tools.single().uses);assertEquals(1,t.state.balances.getValue("tools/timber"))
        assertEquals(4,t.state.balances.getValue("structures/timber"))
        assertEquals(economy.niches,t.event.constructed)
        val job=economy.jobs.first { it.agent=="agent0" }
        assertTrue(job.toolUsed);assertEquals(listOf("RESERVE","MOVE_TO","EXECUTE_WORK","DEPOSIT_LEDGER"),job.toils)
        assertJournal(w,t)
        val structure=economy.niches.single()
        val expired=t.state.copy(tick=structure.expires-1,economy=economy.copy(leases=emptyList()))
        val t2=step(expired,Activity.SLEEP)
        assertFalse(t2.state.rooms.any { it.id==structure.room })
        assertEquals(0,t2.state.balances.getValue("structures/timber"))
        assertTrue(t2.event.transfers.any { it.from=="structures" && it.to=="environment" && it.amount==4 })
        assertJournal(expired,t2)
    }
    @Test fun hammerReuseAndBreakReturnRealTimberAndRefuseMissingInputs() {
        val w=world(Activity.WRITE);val balances=w.balances.toMutableMap()
        var state=w
        repeat(32) {
            val economy=DoubleEntryEconomyAndStigmergy(state) { f,t,r,n -> WorldActor.transfer(balances,f,t,r,n) }
            val citizen=state.agents[0];val room=state.rooms.first { it.id==Activity.WRITE.room }
            economy.prepare(citizen,Activity.WRITE,room);assertTrue(economy.reserve(citizen,room))
            assertTrue(economy.ensureTool(citizen,Activity.WRITE,balances));economy.finish(citizen,Activity.WRITE,true)
            state=state.copy(tick=state.tick+1,balances=balances.toMap(),economy=economy.snapshot())
            assertTrue(state.economy!!.valid(state))
        }
        assertTrue(state.economy!!.tools.isEmpty());assertEquals(0,balances.getValue("tools/timber"))
        assertEquals(1,balances.getValue("environment/timber"))
        WorldActor.transfer(balances,"market","environment","timber",balances.getValue("market/timber"))
        val board=DoubleEntryEconomyAndStigmergy(state) { f,t,r,n -> WorldActor.transfer(balances,f,t,r,n) }
        assertFalse(board.ensureTool(state.agents[0],Activity.WRITE,balances))
    }
    @Test fun leasesBlockOverbookingExpireAndRecordInterruptedJobs() {
        val w=world(Activity.WRITE);val room=w.rooms.first { it.id==Activity.WRITE.room }.copy(capacity=1)
        val board=DoubleEntryEconomyAndStigmergy(w) { _,_,_,_ -> error("No transfer expected") }
        board.prepare(w.agents[0],Activity.WRITE,room);board.prepare(w.agents[1],Activity.WRITE,room)
        assertTrue(board.reserve(w.agents[0],room));assertFalse(board.reserve(w.agents[1],room))
        val prior=board.snapshot();val lease=prior.leases.single()
        val later=w.copy(tick=lease.expires-1,economy=prior)
        val next=DoubleEntryEconomyAndStigmergy(later) { _,_,_,_ -> error("No transfer expected") }
        next.prepare(w.agents[1],Activity.WRITE,room);assertTrue(next.reserve(w.agents[1],room))
        assertFalse(next.reserve(w.agents[0],room))
        val libraryJob=next.snapshot().jobs.first { it.agent==w.agents[1].id }
        val bedroom=w.rooms.first { it.id==Activity.SLEEP.room }
        next.prepare(w.agents[1],Activity.SLEEP,bedroom)
        assertEquals(libraryJob.id,next.snapshot().jobs.first { it.agent==w.agents[1].id }.interruptedJob)
    }
    @Test fun reflectiveDiffusionConservesMassExceptKnownEvaporationAndStaysBounded() {
        val w=world(Activity.SLEEP)
        val grid=MutableList(1152) { 0.0 };grid[0]=100.0
        val next=DoubleEntryEconomyAndStigmergy(w.copy(economy=EconomyState(pheromones=grid))) { _,_,_,_ -> false }.snapshot()
        assertEquals(97.0,next.pheromones.sum(),1e-10)
        assertEquals(91.18,next.pheromones[0],1e-10)
        assertEquals(2.91,next.pheromones[1],1e-10);assertEquals(2.91,next.pheromones[12],1e-10)
        assertTrue(next.pheromones.all { it in 0.0..100.0 })
        assertEquals(0.0,next.pheromones.drop(144).sum(),0.0)
    }
    @Test fun smartTerrainUsesActualAnnexAffordanceAndRejectsExpiredAdvertisement() {
        val w=world(Activity.STUDY)
        val annex=Room("annex_test_research","Pesquisa",11.0,11.0,1.7,1.5,1)
        val citizen=w.agents[0].copy(x=11.0,z=11.0)
        val enabled=w.copy(rooms=w.rooms+annex,economy=EconomyState(niches=listOf(EcologicalNicheStructure(annex.id,citizen.id,40))))
        assertEquals(annex,SmartTerrain.target(enabled,citizen,Activity.STUDY))
        assertEquals("library",SmartTerrain.target(enabled.copy(tick=39),citizen,Activity.STUDY).id)
        val occupied=enabled.copy(economy=enabled.economy!!.copy(leases=listOf(WorkLease(annex.id,w.agents[1].id,30))))
        assertEquals("library",SmartTerrain.target(occupied,citizen,Activity.STUDY).id)
        assertEquals("market",SmartTerrain.target(enabled,citizen,Activity.EAT).id)
    }
    @Test fun pheromoneCoordinatesCoverConstructibleAnnexesWithoutBorderAliasing() {
        val cells=mutableSetOf<Int>();var rooms=WorldActor.genesis().rooms
        repeat(128) {
            val room=ProceduralDistricts.nextAnnex(rooms,42)
            assertTrue(room.x in 0.0..47.999 && room.z in 0.0..47.999)
            cells+=DoubleEntryEconomyAndStigmergy.cell(room.x,room.z);rooms=rooms+room
        }
        assertTrue(cells.size>20)
        assertNotEquals(DoubleEntryEconomyAndStigmergy.cell(30.0,24.0),DoubleEntryEconomyAndStigmergy.cell(38.0,24.0))
        assertEquals(0,DoubleEntryEconomyAndStigmergy.cell(-1.0,-1.0))
        assertEquals(143,DoubleEntryEconomyAndStigmergy.cell(49.0,49.0))
    }
    @Test fun legacyPayloadsOmitFeatureAndNewBoundsRejectCorruption() {
        val w=WorldActor.genesis()
        assertFalse(Canonical.state(w).contains("economy"));assertFalse(Canonical.event(WorldActor().step().event).contains("transfers"))
        assertFalse(EconomyState(pheromones=List(1152) { Double.NaN }).valid(w))
        assertFalse(EconomyState(tools=List(301) { WorkTool("agent0",Activity.WRITE,32) }).valid(w))
        assertFalse(StockTransfer("a","a","coins",1).valid());assertFalse(StockTransfer("a","b","coins",-1).valid())
        val balances=mutableMapOf("a/coins" to 1,"b/coins" to Int.MAX_VALUE)
        assertFalse(WorldActor.transfer(balances,"a","b","coins",1));assertEquals(1,balances["a/coins"])
    }
}
