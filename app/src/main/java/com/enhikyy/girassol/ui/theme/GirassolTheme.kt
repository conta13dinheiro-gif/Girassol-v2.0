package com.enhikyy.girassol.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class GirassolVisualTheme(val label: String) {
    OLED_KODAK_70MM("OLED Kodak 70mm"),
    SEVERANCE("Severance"),
    TROPICAL_MODERNISM("Modernismo Tropical"),
    DARKROOM("Darkroom"),
    ARAUCARIA("Araucária"),
    TERRACOTTA("Terracota"),
    OLIVE_STUDIO("Olive Studio"),
    MONOCHROME("Monochrome"),
    MIDNIGHT_BLUE("Midnight Blue"),
    AMBER_FILM("Amber Film"),
    FOREST("Forest"),
    CONCRETE("Concrete"),
    CINEMA_RED("Cinema Red"),
    PAPER("Paper"),
    HIGH_CONTRAST("High Contrast")
}

private data class Palette(
    val background: Color, val surface: Color, val ink: Color,
    val muted: Color, val accent: Color, val secondary: Color,
    val tertiary: Color, val border: Color
)

private val palettes = mapOf(
    GirassolVisualTheme.OLED_KODAK_70MM to Palette(Color(0xFF080808),Color(0xFF141414),Color(0xFFF4F0E6),Color(0xFFA9A49A),Color(0xFFE0B84F),Color(0xFF78866B),Color(0xFFB6A27A),Color(0xFFE0B84F)),
    GirassolVisualTheme.SEVERANCE to Palette(Color(0xFF080B08),Color(0xFF182018),Color(0xFFE8EDE5),Color(0xFFA2B09D),Color(0xFFB7C99A),Color(0xFF617A55),Color(0xFFD4C69C),Color(0xFF829477)),
    GirassolVisualTheme.TROPICAL_MODERNISM to Palette(Color(0xFF100C0A),Color(0xFF211713),Color(0xFFF2E5D5),Color(0xFFC4A997),Color(0xFFD18B64),Color(0xFF8D9A69),Color(0xFFD6B66F),Color(0xFFB77859)),
    GirassolVisualTheme.DARKROOM to Palette(Color(0xFF070707),Color(0xFF171717),Color(0xFFF1F1F1),Color(0xFF999999),Color(0xFFE7E7E7),Color(0xFF777777),Color(0xFFBBBBBB),Color(0xFF666666)),
    GirassolVisualTheme.ARAUCARIA to Palette(Color(0xFF07100C),Color(0xFF12231A),Color(0xFFE7F0E8),Color(0xFF9DB5A3),Color(0xFFB5C98A),Color(0xFF4E8065),Color(0xFF9CB8A5),Color(0xFF52745F)),
    GirassolVisualTheme.TERRACOTTA to Palette(Color(0xFF120B09),Color(0xFF281713),Color(0xFFF4E6DD),Color(0xFFC8A99C),Color(0xFFD47B5C),Color(0xFF8C6554),Color(0xFFE0B18B),Color(0xFF9D6652)),
    GirassolVisualTheme.OLIVE_STUDIO to Palette(Color(0xFF0D0E08),Color(0xFF1D2013),Color(0xFFECEEDC),Color(0xFFB0B49A),Color(0xFFC4B66A),Color(0xFF78804F),Color(0xFFB5A77A),Color(0xFF777B4C)),
    GirassolVisualTheme.MONOCHROME to Palette(Color(0xFF080808),Color(0xFF181818),Color(0xFFF5F5F5),Color(0xFFA0A0A0),Color(0xFFE0E0E0),Color(0xFF777777),Color(0xFFBDBDBD),Color(0xFF555555)),
    GirassolVisualTheme.MIDNIGHT_BLUE to Palette(Color(0xFF070B13),Color(0xFF111C2B),Color(0xFFE8F0FA),Color(0xFF9AAFC7),Color(0xFF83B8E8),Color(0xFF537AAB),Color(0xFFB4CBE1),Color(0xFF49698B)),
    GirassolVisualTheme.AMBER_FILM to Palette(Color(0xFF100B05),Color(0xFF24180A),Color(0xFFFFEBD0),Color(0xFFC6A982),Color(0xFFFFB84D),Color(0xFF9C6D32),Color(0xFFE3C18A),Color(0xFF8B602D)),
    GirassolVisualTheme.FOREST to Palette(Color(0xFF06100A),Color(0xFF102319),Color(0xFFE6F1E9),Color(0xFF9BB5A2),Color(0xFF78C99B),Color(0xFF387A56),Color(0xFFB0D0B9),Color(0xFF3D7251)),
    GirassolVisualTheme.CONCRETE to Palette(Color(0xFF0C0D0F),Color(0xFF1B1E22),Color(0xFFE8EAED),Color(0xFFA6ABB2),Color(0xFFB9C1CA),Color(0xFF697582),Color(0xFFD0D5DA),Color(0xFF5C6670)),
    GirassolVisualTheme.CINEMA_RED to Palette(Color(0xFF100707),Color(0xFF241010),Color(0xFFF6E8E6),Color(0xFFC5A2A0),Color(0xFFE05A4F),Color(0xFF8D3E3A),Color(0xFFE5B2A8),Color(0xFF8B4440)),
    GirassolVisualTheme.PAPER to Palette(Color(0xFF171510),Color(0xFF29261E),Color(0xFFF2EAD8),Color(0xFFC2B89F),Color(0xFFE3C46F),Color(0xFF8A9272),Color(0xFFD7C8A5),Color(0xFF766D56)),
    GirassolVisualTheme.HIGH_CONTRAST to Palette(Color(0xFF000000),Color(0xFF101010),Color(0xFFFFFFFF),Color(0xFFD0D0D0),Color(0xFFFFD400),Color(0xFF00E5A8),Color(0xFF66CFFF),Color(0xFFFFFFFF))
)

