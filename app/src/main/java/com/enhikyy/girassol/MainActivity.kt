package com.enhikyy.girassol

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.enhikyy.girassol.audio.AmbientAudio
import com.enhikyy.girassol.cognition.CognitionController
import com.enhikyy.girassol.runtime.BiosphereHub
import com.enhikyy.girassol.runtime.BiosphereService
import com.enhikyy.girassol.ui.GirassolApp

class MainActivity : ComponentActivity() {
    private lateinit var audio: AmbientAudio
    private lateinit var cognition: CognitionController
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { cognition.install(it) } }
    private val adapterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { cognition.installAdapter(it) } }
    private val reviewerModelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { cognition.installReviewer(it) } }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = AmbientAudio(this, lifecycle)
        cognition = CognitionController(this, lifecycle)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val ui by BiosphereHub.state.collectAsStateWithLifecycle()
            val sound by audio.status.collectAsStateWithLifecycle()
            val neural by cognition.status.collectAsStateWithLifecycle()
            LaunchedEffect(ui.running, ui.thermal, ui.availableMb) { cognition.updateRuntime(ui.running, ui.thermal, ui.availableMb) }
            LaunchedEffect(ui.world, ui.running) { audio.update(ui.world, ui.running) }
            GirassolApp(ui, onToggle = { BiosphereService.command(this, if (ui.running) BiosphereService.ACTION_PAUSE else "START") }, onSpeed = { BiosphereService.command(this, BiosphereService.ACTION_SPEED) },
                sound = sound, onSound = audio::setEnabled, onVolume = audio::setVolume, cognition = neural,
                onImportModel = { modelPicker.launch(arrayOf("*/*")) }, onRemoveModel = cognition::remove,
                onInfer = cognition::evaluate, onCancelInference = cognition::cancel, onAutomaticInference = cognition::setAutomatic, onImportAdapter={ adapterPicker.launch(arrayOf("*/*")) },onRemoveAdapter=cognition::removeAdapter,
                onImportReviewerModel={ reviewerModelPicker.launch(arrayOf("*/*")) },onRemoveReviewerModel=cognition::removeReviewer)
        }
    }
    override fun onStart() {
        super.onStart()
        if (BiosphereHub.state.value.loading || getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("running", false)) BiosphereService.command(this, BiosphereService.ACTION_LOAD)
    }
}
