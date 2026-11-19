package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

@Serializable data class SpatialPoint(val x: Double, val y: Double, val z: Double)
/** Native fixed-step results are external canonical inputs, never recomputed during journal replay. */
@Serializable data class SpatialPose(val position: SpatialPoint, val verticalVelocity: Double,
    val surface: Double, val distance: Double, val heading: Double, val reached: Boolean,
    val route: List<SpatialPoint>, val corrected: Boolean = false) {
    fun valid() = listOf(position.x,position.y,position.z,verticalVelocity,surface,distance,heading).all { it.isFinite() } &&
        distance>=0 && position.y>=surface-1e-5 && route.isNotEmpty() && route.last()==position &&
        route.all { p->listOf(p.x,p.y,p.z).all { it.isFinite() && abs(it)<10000 } }
}
@Serializable data class SpatialFrame(val sourceTick: Long,val sourceHash: String,val seed: Long,
    val fixedDeltaTime: Double = 1.0/60.0,val substeps: Int = 30,
    val intentions: Map<String,Activity>,val poses: Map<String,SpatialPose>) {
    fun snapshot()=copy(intentions=intentions.toMap(),poses=poses.mapValues { it.value.copy(route=it.value.route.toList()) })
    fun applicable(world: WorldState,proposals: Map<String,Activity>): Boolean {
        if(sourceTick!=world.tick || sourceHash!=Canonical.hash(Canonical.state(world)) || seed!=world.seed ||
            fixedDeltaTime!=1.0/60.0 || substeps!=30 || intentions!=proposals || poses.keys!=proposals.keys) return false
        return world.agents.all { a->
            val p=poses.getValue(a.id);val room=world.rooms.first { it.id==proposals.getValue(a.id).room }
            p.valid() && p.distance<=.4501 && (p.corrected || hypot(p.route.first().x-a.x,p.route.first().z-a.z)<1e-5) &&
                (!p.reached || (abs(p.position.x-room.x)<=room.width/2 && abs(p.position.z-room.z)<=room.depth/2))
        }
    }
}
