package com.enhikyy.girassol.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.ui.theme.Solar
import kotlin.math.*

/** Geographic plan of the published world; heat values come from actual citizens and trail cells. */
@Composable internal fun TacticalMap(world: WorldState,layer: Int,modifier: Modifier,select: (String)->Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val loX=world.rooms.minOf { it.x-it.width/2 }-2
    val loZ=world.rooms.minOf { it.z-it.depth/2 }-2
    val hiX=world.rooms.maxOf { it.x+it.width/2 }+2
    val hiZ=world.rooms.maxOf { it.z+it.depth/2 }+2
    val cx=(loX+hiX)/2; val cz=(loZ+hiZ)/2
    val labels=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xffa8b3bf.toInt(); textAlign=Paint.Align.CENTER } }
    Canvas(modifier.background(Solar.Black).semantics { contentDescription="Mapa tático. ${world.agents.size} cidadãos. Camada ${listOf("Ambientes","Energia","Convívio","Atividade")[layer]}" }
        .pointerInput(Unit) { detectTransformGestures { _,delta,scale,_-> zoom=(zoom*scale).coerceIn(.5f,12f); pan+=delta } }
        .pointerInput(world,zoom,pan,layer) { detectTapGestures { tap->
            val u=min(size.width/(hiX-loX),size.height*.65/(hiZ-loZ)).toFloat()*zoom
            fun position(x: Double,z: Double)=Offset(size.width/2f+((x-cx)*u).toFloat(),size.height*.49f+((z-cz)*u).toFloat())+pan
            val nearest=world.agents.minByOrNull { (position(it.x,it.z)-tap).getDistance() }
            if(nearest!=null && (position(nearest.x,nearest.z)-tap).getDistance()<24* density) select(nearest.id)
            else world.rooms.firstOrNull { r->
                val c=position(r.x,r.z); abs(tap.x-c.x)<r.width*u/2 && abs(tap.y-c.y)<r.depth*u/2
            }?.let { select("room:${it.id}") }
        } }) {
        val unit=min(size.width/(hiX-loX),size.height*.65/(hiZ-loZ)).toFloat()*zoom
        fun p(x: Double,z: Double)=Offset(size.width/2+((x-cx)*unit).toFloat(),size.height*.49f+((z-cz)*unit).toFloat())+pan
        for(i in floor(loX).toInt()..ceil(hiX).toInt()) drawLine(Color.White.copy(alpha=.025f),p(i.toDouble(),loZ),p(i.toDouble(),hiZ))
        for(i in floor(loZ).toInt()..ceil(hiZ).toInt()) drawLine(Color.White.copy(alpha=.025f),p(loX,i.toDouble()),p(hiX,i.toDouble()))
        world.rooms.forEach { r->
            drawLine(Solar.Muted.copy(alpha=.14f),p(r.x,r.z),p(5.5,r.z),unit*.25f)
            drawLine(Solar.Muted.copy(alpha=.14f),p(5.5,r.z),p(5.5,4.5),unit*.25f)
            val pos=p(r.x-r.width/2,r.z-r.depth/2); val dimensions=Size((r.width*unit).toFloat(),(r.depth*unit).toFloat())
            val color=when(r.id) { "library"->Solar.Cognitive; "market","cafe"->Solar.Biosphere; else->Solar.Amber }
            drawRoundRect(color.copy(alpha=.10f),pos,dimensions,androidx.compose.ui.geometry.CornerRadius(5f))
            drawRoundRect(color.copy(alpha=.45f),pos,dimensions,androidx.compose.ui.geometry.CornerRadius(5f),style=Stroke(1.2f))
            labels.textSize=(10* density).coerceAtMost(unit*.30f)
            val label=p(r.x,r.z+r.depth/2+.35)
            drawContext.canvas.nativeCanvas.drawText(r.name,label.x,label.y,labels)
        }
        if(layer==3) world.trail.forEachIndexed { i,v-> if(v>.01) drawCircle(Solar.Amber.copy(alpha=(v*.5).toFloat()),unit*.55f,p((i%12).toDouble(),(i/12).toDouble())) }
        world.agents.forEach { a->
            val pos=p(a.x,a.z)
            val value=when(layer) { 1->a.needs.energy; 2->a.needs.social; else->0.0 }
            if(layer==1 || layer==2) for (ring in 4 downTo 1) drawCircle((if(layer==1) Solar.Biosphere else Solar.Cognitive).copy(alpha=(value*.055).toFloat()),unit*(.3f+ring*.18f),pos)
            drawCircle(Solar.Black,6*density,pos)
            drawCircle(Color(a.color),3.5f*density,pos)
            if(a.acting) drawCircle(Color(a.color).copy(alpha=.4f),7*density,pos,style=Stroke(density))
        }
    }
}
