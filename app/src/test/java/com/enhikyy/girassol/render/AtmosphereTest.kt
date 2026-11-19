package com.enhikyy.girassol.render

import org.junit.Assert.*
import org.junit.Test

class AtmosphereTest {
    @Test fun solarCycleHasNightDayAndSmoothTwilight() {
        assertTrue(Atmosphere.sun(260.0,42).daylight>.99f)
        assertTrue(Atmosphere.sun(980.0,42).daylight<.01f)
        for (tick in 0..1440) {
            val s=Atmosphere.sun(tick.toDouble(),42)
            assertTrue(s.daylight in 0f..1f && s.cloud in 0f..1f)
            assertTrue(kotlin.math.abs(s.daylight-Atmosphere.sun(tick+1.0,42).daylight)<.03f)
        }
        assertEquals(Atmosphere.sun(10.0,42).daylight,Atmosphere.sun(1450.0,42).daylight,1e-6f)
    }
    @Test fun convolutionProducesDeterministicFiniteHdrCubemapsAndCompleteRoughnessMips() {
        val day=Atmosphere.generate(260,42)
        val again=Atmosphere.generate(260,42)
        val night=Atmosphere.generate(980,42)
        assertEquals(day.sky.pixels,again.sky.pixels)
        assertEquals(day.irradiance.pixels,again.irradiance.pixels)
        assertEquals(listOf(32,16,8,4,2,1),day.reflections.map { it.size })
        fun energy(c: Atmosphere.Cube): Double {
            assertTrue(c.pixels.isDirect)
            assertEquals(c.size*c.size*6*12,c.pixels.remaining())
            var total=0.0
            repeat(c.pixels.limit()/4) {
                val v=c.pixels.getFloat(it*4)
                assertTrue("Nonfinite/negative radiance",v.isFinite() && v>=0)
                total+=v
            }
            return total/(c.pixels.limit()/4)
        }
        (day.reflections+day.sky+day.irradiance+night.reflections+night.sky+night.irradiance).forEach { energy(it) }
        assertTrue(energy(day.sky)>energy(night.sky)*4)
        assertTrue(energy(day.irradiance)>energy(night.irradiance)*4)
        day.reflections.zip(again.reflections).forEach { (a,b)->assertEquals(a.pixels,b.pixels) }
    }
    @Test fun cancelledConvolutionDoesNotProduceAnIncompleteEnvironment() {
        Thread.currentThread().interrupt()
        try { Atmosphere.generate(0,0); fail("Expected interruption") }
        catch (_: InterruptedException) { } finally { Thread.interrupted() }
    }
}
