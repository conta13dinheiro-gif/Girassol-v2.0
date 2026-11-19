package com.enhikyy.girassol.ui
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.cognition.RsiHub
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.ui.theme.Solar

@Composable internal fun EvolutionPanel(world: WorldState) {
    val status by RsiHub.state.collectAsStateWithLifecycle()
    Text("Evolução · Shadow-Replay",style=MaterialTheme.typography.titleLarge)
    Text(status.message,color=Solar.Muted,fontSize=12.sp)
    if(status.running) {
        Spacer(Modifier.height(10.dp))
        if(status.total>0) LinearProgressIndicator(progress={ (status.evaluated.toFloat()/status.total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("CMA-ES · ${status.evaluated} / ${status.total} mundos comparados · origem ${status.sourceTick}",fontSize=10.sp,color=Solar.Cognitive)
    }
    world.evolution?.let { policy->
        Spacer(Modifier.height(12.dp)); Text("Última política promovida",color=Solar.Biosphere,fontSize=12.sp)
        Text("${policy.evaluations} avaliações · duas janelas de ${policy.horizon} ciclos · origem ${policy.sourceTick}",color=Solar.Muted,fontSize=11.sp)
        Canvas(Modifier.fillMaxWidth().height(68.dp).padding(vertical=8.dp)) {
            val center=size.height/2
            drawLine(Solar.Muted.copy(alpha=.3f),Offset(0f,center),Offset(size.width,center))
            policy.weights.zip(policy.baseWeights).forEachIndexed { i,(next,old)->
                val x=size.width*(i+.5f)/8; val change=((next-old)/4).toFloat()
                drawLine(if(change>=0) Solar.Biosphere else Solar.Cognitive,Offset(x,center),Offset(x,center-change*size.height),size.width/14)
            }
        }
        Text("Diferença dos oito pesos de decisão. A evidência e os pesos anteriores estão preservados no diário.",color=Solar.Muted,fontSize=10.sp)
    } ?: Text("Nenhuma política promovida. A biosfera segue aprendendo em suas redes locais.",color=Solar.Muted,fontSize=11.sp)
}
