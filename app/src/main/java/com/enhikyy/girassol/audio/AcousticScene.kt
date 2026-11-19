package com.enhikyy.girassol.audio

import com.enhikyy.girassol.core.Activity
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.core.telemetry
import com.enhikyy.girassol.render.Atmosphere
import kotlin.math.sqrt

/** Derived observation only. No random consumption, mutation, IQ claims or logical clock writes. */
internal data class AcousticScene(val left: Float=0f, val right: Float=0f, val mastery: Float=0f, val social: Float=0f,val weather: Float=0f,val tension: Float=0f,val milestone: Float=0f) {
    companion object {
        fun from(world: WorldState, previous: WorldState? = null): AcousticScene {
            if (world.agents.isEmpty()) return AcousticScene()
            var left=0.0; var right=0.0
            val prior=previous?.takeIf { it.seed==world.seed && it.tick<world.tick }?.agents?.associateBy { it.id }.orEmpty()
            world.agents.filter { a -> prior[a.id]?.let { p -> kotlin.math.hypot(a.x-p.x,a.z-p.z)>.001 } == true }.forEach { a ->
                // Fixed isometric listening axis. This stereo panorama does not track the camera.
                val pan=((a.x-a.z)/12).coerceIn(-1.0,1.0)
                left+=sqrt((1-pan)/2); right+=sqrt((1+pan)/2)
            }
            val count=world.agents.size
            return AcousticScene((left/count).toFloat(),(right/count).toFloat(),
                world.agents.map { it.skill.mastery }.average().toFloat().coerceIn(0f,1f),
                world.agents.count { it.acting && it.activity==Activity.TALK }.toFloat()/count,
                Atmosphere.sun(world.tick.toDouble(),world.seed).cloud,world.telemetry().stress.toFloat(),
                if(previous!=null && previous.seed==world.seed && previous.tick<world.tick && (world.rooms.size>previous.rooms.size || world.artifacts.size>previous.artifacts.size || world.agents.any { a->a.skill.shareable && previous.agents.firstOrNull { it.id==a.id }?.skill?.shareable==false })) 1f else 0f)
        }
    }
}
