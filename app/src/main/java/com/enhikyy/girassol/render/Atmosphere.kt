package com.enhikyy.girassol.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Presentation-only sky. Tick and seed select a reproducible day; no logical state is mutated. */
internal object Atmosphere {
    data class Sun(val x: Double, val y: Double, val z: Double, val daylight: Float, val cloud: Float) {
        val night get() = 1f - daylight
    }
    data class Cube(val size: Int, val pixels: ByteBuffer)
    data class Environment(val sky: Cube, val irradiance: Cube, val reflections: List<Cube>)
    fun sun(tick: Double, seed: Long): Sun {
        val phase = ((tick % 1440 + 1440) % 1440 / 1440 + .32) * PI * 2
        val y = sin(phase - PI / 2)
        val day = ((y + .12) / .32).coerceIn(0.0, 1.0)
        val clouds = (.22 + .15 * sin(floor(tick / 1440) * 1.73 + (seed % 997) * .01)).toFloat()
        return Sun(cos(phase), y, .30, (day * day * (3 - 2 * day)).toFloat(), clouds)
    }
    private data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(v: V) = V(x+v.x,y+v.y,z+v.z)
        operator fun times(s: Double) = V(x*s,y*s,z*s)
        fun dot(v: V) = x*v.x+y*v.y+z*v.z
        fun normalized(): V = this * (1 / sqrt(dot(this)).coerceAtLeast(1e-12))
        fun cross(v: V) = V(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
    }
    private fun direction(face: Int, x: Int, y: Int, size: Int): V {
        val u=2*(x+.5)/size-1; val v=2*(y+.5)/size-1
        return when(face) {
            0->V(1.0,-v,-u); 1->V(-1.0,-v,u); 2->V(u,1.0,v)
            3->V(u,-1.0,-v); 4->V(u,-v,1.0); else->V(-u,-v,-1.0)
        }.normalized()
    }
    private fun radiance(n: V, sun: Sun, seed: Long): V {
        val day=sun.daylight.toDouble(); val dusk=1-abs(sun.y).coerceIn(0.0,1.0)
        val horizon=exp(-abs(n.y)*7)
        val disk=n.dot(V(sun.x,sun.y,sun.z).normalized())
        val glow=max(disk,0.0).pow(48) * day * 2
        val cloudWave=sin(n.x*13+n.z*7+seed%31)*sin(n.z*19-n.y*11)
        val cloud=if(n.y>0) max(0.0,cloudWave-.24)*sun.cloud else 0.0
        val warm=horizon*dusk*day
        val ground=if(n.y<0) .24 else 1.0
        var color=V(.006+.19*day+.48*warm, .012+.34*day+.18*warm, .027+.58*day+.045*warm)*ground
        color+=V(1.0,.72,.4)*glow + V(.34,.37,.40)*cloud*day
        // Stable sparse stars, with a cool moon disc opposite the solar direction.
        if(day<.8 && n.y>0) {
            val cell=floor(n.x*280)*127.1+floor(n.y*280)*311.7+floor(n.z*280)*74.7+seed%101
            val hash=sin(cell)*43758.5453; val star=if(hash-floor(hash)>.999) (1-day)*1.8 else 0.0
            val moon=if(disk<-.9994) (1-day)*1.4 else 0.0
            color+=V(.60,.73,1.0)*(star+moon)
        }
        return color
    }
    /** Cosine diffuse convolution and GGX importance sampling run exclusively on the render worker. */
    fun generate(tick: Long, seed: Long): Environment {
        val sun=sun(tick.toDouble(),seed)
        fun cube(size: Int, sample: (V)->V): Cube {
            val pixels=ByteBuffer.allocateDirect(size*size*6*12).order(ByteOrder.nativeOrder())
            for(face in 0..5) for(y in 0 until size) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                for(x in 0 until size) {
                    val c=sample(direction(face,x,y,size))
                    pixels.putFloat(c.x.toFloat()).putFloat(c.y.toFloat()).putFloat(c.z.toFloat())
                }
            }
            pixels.flip(); return Cube(size,pixels)
        }
        fun integrate(n: V, roughness: Double, diffuse: Boolean): V {
            val up=if(abs(n.y)<.99) V(0.0,1.0,0.0) else V(1.0,0.0,0.0)
            val tangent=up.cross(n).normalized(); val bitangent=n.cross(tangent)
            var sum=V(0.0,0.0,0.0); var weight=0.0
            val count=48
            for(i in 0 until count) {
                val u=(i+.5)/count
                val reversed=Integer.reverse(i).toLong() and 0xffffffffL
                val phi=2*PI*reversed/4294967296.0
                val alpha=roughness*roughness
                val cosTheta=if(diffuse) sqrt(1-u) else sqrt((1-u)/(1+(alpha*alpha-1)*u))
                val sinTheta=sqrt(max(0.0,1-cosTheta*cosTheta))
                val h=tangent*(cos(phi)*sinTheta)+bitangent*(sin(phi)*sinTheta)+n*cosTheta
                val l=if(diffuse) h else h*(2*n.dot(h))+n*(-1.0)
                val w=if(diffuse) 1.0 else max(n.dot(l),0.0)
                if(w>0) { sum+=radiance(l,sun,seed)*w; weight+=w }
            }
            // Cosine samples give irradiance / pi, as required by Filament's Lambertian IBL.
            return sum*(1/weight.coerceAtLeast(1e-9))
        }
        val sky=cube(64) { radiance(it,sun,seed) }
        val irradiance=cube(8) { integrate(it,1.0,true) }
        val specular=(0..5).map { level->
            cube(32 shr level) { if(level==0) radiance(it,sun,seed) else integrate(it,level/5.0,false) }
        }
        return Environment(sky,irradiance,specular)
    }
}
