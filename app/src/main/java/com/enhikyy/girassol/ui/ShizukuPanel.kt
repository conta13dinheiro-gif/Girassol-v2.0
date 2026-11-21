package com.enhikyy.girassol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.privileged.ShizukuBridge
import com.enhikyy.girassol.ui.theme.Solar
import kotlinx.coroutines.launch

/** Optional read-only kernel telemetry. No Shizuku installation is required for normal operation. */
@Composable
internal fun ShizukuPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ShizukuBridge.initialize(context)
    val state by ShizukuBridge.state.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxWidth()
            .background(Solar.Surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("TELEMETRIA PRIVILEGIADA OPCIONAL", color = Solar.Amber, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Text(state.message, color = if (state.connected) Solar.Biosphere else Solar.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state.binderAvailable && !state.permissionGranted,
                onClick = {
                    runCatching { ShizukuBridge.requestPermission(context) }
                        .onFailure { error ->
                            android.widget.Toast.makeText(context, error.message ?: "Shizuku indisponível", android.widget.Toast.LENGTH_LONG).show()
                        }
                }
            ) { Text("Autorizar") }
            OutlinedButton(
                enabled = state.binderAvailable && state.permissionGranted && !state.connected,
                onClick = {
                    runCatching { ShizukuBridge.bind(context) }
                        .onFailure { error ->
                            android.widget.Toast.makeText(context, error.message ?: "Falha de conexão", android.widget.Toast.LENGTH_LONG).show()
                        }
                }
            ) { Text("Conectar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = state.connected,
                onClick = {
                    scope.launch {
                        runCatching { ShizukuBridge.refreshTelemetry() }
                            .onFailure { error ->
                                android.widget.Toast.makeText(context, error.message ?: "Falha ao ler telemetria", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                }
            ) { Text("Atualizar leituras") }
            OutlinedButton(
                enabled = state.connected,
                onClick = { ShizukuBridge.unbind() }
            ) { Text("Desconectar") }
        }
        Text(
            "Binder: ${if (state.binderAvailable) "disponível" else "ausente"} · permissão: ${if (state.permissionGranted) "concedida" else "não concedida"}",
            color = Solar.Muted,
            fontSize = 10.sp
        )
        state.remoteUid?.let { Text("UID do servidor Shizuku: $it", color = Solar.Muted, fontSize = 10.sp) }
        if (state.telemetry.isNotBlank()) {
            Text("LEITURAS DO PROCESSO REMOTO", color = Solar.Amber, fontSize = 9.sp, letterSpacing = 1.sp)
            Text(state.telemetry, color = Solar.Ink, fontSize = 10.sp, lineHeight = 15.sp)
        }
        Text(
            "A conexão é opcional e requer o usuário iniciar Shizuku por ADB/depuração sem fio e conceder autorização. O serviço lê apenas /proc/meminfo, temperatura e zRAM; não executa comandos, muda prioridades do kernel ou substitui o fallback sem privilégios.",
            color = Solar.Muted,
            fontSize = 10.sp
        )
    }
}
