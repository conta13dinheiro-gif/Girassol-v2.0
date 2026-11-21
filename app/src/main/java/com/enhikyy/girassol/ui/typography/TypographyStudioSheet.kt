package com.enhikyy.girassol.ui.typography

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.ui.theme.GirassolThemePalette

private enum class FontScope(val label: String) { INTERFACE("Interface"), CHAT("Chat"), EDITORIAL("Editorial"), TELEMETRY("Telemetria") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographyStudioSheet(settings: FontSettings, onSettingsChange: (FontSettings) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var categoryTab by rememberSaveable { mutableIntStateOf(0) }
    var scope by rememberSaveable { mutableStateOf(FontScope.INTERFACE) }
    val haptics = LocalHapticFeedback.current
    val categories = GirassolFontRegistry.categories
    val visibleFonts = remember(categoryTab, query) {
        val source = if (categoryTab == 0) emptyList() else GirassolFontRegistry.catalog.filter { it.category == categories[categoryTab - 1] }
        source.filter { it.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF090C10), scrimColor = Color.Black.copy(alpha = .72f)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Typography Studio", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFF6F1E8))
                    Text("${GirassolFontRegistry.catalog.size} famílias · 32 offline · preferências locais", color = Color(0xFFA8B3BF), fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FontScope.entries.forEach { item -> FilterChip(selected = scope == item, onClick = { scope = item }, label = { Text(item.label, fontSize = 11.sp) }) }
            }
            Text("PRESETS DE IA", color = Color(0xFFE89B48), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GirassolFontRegistry.presets.take(3).forEach { preset -> AssistChip(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSettingsChange(settings.applyPreset(preset)) }, label = { Text(preset.label, fontSize = 10.sp) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GirassolFontRegistry.presets.drop(3).forEach { preset -> AssistChip(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSettingsChange(settings.applyPreset(preset)) }, label = { Text(preset.label, fontSize = 10.sp) }) }
            }
            Text("TEMAS OLED · 15", color = Color(0xFFE89B48), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GirassolThemePalette.all.forEach { palette -> FilterChip(selected = settings.themeIndex == palette.index, onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSettingsChange(settings.copy(themeIndex = palette.index)) }, label = { Text(palette.name, fontSize = 10.sp) }) }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Buscar fonte por nome") }, placeholder = { Text("Newsreader, Inter, JetBrains Mono…") })
            ScrollableTabRow(selectedTabIndex = categoryTab, containerColor = Color.Transparent, edgePadding = 0.dp) {
                Tab(selected = categoryTab == 0, onClick = { categoryTab = 0 }, text = { Text("Presets", fontSize = 10.sp) })
                categories.forEachIndexed { index, category -> Tab(selected = categoryTab == index + 1, onClick = { categoryTab = index + 1 }, text = { Text(category.title, fontSize = 9.sp, maxLines = 1) }) }
            }
            if (categoryTab == 0) {
                Text("Escolha um preset acima para aplicar uma combinação coordenada às quatro áreas tipográficas.", color = Color(0xFFA8B3BF), fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                Text("${visibleFonts.size} famílias nesta categoria", color = Color(0xFFA8B3BF), fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
                LazyColumn(Modifier.height(250.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(visibleFonts, key = { it.name }) { entry ->
                        val family = remember(entry.name) { GirassolFontRegistry.family(entry.name) }
                        val active = when (scope) { FontScope.INTERFACE -> settings.interfaceFont; FontScope.CHAT -> settings.chatFont; FontScope.EDITORIAL -> settings.editorialFont; FontScope.TELEMETRY -> settings.telemetryFont }
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            val updated = when (scope) {
                                FontScope.INTERFACE -> settings.copy(interfaceFont = entry.name)
                                FontScope.CHAT -> settings.copy(chatFont = entry.name)
                                FontScope.EDITORIAL -> settings.copy(editorialFont = entry.name)
                                FontScope.TELEMETRY -> settings.copy(telemetryFont = entry.name)
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSettingsChange(updated)
                        }.semantics { role = Role.Button; contentDescription = "Selecionar fonte ${entry.name}" }.background(if (active == entry.name) Color(0xFF17232B) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.name, Modifier.weight(1f), color = Color(0xFFF5F2EC), fontFamily = family, fontSize = 17.sp)
                                if (active == entry.name) Text("ATIVA", color = Color(0xFFE89B48), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("A civilização floresce quando ideias encontram espaço.", color = Color(0xFFA8B3BF), fontFamily = family, fontSize = 11.sp)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = .055f))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Escala ${"%.2f".format(settings.scale)}×", color = Color(0xFFF5F2EC), fontSize = 12.sp)
            Slider(value = settings.scale, onValueChange = { onSettingsChange(settings.copy(scale = it)) }, valueRange = .85f..1.30f, modifier = Modifier.semantics { contentDescription = "Escala tipográfica de 0.85 a 1.30" })
            Text("Altura de linha ${"%.2f".format(settings.lineHeightScale)}×", color = Color(0xFFF5F2EC), fontSize = 12.sp)
            Slider(value = settings.lineHeightScale, onValueChange = { onSettingsChange(settings.copy(lineHeightScale = it)) }, valueRange = 1.2f..1.8f, modifier = Modifier.semantics { contentDescription = "Altura de linha de 1.2 a 1.8" })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Números tabulares", color = Color(0xFFF5F2EC), fontSize = 13.sp); Text("tnum · relógios, coordenadas e telemetria", color = Color(0xFFA8B3BF), fontSize = 10.sp) }
                Switch(checked = settings.tabularNumbers, onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSettingsChange(settings.copy(tabularNumbers = it)) }, modifier = Modifier.semantics { contentDescription = "Ativar números tabulares para métricas" })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun FontSettings.applyPreset(preset: AiFontPreset): FontSettings = copy(interfaceFont = preset.interfaceFont, chatFont = preset.interfaceFont, editorialFont = preset.editorialFont, telemetryFont = preset.codeFont, presetId = preset.id)