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
    world.qualityDiversity?.let { archive ->
        val view=remember(archive) { archive.view() }
        Spacer(Modifier.height(10.dp))
        Text("Nichos comportamentais: ${view.occupied}/512 · ${"%.2f".format(java.util.Locale.ROOT,view.coverage*100)}%",color=Solar.Biosphere,fontSize=12.sp)
        Text("${view.samples} resultados físicos · qualidade média ${"%.3f".format(java.util.Locale.ROOT,view.meanFitness())}",color=Solar.Muted,fontSize=11.sp)
    }
    world.evolution?.let { policy->
        Spacer(Modifier.height(12.dp)); Text("Última política promovida",color=Solar.Biosphere,fontSize=12.sp)
        Text("${policy.evaluations} avaliações · duas janelas de ${policy.horizon} ciclos · origem ${policy.sourceTick}",color=Solar.Muted,fontSize=11.sp)
        val change=policy.cognitive
        if(change!=null) {
            Text("Genoma cognitivo v${change.candidate.version} · regras verificadas ${change.candidate.activeVpoRules.size}",color=Solar.Biosphere,fontSize=11.sp)
            Text("Diversidade ${"%.3f".format(java.util.Locale.ROOT,change.validationCandidate.safety.semanticEntropy)} · Brier ${"%.3f".format(java.util.Locale.ROOT,change.validationCandidate.safety.brierScore)}",color=Solar.Muted,fontSize=11.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(68.dp).padding(vertical=8.dp)) {
            val center=size.height/2
            drawLine(Solar.Muted.copy(alpha=.3f),Offset(0f,center),Offset(size.width,center))
            val nextValues=change?.candidate?.vector()?.take(7) ?: policy.weights
            val oldValues=change?.baseline?.vector()?.take(7) ?: policy.baseWeights
            nextValues.zip(oldValues).forEachIndexed { i,(next,old)->
                val x=size.width*(i+.5f)/nextValues.size; val change=((next-old)/4).toFloat()
                drawLine(if(change>=0) Solar.Biosphere else Solar.Cognitive,Offset(x,center),Offset(x,center-change*size.height),size.width/14)
            }
        }
        Text(if(change==null) "Diferença dos oito pesos de decisão; evidência preservada no diário." else "Diferença normalizada dos sete parâmetros cognitivos; evidência preservada no diário.",color=Solar.Muted,fontSize=10.sp)
    } ?: Text("Nenhuma política promovida. A biosfera segue aprendendo em suas redes locais.",color=Solar.Muted,fontSize=11.sp)
}
