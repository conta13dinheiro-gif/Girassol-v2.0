package com.enhikyy.girassol.ui.typography

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.enhikyy.girassol.ui.theme.GirassolTheme
import com.enhikyy.girassol.ui.theme.Solar

/** Debug-only deterministic host for typography UI screenshot tests. */
class TypographyStudioPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var settings by remember { mutableStateOf(FontSettings()) }
            GirassolTheme {
                Surface(Modifier.fillMaxSize(), color = Solar.Black) {
                    Box(Modifier.fillMaxSize()) {
                        TypographyStudioSheet(settings, onSettingsChange = { settings = it }, onDismiss = {})
                    }
                }
            }
        }
    }
}