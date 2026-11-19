package com.enhikyy.girassol.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.enhikyy.girassol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

data class ShizukuTelemetryState(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val connected: Boolean = false,
    val remoteUid: Int? = null,
    val telemetry: String = "",
    val message: String = "Shizuku não conectado"
)

/**
 * Optional read-only bridge to the Shizuku shell UserService.
 * The simulation does not depend on this service; all failures preserve the
 * normal app-process telemetry and do not attempt root or shell fallbacks.
 */
object ShizukuBridge {
    private const val REQUEST_PERMISSION_CODE = 7301
    private val mutableState = MutableStateFlow(ShizukuTelemetryState())
    val state = mutableState.asStateFlow()

    @Volatile private var application: Context? = null
    @Volatile private var remote: IShizukuTelemetry? = null
    @Volatile private var listenersRegistered = false
    @Volatile private var binding = false

    private val serviceArgs: Shizuku.UserServiceArgs
        get() {
            val context = checkNotNull(application)
            return Shizuku.UserServiceArgs(
                ComponentName(context, TelemetryUserService::class.java)
            )
                .daemon(false)
                .processNameSuffix("girassol_telemetry")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val client = service?.let { IShizukuTelemetry.Stub.asInterface(it) }
            remote = client
            binding = false
            mutableState.value = mutableState.value.copy(
                binderAvailable = Shizuku.pingBinder(),
                permissionGranted = hasPermission(),
                connected = client != null && service.pingBinder(),
                remoteUid = runCatching { Shizuku.getUid() }.getOrNull(),
                message = if (client != null && service.pingBinder())
                    "Telemetria privilegiada somente de leitura conectada"
                else "Shizuku retornou um serviço inválido"
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            mutableState.value = mutableState.value.copy(
                connected = false,
                telemetry = "",
                message = "O processo remoto do Shizuku foi desconectado"
            )
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshPermissionState("Binder Shizuku recebido")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        binding = false
        mutableState.value = ShizukuTelemetryState(message = "Binder Shizuku encerrado; fallback local ativo")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            mutableState.value = mutableState.value.copy(
                binderAvailable = Shizuku.pingBinder(),
                permissionGranted = granted,
                message = if (granted) "Permissão concedida; conecte a telemetria" else "Permissão Shizuku negada"
            )
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (application != null) return
        application = context.applicationContext
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenersRegistered = true
        }
        refreshPermissionState(
            if (Shizuku.pingBinder()) "Binder Shizuku disponível" else
                "Shizuku não iniciado; a simulação usa telemetria local"
        )
    }

    fun requestPermission(context: Context) {
        initialize(context)
        check(Shizuku.pingBinder()) {
            "Inicie Shizuku por depuração sem fio/ADB ou pelo método compatível do dispositivo"
        }
        if (hasPermission()) {
            refreshPermissionState("Permissão já concedida")
            return
        }
        Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        mutableState.value = mutableState.value.copy(message = "Aguardando autorização no Shizuku")
    }

    @Synchronized
    fun bind(context: Context) {
        initialize(context)
        check(Shizuku.pingBinder()) { "Binder Shizuku indisponível" }
        check(hasPermission()) { "Conceda a permissão Shizuku antes de conectar" }
        if (remote != null || binding) return
        binding = true
        mutableState.value = mutableState.value.copy(message = "Conectando serviço de telemetria…")
        try {
            Shizuku.bindUserService(serviceArgs, serviceConnection)
        } catch (error: Throwable) {
            binding = false
            remote = null
            mutableState.value = mutableState.value.copy(
                connected = false,
                message = "Falha ao conectar Shizuku: ${error.message ?: error.javaClass.simpleName}"
            )
            throw error
        }
    }

    suspend fun refreshTelemetry() {
        val client = remote ?: throw IllegalStateException("Conecte o serviço Shizuku primeiro")
        val result = withContext(Dispatchers.IO) {
            try {
                client.readTelemetry()
            } catch (error: RemoteException) {
                throw IllegalStateException("O serviço Shizuku perdeu a conexão", error)
            }
        }
        mutableState.value = mutableState.value.copy(
            telemetry = result,
            connected = true,
            message = "Telemetria shell atualizada"
        )
    }

    @Synchronized
    fun unbind() {
        val wasBound = remote != null || binding
        remote = null
        binding = false
        if (wasBound && Shizuku.pingBinder()) {
            runCatching { Shizuku.unbindUserService(serviceArgs, serviceConnection, true) }
        }
        mutableState.value = mutableState.value.copy(
            connected = false,
            telemetry = "",
            message = "Serviço Shizuku desconectado; fallback local ativo"
        )
    }

    private fun hasPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun refreshPermissionState(message: String) {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (available) hasPermission() else false
        mutableState.value = mutableState.value.copy(
            binderAvailable = available,
            permissionGranted = granted,
            message = message
        )
    }
}
