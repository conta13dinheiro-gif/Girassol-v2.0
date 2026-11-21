package com.enhikyy.girassol.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.audio.NeuralSpeechEngine
import com.enhikyy.girassol.ui.theme.Solar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional local Kokoro PT-BR voice. A large FP32 ONNX package is selected by
 * the user and stored privately; it is not downloaded automatically or bundled
 * inside the APK. Speech synthesis runs off the UI and AAudio ambience threads.
 */
@Composable
internal fun NeuralSpeechPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by NeuralSpeechEngine.state.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    NeuralSpeechEngine.initialize(context)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch {
                runCatching {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    withContext(Dispatchers.IO) { NeuralSpeechEngine.importKokoroBundle(context, uri) }
                }.onFailure { error ->
                    android.widget.Toast.makeText(
                        context,
                        error.message ?: "Não foi possível importar a voz",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                importing = false
            }
        }
    }

    Column(
        modifier.fillMaxWidth()
            .background(Solar.Surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("KOKORO-82M · PORTUGUÊS BRASILEIRO", color = Solar.Amber, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Text(state.message, color = if (state.installed) Solar.Biosphere else Solar.Muted, fontSize = 12.sp)
        Text(
            "A voz é opcional e totalmente offline após a importação. O pacote FP32 tem aproximadamente 350 MB, não acompanha o APK e requer espaço livre adicional para extração.",
            color = Solar.Muted,
            fontSize = 10.sp
        )
        Button(
            enabled = !state.busy && !importing,
            onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        ) { Text(if (state.installed) "Substituir pacote Kokoro PT-BR" else "Importar pacote Kokoro PT-BR (.zip)") }

        if (importing || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        Text("Voz", color = Solar.Ink, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(42 to "Dora", 43 to "Alex", 44 to "Santa").forEach { (id, label) ->
                FilterChip(
                    selected = state.voiceId == id,
                    onClick = { NeuralSpeechEngine.setVoice(id) },
                    enabled = !state.busy,
                    label = { Text(label) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state.installed && !state.busy && !state.playing && !importing,
                onClick = {
                    runCatching {
                        NeuralSpeechEngine.speak(
                            "Olá! Estou a observar a biosfera e a escolher o próximo passo.",
                            voiceId = state.voiceId
                        )
                    }.onFailure {
                        android.widget.Toast.makeText(context, it.message ?: "Falha na síntese Kokoro", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            ) { Text("Testar fala") }
            OutlinedButton(
                enabled = state.busy || state.playing,
                onClick = { NeuralSpeechEngine.stop() }
            ) { Text("Parar") }
            OutlinedButton(
                enabled = state.installed && !state.busy && !state.playing && !importing,
                onClick = {
                    runCatching { NeuralSpeechEngine.remove() }
                        .onFailure {
                            android.widget.Toast.makeText(context, it.message ?: "Falha ao remover voz", android.widget.Toast.LENGTH_LONG).show()
                        }
                }
            ) { Text("Remover") }
        }
        Text(
            "Pacote esperado: model.onnx FP32, voices.bin, tokens.txt e espeak-ng-data do bundle Kokoro PT-BR. Os IDs 42/43/44 correspondem a Dora/Alex/Santa. O áudio atual usa panorama estéreo por cidadão; HRTF binaural e propagação Steam Audio ainda não estão integradas.",
            color = Solar.Muted,
            fontSize = 10.sp
        )
    }
}
