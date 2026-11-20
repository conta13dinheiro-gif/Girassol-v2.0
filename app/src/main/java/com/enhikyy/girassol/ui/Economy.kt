package com.enhikyy.girassol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.core.Activity
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.ui.theme.Solar
import kotlin.math.*

@Composable internal fun Economy(world: WorldState,modifier: Modifier=Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Ecossistema & economia",style=MaterialTheme.typography.headlineLarge)
        Text("Os recursos circulam entre habitantes, mercado e ambiente.",color=Solar.Muted)
        Text("Distribuição de recursos",style=MaterialTheme.typography.titleLarge)
        world.initialTotals.forEach { (item,total)->
            val market=world.balances["market/$item"] ?: 0
            val citizens=world.agents.sumOf { world.balances["${it.id}/$item"] ?: 0 }
            val used=total-market-citizens
            val name=mapOf("water" to "Água","food" to "Alimento","coffee" to "Café","paper" to "Papel","timber" to "Madeira","coins" to "Moedas")[item] ?: item
            Row(Modifier.fillMaxWidth()) {
                Text(name,Modifier.weight(1f)); Text("$market / $total",style=Solar.Metric.copy(fontSize=12.sp),color=Solar.Muted)
            }
            Canvas(Modifier.fillMaxWidth().height(9.dp)) {
                var x=0f
                listOf(market to Solar.Biosphere,citizens to Solar.Cognitive,used to Solar.Amber).forEach { (v,color)->
                    val width=size.width*v/total.coerceAtLeast(1)
                    drawRect(color,Offset(x,0f),Size(width,size.height)); x+=width
                }
            }
        }
        Text("Verde: mercado · azul: cidadãos · âmbar: ambiente, bens comuns, arquivo, ferramentas e estruturas",color=Solar.Muted,fontSize=11.sp)
        world.economy?.let { economy ->
            Text("Bens comuns",style=MaterialTheme.typography.titleLarge)
            val account=com.enhikyy.girassol.core.DoubleEntryEconomyAndStigmergy.COMMONS
            Text("Água: ${world.balances["$account/water"] ?: 0} · Alimento: ${world.balances["$account/food"] ?: 0} · Moedas: ${world.balances["$account/coins"] ?: 0}")
            Text("${economy.tools.size} ferramentas · ${economy.niches.size} anexos ativos · ${economy.leases.size} reservas",color=Solar.Muted)
            val completed=economy.jobs.count { it.phase=="DONE" }
            val travelling=economy.jobs.count { it.phase=="MOVE_TO" }
            Text("Trabalho no ciclo: $completed concluídos · $travelling em deslocamento",color=Solar.Muted)
        }
        Spacer(Modifier.height(8.dp)); Text("Práticas da comunidade",style=MaterialTheme.typography.titleLarge)
        val total=world.actionCounts.values.sum().coerceAtLeast(1)
        val colors=listOf(Solar.Cognitive,Solar.Amber,Solar.Biosphere,Color(0xffa78bfa),Color(0xfff472b6),Color(0xfffb923c),Color(0xff2dd4bf),Color(0xff818cf8))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            var start=-90f
            Activity.entries.forEachIndexed { i,a->
                val sweep=(world.actionCounts[a.name] ?: 0)*360f/total
                drawArc(colors[i],start,sweep,true,Offset(size.width/2-70.dp.toPx(),5.dp.toPx()),Size(140.dp.toPx(),140.dp.toPx()))
                start+=sweep
            }
            drawCircle(Solar.Surface,43.dp.toPx(),center)
        }
        Activity.entries.forEachIndexed { i,a->
            Row(Modifier.fillMaxWidth()) { Text(a.label,Modifier.weight(1f),color=colors[i]); Text("${world.actionCounts[a.name] ?: 0}",style=Solar.Metric) }
        }
        Spacer(Modifier.height(18.dp))
    }
}
