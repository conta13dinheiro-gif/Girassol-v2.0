package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.hypot

@Serializable data class StockTransfer(val from: String,val to: String,val resource: String,val amount: Int) {
    fun valid() = from!=to && listOf(from,to).all { it.isNotBlank() && it.length<=128 && '/' !in it } &&
        resource in setOf("water","food","coffee","timber","paper","coins") && amount>0
}
@Serializable data class WorkLease(val room: String,val agent: String,val expires: Long)
@Serializable data class WorkTool(val agent: String,val action: Activity,val uses: Int)
@Serializable data class EcologicalNicheStructure(val room: String,val builder: String,val expires: Long,val investedTimber: Int=4)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PhysicalJob(val id: String,val agent: String,val action: Activity,val room: String,val started: Long,
    val phase: String,val toils: List<String>,val attachmentEvent: Long=0,val toolUsed: Boolean=false,val receiptTick: Long=0,val interruptedJob: String="",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val skill: VoyagerSkill?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val programCounter: Int=0)
@Serializable data class EconomyState(val pheromones: List<Double> = List(8*144){0.0},val leases: List<WorkLease> = emptyList(),
    val tools: List<WorkTool> = emptyList(),val niches: List<EcologicalNicheStructure> = emptyList(),val jobs: List<PhysicalJob> = emptyList(),val legacyTimber: Int=0) {
    fun valid(world: WorldState): Boolean {
        val ids=world.agents.map { it.id }.toSet();val rooms=world.rooms.map { it.id }.toSet()
        return legacyTimber>=0 && (world.balances["structures/timber"] ?: 0).toLong()==legacyTimber.toLong()+niches.sumOf { it.investedTimber.toLong() } &&
            (world.balances["tools/timber"] ?: 0)==tools.size && pheromones.size==1152 && pheromones.all { it.isFinite() && it in 0.0..100.0 } &&
            leases.size<=150 && leases.map { it.agent }.distinct().size==leases.size && leases.all { it.agent in ids && it.room in rooms && it.expires>world.tick &&
                jobs.any { job -> job.agent==it.agent && job.room==it.room && job.phase!="FAILED" } } &&
            leases.groupingBy { it.room }.eachCount().all { (id,count) -> count<=world.rooms.first { it.id==id }.capacity } &&
            tools.size<=300 && tools.map { it.agent to it.action }.distinct().size==tools.size && tools.all { it.agent in ids && it.action in setOf(Activity.BUILD,Activity.WRITE) && it.uses in 1..32 } &&
            niches.size<=128 && niches.map { it.room }.distinct().size==niches.size && niches.all { it.room in rooms && it.builder in ids && it.expires>world.tick && it.investedTimber==4 } &&
            jobs.size<=150 && jobs.map { it.agent }.distinct().size==jobs.size && jobs.all { it.agent in ids && it.room in rooms && it.started in 1..world.tick &&
                it.id==Canonical.hash("${world.seed}:${it.agent}:${it.started}:${it.action}:${it.room}") &&
                SmartTerrain.supports(world.rooms.first { room -> room.id==it.room },it.action) && it.phase in setOf("MOVE_TO","DONE","FAILED") && it.toils in listOf(emptyList(),listOf("RESERVE"),listOf("RESERVE","MOVE_TO"),listOf("RESERVE","MOVE_TO","EXECUTE_WORK","DEPOSIT_LEDGER")) &&
                (if(it.skill==null) it.programCounter==0 else it.skill.valid(world.tick) && it.skill.action==it.action &&
                    it.programCounter in 0..it.skill.operators.size && (it.phase!="DONE" || it.programCounter==it.skill.operators.size) &&
                    (it.phase!="MOVE_TO" || it.programCounter in 0..1 && (it.programCounter==0 || leases.any { lease -> lease.agent==it.agent && lease.room==it.room }))) &&
                it.attachmentEvent in 0..world.tick && it.receiptTick in 0..world.tick && (it.phase!="DONE" || (it.receiptTick>0 && it.toils==listOf("RESERVE","MOVE_TO","EXECUTE_WORK","DEPOSIT_LEDGER"))) && (it.interruptedJob.isEmpty() || it.interruptedJob.matches(Regex("[a-f0-9]{64}"))) }
    }
    fun snapshot() = copy(pheromones=pheromones.toList(),leases=leases.toList(),tools=tools.toList(),niches=niches.toList(),jobs=jobs.map { it.copy(toils=it.toils.toList(),skill=it.skill?.copy(operators=it.skill.operators.toList(),proofs=it.skill.proofs.toList())) })
}

