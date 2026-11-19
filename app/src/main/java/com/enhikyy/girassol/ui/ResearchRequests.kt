package com.enhikyy.girassol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.research.ResearchHub
import com.enhikyy.girassol.ui.theme.Solar

/** Displays the research requests actually written to the local epistemic journal. */
@Composable
internal fun ResearchRequests(modifier: Modifier = Modifier) {
    val state by ResearchHub.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PEDIDOS DOS HABITANTES", color = Solar.Amber, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Text("O que a biosfera quer aprender", color = Solar.Ink, fontSize = 26.sp)
                Text(
                    "Cada pedido nasce de uma lacuna observada no mundo. A pesquisa, aquisição, quarentena e validação ficam ligadas ao ciclo e ao cidadão que formulou a consulta.",
                    color = Solar.Muted,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(state.message, color = Solar.Muted, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("%.1f MB".format(state.bytes / 1048576.0), color = Solar.Amber, fontSize = 10.sp)
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (state.entries.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Solar.Surface, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Nenhum pedido registrado", color = Solar.Ink, fontSize = 18.sp)
                    Text(
                        "Quando um habitante encontrar uma lacuna de conhecimento ou uma dificuldade recorrente, o motor de currículo criará uma consulta e a exibirá aqui.",
                        color = Solar.Muted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            items(state.entries.sortedByDescending { it.intent.tick }, key = { it.intent.id }) { entry ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(Solar.Surface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.intent.domain.title, color = Solar.Amber, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(entry.phase.title, color = Solar.Biosphere, fontSize = 10.sp)
                    }
                    Text("Cidadão ${entry.intent.discoverer} · ciclo ${entry.intent.tick}", color = Solar.Muted, fontSize = 10.sp)
                    Text(entry.intent.reason, color = Solar.Ink, fontSize = 13.sp)
                    Text("PT · ${entry.intent.queryPt}", color = Solar.Muted, fontSize = 11.sp)
                    Text("EN · ${entry.intent.queryEn}", color = Solar.Muted, fontSize = 11.sp)
                    Text(entry.message, color = Solar.Muted, fontSize = 10.sp)
                    entry.repo?.let { Text("Origem · $it", color = Solar.Cognitive, fontSize = 10.sp) }
                    if (entry.acquired > 0 || entry.learned > 0) {
                        Text("${entry.acquired} trechos adquiridos · ${entry.learned} aprendidos", color = Solar.Biosphere, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
