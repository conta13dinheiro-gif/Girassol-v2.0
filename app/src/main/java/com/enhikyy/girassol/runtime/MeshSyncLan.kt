package com.enhikyy.girassol.runtime

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.enhikyy.girassol.data.WorldStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MeshPeer(val id: String, val host: String, val port: Int)

/** Authenticated local-only snapshot migration: NSD + UDP discovery and bounded HMAC-SHA256 TCP transfer. */
class MeshSyncLan(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val id = UUID.randomUUID().toString().take(12)
    private val acceptedNonces = ConcurrentHashMap.newKeySet<String>()
    private val mutablePeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers = mutablePeers.asStateFlow()
    private val mutableStatus = MutableStateFlow("Desconectado")
    val status = mutableStatus.asStateFlow()
    @Volatile private var secret: ByteArray? = null
    @Volatile private var closed = true
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var releaseLease: Runnable? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start(passphrase: String) {
        require(passphrase.toByteArray(Charsets.UTF_8).size >= 12) { "Use uma chave compartilhada com pelo menos 12 bytes" }
        closed = true
        stopNetwork()
        closed = false
        secret = passphrase.toByteArray(Charsets.UTF_8)
        mutableStatus.value = "Procurando aparelhos na rede local…"
        acquireTimedWifiLease()
        startTcpServer()
        startUdpDiscovery()
        startNsd()
    }

    private fun acquireTimedWifiLease() {
        runCatching {
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Girassol:MeshSync")
            wifiLock?.acquire(60_000L)
            multicast = wifi?.createMulticastLock("Girassol:MeshSyncDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val release = Runnable {
                runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
                runCatching { if (multicast?.isHeld == true) multicast?.release() }
                wifiLock = null
                multicast = null
            }
            releaseLease = release
            main.postDelayed(release, 60_000L)
        }.onFailure { mutableStatus.value = "Lease Wi-Fi indisponível: ${it.message}" }
    }

    private fun startTcpServer() {
        executor.execute {
            try {
                ServerSocket(TCP_PORT, 8, InetAddress.getByName("0.0.0.0")).use { listener ->
                    server = listener
                    while (!closed) {
                        val client = listener.accept()
                        executor.execute {
                            runCatching { receive(client) }
                                .onFailure { mutableStatus.value = "Transferência recusada: ${it.message ?: "erro de validação"}" }
                        }
                    }
                }
            } catch (error: Exception) {
                if (!closed) mutableStatus.value = "Servidor LAN: ${error.message}"
            }
        }
    }

    private fun receive(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream())
            require(input.readUTF() == TRANSFER_MAGIC) { "Protocolo MeshSync incompatível" }
            val size = input.readLong()
            require(size in 1..MAX_TRANSFER_BYTES) { "Snapshot acima de 256 MiB" }
            val nonce = input.readUTF()
            require(nonce.matches(Regex("[a-f0-9-]{36}"))) { "Nonce de transferência inválido" }
            val expectedHash = input.readUTF()
            val expectedMac = input.readUTF()
            val temp = File.createTempFile("girassol-mesh-", ".grs", app.cacheDir)
            try {
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(read > 0) { "Transferência interrompida" }
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.fd.sync()
                }
                val digest = sha256(temp.readBytes())
                require(constantEquals(digest, expectedHash)) { "Hash SHA-256 do snapshot não confere" }
                val signed = "$TRANSFER_MAGIC|$nonce|$size|$digest"
                require(constantEquals(hmac(requireNotNull(secret), signed), expectedMac)) { "Autenticação HMAC inválida" }
                require(acceptedNonces.size < 1024 && acceptedNonces.add(nonce)) { "Nonce de transferência repetido ou limite de sessões atingido" }
                val database = WorldStore.importGrs(app, temp)
                mutableStatus.value = "Migração concluída · linha $database ativada"
            } finally { temp.delete() }
        }
    }

    fun sendSnapshot(peer: MeshPeer) {
        val key = secret ?: throw IllegalStateException("Ative o MeshSync e informe a chave compartilhada")
        val file = File.createTempFile("girassol-export-", ".grs", app.cacheDir)
        try {
            WorldStore(app).use { it.exportGrs(file) }
            val size = file.length()
            require(size in 1..MAX_TRANSFER_BYTES) { "Snapshot acima de 256 MiB" }
            val digest = sha256(file.readBytes())
            val nonce = UUID.randomUUID().toString()
            val signed = "$TRANSFER_MAGIC|$nonce|$size|$digest"
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.host, peer.port), 8_000)
                socket.soTimeout = 30_000
                val output = DataOutputStream(socket.getOutputStream())
                output.writeUTF(TRANSFER_MAGIC)
                output.writeLong(size)
                output.writeUTF(nonce)
                output.writeUTF(digest)
                output.writeUTF(hmac(key, signed))
                FileInputStream(file).use { it.copyTo(output, 64 * 1024) }
                output.flush()
            }
            mutableStatus.value = "Snapshot autenticado enviado para ${peer.id}"
        } finally { file.delete() }
    }

    private fun startUdpDiscovery() {
        executor.execute {
            try {
                DatagramSocket(null).use { socket ->
                    udp = socket
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(UDP_PORT))
                    socket.soTimeout = 900
                    val buffer = ByteArray(512)
                    while (!closed) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val line = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            val fields = line.split('|')
                            if (fields.size == 4 && fields[0] == DISCOVERY_MAGIC) {
                                val body = fields.take(3).joinToString("|")
                                val key = secret
                                if (key != null && constantEquals(hmac(key, body), fields[3])) {
                                    val port = fields[2].toIntOrNull() ?: continue
                                    val host = packet.address.hostAddress ?: continue
                                    if (host != localAddress()) publish(MeshPeer(fields[1], host, port))
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (error: Exception) {
                if (!closed) mutableStatus.value = "Descoberta UDP indisponível: ${error.message}"
            }
        }
        executor.execute {
            while (!closed) {
                val key = secret ?: break
                val body = "$DISCOVERY_MAGIC|$id|$TCP_PORT"
                val bytes = "$body|${hmac(key, body)}".toByteArray(Charsets.UTF_8)
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), UDP_PORT))
                    }
                }
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun startNsd() {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Girassol-$id"
            serviceType = SERVICE_TYPE
            port = TCP_PORT
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { mutableStatus.value = "MeshSync ativo · NSD + UDP · porta $TCP_PORT" }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { mutableStatus.value = "NSD indisponível ($errorCode); UDP continua ativo" }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
        }
        registration = registrationListener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE || info.serviceName == "Girassol-$id") return
                runCatching { manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) { }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host?.hostAddress ?: return
                        if (host != localAddress()) publish(MeshPeer(service.serviceName.removePrefix("Girassol-"), host, service.port))
                    }
                }) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                mutablePeers.value = mutablePeers.value.filterNot { it.id == info.serviceName.removePrefix("Girassol-") }
            }
            override fun onDiscoveryStopped(type: String) { }
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { runCatching { manager.stopServiceDiscovery(this) } }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) { }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun publish(peer: MeshPeer) {
        if (peer.host.isBlank() || peer.port !in 1..65535) return
        mutablePeers.value = (mutablePeers.value.filterNot { it.id == peer.id || it.host == peer.host } + peer).take(16)
    }

    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun stopNetwork() {
        releaseLease?.let { main.removeCallbacks(it); it.run() }
        releaseLease = null
        runCatching { server?.close() }; server = null
        runCatching { udp?.close() }; udp = null
        registration?.let { runCatching { nsd?.unregisterService(it) } }; registration = null
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }; discovery = null
        mutablePeers.value = emptyList()
    }

    fun stop() {
        closed = true
        stopNetwork()
        secret = null
        mutableStatus.value = "Desconectado"
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    companion object {
        const val TCP_PORT = 45455
        const val UDP_PORT = 45454
        const val SERVICE_TYPE = "_girassol._tcp."
        private const val DISCOVERY_MAGIC = "GIRASSOL-LAN/1"
        private const val TRANSFER_MAGIC = "GIRASSOL-GRS/1"
        private const val MAX_TRANSFER_BYTES = 256L * 1024L * 1024L
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun hmac(key: ByteArray, value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        private fun constantEquals(left: String, right: String) =
            MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
    }
}
