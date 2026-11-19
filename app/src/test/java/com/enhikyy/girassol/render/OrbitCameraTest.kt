package com.enhikyy.girassol.render

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OrbitCameraTest {
    private fun settle(c: OrbitCamera) { repeat(240) { c.advance(1.0/60) } }
    @Test fun targetProjectsToCenterAcrossAspectRatios() {
        val camera=OrbitCamera()
        for ((w,h) in listOf(800 to 300,300 to 800,400 to 400)) {
            camera.orbit(.7,.2); settle(camera)
            val p=camera.project(camera.centerX,camera.centerY,camera.centerZ,w,h)!!
            assertEquals(w/2f,p.first,.001f); assertEquals(h/2f,p.second,.001f)
            val eye=camera.eye(w.toDouble()/h)
            assertNull(camera.project(eye[0],eye[1],eye[2],w,h))
        }
    }
    @Test fun zoomIsSmoothAndPerspectiveProjectsIntoTheCorrectQuadrant() {
        val c=OrbitCamera()
        // A point perpendicular to the viewing direction has unchanged depth.
        val x=c.centerX+cos(c.yaw); val z=c.centerZ-sin(c.yaw)
        val before=c.project(x,c.centerY,z,800,400)!!
        c.scale(2.0)
        assertEquals(before,c.project(x,c.centerY,z,800,400))
        settle(c)
        val after=c.project(x,c.centerY,z,800,400)!!
        assertEquals((before.first-400)*2,after.first-400,.001f)
        assertEquals(200f,after.second,.001f)
    }
    @Test fun invalidGesturesAndExtremeZoomKeepCameraFinite() {
        val c=OrbitCamera(); val before=c.eye()
        c.orbit(Double.NaN,1.0); c.scale(Double.POSITIVE_INFINITY); c.scale(-1.0)
        c.focus(Double.NaN,2.0); c.pan(Double.NaN,0.0,400,800); c.advance(Double.NaN)
        assertArrayEquals(before,c.eye(),0.0)
        c.scale(1e300); settle(c); assertEquals(9.0,c.zoom,1e-10)
        c.scale(1e-300); settle(c); assertEquals(.55,c.zoom,1e-10)
        c.orbit(100.0,1e9); settle(c); assertTrue(c.eye().all { it.isFinite() })
        assertNull(c.project(1.0,0.0,1.0,0,300))
    }
    @Test fun dampingIsIndependentOfFrameRateAndResetRestoresOrbit() {
        val a=OrbitCamera(); val b=OrbitCamera()
        for (c in listOf(a,b)) { c.orbit(1.2,-.3); c.scale(2.0); c.focus(8.0,3.0) }
        repeat(60) { a.advance(1.0/60) }; repeat(30) { b.advance(1.0/30) }
        assertArrayEquals(a.eye(),b.eye(),1e-10)
        a.reset(); settle(a)
        assertEquals(PI/4,a.yaw,1e-10); assertEquals(1.0,a.zoom,1e-10)
        assertEquals(8.0,a.centerX,1e-10); assertEquals(3.0,a.centerZ,1e-10)
    }
    @Test fun focusPanAndInertiaConvergeWithoutTeleporting() {
        val c=OrbitCamera(); val before=c.eye()
        c.focus(20.0,10.0,1.0,true)
        assertArrayEquals(before,c.eye(),0.0)
        c.advance(.016); assertTrue(c.centerX in 5.5..20.0)
        settle(c); val focusX=c.centerX
        c.pan(100.0,0.0,400,800); settle(c); assertTrue(c.centerX<focusX)
        c.fling(1.0,0.0); val yaw=c.yaw; settle(c)
        assertEquals(.2,c.yaw-yaw,.0001)
        val stopped=c.yaw; settle(c); assertEquals(stopped,c.yaw,1e-8)
    }
}
