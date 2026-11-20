package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class SkillOperator { RESERVE, MOVE_TO, ACQUIRE_TOOL, EXECUTE_WORK, DEPOSIT_LEDGER }
@Serializable data class SkillProof(val tick: Long,val stateHash: String,val job: String,val room: String)
@Serializable data class SkillPreconditions(val domain: String,val mastery: Double=.75,val minimumEnergy: Double=.18,
    val minimumHydration: Double=.18) {
    fun valid()=domain in ProvenanceImmunity.DOMAINS && mastery.isFinite() && mastery in .75..1.0 &&
        minimumEnergy.isFinite() && minimumEnergy in .0..1.0 && minimumHydration.isFinite() && minimumHydration in .0..1.0
}
/** A bounded interpreted work program, distilled only from completed native physical jobs. */
@Serializable data class VoyagerSkill(val author: String,val action: Activity,val preconditions: SkillPreconditions,
    val operators: List<SkillOperator>,val executions: Int,val knowledge: Double,val proofs: List<SkillProof>,
    val created: Long,val teacher: String?=null) {
    val id get()=Canonical.hash("voyager1:$author:${action.name}:${Json.encodeToString(preconditions)}:${operators.joinToString()}")
    fun valid(tick: Long)=author.isNotBlank() && author.length<=128 && teacher?.let { it.isNotBlank() && it.length<=128 }!=false &&
        preconditions.valid() && preconditions.domain==SocietyAdicoCliodynamicsEngine.domain(action) &&
        operators==VoyagerSkillLibrary.recipe(action) && executions>=3 && knowledge.isFinite() && knowledge in .75..1.0 && created in 1..tick &&
        proofs.size==3 && proofs.map { it.tick }.distinct().size==3 && proofs.all { it.tick in 1..tick &&
            it.stateHash.matches(Regex("[a-f0-9]{64}")) && it.job.matches(Regex("[a-f0-9]{64}")) && it.room.isNotBlank() && it.room.length<=128 }
    fun admits(world: WorldState,citizen: Citizen,bank: MemoryBank): Boolean {
        if(!valid(world.tick) || bank.learningFor(action).knowledgeProbability<preconditions.mastery ||
            citizen.needs.energy<preconditions.minimumEnergy || citizen.needs.hydration<preconditions.minimumHydration) return false
        val stock=world.balances
        return when(action) {
            Activity.WRITE -> (stock["market/paper"] ?: 0)>0 && (world.economy?.tools?.any { it.agent==citizen.id && it.action==action }==true || (stock["market/timber"] ?: 0)>0)
            Activity.BUILD -> (stock["market/timber"] ?: 0)>=if(world.economy?.tools?.any { it.agent==citizen.id && it.action==action }==true) 4 else 5
            Activity.WATER -> (stock["market/water"] ?: 0).toLong()+(stock["${DoubleEntryEconomyAndStigmergy.COMMONS}/water"] ?: 0).toLong()>0
            Activity.EAT -> (stock["market/food"] ?: 0).toLong()+(stock["${DoubleEntryEconomyAndStigmergy.COMMONS}/food"] ?: 0).toLong()>0 &&
                (stock["${citizen.id}/coins"] ?: 0).toLong()+(stock["${DoubleEntryEconomyAndStigmergy.COMMONS}/coins"] ?: 0).toLong()>0
            Activity.COFFEE -> (stock["market/coffee"] ?: 0)>0
            Activity.TALK -> world.agents.any { it.id!=citizen.id && kotlin.math.hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }
            else -> true
        }
    }
}

object VoyagerSkillLibrary {
    const val MAX_SKILLS=8
    fun recipe(action: Activity)=listOf(SkillOperator.RESERVE,SkillOperator.MOVE_TO)+
        (if(action in setOf(Activity.BUILD,Activity.WRITE)) listOf(SkillOperator.ACQUIRE_TOOL) else emptyList())+
        listOf(SkillOperator.EXECUTE_WORK,SkillOperator.DEPOSIT_LEDGER)
    fun valid(skills: List<VoyagerSkill>,tick: Long)=skills.size<=MAX_SKILLS && skills.map { it.action }.distinct().size==skills.size && skills.all { it.valid(tick) }
    fun distill(world: WorldState,citizen: Citizen,bank: MemoryBank,stateHash: String): MemoryBank {
        val job=world.economy?.jobs?.singleOrNull { it.agent==citizen.id && it.phase=="DONE" && it.receiptTick==world.tick && it.action==citizen.activity } ?: return bank
        val trace=bank.learningFor(job.action)
        if(trace.verifiedExecutions<3 || trace.knowledgeProbability<.75) return bank
        val old=bank.skills.firstOrNull { it.action==job.action && it.author==citizen.id }
        val proof=SkillProof(world.tick,stateHash,job.id,job.room)
        // Evidence is accumulated before mastery eligibility; do not fabricate missing source executions.
        val proofs=(bank.practiceProofs.filter { it.action==job.action }.map { it.proof }+proof).distinctBy { it.tick }.takeLast(3)
        if(proofs.size<3) return bank
        val skill=VoyagerSkill(citizen.id,job.action,SkillPreconditions(SocietyAdicoCliodynamicsEngine.domain(job.action)),recipe(job.action),
            trace.verifiedExecutions,trace.knowledgeProbability,proofs,old?.created ?: world.tick)
        check(skill.valid(world.tick))
        return bank.copy(skills=(bank.skills.filterNot { it.action==job.action }+skill).sortedBy { it.action.ordinal })
    }
    fun teach(world: WorldState,student: Citizen,bank: MemoryBank,teacher: Citizen,teacherBank: MemoryBank): MemoryBank {
        val learned=bank.skills.associateBy { it.action }.toMutableMap()
        for(skill in teacherBank.skills) {
            val local=teacherBank.learningFor(skill.action)
            val trust=ProvenanceImmunity.trust(world,student.id,teacher.id,skill.preconditions.domain)
            if(!skill.valid(world.tick) || local.verifiedExecutions<3 || local.knowledgeProbability<.75 || trust<.2) continue
            val existing=learned[skill.action]
            if(existing==null || (existing.author!=student.id && existing.knowledge<skill.knowledge)) learned[skill.action]=skill.copy(teacher=teacher.id)
        }
        return bank.copy(skills=learned.values.sortedBy { it.action.ordinal })
    }
}
@Serializable data class PracticeProof(val action: Activity,val proof: SkillProof)