/** Smart Terrain advertisements are derived from actual rooms, including constructed annexes. */
object SmartTerrain {
    fun supports(room: Room,action: Activity): Boolean = room.id==action.room || when {
        room.id.endsWith("_residential") -> action==Activity.SLEEP
        room.id.endsWith("_agriculture") -> action==Activity.EAT
        room.id.endsWith("_recycling") -> action in setOf(Activity.WATER,Activity.COFFEE)
        room.id.endsWith("_energy") -> action==Activity.BUILD
        room.id.endsWith("_research") -> action in setOf(Activity.STUDY,Activity.WRITE)
        room.id.endsWith("_civic") -> action==Activity.TALK
        else -> false
    }
    fun target(world: WorldState,citizen: Citizen,action: Activity): Room {
        val canonical=world.rooms.first { it.id==action.room }
        val economy=world.economy ?: return canonical
        val available=world.rooms.filter { room -> supports(room,action) &&
            economy.niches.none { it.room==room.id && it.expires<=world.tick+1 } &&
            economy.leases.count { lease -> lease.room==room.id && lease.agent!=citizen.id && lease.expires>world.tick+1 && economy.jobs.none { job -> job.agent==lease.agent && job.phase!="MOVE_TO" } }<room.capacity }
        return available.minWithOrNull(compareBy<Room> { hypot(it.x-citizen.x,it.z-citizen.z) }.thenBy { it.id }) ?: canonical
    }
}