private val solarPaletteState = mutableStateOf(palettes.getValue(GirassolVisualTheme.OLED_KODAK_70MM))

object Solar {
    // Snapshot-backed getters remain ordinary Kotlin properties so non-composable
    // render helpers can read the active semantic palette without composable calls.
    val Black: Color get() = solarPaletteState.value.background
    val Surface: Color get() = solarPaletteState.value.surface
    val Ink: Color get() = solarPaletteState.value.ink
    val Muted: Color get() = solarPaletteState.value.muted
    val Amber: Color get() = solarPaletteState.value.accent
    val Biosphere: Color get() = solarPaletteState.value.secondary
    val Cognitive: Color get() = solarPaletteState.value.tertiary
    val Border: Color get() = solarPaletteState.value.border.copy(alpha=.20f)
    val Metric=TextStyle(fontFamily=FontFamily.Monospace,fontFeatureSettings="tnum",fontWeight=FontWeight.Medium)
}

@Composable fun GirassolTheme(
    theme: GirassolVisualTheme = GirassolVisualTheme.OLED_KODAK_70MM,
    content: @Composable ()->Unit
) {
    val p=palettes.getValue(theme)
    SideEffect { solarPaletteState.value = p }
    MaterialTheme(colorScheme=darkColorScheme(
        primary=p.accent,onPrimary=p.background,secondary=p.secondary,tertiary=p.tertiary,
        background=p.background,onBackground=p.ink,surface=p.surface,onSurface=p.ink,
        onSurfaceVariant=p.muted,outline=p.border),
        typography=Typography(
            headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=30.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.8).sp),
            titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=21.sp,fontWeight=FontWeight.SemiBold),
            bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=21.sp),
            labelMedium=Solar.Metric.copy(fontSize=12.sp)),content=content)
}

@Composable fun SurfaceGlass(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=Solar.Surface.copy(alpha=.90f),
        border=BorderStroke(1.dp,Solar.Border),shadowElevation=12.dp) {
        Column(Modifier.padding(14.dp),content=content)
    }
}
