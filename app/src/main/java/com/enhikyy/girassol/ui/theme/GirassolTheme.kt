package com.enhikyy.girassol.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Solar {
    val Black=Color(0xFF050507)
    val Surface=Color(0xFF10161D)
    val Ink=Color(0xFFF0F1EC)
    val Muted=Color(0xFFA8B3BF)
    val Amber=Color(0xFFFBBF24)
    val Biosphere=Color(0xFF10B981)
    val Cognitive=Color(0xFF38BDF8)
    val Border=Color(0xFFF59E0B).copy(alpha=.20f)
    val Metric=TextStyle(fontFeatureSettings="tnum",fontWeight=FontWeight.Medium)
}
@Composable fun GirassolTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=darkColorScheme(
        primary=Solar.Amber,onPrimary=Solar.Black,secondary=Solar.Biosphere,tertiary=Solar.Cognitive,
        background=Solar.Black,onBackground=Solar.Ink,surface=Solar.Surface,onSurface=Solar.Ink,
        onSurfaceVariant=Solar.Muted,outline=Solar.Border),
        typography=Typography(
            headlineLarge=TextStyle(fontSize=30.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.8).sp),
            titleLarge=TextStyle(fontSize=21.sp,fontWeight=FontWeight.SemiBold),
            bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
            labelMedium=Solar.Metric.copy(fontSize=12.sp)),content=content)
}
@Composable fun SurfaceGlass(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(24.dp),color=Solar.Surface.copy(alpha=.90f),
        border=BorderStroke(1.dp,Solar.Border),shadowElevation=12.dp) {
        Column(Modifier.padding(14.dp),content=content)
    }
}
