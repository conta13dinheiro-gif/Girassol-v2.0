package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/** Exact external evidence identity. Text remains in the immutable acquisition journal. */
@Serializable data class KnowledgeLesson(val id: String,val acquisitionHash: String,val rowHash: String,
    val domain: String,val researcher: String,val verifier: String,val confidence: Int,val tick: Long,
    val baseline: ReplayMetrics,val candidate: ReplayMetrics,val originTick: Long,val originStateHash: String,val originEventHash: String) {
    fun valid(worldTick: Long) = listOf(id,acquisitionHash,rowHash,originStateHash,originEventHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        domain in setOf("LOGIC","LANGUAGE","ECOLOGY","PHYSICS","AGRICULTURE","ENGINEERING","ETHICS","MUSIC","MEDICINE","GOVERNANCE") &&
        researcher.isNotBlank() && verifier.isNotBlank() && researcher!=verifier && confidence in 90..100 && tick in 0..worldTick &&
        originTick in 0..tick && baseline.valid() && candidate.valid() && listOf(candidate.innovation-baseline.innovation,candidate.resilience-baseline.resilience,
            candidate.harmony-baseline.harmony,candidate.energyEfficiency-baseline.energyEfficiency).all { it>=0.0 }
    fun applicable(world: WorldState): Boolean {
        if(!valid(world.tick) || tick!=world.tick || world.knowledge.any { it.id==id } || world.agents.none { it.id==verifier }) return false
        val citizen=world.agents.firstOrNull { it.id==researcher } ?: return false
        val library=world.rooms.firstOrNull { it.id==Activity.STUDY.room } ?: return false
        val physical=world.spatial[citizen.id]
        val present=if(physical==null) hypot(citizen.x-library.x,citizen.z-library.z)<=.36
            else physical.reached && kotlin.math.abs(citizen.x-library.x)<=library.width/2 && kotlin.math.abs(citizen.z-library.z)<=library.depth/2
        return citizen.acting && citizen.activity==Activity.STUDY && present
    }
}
