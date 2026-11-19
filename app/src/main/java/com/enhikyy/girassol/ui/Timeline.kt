package com.enhikyy.girassol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.data.WorldStore
import com.enhikyy.girassol.runtime.BiosphereService
import com.enhikyy.girassol.ui.theme.Solar
import kotlinx.coroutines.*

@Composable internal fun Timeline(world: WorldState,modifier: Modifier,onFlashback: (WorldState)->Unit) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var moments by remember { mutableStateOf<List<WorldStore.Moment>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmFork by remember { mutableStateOf<WorldStore.Moment?>(null) }
    val database=WorldStore.activeName(context)
    LaunchedEffect(world.tick/24,database) {
        try { moments=withContext(Dispatchers.IO) { WorldStore(context,database).use { it.moments() } } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { message=e.message }
    }
    LazyColumn(modifier,verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=12.dp)) {
        item {
            Text("Marcos temporais",style=MaterialTheme.typography.headlineLarge)
            Text("Revisite o estado gravado em cada transformação da biosfera.",color=Solar.Muted)
        }
        message?.let { item { Text(it,color=Solar.Amber) } }
        if(moments.isEmpty()) item { Text("O primeiro marco é preservado ao concluir o próximo ciclo.",color=Solar.Muted) }
        items(moments,key={ it.id }) { moment->
            OutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=Solar.Black.copy(alpha=.35f))) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("DIA ${moment.tick/1440+1} · CICLO ${moment.tick}",style=Solar.Metric.copy(fontSize=10.sp),color=Solar.Amber)
                    Text(moment.title,style=MaterialTheme.typography.titleLarge)
                    Text(moment.cause,color=Solar.Muted,fontSize=12.sp)
                    val names=moment.agents.map { id-> world.agents.firstOrNull { it.id==id }?.name ?: id }
                    if(names.isNotEmpty()) Text(names.joinToString(" · "),fontSize=11.sp,color=Solar.Cognitive)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=!busy,onClick={
                            busy=true
                            scope.launch {
                                try { onFlashback(withContext(Dispatchers.IO) { WorldStore(context,database).use { it.moment(moment.id).state } }) }
                                catch(e: CancellationException) { throw e }
                                catch(e: Exception) { message=e.message }
                                finally { busy=false }
                            }
                        }) { Text("Flashback") }
                        TextButton(enabled=!busy,onClick={ confirmFork=moment }) { Text("Bifurcar") }
                    }
                }
            }
        }
    }
    confirmFork?.let { moment->
        AlertDialog(onDismissRequest={ confirmFork=null },title={ Text("Abrir linha alternativa?") },
            text={ Text("A simulação continuará a partir do ciclo ${moment.tick} em uma cópia independente. O mundo atual será preservado.") },
            confirmButton={ TextButton(onClick={ BiosphereService.fork(context,moment.id); confirmFork=null; message="Preparando a linha alternativa…" }) { Text("Criar linha") } },
            dismissButton={ TextButton(onClick={ confirmFork=null }) { Text("Cancelar") } })
    }
}
