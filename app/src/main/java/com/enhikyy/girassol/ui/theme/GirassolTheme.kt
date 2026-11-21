package com.enhikyy.girassol.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.ui.typography.GirassolFontRegistry

data class GirassolPalette(
    val index: Int, val name: String, val background: Color, val surface: Color,
    val ink: Color, val muted: Color, val amber: Color, val biosphere: Color,
    val telemetry: Color, val border: Color
)

object GirassolThemePalette {
    val all = listOf(
        GirassolPalette(0,"OLED Kodak 70mm",Color(0xFF000000),Color(0xFF080A0B),Color(0xFFF4F0E8),Color(0xFFA8B0B7),Color(0xFFFF6B35),Color(0xFF103B2C),Color(0xFF38BDF8),Color(0x33E89B48)),
        GirassolPalette(1,"Kodak Night",Color(0xFF050505),Color(0xFF11100E),Color(0xFFFFF4E6),Color(0xFFB6A99A),Color(0xFFFF8A4C),Color(0xFF174D36),Color(0xFF64D2FF),Color(0x33F6A76B)),
        GirassolPalette(2,"Amber Lab",Color(0xFF070604),Color(0xFF15110B),Color(0xFFFFF5D8),Color(0xFFB9AA8A),Color(0xFFFFB454),Color(0xFF1B523A),Color(0xFF55C7E8),Color(0x33FFC46B)),
        GirassolPalette(3,"Arctic OLED",Color(0xFF02070A),Color(0xFF0A141A),Color(0xFFEAF7FC),Color(0xFFA4BAC5),Color(0xFFFF9466),Color(0xFF14513E),Color(0xFF55D6FF),Color(0x3349C8EA)),
        GirassolPalette(4,"Emerald Dark",Color(0xFF020806),Color(0xFF0A1510),Color(0xFFE8F5ED),Color(0xFFA3B9AA),Color(0xFFFF9A68),Color(0xFF176342),Color(0xFF59C8EE),Color(0x3335B77B)),
        GirassolPalette(5,"Graphite",Color(0xFF050607),Color(0xFF121518),Color(0xFFF1F3F4),Color(0xFFADB5BB),Color(0xFFFF875C),Color(0xFF164B38),Color(0xFF57C7F2),Color(0x33AAB4BC)),
        GirassolPalette(6,"Cyan Scope",Color(0xFF020609),Color(0xFF0A151B),Color(0xFFEAFBFF),Color(0xFFA6C1CC),Color(0xFFFF936A),Color(0xFF14503A),Color(0xFF38BDF8),Color(0x3348C8F0)),
        GirassolPalette(7,"Copper Film",Color(0xFF090605),Color(0xFF18100D),Color(0xFFFFF0E7),Color(0xFFC1AAA0),Color(0xFFFF754D),Color(0xFF164A36),Color(0xFF69CBEA),Color(0x33D88A63)),
        GirassolPalette(8,"Forest Negative",Color(0xFF020604),Color(0xFF0A120D),Color(0xFFEAF4E9),Color(0xFFA7B8A7),Color(0xFFFF8D62),Color(0xFF1C5B3D),Color(0xFF55C6E8),Color(0x3338A66B)),
        GirassolPalette(9,"Indigo Lab",Color(0xFF05050B),Color(0xFF11111C),Color(0xFFF0EFFB),Color(0xFFB4B2C8),Color(0xFFFF956B),Color(0xFF174C39),Color(0xFF72B8FF),Color(0x334D69C9)),
        GirassolPalette(10,"Silver Grain",Color(0xFF070809),Color(0xFF15181A),Color(0xFFF2F3F1),Color(0xFFB5B9B8),Color(0xFFFF8C5E),Color(0xFF174C38),Color(0xFF61C8ED),Color(0x33D7E0E4)),
        GirassolPalette(11,"Ruby Darkroom",Color(0xFF090405),Color(0xFF180B0E),Color(0xFFFFEFF0),Color(0xFFC5AEB2),Color(0xFFFF795F),Color(0xFF14513B),Color(0xFF69C7F2),Color(0x33D65D66)),
        GirassolPalette(12,"Violet Spectra",Color(0xFF07050A),Color(0xFF15101B),Color(0xFFF5EFFB),Color(0xFFBDB0C9),Color(0xFFFF956B),Color(0xFF14513A),Color(0xFF79C7FF),Color(0x338F6BC8)),
        GirassolPalette(13,"Monochrome",Color(0xFF000000),Color(0xFF101010),Color(0xFFFFFFFF),Color(0xFFB8B8B8),Color(0xFFFF8B60),Color(0xFF18503A),Color(0xFF76D4F8),Color(0x33FFFFFF)),
        GirassolPalette(14,"Warm Print",Color(0xFF080604),Color(0xFF17110D),Color(0xFFFFF1E2),Color(0xFFC3AA94),Color(0xFFFF7D48),Color(0xFF174D36),Color(0xFF66C8E9),Color(0x33E89B48))
    )
}

