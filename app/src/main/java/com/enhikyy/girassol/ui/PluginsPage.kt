package com.enhikyy.girassol.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.plugins.PluginRegistry
import com.enhikyy.girassol.ui.theme.Solar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hot-loads validated configuration packages without loading executable native code. */
@Composable
internal fun PluginsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PluginRegistry.initialize(context)
    val plugins by PluginRegistry.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { PluginRegistry.installZip(context, uri) }
                }.onSuccess { installed ->
                    android.widget.Toast.makeText(context, "Plugin ativado: ${installed.name}", android.widget.Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    android.widget.Toast.makeText(context, error.message ?: "Pacote recusado", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("EXTENSÕES DA BIOSFERA", color = Solar.Amber, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Text("Plugins hot-loadable", color = Solar.Ink, fontSize = 26.sp)
                Text(
                    "Três extensões nativas configuram pesquisa Hugging Face, película 70mm e afinação Trautonium. Pacotes ZIP podem acrescentar configurações compatíveis sem reiniciar o mundo.",
                    color = Solar.Muted,
                    fontSize = 12.sp
                )
                Button(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }) {
                    Text("Instalar pacote .zip")
                }
                Text(
                    "Pacotes aceitam manifest.json, JSON de configuração, materiais Filament .filamat e LUT .cube. Bibliotecas .so, DEX, JAR e scripts executáveis são recusados.",
                    color = Solar.Muted,
                    fontSize = 10.sp
                )
            }
        }
        if (plugins.isEmpty()) {
            item { Text("Nenhum plugin válido foi carregado.", color = Solar.Muted, fontSize = 12.sp) }
        }
        items(plugins, key = { it.id }) { plugin ->
            Column(
                Modifier.fillMaxWidth()
                    .background(Solar.Surface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.name, color = Solar.Ink, fontSize = 15.sp)
                        Text("${plugin.kind.label} · v${plugin.version}", color = Solar.Amber, fontSize = 10.sp)
                    }
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = { value ->
                            runCatching { PluginRegistry.setEnabled(context, plugin.id, value) }
                                .onFailure { android.widget.Toast.makeText(context, it.message ?: "Falha ao ativar", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    )
                }
                Text(plugin.description, color = Solar.Muted, fontSize = 11.sp)
                Text("${plugin.id} · SHA-256 ${plugin.digest.take(16)}…", color = Solar.Muted, fontSize = 9.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (plugin.builtIn) "Nativo protegido" else "Instalado pelo usuário", color = Solar.Biosphere, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    if (!plugin.builtIn) {
                        TextButton(onClick = {
                            runCatching { PluginRegistry.remove(context, plugin.id) }
                                .onFailure { android.widget.Toast.makeText(context, it.message ?: "Falha ao remover", android.widget.Toast.LENGTH_SHORT).show() }
                        }) { Text("Remover") }
                    }
                }
            }
            HorizontalDivider(color = Solar.Border)
        }
        item {
            Spacer(Modifier.padding(bottom = 10.dp))
            Text(
                "A ativação altera apenas pontos de extensão conhecidos. O registry não executa código arbitrário recebido em ZIP.",
                color = Solar.Muted,
                fontSize = 10.sp
            )
        }
    }
}