/** One tick writer, fixed bounds; resource mutations are accepted only through the ledger callback. */
class DoubleEntryEconomyAndStigmergy(private val world: WorldState,private val skillMinds: Map<String,MindState>?=null,private val transfer: (String,String,String,Int)->Boolean) {
    companion object {
        const val COMMONS="COMMUNAL_COMMONS"
        const val TOOL_USES=32
        // Four-metre cells cover the 48m city, including all 128 bounded spiral annex parcels.
        fun cell(x: Double,z: Double): Int = (x/4.0).toInt().coerceIn(0,11)+12*(z/4.0).toInt().coerceIn(0,11)
    }
    private val tick=world.tick+1
    private val prior=world.economy ?: EconomyState(legacyTimber=world.balances["structures/timber"] ?: 0)
    private val leases=prior.leases.filter { it.expires>tick && prior.jobs.none { job -> job.agent==it.agent && job.phase!="MOVE_TO" } }.toMutableList()
    private val tools=prior.tools.toMutableList()
    private val niches=prior.niches.filter { it.expires>tick }.toMutableList()
    private val jobs=prior.jobs.associateBy { it.agent }.toMutableMap()
    private val grid=prior.pheromones.toDoubleArray()
    fun expiredRooms(): Set<String> {
        val expired=prior.niches.filter { it.expires<=tick }
        expired.forEach { check(transfer("structures","environment","timber",it.investedTimber)) }
        val ids=expired.map { it.room }.toSet()
        leases.removeAll { it.room in ids }; jobs.entries.removeAll { it.value.room in ids }
        return ids
    }
    fun prepare(citizen: Citizen,action: Activity,room: Room) {
        val old=jobs[citizen.id]
        if(old?.action!=action || old.room!=room.id || old.phase!="MOVE_TO") {
            leases.removeAll { it.agent==citizen.id }
            val bank=skillMinds?.get(citizen.id)?.memory
            val skill=bank?.skills?.singleOrNull { it.action==action && it.admits(world,citizen,bank) }
            jobs[citizen.id]=WorkGiver.assign(world,citizen,action,room,old).copy(skill=skill)
        }
        // A long native route can outlive its lease. Re-execute RESERVE before continuing MOVE_TO.
        val pending=jobs.getValue(citizen.id)
        if(pending.skill!=null && pending.programCounter==1 && leases.none { it.agent==citizen.id })
            jobs[citizen.id]=pending.copy(programCounter=0,toils=emptyList())
        if(leases.none { it.agent==citizen.id } && leases.count { it.room==room.id }<room.capacity) {
            leases+=WorkLease(room.id,citizen.id,Math.addExact(tick,30))
            val job=jobs.getValue(citizen.id)
            if(job.skill!=null) check(job.skill.operators[job.programCounter]==SkillOperator.RESERVE)
            jobs[citizen.id]=job.copy(toils=listOf("RESERVE"),programCounter=if(job.skill==null) 0 else 1)
        }
        check(jobs.size<=150 && leases.size<=150)
    }
    fun reserve(citizen: Citizen,room: Room): Boolean {
        if(leases.none { it.agent==citizen.id && it.room==room.id }) return false
        val job=jobs.getValue(citizen.id)
        if(job.skill!=null) check(job.skill.operators[job.programCounter]==SkillOperator.MOVE_TO)
        jobs[citizen.id]=job.copy(toils=listOf("RESERVE","MOVE_TO"),programCounter=if(job.skill==null) 0 else job.programCounter+1)
        return true
    }
    /** Interprets the stored work program only after physical arrival and a valid lease.
     * The caller performs the guarded work effect; finish consumes its actual outcome and ledger receipt.
     */
    fun beginWork(citizen: Citizen,action: Activity,balances: Map<String,Int>): Boolean {
        var job=jobs.getValue(citizen.id)
        val skill=job.skill ?: return true
        val bank=skillMinds?.get(citizen.id)?.memory ?: return false
        if(!skill.admits(world.copy(balances=balances),citizen,bank)) return false
        check(job.action==action && leases.any { it.agent==citizen.id && it.room==job.room })
        if(skill.operators.getOrNull(job.programCounter)==SkillOperator.ACQUIRE_TOOL) {
            if(!ensureTool(citizen,action,balances)) return false
            job=job.copy(programCounter=job.programCounter+1);jobs[citizen.id]=job
        }
        check(skill.operators.getOrNull(job.programCounter)==SkillOperator.EXECUTE_WORK)
        return true
    }
    /** A hammer/stylus is physical timber held in tools, reused 32 times, then returned to environment. */
    fun ensureTool(citizen: Citizen,action: Activity,balances: Map<String,Int>): Boolean {
        if(action !in setOf(Activity.BUILD,Activity.WRITE)) return true
        if(tools.any { it.agent==citizen.id && it.action==action }) return true
        val timberNeeded=if(action==Activity.BUILD) 5 else 1
        if((balances["market/timber"] ?: 0)<timberNeeded || (action==Activity.WRITE && (balances["market/paper"] ?: 0)<1)) return false
        if(!transfer("market","tools","timber",1)) return false
        tools+=WorkTool(citizen.id,action,TOOL_USES)
        check(tools.size<=300)
        return true
    }
    fun finish(citizen: Citizen,action: Activity,success: Boolean) {
        val job=jobs.getValue(citizen.id)
        if(success) {
            if(job.skill!=null) {
                check(job.skill.operators.getOrNull(job.programCounter)==SkillOperator.EXECUTE_WORK)
                check(job.skill.operators.getOrNull(job.programCounter+1)==SkillOperator.DEPOSIT_LEDGER)
                check(job.programCounter+2==job.skill.operators.size)
            }
            // DEPOSIT_LEDGER settles real tool wear, receipt and the spatial work signal.
            val index=tools.indexOfFirst { it.agent==citizen.id && it.action==action }
            if(index>=0) {
                val tool=tools[index]
                if(tool.uses==1) { check(transfer("tools","environment","timber",1)); tools.removeAt(index) }
                else tools[index]=tool.copy(uses=tool.uses-1)
            }
            jobs[citizen.id]=job.copy(phase="DONE",programCounter=if(job.skill==null) 0 else job.programCounter+2,toils=listOf("RESERVE","MOVE_TO","EXECUTE_WORK","DEPOSIT_LEDGER"),toolUsed=index>=0,receiptTick=tick)
            val cell=cell(citizen.x,citizen.z)
            grid[action.ordinal*144+cell]=(grid[action.ordinal*144+cell]+1.0).coerceAtMost(100.0)
        } else jobs[citizen.id]=job.copy(phase="FAILED")
        if(!success) leases.removeAll { it.agent==citizen.id }
    }
    fun constructed(room: Room,builder: String) {
        require(niches.size<128)
        niches+=EcologicalNicheStructure(room.id,builder,Math.addExact(tick,2400))
    }
    fun snapshot(): EconomyState {
        // Constant work per cell, eight fixed 12x12 channels. Reflective boundaries conserve mass before evaporation.
        val next=DoubleArray(1152)
        for(channel in 0..7) for(z in 0..11) for(x in 0..11) {
            val i=channel*144+z*12+x; val c=grid[i]
            val north=if(z>0) grid[i-12] else c; val south=if(z<11) grid[i+12] else c
            val west=if(x>0) grid[i-1] else c; val east=if(x<11) grid[i+1] else c
            next[i]=(.88*c+.03*(north+south+west+east))*.97
        }
        return EconomyState(next.toList(),leases.sortedWith(compareBy<WorkLease>{it.room}.thenBy{it.agent}),tools.sortedWith(compareBy<WorkTool>{it.agent}.thenBy{it.action.ordinal}),niches.sortedBy{it.room},jobs.values.sortedBy{it.agent},prior.legacyTimber)
    }
}

/** The work giver binds a real target and an actual prior experience to each new physical task. */
object WorkGiver {
    fun assign(world: WorldState,citizen: Citizen,action: Activity,room: Room,previous: PhysicalJob?): PhysicalJob {
        require(SmartTerrain.supports(room,action))
        val tick=world.tick+1
        val attachment=citizen.memories.lastOrNull { it.action==action }?.eventId ?: 0
        return PhysicalJob(Canonical.hash("${world.seed}:${citizen.id}:$tick:$action:${room.id}"),citizen.id,action,room.id,tick,"MOVE_TO",emptyList(),attachment,
            interruptedJob=previous?.takeIf { it.phase=="MOVE_TO" }?.id ?: "")
    }
}