object Solar {
    var palette by mutableStateOf(GirassolThemePalette.all.first())
    var tabularNumbers by mutableStateOf(true)
    var metricFamily by mutableStateOf(FontFamily.Monospace)
    var editorialFamily by mutableStateOf(FontFamily.Serif)
    var chatFamily by mutableStateOf(FontFamily.SansSerif)
    val Black get() = palette.background
    val Surface get() = palette.surface
    val Ink get() = palette.ink
    val Muted get() = palette.muted
    val Amber get() = palette.amber
    val Biosphere get() = palette.biosphere
    val Cognitive get() = palette.telemetry
    val Border get() = palette.border
    val Metric get() = TextStyle(fontFeatureSettings = if (tabularNumbers) "tnum" else "", fontFamily = metricFamily, fontWeight = FontWeight.Medium)
    val Editorial get() = editorialFamily
    val Chat get() = chatFamily
}

@Composable
fun GirassolTheme(
    themeIndex: Int = 0,
    interfaceFont: String = "Inter",
    telemetryFont: String = "JetBrains Mono",
    editorialFont: String = "Newsreader",
    chatFont: String = "Inter",
    fontScale: Float = 1f,
    lineHeightScale: Float = 1.45f,
    tabularNumbers: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = GirassolThemePalette.all.getOrElse(themeIndex.coerceIn(0,14)) { GirassolThemePalette.all.first() }
    val interfaceFamily = remember(interfaceFont) { GirassolFontRegistry.family(interfaceFont) }
    val metricFamily = remember(telemetryFont) { GirassolFontRegistry.family(telemetryFont) }
    val editorialFamily = remember(editorialFont) { GirassolFontRegistry.family(editorialFont) }
    val chatFamily = remember(chatFont) { GirassolFontRegistry.family(chatFont) }
    SideEffect {
        Solar.palette = palette
        Solar.tabularNumbers = tabularNumbers
        Solar.metricFamily = metricFamily
        Solar.editorialFamily = editorialFamily
        Solar.chatFamily = chatFamily
    }
    val scale = fontScale.coerceIn(.85f,1.30f)
    val line = lineHeightScale.coerceIn(1.2f,1.8f)
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, scale) { Density(baseDensity.density, baseDensity.fontScale * scale) }
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = interfaceFamily, fontSize = 30.sp, lineHeight = 30.sp * line, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp),
        titleLarge = TextStyle(fontFamily = interfaceFamily, fontSize = 21.sp, lineHeight = 21.sp * line, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = interfaceFamily, fontSize = 15.sp, lineHeight = 15.sp * line),
        bodyMedium = TextStyle(fontFamily = interfaceFamily, fontSize = 14.sp, lineHeight = 14.sp * line),
        labelMedium = TextStyle(fontFamily = metricFamily, fontSize = 12.sp, lineHeight = 12.sp * line, fontFeatureSettings = if(tabularNumbers) "tnum" else "")
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.amber, onPrimary = palette.background, secondary = palette.biosphere,
            tertiary = palette.telemetry, background = palette.background, onBackground = palette.ink,
            surface = palette.surface, onSurface = palette.ink, onSurfaceVariant = palette.muted,
            outline = palette.border
        ),
        typography = typography,
        content = content
    )
    }
}

/** Frosted layered surface; Android RenderEffect blur is intentionally not applied to child content as a substitute for backdrop blur. */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier.shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = .65f), spotColor = Color.Black.copy(alpha = .75f)).clip(shape),
        shape = shape,
        color = Color(0xFF050708).copy(alpha = .80f),
        border = BorderStroke(.75.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .06f), Color.Black.copy(alpha = .32f)))),
        shadowElevation = 0.dp
    ) {
        Box {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = .055f), Color.Transparent, Color.Black.copy(alpha = .16f)))))
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

@Composable
fun SurfaceGlass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassSurface(modifier, content)
}
