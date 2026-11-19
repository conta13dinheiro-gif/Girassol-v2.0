package com.enhikyy.girassol.plugins

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class PluginKind(val label: String) {
    DATA_SOURCE("Fonte de pesquisa"),
    FILM_PROFILE("Perfil de película"),
    AUDIO_SCALE("Escala Trautonium"),
    BIOME_RULE("Regra de bioma")
}

data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: Int,
    val kind: PluginKind,
    val description: String,
    val config: JsonObject,
    val digest: String,
    val builtIn: Boolean,
    val enabled: Boolean
)

/**
 * Configuration-only hot-load registry. Packages may ship JSON, Filament .filamat
 * and LUT .cube assets. Native libraries, bytecode and scripts are deliberately
 * rejected; a manifest can configure known extension points but cannot execute code.
 */
object PluginRegistry {
    private const val MAX_ENTRIES = 64
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val allowedHosts = setOf(
        "huggingface.co",
        "datasets-server.huggingface.co",
        "api.wikipedia.org",
        "export.arxiv.org"
    )
    private val mutable = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val state = mutable.asStateFlow()
    @Volatile private var application: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (application != null) return
        application = context.applicationContext
        reload()
    }

    @Synchronized
    fun reload() {
        val context = checkNotNull(application) { "PluginRegistry ainda não inicializado" }
        val prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
        val builtins = context.assets.list("plugins/builtin").orEmpty()
            .filter { it.endsWith(".json") }
            .sorted()
            .mapNotNull { name ->
                runCatching {
                    val bytes = context.assets.open("plugins/builtin/$name").use { it.readBytes() }
                    parse(bytes, builtIn = true, enabled = prefs.getBoolean("enabled:${name.substringBeforeLast('.')}", true))
                }.getOrNull()
            }
        val custom = File(context.filesDir, "plugins").listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { directory ->
                runCatching {
                    val file = File(directory, "manifest.json")
                    require(file.isFile)
                    parse(
                        file.readBytes(),
                        builtIn = false,
                        enabled = prefs.getBoolean("enabled:${directory.name}", true)
                    )
                }.getOrNull()
            }
        mutable.value = (builtins + custom).distinctBy { it.id }.sortedWith(compareBy<InstalledPlugin> { it.kind.ordinal }.thenBy { it.name })
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        initialize(context)
        require(mutable.value.any { it.id == id }) { "Plugin não instalado" }
        context.applicationContext.getSharedPreferences("plugins", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled:$id", enabled).apply()
        reload()
    }

    fun installZip(context: Context, uri: Uri): InstalledPlugin {
        initialize(context)
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Não foi possível abrir o pacote ZIP")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                require(files.size < MAX_ENTRIES) { "Pacote excede $MAX_ENTRIES arquivos" }
                val path = safePath(entry.name)
                val extension = path.substringAfterLast('.', "").lowercase()
                require(path == "manifest.json" || extension in setOf("json", "filamat", "cube")) {
                    "Tipo de arquivo não permitido no plugin: $path"
                }
                require(path !in files) { "Entrada ZIP duplicada: $path" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_FILE_BYTES) { "Arquivo ZIP excede 5 MiB: $path" }
                    total = Math.addExact(total, read)
                    require(total <= MAX_TOTAL_BYTES) { "Pacote ZIP excede 8 MiB descompactados" }
                    output.write(buffer, 0, read)
                }
                files[path] = output.toByteArray()
                zip.closeEntry()
            }
        }
        val manifestBytes = files["manifest.json"] ?: throw IllegalArgumentException("manifest.json ausente")
        val manifest = parse(manifestBytes, builtIn = false, enabled = true)
        require(mutable.value.none { it.id == manifest.id && it.builtIn }) { "O ID pertence a um plugin nativo protegido" }
        val root = json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        val requiredFiles = (root["files"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        require(requiredFiles.all { safePath(it) in files }) { "O manifest declara arquivos ausentes" }
        val contextApp = context.applicationContext
        val pluginRoot = File(contextApp.filesDir, "plugins")
        require(pluginRoot.exists() || pluginRoot.mkdirs()) { "Não foi possível criar o diretório de plugins" }
        val target = File(pluginRoot, manifest.id)
        val staging = File(pluginRoot, ".${manifest.id}.tmp")
        staging.deleteRecursively()
        require(staging.mkdirs()) { "Não foi possível preparar a instalação" }
        try {
            files.forEach { (path, bytes) ->
                val output = File(staging, path)
                require(output.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                output.parentFile?.let { require(it.exists() || it.mkdirs()) }
                output.writeBytes(bytes)
            }
            val old = File(pluginRoot, ".${manifest.id}.old")
            old.deleteRecursively()
            if (target.exists()) require(target.renameTo(old)) { "Não foi possível substituir a versão anterior" }
            if (!staging.renameTo(target)) {
                if (old.exists()) old.renameTo(target)
                throw IllegalStateException("Não foi possível finalizar a instalação do plugin")
            }
            old.deleteRecursively()
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
        contextApp.getSharedPreferences("plugins", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled:${manifest.id}", true).apply()
        reload()
        return checkNotNull(mutable.value.firstOrNull { it.id == manifest.id })
    }

    fun remove(context: Context, id: String) {
        initialize(context)
        val plugin = mutable.value.firstOrNull { it.id == id } ?: return
        require(!plugin.builtIn) { "Plugins nativos não podem ser removidos" }
        File(context.applicationContext.filesDir, "plugins/$id").deleteRecursively()
        context.applicationContext.getSharedPreferences("plugins", Context.MODE_PRIVATE)
            .edit().remove("enabled:$id").apply()
        reload()
    }

    fun isEnabled(id: String): Boolean = mutable.value.firstOrNull { it.id == id }?.enabled == true

    fun activeDataSourceEndpoint(): String? = mutable.value.firstOrNull {
        it.enabled && it.kind == PluginKind.DATA_SOURCE
    }?.config?.get("endpoint")?.jsonPrimitive?.contentOrNull

    fun filmAmount(): Float = mutable.value.firstOrNull {
        it.enabled && it.kind == PluginKind.FILM_PROFILE
    }?.config?.get("amount")?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: 0f

    fun audioRootMultiplier(): Float = mutable.value.firstOrNull {
        it.enabled && it.kind == PluginKind.AUDIO_SCALE
    }?.config?.get("rootMultiplier")?.jsonPrimitive?.floatOrNull?.coerceIn(.5f, 2f) ?: 1f

    private fun parse(bytes: ByteArray, builtIn: Boolean, enabled: Boolean): InstalledPlugin {
        require(bytes.size in 1..MAX_FILE_BYTES)
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val id = root["id"]?.jsonPrimitive?.content ?: error("ID ausente")
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{2,63}")))
        val version = root["version"]?.jsonPrimitive?.int ?: error("Versão ausente")
        require(version > 0)
        val kind = PluginKind.valueOf(root["kind"]?.jsonPrimitive?.content ?: error("Tipo ausente"))
        val name = root["name"]?.jsonPrimitive?.content ?: error("Nome ausente")
        val description = root["description"]?.jsonPrimitive?.content ?: ""
        val config = root["config"] as? JsonObject ?: JsonObject(emptyMap())
        validateConfig(kind, config)
        return InstalledPlugin(id, name, version, kind, description, config, sha256(bytes), builtIn, enabled)
    }

    private fun validateConfig(kind: PluginKind, config: JsonObject) {
        fun number(key: String, min: Float, max: Float): Float {
            val value = config[key]?.jsonPrimitive?.floatOrNull ?: error("Configuração $key ausente")
            require(value.isFinite() && value in min..max) { "Configuração $key fora do intervalo" }
            return value
        }
        when (kind) {
            PluginKind.DATA_SOURCE -> {
                val endpoint = config["endpoint"]?.jsonPrimitive?.content ?: error("Endpoint ausente")
                val uri = java.net.URI(endpoint)
                require(uri.scheme == "https" && uri.host in allowedHosts && uri.userInfo == null)
            }
            PluginKind.FILM_PROFILE -> {
                number("amount", 0f, 1f)
                number("grain", 0f, 1f)
                number("halation", 0f, 1f)
            }
            PluginKind.AUDIO_SCALE -> {
                number("rootMultiplier", .5f, 2f)
                val ratios = config["ratios"] as? JsonArray ?: error("Escala musical ausente")
                require(ratios.size in 5..24)
                require(ratios.all { it.jsonPrimitive.floatOrNull?.let { value -> value.isFinite() && value in .25f..4f } == true })
            }
            PluginKind.BIOME_RULE -> {
                number("growthRate", 0f, 2f)
                number("waterDemand", 0f, 2f)
            }
        }
    }

    private fun safePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.contains(':'))
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." })
        require(parts.size <= 4)
        return normalized
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
