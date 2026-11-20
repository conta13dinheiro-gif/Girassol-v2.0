package com.enhikyy.girassol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.NativeMemory
import com.enhikyy.girassol.ui.theme.Solar
import kotlinx.coroutines.*
import kotlin.math.*

@Composable internal fun MemoryGraph(citizen: Citizen,mind: MindState,tick: Long) {
    var recalled by remember(citizen.id) { mutableStateOf<List<NativeMemory.Hit>>(emptyList()) }
    var error by remember(citizen.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(citizen.id,mind.learnedTick,mind.memory.reflectedTick) {
        try { recalled=withContext(Dispatchers.Default) { NativeMemory.recall(mind.memory,citizen,tick) }; error=null }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { error="Não foi possível recuperar as experiências." }
    }
    Text("Memória viva",style=MaterialTheme.typography.titleLarge)
    Text("${mind.memory.episodes.size} experiências · ${mind.memory.reflections.size} reflexões · ${mind.memory.procedures.size} técnicas",color=Solar.Muted,fontSize=11.sp)
    if(recalled.isNotEmpty()) {
        Canvas(Modifier.fillMaxWidth().height(136.dp)) {
            val center=Offset(size.width/2,size.height/2); val radius=min(size.width*.36f,size.height*.4f)
            drawCircle(Solar.Amber,8f,center)
            recalled.forEachIndexed { i,hit->
                val angle=2*PI*i/recalled.size
                val point=center+Offset((cos(angle)*radius).toFloat(),(sin(angle)*radius).toFloat())
                val color=if(hit.episode.reward>=0) Solar.Biosphere else Solar.Cognitive
                drawLine(color.copy(alpha=hit.score.coerceIn(.1f,1f)),center,point,1f+hit.score*2)
                drawCircle(color,4f+hit.episode.importance.toFloat()*6,point)
            }
        }
        Text("Experiências que influenciam esta decisão",color=Solar.Amber,fontSize=12.sp)
        recalled.take(4).forEach { hit-> Text("Ciclo ${hit.episode.tick} · ${hit.episode.action.label} · relevância ${(hit.score*100).roundToInt()}%",fontSize=11.sp,color=Solar.Muted) }
    }
    error?.let { Text(it,color=Solar.Amber,fontSize=11.sp) }
    if(mind.memory.reflections.isNotEmpty()) {
        Spacer(Modifier.height(12.dp)); Text("Lições consolidadas durante o repouso",fontSize=12.sp,color=Solar.Cognitive)
        mind.memory.reflections.forEach { r-> Text("${r.action.label} · ${r.observations} experiências · ciclo ${r.lastTick}",fontSize=11.sp,color=Solar.Muted) }
    }
    if(mind.memory.procedures.isNotEmpty()) {
        Spacer(Modifier.height(12.dp)); Text("Biblioteca de técnicas",fontSize=12.sp,color=Solar.Biosphere)
        mind.memory.procedures.forEach { p-> Text("${p.action.label} · ${p.successes} sucessos${p.teacher?.let { " · aprendido com $it" } ?: ""}",fontSize=11.sp,color=Solar.Muted) }
    }
    if(mind.memory.skills.isNotEmpty()) {
        Spacer(Modifier.height(12.dp));Text("Habilidades verificadas",fontSize=12.sp,color=Solar.Biosphere)
        mind.memory.skills.forEach { skill ->
            val local=mind.memory.learningFor(skill.action)
            Text("${skill.action.label} · ${(local.knowledgeProbability*100).roundToInt()}% de domínio · ${local.verifiedExecutions} execuções locais${skill.teacher?.let { " · ensino de $it" } ?: ""}",fontSize=11.sp,color=Solar.Muted)
            local.fsrs?.let { review -> Text("Revisão prevista: ciclo ${review.due}",fontSize=11.sp,color=Solar.Muted) }
        }
    }

}
