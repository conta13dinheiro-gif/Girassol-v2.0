package com.enhikyy.girassol.render

import kotlin.math.*

/** Perspective orbit with frame-rate independent damping; does not depend on the simulation clock. */
internal class OrbitCamera {
    var yaw=PI/4; private set
    var elevation=.84; private set
    var zoom=1.0; private set
    var centerX=5.5; private set
    var centerZ=5.0; private set
    var centerY=.4; private set
    var extent=7.5
    private var targetYaw=yaw
    private var targetElevation=elevation
    private var targetZoom=zoom
    private var targetX=centerX
    private var targetZ=centerZ
    private var targetY=centerY
    private var velocityX=0.0
    private var velocityY=0.0
    val fov=42.0
    fun orbit(dx: Double,dy: Double) {
        if(!dx.isFinite() || !dy.isFinite()) return
        targetYaw+=dx.coerceIn(-PI,PI); targetElevation=(targetElevation+dy).coerceIn(.12,1.42)
    }
    fun scale(factor: Double) {
        if(factor.isFinite() && factor>0) targetZoom=(targetZoom*factor).coerceIn(.55,9.0)
    }
    fun focus(x: Double,z: Double,y: Double=.4,close: Boolean=false) {
        if(!x.isFinite() || !z.isFinite() || !y.isFinite()) return
        targetX=x; targetZ=z; targetY=y
        if(close) { targetZoom=4.5; targetElevation=.38 }
    }
    fun pan(dx: Double,dy: Double,width: Int,height: Int) {
        if(width<=0 || height<=0 || !dx.isFinite() || !dy.isFinite()) return
        val unit=2*distance(width.toDouble()/height)*tan(Math.toRadians(fov)/2)/height
        targetX+=(-dx*cos(yaw)+dy*sin(yaw))*unit
        targetZ+=(dx*sin(yaw)+dy*cos(yaw))*unit
    }
    fun fling(x: Double,y: Double) { if(x.isFinite() && y.isFinite()) { velocityX=x.coerceIn(-3.0,3.0); velocityY=y.coerceIn(-1.0,1.0) } }
    fun stop() { velocityX=0.0; velocityY=0.0 }
    fun reset() { targetYaw=yaw+atan2(sin(PI/4-yaw),cos(PI/4-yaw)); targetElevation=.84; targetZoom=1.0; stop() }
    fun advance(seconds: Double) {
        if(!seconds.isFinite() || seconds<=0) return
        val dt=seconds.coerceAtMost(.1); val decay=exp(-5*dt)
        orbit(velocityX*(1-decay)/5,velocityY*(1-decay)/5)
        velocityX*=decay; velocityY*=decay
        val alpha=1-exp(-9*dt)
        yaw+=(targetYaw-yaw)*alpha; elevation+=(targetElevation-elevation)*alpha; zoom+=(targetZoom-zoom)*alpha
        centerX+=(targetX-centerX)*alpha; centerY+=(targetY-centerY)*alpha; centerZ+=(targetZ-centerZ)*alpha
        if(abs(yaw)>PI*100) { val turns=floor(yaw/(2*PI))*2*PI; yaw-=turns; targetYaw-=turns }
    }
    fun distance(aspect: Double)=(extent*2.6*max(1.0,1/aspect)/zoom).coerceIn(2.5,2000.0)
    fun halfHeight(aspect: Double)=distance(aspect)*tan(Math.toRadians(fov)/2)
    fun eye(aspect: Double=1.0): DoubleArray {
        val d=distance(aspect)
        return doubleArrayOf(centerX+d*sin(yaw)*cos(elevation),centerY+d*sin(elevation),centerZ+d*cos(yaw)*cos(elevation))
    }
    fun project(x: Double,y: Double,z: Double,width: Int,height: Int): Pair<Float,Float>? {
        if(width<=0 || height<=0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val dx=x-centerX; val dy=y-centerY; val dz=z-centerZ
        val aspect=width.toDouble()/height
        val depth=distance(aspect)-(sin(yaw)*cos(elevation)*dx+sin(elevation)*dy+cos(yaw)*cos(elevation)*dz)
        if(depth<=.1) return null
        val right=cos(yaw)*dx-sin(yaw)*dz
        val up=-sin(elevation)*sin(yaw)*dx+cos(elevation)*dy-sin(elevation)*cos(yaw)*dz
        val unit=height/(2*depth*tan(Math.toRadians(fov)/2))
        return (width/2.0+right*unit).toFloat() to (height/2.0-up*unit).toFloat()
    }
}
