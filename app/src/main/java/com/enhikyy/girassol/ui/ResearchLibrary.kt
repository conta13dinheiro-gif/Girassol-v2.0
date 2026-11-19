package com.enhikyy.girassol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.data.WorldStore
import com.enhikyy.girassol.research.*
import com.enhikyy.girassol.runtime.BiosphereService
import com.enhikyy.girassol.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable internal fun ResearchLibrary(modifier: Modifier=Modifier,readOnly: Boolean=false) {
    val app=LocalContext.current
    val state by ResearchHub.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var selected by remember { mutableStateOf<ResearchEntry?>(null) }
    var snippets by remember { mutableStateOf<List<Pair<KnowledgeCandidate,String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(settings: ResearchSettings) { if(!readOnly) BiosphereService.researchSettings(app,settings) }
    LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=10.dp)) {
        item {
            Text("Hugging Face & Biblioteca",style=MaterialTheme.typography.titleLarge,color=Solar.Amber)
            Text("Os habitantes buscam evidências e estudam na biblioteca. Cada aquisição preserva fonte, licença e hash para consulta offline.",color=Solar.Muted,fontSize=12.sp)
            if(readOnly) Text("Flashback: volte ao presente para configurar a pesquisa.",color=Solar.Amber,fontSize=12.sp)
        }
        item { SurfaceGlass {
            Row(Modifier.fillMaxWidth()) { Text("Auto-pesquisa pelos habitantes",Modifier.weight(1f));Switch(state.settings.automatic,{ update(state.settings.copy(automatic=it)) },enabled=!readOnly) }
            Row(Modifier.fillMaxWidth()) { Text("Somente Wi-Fi",Modifier.weight(1f));Switch(state.settings.wifiOnly,{ update(state.settings.copy(wifiOnly=it)) },enabled=!readOnly) }
            Row(Modifier.fillMaxWidth()) { Text("Revisão cruzada automática",Modifier.weight(1f));Switch(state.settings.quarantine,{ update(state.settings.copy(quarantine=it)) },enabled=!readOnly) }
            Text("Sem GGUF, os dados aguardam revisão. O replay avalia o efeito nas decisões; duas opiniões do mesmo modelo não comprovam verdade factual.",color=Solar.Muted,fontSize=11.sp)
            Text("Cota de evidências · ${state.bytes/1048576} / ${state.settings.quotaMb} MB",Modifier.padding(top=12.dp),fontSize=12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(256,512,1024,2048,4096).forEach { mb->FilterChip(state.settings.quotaMb==mb,{ update(state.settings.copy(quotaMb=mb)) },label={ Text(if(mb<1024) "$mb MB" else "${mb/1024} GB") },enabled=!readOnly) }
            }
            Text("O diário imutável conta na cota. A aquisição pausa ao atingir o limite.",color=Solar.Muted,fontSize=11.sp)
        } }
        item { Text(state.message,color=if(state.busy) Solar.Cognitive else Solar.Muted,fontSize=12.sp);if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp)) }
        if(state.entries.isEmpty()) item { Text("As primeiras perguntas surgirão de impasses reais ou de pouca novidade nas experiências.",color=Solar.Muted,fontSize=12.sp) }
        items(state.entries,key={ it.intent.id }) { entry->
            SurfaceGlass {
                Text("${entry.intent.domain.title} · ${entry.phase.title}",color=Solar.Amber,style=MaterialTheme.typography.titleMedium)
                Text("${entry.intent.discoverer} · ciclo ${entry.intent.tick}",color=Solar.Muted,fontSize=11.sp)
                Text(entry.intent.reason,Modifier.padding(vertical=8.dp),fontSize=13.sp)
                Text("PT · ${entry.intent.queryPt}\nEN · ${entry.intent.queryEn}",color=Solar.Cognitive,fontSize=11.sp)
                entry.repo?.let { Text(it,Modifier.padding(top=8.dp),color=Solar.Biosphere,fontSize=12.sp) }
                Text("${entry.acquired} trechos recebidos · ${entry.learned} incorporados",color=Solar.Muted,fontSize=11.sp)
                Text(entry.message,Modifier.padding(vertical=6.dp),fontSize=11.sp)
                if(entry.acquired>0) TextButton(onClick={
                    selected=entry
                    scope.launch {
                        try { snippets=withContext(Dispatchers.IO) { WorldStore(app).use { it.research().snippets(entry.intent.id) } } }
                        catch(e: Exception) { error=e.message }
                    }
                }) { Text("Inspecionar evidências") }
            }
        }
        item { Text("Destilação LoRA: o carregamento de adaptadores está disponível em Sistema. Treino automático ainda não está disponível.",color=Solar.Muted,fontSize=11.sp) }
    }
    selected?.let { entry->AlertDialog(onDismissRequest={ selected=null;snippets=emptyList() },title={ Text(entry.repo ?: "Evidências") },text={
        Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it,color=Solar.Amber) }
            snippets.forEach { (c,status)->
                Text("$status · ${c.license}\nSHA-256 ${c.row.digest}",color=Solar.Cognitive,fontSize=10.sp)
                Text(c.row.text,fontSize=12.sp)
                c.researcher?.let { Text("Pesquisador ${it.agent}: ${it.verdict} · ${it.confidence}%",fontSize=10.sp) }
                c.verifier?.let { Text("Revisor ${it.agent}: ${it.verdict} · ${it.confidence}%",fontSize=10.sp) }
            }
        }
    },confirmButton={ TextButton(onClick={ selected=null;snippets=emptyList() }) { Text("Fechar") } }) }
}
