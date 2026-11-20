package com.enhikyy.girassol.ui

import com.enhikyy.girassol.ui.theme.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import com.enhikyy.girassol.data.WorldStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.runtime.BiosphereUi
import com.enhikyy.girassol.render.NativeProjection
import com.enhikyy.girassol.render.FilamentWorldView
import com.enhikyy.girassol.audio.AudioStatus
import com.enhikyy.girassol.audio.NeuralSpeechEngine
import com.enhikyy.girassol.cognition.CognitionStatus
import java.util.Locale
import kotlin.math.*

private val Ink=Solar.Ink
private val Muted=Solar.Muted
private val Ground=Solar.Black
private val Panel=Solar.Surface
private val Gold=Solar.Amber
private val Mint=Solar.Biosphere
private val Line=Solar.Border
private fun percent(v: Double) = "${(v * 100).roundToInt()}%"
private fun number(v: Double) = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GirassolApp(ui: BiosphereUi, onToggle: () -> Unit, onSpeed: () -> Unit,
    sound: AudioStatus, onSound: (Boolean) -> Unit, onVolume: (Float) -> Unit,
    cognition: CognitionStatus, onImportModel: () -> Unit, onRemoveModel: () -> Unit,
    onInfer: () -> Unit, onCancelInference: () -> Unit, onAutomaticInference: (Boolean) -> Unit, onImportAdapter: ()->Unit,onRemoveAdapter: ()->Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusRequest by remember { mutableIntStateOf(0) }
    var cinema by rememberSaveable { mutableStateOf(false) }
    var tactical by rememberSaveable { mutableStateOf(false) }
    var heat by rememberSaveable { mutableIntStateOf(0) }
    var economy by rememberSaveable { mutableStateOf(false) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var historical by remember { mutableStateOf<WorldState?>(null) }
    var historySection by rememberSaveable { mutableIntStateOf(0) }
    var hfSection by rememberSaveable { mutableIntStateOf(0) }
    val research by com.enhikyy.girassol.research.ResearchHub.state.collectAsStateWithLifecycle()
    val world=historical ?: ui.world
    val selected=world?.agents?.firstOrNull { it.id==selectedId }
    val room=world?.rooms?.firstOrNull { "room:${it.id}"==selectedId }
    androidx.activity.compose.BackHandler(cinema || tab!=0 || selectedId!=null) {
        when { selectedId!=null->selectedId=null; cinema->cinema=false; else->tab=0 }
    }
    GirassolTheme {
        Box(Modifier.fillMaxSize().background(Solar.Black)) {
            if(world!=null) {
                if(!tactical && renderError==null) {
                    val lifecycle=LocalLifecycleOwner.current.lifecycle
                    var appliedFocus by remember { mutableIntStateOf(-1) }
                    AndroidView(
                        factory={ context-> FilamentWorldView(context,lifecycle,{ selectedId=it }) { renderError=it } },
                        modifier=Modifier.fillMaxSize().then(if(tab!=0) Modifier.blur(8.dp) else Modifier),
                        onRelease={ it.close() },
                        update={ view->
                            view.select={ if(!cinema) selectedId=it }
                            view.update(world,ui.running && historical==null,ui.thermal,1f,research.busy && historical==null)
                            if(appliedFocus!=focusRequest) { view.focus(focusId); appliedFocus=focusRequest }
                        }
                    )
                } else TacticalMap(world,heat,Modifier.fillMaxSize()) { if(!cinema) selectedId=it }
                if(!cinema) {
                    Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Solar.Black.copy(alpha=.94f),Color.Transparent))))
                    Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent,Solar.Black.copy(alpha=.94f)))))
                    CompactHud(ui.copy(world=world,running=ui.running && historical==null),cognition,{ if(historical==null) onToggle() },{ if(historical==null) onSpeed() },Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal=14.dp,vertical=8.dp))
                    if(historical!=null) {
                        FilledTonalButton(onClick={ historical=null },modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=154.dp,end=14.dp)) { Text("Flashback · voltar ao presente") }
                    }
                    if(tab==0) {
                        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top=116.dp,start=14.dp,end=14.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            AssistChip(onClick={ tactical=!tactical; renderError=null },label={ Text(if(tactical) "Cena 3D" else "Mapa") },leadingIcon={ Icon(Icons.Outlined.Map,null,Modifier.size(16.dp)) })
                            AssistChip(onClick={ heat=(heat+1)%4; tactical=heat!=0 || tactical },label={ Text(listOf("Camadas","Energia","Convívio","Atividade")[heat]) },leadingIcon={ Icon(Icons.Outlined.Layers,null,Modifier.size(16.dp)) })
                            AssistChip(onClick={ economy=true },label={ Text("Ecossistema") },leadingIcon={ Icon(Icons.Outlined.EnergySavingsLeaf,null,Modifier.size(16.dp)) })
                            AssistChip(onClick={ cinema=true },label={ Text("Cinema") },leadingIcon={ Icon(Icons.Outlined.CropFree,null,Modifier.size(16.dp)) })
                        }
                        SurfaceGlass(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start=14.dp,end=14.dp,bottom=91.dp).fillMaxWidth()) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if(ui.running) Solar.Biosphere else Solar.Amber,CircleShape))
                                Spacer(Modifier.width(8.dp)); Text("NÚCLEO AURORA",fontSize=10.sp,letterSpacing=1.4.sp,color=Solar.Amber)
                                Spacer(Modifier.weight(1f)); Text("${world.rooms.size} ambientes",fontSize=10.sp,color=Solar.Muted)
                            }
                            val dialogue=world.dialogues.lastOrNull()
                            Text(dialogue?.portuguese ?: "Acompanhe os primeiros caminhos desta civilização.",Modifier.padding(top=6.dp),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                            if(renderError!=null) Text("Cena indisponível. Toque em Cena 3D para tentar novamente.",color=Solar.Amber,fontSize=11.sp)
                        }
                    } else {
                        SurfaceGlass(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start=10.dp,end=10.dp,bottom=92.dp).fillMaxWidth().fillMaxHeight(.72f)) {
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text(listOf("","Gênesis & Cidadãos","Pedidos & Biblioteca","Hugging Face & Plugins","Marcos, Laboratório & Sistema")[tab],style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                                IconButton(onClick={ tab=0 }) { Icon(Icons.Outlined.Close,"Voltar ao mundo") }
                            }
                            when(tab) {
                                1->Citizens(world,Modifier.weight(1f)) { selectedId=it }
                                2->ResearchRequests(Modifier.weight(1f))
                                3->{
                                    TabRow(hfSection,containerColor=Color.Transparent) {
                                        listOf("Pesquisa","Plugins").forEachIndexed { i,label->
                                            Tab(hfSection==i,{ hfSection=i },text={ Text(label) })
                                        }
                                    }
                                    if(hfSection==0) {
                                        if(historical==null) ResearchLibrary(Modifier.weight(1f))
                                        else Text("Biblioteca do presente indisponível durante o flashback. Volte ao presente para consultar a pesquisa.",Modifier.padding(16.dp),color=Solar.Muted)
                                    } else PluginsPage(Modifier.weight(1f))
                                }
                                4->{
                                    TabRow(historySection,containerColor=Color.Transparent) {
                                        listOf("Marcos","Cultura","Sistema").forEachIndexed { i,label-> Tab(historySection==i,{ historySection=i },text={ Text(label) }) }
                                    }
                                    when(historySection) {
                                        0->Timeline(ui.world ?: world,Modifier.weight(1f)) { historical=it; selectedId=null; tab=0 }
                                        1->Culture(world,Modifier.weight(1f))
                                        else->SystemPage(ui,Modifier.weight(1f),sound,onSound,onVolume,cognition,onImportModel,onRemoveModel,onInfer,onCancelInference,onAutomaticInference,onImportAdapter,onRemoveAdapter)
                                    }
                                }
                            }
                        }
                    }
                    SurfaceGlass(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal=14.dp,vertical=8.dp).fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                            val icons=listOf(Icons.Outlined.Public,Icons.Outlined.PeopleOutline,Icons.Outlined.Inbox,Icons.Outlined.CloudDownload,Icons.Outlined.Tune)
                            listOf("Biosfera","Gênesis","Pedidos","HF & Plugins","Marcos/Sistema").forEachIndexed { i,label->
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min=48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if(tab==i) Solar.Amber.copy(alpha=.12f) else Color.Transparent)
                                        .clickable(role=Role.Tab) { tab=i }
                                        .semantics {
                                            this.selected = tab==i
                                            contentDescription = "$label, ${if(tab==i) "selecionado" else "aba"}"
                                        }
                                        .padding(vertical=6.dp),
                                    horizontalAlignment=Alignment.CenterHorizontally
                                ) {
                                    Icon(icons[i],null,Modifier.size(22.dp),tint=if(tab==i) Solar.Amber else Solar.Muted)
                                    Text(label,fontSize=11.sp,color=if(tab==i) Solar.Amber else Solar.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    IconButton(onClick={ cinema=false },modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).background(Solar.Black.copy(alpha=.6f),CircleShape)) { Icon(Icons.Outlined.FullscreenExit,"Sair do modo cinema",tint=Solar.Amber) }
                }
            } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally) { CircularProgressIndicator(color=Solar.Amber); Text("Despertando a biosfera",Modifier.padding(20.dp)) }
            }
            ui.error?.let { error->
                SurfaceGlass(Modifier.align(Alignment.Center).padding(24.dp)) {
                    Text("A biosfera precisa de atenção",style=MaterialTheme.typography.titleLarge,color=Solar.Amber)
                    Text(error,Modifier.padding(vertical=12.dp)); Text("O mundo gravado foi preservado.",color=Solar.Muted)
                }
            }
        }
        if(world!=null && (selected!=null || room!=null)) ModalBottomSheet(onDismissRequest={ selectedId=null },containerColor=Solar.Surface,scrimColor=Solar.Black.copy(alpha=.35f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=32.dp)) {
                if(selected!=null) {
                    Eyebrow("CIDADÃO · ${selected.lineage.uppercase()}")
                    Text(selected.name,style=MaterialTheme.typography.headlineLarge,color=Color(selected.color))
                    Text(if(selected.acting) selected.activity.label else "A caminho · ${selected.activity.label.lowercase()}",color=Muted)
                    world.minds[selected.id]?.let { mind->
                        Spacer(Modifier.height(16.dp)); SectionTitle("Personalidade & aprendizagem")
                        listOf("Abertura","Conscienciosidade","Extroversão","Amabilidade","Sensibilidade").zip(mind.personality).forEach { (label,value)-> KeyValue(label,percent(value)) }
                        Text("LTC · plasticidade Hebbiana",color=Solar.Cognitive,fontSize=12.sp)
                        Text("Novidade ${percent(mind.novelty)} · ${mind.behaviors.size} experiências distintas",color=Muted,fontSize=11.sp)
                        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(vertical=10.dp)) {
                            mind.parameters.takeLast(64).forEachIndexed { i,w->
                                val cell=size.width/32
                                drawRect((if(w>=0) Solar.Biosphere else Solar.Cognitive).copy(alpha=(.15+abs(w).coerceAtMost(1.0)*.85).toFloat()),Offset((i%32)*cell,(i/32)*size.height/2),Size(cell-1,size.height/2-1))
                            }
                        }
                    }
                    Button(onClick={ focusId=selected.id; focusRequest++; selectedId=null; tab=0; tactical=false }) { Icon(Icons.Outlined.MyLocation,null,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Acompanhar no mundo") }
                    NeedsBars(selected.needs)
                    Spacer(Modifier.height(20.dp)); SectionTitle("Experiências & habilidades")
                    Text("Domínio ${percent(selected.skill.mastery)} · ${selected.skill.successes} práticas · geração ${selected.genome.generation}",color=Muted)
                    KeyValue("Inventário","${world.balances["${selected.id}/coins"] ?: 0} moedas")
                    SectionTitle("Vínculos")
                    world.ties.filter { it.from==selected.id }.sortedByDescending { it.affinity }.take(5).forEach { tie->
                        KeyValue(world.agents.firstOrNull { it.id==tie.to }?.name ?: tie.to,percent(tie.affinity))
                    }
                    world.minds[selected.id]?.let { mind-> Spacer(Modifier.height(16.dp)); MemoryGraph(selected,mind,world.tick) }
                    SectionTitle("Memórias recentes")
                    selected.memories.takeLast(8).reversed().forEach { m-> KeyValue("Ciclo ${m.tick}",m.action.label) }
                } else if(room!=null) {
                    Eyebrow("AMBIENTE · NÚCLEO AURORA")
                    Text(room.name,style=MaterialTheme.typography.headlineLarge)
                    val present=world.agents.filter { hypot(it.x-room.x,it.z-room.z)<1.5 }
                    KeyValue("Ocupação","${present.size} / ${room.capacity}")
                    KeyValue("Área",number(room.width*room.depth)+" m²")
                    present.forEach { a->AgentRow(a) { selectedId=it } }
                    Button(onClick={ focusId="room:${room.id}"; focusRequest++; selectedId=null; tab=0; tactical=false }) { Text("Inspecionar no mundo") }
                }
            }
        }
        if(economy && world!=null) ModalBottomSheet(onDismissRequest={ economy=false },containerColor=Solar.Surface) {
            Economy(world,Modifier.fillMaxWidth().fillMaxHeight(.82f))
        }
    }
}
@Composable private fun CompactHud(ui: BiosphereUi,cognition: CognitionStatus,onToggle: ()->Unit,onSpeed: ()->Unit,modifier: Modifier) {
    val w=ui.world!!
    val metrics=remember(w.tick) { w.telemetry() }
    val minutes=(w.tick+461)%1440
    SurfaceGlass(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Sunflower(Modifier.size(24.dp)); Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("girassol",fontSize=18.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.6).sp)
                Text("DIA ${w.tick/1440+1} · %02d:%02d".format(minutes/60,minutes%60),style=Solar.Metric.copy(fontSize=10.sp),color=Solar.Muted)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text("${w.agents.size} cidadãos",style=Solar.Metric.copy(fontSize=12.sp))
                Text("${percent(metrics.socialCohesion)} harmonia",style=Solar.Metric.copy(fontSize=10.sp),color=Solar.Biosphere)
            }
            Spacer(Modifier.width(10.dp))
            FilledIconButton(onClick=onToggle,modifier=Modifier.size(34.dp),shape=RoundedCornerShape(11.dp)) { Icon(if(ui.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(ui.running) "Pausar" else "Continuar",Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(if(cognition.model!=null) Solar.Cognitive else Solar.Biosphere,CircleShape)); Spacer(Modifier.width(6.dp))
            Text(if(cognition.busy) "Cognição em atividade" else if(cognition.model!=null) "GGUF disponível" else "Autonomia local",fontSize=10.sp,color=Solar.Muted,modifier=Modifier.weight(1f))
            TextButton(onClick=onSpeed,contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.height(26.dp)) { Text("${ui.speed}×",style=Solar.Metric.copy(fontSize=12.sp)) }
        }
    }
}
@Composable private fun Sunflower(modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        for (i in 0..11) { val a = i * PI / 6; drawCircle(Gold, size.minDimension * .115f, c + Offset(cos(a).toFloat(), sin(a).toFloat()) * size.minDimension * .32f) }
        drawCircle(Gold, size.minDimension * .22f); drawCircle(Ground, size.minDimension * .12f)
    }
}
@Composable private fun Eyebrow(text: String) { Text(text, fontSize = 9.sp, letterSpacing = 1.6.sp, color = Gold, fontWeight = FontWeight.Medium) }
@Composable private fun PageHeading(kicker: String, title: String, description: String) {
    Eyebrow(kicker); Spacer(Modifier.height(10.dp)); Text(title, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(9.dp)); Text(description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
}
@Composable private fun SectionTitle(title: String, aside: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.Medium, fontSize = 21.sp); Spacer(Modifier.weight(1f)); Text(aside, color = Muted, fontSize = 10.sp) }
}
@Composable private fun AgentRow(a: Citizen, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { select(a.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Color(a.color).copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(a.name.take(1), color = Color(a.color), fontWeight = FontWeight.Medium, fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(a.name, fontSize = 14.sp); Text(a.lineage, fontSize = 10.sp, color = Muted) }
        Text(if (a.acting) a.activity.label else "Em movimento", color = Muted, fontSize = 11.sp)
        Icon(Icons.Outlined.ChevronRight, null, tint = Line, modifier = Modifier.size(20.dp))
    }
}

@Composable private fun Citizens(world: WorldState, modifier: Modifier, select: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    NeuralSpeechEngine.initialize(context)
    val speechStatus by NeuralSpeechEngine.state.collectAsStateWithLifecycle()
    val agents = world.agents.filter { it.name.contains(query, true) || it.lineage.contains(query, true) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("INDIVÍDUOS, HISTÓRIAS E VÍNCULOS", "Quem habita este mundo", "Cada habitante tem necessidades, experiências e um perfil genético próprio.") }
        item { OutlinedTextField(query, { query = it }, label = { Text("Buscar nome ou linhagem") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
        if (agents.isEmpty()) item { Text("Nenhum habitante encontrado.", color = Muted) }
        items(agents, key = { it.id }) { a ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(16.dp)) {
                AgentRow(a, select)
                Spacer(Modifier.height(14.dp))
                NeedsBars(a.needs)
                Spacer(Modifier.height(12.dp))
                Text("${a.memories.size} memórias · geração ${a.genome.generation}", color = Muted, fontSize = 10.sp)
                world.minds[a.id]?.psyche?.let { soul ->
                    Text(
                        "PAD ${"%.2f".format(soul.pleasure)} / ${"%.2f".format(soul.arousal)} / ${"%.2f".format(soul.dominance)} · cortisol ${"%.2f".format(soul.cortisol)} · oxitocina ${"%.2f".format(soul.oxytocin)}",
                        color=Muted,fontSize=9.sp
                    )
                    Text(
                        "GNW ${(soul.ignitionIndex*100).toInt()}% · erro ARD ${"%.2f".format(soul.lastPredictionError)} · vínculo ${soul.attachment.name.lowercase()}",
                        color=if(soul.ignitionIndex>.65) Solar.Amber else Muted,fontSize=9.sp
                    )
                }
                world.artifacts.lastOrNull { it.author==a.id && it.title=="Fala interna" }?.let { thought ->
                    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("REFLEXÃO PRIVADA · ${thought.text}",color=Solar.Cognitive,fontSize=10.sp)
                        OutlinedButton(
                            enabled=speechStatus.installed && !speechStatus.busy,
                            onClick={
                                runCatching {
                                    NeuralSpeechEngine.speak(
                                        thought.text,
                                        voiceId=speechStatus.voiceId,
                                        pan=((a.x-a.z)/30.0).toFloat().coerceIn(-1f,1f)
                                    )
                                }.onFailure {
                                    android.widget.Toast.makeText(context,it.message ?: "Falha na síntese de voz",android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        ) { Text("Ouvir reflexão") }
                    }
                }
            }
        }
        if (world.genealogy.isNotEmpty()) {
            item { SectionTitle("Genealogia", "${world.genealogy.size} nascimentos registrados") }
            items(world.genealogy.asReversed(), key = { it.child }) { family ->
                val child = world.agents.firstOrNull { it.id == family.child }
                val parentOne = world.agents.firstOrNull { it.id == family.parentOne }
                val parentTwo = world.agents.firstOrNull { it.id == family.parentTwo }
                if (child != null && parentOne != null && parentTwo != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).clickable { select(child.id) }.padding(16.dp)
                    ) {
                        Text(child.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("${parentOne.name} + ${parentTwo.name}", color = Gold, fontSize = 12.sp)
                        Text("Geração ${child.genome.generation} · ciclo ${family.tick}", color = Muted, fontSize = 10.sp)
                        Text("Genoma SHA-256 · ${family.genomeHash.take(16)}…", color = Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
@Composable private fun NeedsBars(needs: Needs) {
    listOf("Energia" to needs.energy, "Hidratação" to needs.hydration, "Alimentação" to needs.glucose, "Social" to needs.social, "Curiosidade" to needs.curiosity).forEach { (label,v) ->
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.width(88.dp), color = Muted, fontSize = 11.sp); LinearProgressIndicator(progress = { v.toFloat() }, Modifier.weight(1f).height(3.dp).clip(CircleShape), color = if (v < .4) Gold else Mint, trackColor = Line); Text(percent(v), Modifier.width(42.dp), fontSize = 10.sp, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
    }
}
@Composable private fun Culture(world: WorldState, modifier: Modifier) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var language by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeading("O QUE UMA CIVILIZAÇÃO DEIXA", "Cultura viva", "Palavras, encontros e registros escritos pelos próprios habitantes.") }
        item { ScrollableTabRow(section, containerColor = Ground, edgePadding = 0.dp) { listOf("Conversas", "Dicionário", "Obras").forEachIndexed { i,s -> Tab(section==i, { section=i }, text={ Text(s, fontSize=12.sp) }) } } }
        when (section) {
            0 -> {
                item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Bilíngue", "Nativo", "Português").forEachIndexed { i,s -> FilterChip(language==i, { language=i }, { Text(s, fontSize=10.sp) }) } } }
                if (world.dialogues.isEmpty()) item { EmptyCard("Encontros ainda por acontecer", "Acompanhe a simulação. Conversas surgem quando os habitantes se aproximam.") }
                items(world.dialogues.reversed()) { d ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)) {
                        Text("${d.speaker} → ${d.listener}", color=Gold, fontSize=11.sp); Spacer(Modifier.height(10.dp))
                        if (language != 2) Text(d.native, fontWeight=FontWeight.Medium, fontSize=22.sp)
                        if (language != 1) Text(d.portuguese, color=Muted, fontSize=12.sp)
                        Text("CICLO ${d.tick}", Modifier.padding(top=10.dp), fontSize=8.sp, color=Muted, letterSpacing=1.sp)
                    }
                }
            }
            1 -> {
                item { Text("${world.words.size} palavras · ${world.words.map { it.lineage }.distinct().size} dialetos", color=Muted, fontSize=12.sp) }
                items(world.words.sortedByDescending { it.uses }) { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(w.native, fontSize=23.sp, fontWeight=FontWeight.Medium); Text("${w.concept.label} · ${w.lineage}", color=Muted, fontSize=11.sp) }; Text("${w.uses} usos", color=Gold, fontSize=10.sp) }
                    HorizontalDivider(color=Line)
                }
            }
            2 -> {
                if (world.artifacts.isEmpty()) item { EmptyCard("A biblioteca aguarda suas histórias", "Habitantes curiosos procuram a escrivaninha para registrar suas experiências.") }
                items(world.artifacts.reversed()) { a ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(20.dp)) { Eyebrow("ARQUIVO · EVENTO #${a.sourceEventId}"); Spacer(Modifier.height(12.dp)); Text(a.title, fontSize=24.sp, fontWeight=FontWeight.Medium); Spacer(Modifier.height(12.dp)); Text(a.text, color=Muted); Spacer(Modifier.height(12.dp)); Text("${a.author} · ciclo ${a.tick}", fontSize=10.sp, color=Gold) }
                }
            }
        }
    }
}
@Composable private fun EmptyCard(title: String, subtitle: String) { Column(Modifier.background(Panel, RoundedCornerShape(14.dp)).padding(22.dp)) { Text(title, fontSize=22.sp,fontWeight=FontWeight.Medium); Spacer(Modifier.height(10.dp)); Text(subtitle,color=Muted) } }
@Composable private fun SystemPage(ui: BiosphereUi, modifier: Modifier, sound: AudioStatus,
    onSound: (Boolean) -> Unit, onVolume: (Float) -> Unit,
    cognition: CognitionStatus, onImportModel: () -> Unit, onRemoveModel: () -> Unit,
    onInfer: () -> Unit, onCancelInference: () -> Unit, onAutomaticInference: (Boolean) -> Unit, onImportAdapter: ()->Unit,onRemoveAdapter: ()->Unit) {
    val w=ui.world!!; val t=w.telemetry()
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var hardwareReport by remember { mutableStateOf<com.enhikyy.girassol.hardware.DeviceHardwareReport?>(null) }
    LaunchedEffect(context) { hardwareReport = withContext(Dispatchers.IO) { com.enhikyy.girassol.hardware.HardwareAutoTuner.probe(context) } }
    var auditing by remember { mutableStateOf(false) }
    var auditResult by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding=PaddingValues(22.dp), verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item { PageHeading("TRANSPARÊNCIA DO ECOSSISTEMA", "Por dentro da biosfera", "Estado real da simulação, integridade e recursos do dispositivo.") }
        item {
            SectionTitle("Cognição local · GGUF")
            Text(cognition.model ?: "Nenhum modelo instalado", color=Gold, fontSize=12.sp)
            Text(cognition.message, color=Muted, fontSize=11.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled=!cognition.busy, onClick=onImportModel) { Text("Importar GGUF", fontSize=11.sp) }
                if (cognition.model != null) TextButton(enabled=!cognition.busy, onClick=onRemoveModel) { Text("Remover", fontSize=11.sp) }
            }
            if (cognition.model != null) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Propostas automáticas", Modifier.weight(1f), fontSize=12.sp)
                    Switch(checked=cognition.automatic, enabled=!cognition.busy || cognition.automatic,
                        onCheckedChange=onAutomaticInference)
                }
                Button(enabled=!cognition.busy && ui.running, onClick=onInfer) { Text("Avaliar próximo habitante") }
            }
            if (cognition.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=onCancelInference) { Text("Cancelar operação") }
            }
            Text(cognition.adapter ?: "Sem adaptador LoRA",color=Solar.Cognitive,fontSize=11.sp)
            Row {
                OutlinedButton(onClick=onImportAdapter,enabled=!cognition.busy && cognition.model!=null) { Text("Importar LoRA") }
                if(cognition.adapter!=null) TextButton(onClick=onRemoveAdapter,enabled=!cognition.busy) { Text("Remover LoRA") }
            }
            Text(ui.neuralResult, color=Mint, fontSize=11.sp)
            Text("Modelo de instruções compatível com a RAM e o armazenamento disponíveis. Inferência CPU em 64 bits, contexto de 512 tokens, duas threads e limite de 30 s com cancelamento cooperativo. Modo automático a cada 15 s apenas com o app aberto; é desligado ao sair. O modelo não acompanha o APK. Necessidades urgentes têm prioridade e propostas aceitas ficam no diário.", color=Muted, fontSize=10.sp)
        }
        item { EvolutionPanel(w) }
        item {
            SectionTitle("Paisagem sonora")
            SoundControl(sound, onSound)
            Text("Volume · ${percent(sound.volume.toDouble())}", color = Muted, fontSize = 12.sp)
            Slider(value = sound.volume, onValueChange = onVolume, modifier = Modifier.semantics { contentDescription = "Volume da paisagem sonora" })
            Text("Sub-harmônicos, corda friccionada, vento, passos e marcos musicais gerados no dispositivo. O som acompanha a atividade dos habitantes e é suspenso ao sair do app. Ajuste também o volume de mídia do Android.", color = Muted, fontSize = 11.sp)
        }
        item { SectionTitle("Integridade do mundo"); Spacer(Modifier.height(12.dp)); KeyValue("Contratos do núcleo", if (WorldActor.violations(w).isEmpty()) "Válidos" else "Verificar"); KeyValue("Persistência", "SQLite · WAL"); KeyValue("Escritor lógico", "WorldActor"); KeyValue("Semente", "${w.seed}"); KeyValue("Ciclo persistido", "${w.tick}"); Text("SHA-256 · último evento", Modifier.padding(top=14.dp), fontSize=10.sp, color=Muted); Text(ui.hash, fontSize=9.sp, fontFamily=FontFamily.Monospace, color=Mint) }
        item {
            SectionTitle("Auditoria do diário")
            Text("Confere sequência, hashes e ligação ao checkpoint. Não substitui o replay das decisões. A gravação pode aguardar durante a conferência.", color=Muted, fontSize=11.sp)
            OutlinedButton(enabled=!auditing,onClick={
                auditing=true
                scope.launch {
                    try {
                        auditResult=withContext(Dispatchers.IO) {
                            try { WorldStore(context).use { "Cadeia válida · ${it.audit()} eventos até o checkpoint conferido" } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { "Falha na auditoria: ${e.message ?: "não foi possível ler o diário"}" }
                        }
                    } finally { auditing=false }
                }
            }) { Text(if(auditing) "Conferindo…" else "Conferir diário completo") }
            auditResult?.let { Text(it,color=Gold,fontSize=11.sp) }
        }
        item { SectionTitle("Observação social"); Spacer(Modifier.height(12.dp)); KeyValue("Entropia de ações", number(t.entropy)); KeyValue("Gini de moedas", number(t.gini)); KeyValue("Coesão média", percent(t.socialCohesion)); KeyValue("Pressão por escassez", number(t.stress)) }
        item {
            SectionTitle("Linhas da biosfera")
            val active=WorldStore.activeName(context)
            context.databaseList().filter { it=="girassol.db" || it.startsWith("girassol-branch-") && it.endsWith(".db") }.sorted().forEach { name->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(name=="girassol.db") "Linha original" else "Alternativa ${name.removeSuffix(".db").takeLast(8)}",Modifier.weight(1f),fontSize=12.sp)
                    TextButton(onClick={ com.enhikyy.girassol.runtime.BiosphereService.switchWorld(context,name) },enabled=name!=active) { Text(if(name==active) "Ativa" else "Abrir") }
                }
            }
        }
        item {
            SectionTitle("Auto-detecção de hardware")
            val report = hardwareReport
            if (report == null) Text("Lendo SoC, ABI, RAM e renderer gráfico…", color=Muted, fontSize=11.sp)
            else {
                KeyValue("Fabricante / SoC", "${report.socVendor} · ${report.socModel}")
                KeyValue("Dispositivo", "${report.manufacturer} ${report.model}")
                KeyValue("Arquitetura / ABI", "${report.architecture} · ${report.primaryAbi}")
                KeyValue("ABIs suportadas", report.supportedAbis.joinToString())
                KeyValue("GPU / renderer", report.gpuRenderer)
                KeyValue("RAM física / CPUs visíveis", String.format(Locale.ROOT, "%.1f GB · %d", report.totalRamGb, report.logicalCpuCount))
                KeyValue("Sonda nativa", if(report.nativeProbeAvailable) "Ativa" else "Indisponível · fallback Kotlin")
                KeyValue("CPU SIMD", listOfNotNull(
                    "NEON".takeIf { report.hasNeon },
                    "DOTPROD".takeIf { report.hasDotProd },
                    "I8MM".takeIf { report.hasI8mm },
                    "SVE2".takeIf { report.hasSve2 },
                    "AVX2".takeIf { report.hasAvx2 }
                ).ifEmpty { listOf("Sem recursos adicionais confirmados") }.joinToString(" · "))
                KeyValue("Núcleos Big/Prime", report.bigCoreIds.joinToString().ifBlank { "Não identificados" })
                KeyValue("Núcleos LITTLE", report.littleCoreIds.joinToString().ifBlank { "Não identificados" })
                KeyValue("Variantes CPU GGML", report.ggmlVariantOrder)
                KeyValue("Loader OpenCL", when {
                    report.nativeProbeAvailable && report.openClLibraryFileFound -> "Loader e símbolo disponíveis"
                    report.openClLibraryFileFound -> "Arquivo presente; símbolo não verificado"
                    else -> "Não disponível"
                })
                KeyValue("Loader Vulkan", when {
                    report.nativeProbeAvailable && report.vulkanLibraryFileFound -> "Loader e símbolo disponíveis"
                    report.vulkanLibraryFileFound -> "Arquivo presente; símbolo não verificado"
                    else -> "Não disponível"
                })
                KeyValue("Backend GGUF neste build", "CPU · llama.cpp")
                Text("A sonda identifica instruções e símbolos de loaders sem inicializar dispositivos nem executar código do driver. Isso não comprova um ICD funcional nem aceleração ativa. O backend deste build permanece CPU; a seleção GPU exige integração e validação próprias.", color=Muted, fontSize=10.sp)
            }
        }
        item { SectionTitle("Dispositivo"); Spacer(Modifier.height(12.dp)); KeyValue("Memória disponível", "${ui.availableMb} MB"); KeyValue("Estado térmico", when { ui.thermal >= 3 -> "Carga reduzida"; ui.thermal > 0 -> "Aquecendo"; else -> "Normal" }); KeyValue("Operação", if(ui.running) "Serviço ativo" else "Pausada"); KeyValue("WakeLock parcial", if(ui.wakeLockHeld && ui.running) "Ativo" else "Liberado"); Text("O estado do WakeLock é lido de PowerManager. Isso não garante execução ininterrupta: Android e fabricantes continuam controlando processos, bateria e temperatura. Cada ciclo concluído é salvo antes de aparecer na interface.", color=Muted, fontSize=11.sp) }
        item { SectionTitle("Voz neural offline"); Spacer(Modifier.height(10.dp)); NeuralSpeechPanel() }
        item { SectionTitle("Shizuku · telemetria de sistema"); Spacer(Modifier.height(10.dp)); ShizukuPanel() }
        item {
            SectionTitle("Recursos do mercado"); Spacer(Modifier.height(12.dp))
            w.initialTotals.forEach { (item, total) ->
                val label = mapOf("water" to "Água", "food" to "Alimento", "coffee" to "Café", "paper" to "Papel", "timber" to "Madeira", "coins" to "Moedas")[item] ?: item
                KeyValue(label, "${w.balances["market/$item"] ?: 0} / $total")
            }
            Text("Disponível no mercado / total da biosfera. Água, alimento, café e papel retornam aos poucos; moedas circulam pelo trabalho e pelas compras.", color=Muted, fontSize=11.sp)
        }
    }
}
@Composable private fun SoundControl(sound: AudioStatus, onSound: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (sound.enabled) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff, null, tint = Gold, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text("Sons da biosfera", fontSize = 14.sp); Text(sound.message, color = Muted, fontSize = 10.sp) }
        Switch(checked = sound.enabled, onCheckedChange = onSound, modifier = Modifier.semantics { contentDescription = "Ativar sons da biosfera" })
    }
}
@Composable private fun KeyValue(label:String,value:String) { Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) { Text(label, color=Muted, modifier=Modifier.weight(1f),fontSize=12.sp); Text(value,color=Ink,fontSize=12.sp) }; HorizontalDivider(color=Line.copy(alpha=.5f)) }
