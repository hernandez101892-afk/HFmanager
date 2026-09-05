@file:Suppress("MissingPermission")
package com.tuusuario.hfmanager.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import com.tuusuario.hfmanager.data.SettingsManager
import com.tuusuario.hfmanager.data.UploadTracker
import com.tuusuario.hfmanager.network.HfLfsUploader
import com.tuusuario.hfmanager.network.ArchiveUploader

/**
 * Interceptor de red para reintentar de forma autónoma las consultas del Long Polling
 * de Telegram o el borrado de archivos en caso de micro-cortes o fallos de Wi-Fi.
 */
class BotRetryInterceptor(
    private val maxRetries: Int = 5,
    private val baseDelayMillis: Long = 1500
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var lastException: IOException? = null
        var tryCount = 0

        while (tryCount < maxRetries) {
            try {
                if (tryCount > 0) {
                    val sleepTime = baseDelayMillis * tryCount
                    UploadTracker.addLog("BOT_NETWORK [Reintento $tryCount/$maxRetries]: Reintentando conexión con Telegram en ${sleepTime}ms...")
                    Thread.sleep(sleepTime)
                }
                response = chain.proceed(request)
                if (response.isSuccessful) {
                    return response
                } else {
                    val code = response.code
                    if (code == 502 || code == 503 || code == 504) {
                        tryCount++
                        response.close()
                        continue
                    }
                    return response
                }
            } catch (e: IOException) {
                lastException = e
                tryCount++
                UploadTracker.addLog("BOT_ALERT: Micro-corte Wi-Fi en Bot de Telegram (${e.message}). Reintentando...")
            }
        }

        if (response != null) return response
        throw lastException ?: IOException("Error de conexión con Telegram tras $maxRetries intentos.")
    }
}

@SuppressLint("MissingPermission", "NotificationPermission")
class TelegramBotService : Service() {
    private fun extractTmdbId(filename: String): String {
        val nameWithoutExt = filename.substringBeforeLast(".")
        val patternUnderscore = java.util.regex.Pattern.compile("tmdb_(\\d+)_", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcherUnderscore = patternUnderscore.matcher(nameWithoutExt)
        if (matcherUnderscore.find()) {
            return matcherUnderscore.group(1) ?: ""
        }
        val patternSimple = java.util.regex.Pattern.compile("tmdb_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcherSimple = patternSimple.matcher(nameWithoutExt)
        if (matcherSimple.find()) {
            return matcherSimple.group(1) ?: ""
        }
        var cleaned = nameWithoutExt.replace("_", "").replace("/", "")
        val prefixes = listOf("videostmdb", "tmdb")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length)
                break
            }
        }
        if (cleaned.length >= 9) {
            val tmdbPart = cleaned.substring(0, cleaned.length - 8)
            if (tmdbPart.all { it.isDigit() }) {
                return tmdbPart
            }
        }
        val patternFallback = java.util.regex.Pattern.compile("(\\d{5,8})")
        val matcherFallback = patternFallback.matcher(nameWithoutExt)
        if (matcherFallback.find()) {
            return matcherFallback.group(1) ?: ""
        }
        return ""
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var settings: SettingsManager
    private var uploader: HfLfsUploader? = null
    private var archiveUploader: ArchiveUploader? = null

    private var isBotRunning = false
    private var lastUpdateId = 0

    // ⚙️ OkHttpClient para Telegram con tiempos de espera ampliados (120s) y reintentos automatizados
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(BotRetryInterceptor(maxRetries = 5, baseDelayMillis = 1500))
        .build()

    companion object {
        private const val NOTIFICATION_ID = 8812
        private const val CHANNEL_ID = "telegram_bot_service_channel"
        private const val ACTION_STOP_SERVICE = "ACTION_STOP_TELEGRAM_BOT"
        const val ACTION_EXECUTE_COMMAND = "com.tuusuario.hfmanager.ACTION_EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "EXTRA_COMMAND"
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        uploader = null
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            UploadTracker.addLog("SYSTEM: Solicitud de apagado del servicio recibida.")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando controlador asíncrono de Telegram Bot..."))

        if (intent?.action == ACTION_EXECUTE_COMMAND) {
            val commandText = intent.getStringExtra(EXTRA_COMMAND)
            if (commandText != null) {
                val defaultChatId = settings.getTelegramChatId()
                serviceScope.launch {
                    handleTelegramCommand(defaultChatId, commandText)
                }
            }
            return START_STICKY
        }

        if (!isBotRunning) {
            isBotRunning = true
            serviceScope.launch {
                startTelegramPolling()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isBotRunning = false
        serviceJob.cancel()
        UploadTracker.addLog("SYSTEM: Servicio detenido por el usuario o sistema.")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HF Manager Bot Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene el bot de Telegram activo en segundo plano para procesar tus subidas"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TelegramBotService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HF Android Manager Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener Bot", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Loop principal del Long Polling de Telegram Bot
     */

    private fun getUploader(): HfLfsUploader {
        return uploader ?: HfLfsUploader(applicationContext, settings.getHfBackupToken(), settings.getTmdbKey()).also {
            uploader = it
        }
    }

    private fun getArchiveUploader(): ArchiveUploader {
        return archiveUploader ?: ArchiveUploader(applicationContext, settings.getToken(), settings.getTargetRepoType(), settings.getTmdbKey()).also {
            archiveUploader = it
        }
    }

    private suspend fun startTelegramPolling() {
        val botToken = settings.getTelegramToken()
        if (botToken.isEmpty()) {
            updateNotification("Error: Token de Telegram no configurado en los ajustes")
            UploadTracker.addLog("SYSTEM: Error - Token de Telegram vacío en los ajustes")
            isBotRunning = false
            return
        }

        updateNotification("Bot escuchando comandos en Telegram...")
        UploadTracker.addLog("SYSTEM: Conexión establecida. Escuchando comandos en Telegram...")

        sendTelegramMessage(settings.getTelegramChatId(), "🤖 ¡Servicio iniciado nativamente en tu celular Android!\nUsa /help para listar los comandos de control remoto.")

        while (isBotRunning) {
            try {
                val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            parseTelegramUpdates(body)
                        }
                    }
                }
            } catch (e: Exception) {
                UploadTracker.addLog("BOT_NETWORK_WARN: Error en polling de Telegram: ${e.message}")
                delay(5000) // Reintentar tras fallo de red
            }
            delay(1000)
        }
    }

    private suspend fun parseTelegramUpdates(jsonStr: String) = withContext(Dispatchers.Default) {
        val json = JSONObject(jsonStr)
        val ok = json.getBoolean("ok")
        if (!ok) return@withContext

        val result = json.getJSONArray("result")
        for (i in 0 until result.length()) {
            val update = result.getJSONObject(i)
            lastUpdateId = update.getInt("update_id")

            if (update.has("message")) {
                val message = update.getJSONObject("message")
                val chatId = message.getJSONObject("chat").getLong("id").toString()

                // Guardar Chat ID para notificaciones automáticas si no está guardado
                if (settings.getTelegramChatId().isEmpty()) {
                    settings.saveTelegramChatId(chatId)
                }

                if (message.has("text")) {
                    val text = message.getString("text")
                    handleTelegramCommand(chatId, text)
                }
            }
        }
    }

    /**
     * Procesador de Comandos del Controlador Remoto de Telegram
     */
    private fun handleTelegramCommand(chatId: String, text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return
        
        val args = trimmedText.split(Regex("\\s+"))
        val command = if (args[0].lowercase().contains("@")) {
            args[0].lowercase().substringBefore("@")
        } else {
            args[0].lowercase()
        }
        
        UploadTracker.addLog("TELEGRAM: Comando recibido: '$trimmedText' de chat $chatId")

        when (command) {
            "/start", "/help" -> {
                val helpText = """
                    🤗 *Hugging Face Android Manager Bot*
                    Controla remotamente tu celular y tus datasets sin necesidad de una computadora.

                    📌 *Comandos Disponibles:*
                    ⚡ `/status` - Estado del celular (RAM, batería, colas).
                    ⚙️ `/config` - Mostrar repositorio y almacenamiento configurado.
                    📁 `/list` - Listar archivos listos en la carpeta de tu celular.
                    🔄 `/upload <ext> [id_o_busqueda]` - Inteligente: busca local por nombre o ID TMDB, limpia, asocia, renombra físicamente en local y sube.
                    🔄 `/sync <ext>` - Sincronizacion dual: valida existencia en IA y HF, renombra localmente y sube lo faltante a la nube correspondiente.
                    📺 `/m3u` - Genera lista IPTV M3U unificada de IA y HF con posters de TMDB, la guarda en Hugging Face y te da el enlace RAW.
                    🛑 `/cancel` - Cancelar la subida asíncrona que esté activa.
                    📝 `/rename <id_incorrecto> <id_correcto>` - Corrige un ID de película incorrecto remotamente y en local.
                    🔧 `/repair` - Auditoría completa: identifica, renombra local y repara subidas incompletas sin re-subir gigabytes.
                    📦 `/repos` - Listar todos tus repositorios en el Hub.
                    🆕 `/create <tipo> <nombre> [privado: true/false]` - Crea un nuevo repositorio (dataset, model, space) en el Hub.
                    🎯 `/select <tipo> <repo_id>` - Selecciona al instante el repositorio y tipo de sincronización activo en tu celular de forma remota.
                    🗑️ `/delete <archivo>` - Borrar un archivo remoto del Dataset.
                """.trimIndent()
                sendTelegramMessage(chatId, helpText)
                UploadTracker.addLog("TELEGRAM: Respondiendo ayuda /help")
            }

            "/status" -> {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                val totalMem = runtime.maxMemory() / (1024 * 1024)

                val statusText = """
                    ⚡ *Estado del Dispositivo Android:*
                    🔋 CPU & RAM: Celular activo 24/7.
                    🧠 RAM de la App: $usedMem MB de un total de $totalMem MB.
                    📁 Repositorio Destino (HF): `${settings.getHfBackupRepo()}`
                    📁 Ítem Destino (IA): `${settings.getTargetRepo()}`
                """.trimIndent()
                sendTelegramMessage(chatId, statusText)
                UploadTracker.addLog("TELEGRAM: Respondiendo estado /status")
            }

            "/config" -> {
                val configText = """
                    ⚙️ *Configuración de Sincronización:*
                    📍 Repositorio ID (HF): `${settings.getHfBackupRepo()}`
                    🔑 Token HF: `${settings.getHfBackupToken().take(6)}...${settings.getHfBackupToken().takeLast(4)}`
                    📍 Ítem ID (IA): `${settings.getTargetRepo()}`
                    🔑 Token IA: `${settings.getToken().take(6)}...`
                    🌐 API TMDB: `${settings.getTmdbKey().take(6)}...`
                    📂 Ruta Local Android Configurada:
                    `${settings.getTargetFolderUri()}`
                """.trimIndent()
                sendTelegramMessage(chatId, configText)
                UploadTracker.addLog("TELEGRAM: Respondiendo configuración /config")
            }

            "/list" -> {
                val folderUriStr = settings.getTargetFolderUri()
                if (folderUriStr.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ No has configurado ninguna carpeta en la interfaz de la App.")
                    UploadTracker.addLog("TELEGRAM: Intento de /list sin carpeta configurada.")
                    return
                }

                val folderUri = folderUriStr.toUri()
                val documentFile = DocumentFile.fromTreeUri(applicationContext, folderUri)
                if (documentFile == null || !documentFile.exists()) {
                    sendTelegramMessage(chatId, "❌ Carpeta local no accesible o permisos revocados.")
                    UploadTracker.addLog("TELEGRAM: Carpeta local no accesible.")
                    return
                }

                val files = documentFile.listFiles()
                if (files.isEmpty()) {
                    sendTelegramMessage(chatId, "📂 La carpeta está vacía.")
                    UploadTracker.addLog("TELEGRAM: Carpeta escaneada vacía.")
                    return
                }

                val listBuilder = StringBuilder("📂 *Archivos locales en tu celular:*\\n")
                files.take(20).forEach { file ->
                    val sizeMb = file.length() / (1024 * 1024)
                    listBuilder.append("• `${file.name}` (${sizeMb} MB)\\n")
                }
                if (files.size > 20) {
                    listBuilder.append("\\n_...y ${files.size - 20} archivos más._")
                }
                sendTelegramMessage(chatId, listBuilder.toString())
                UploadTracker.addLog("TELEGRAM: Respondiendo /list con ${files.size} archivos escaneados")
            }

            "/upload" -> {
                if (args.size < 2) {
                    sendTelegramMessage(
                        chatId,
                        "⚠️ Modo de uso correcto:\n" +
                                "1. *Búsqueda por Palabra Clave (Recomendado):*\n" +
                                "`/upload <ext> <palabra_clave>` (Ej: `/upload mp4 salvacion` -> Busca archivo con 'salvacion', lo identifica en TMDB y sube)\n" +
                                "2. *Forzar por ID TMDB de Película:*\n" +
                                "`/upload <ext> <id_tmdb>` (Ej: `/upload mp4 687163` -> Obtiene título de TMDB, busca el archivo local correspondiente y sube)\n" +
                                "3. *Automático (Archivo más nuevo):*\n" +
                                "`/upload <ext>` (Ej: `/upload mp4` -> Sube el archivo .mp4 más reciente)"
                    )
                    return
                }
                val extension = args[1]
                // Une el resto de los argumentos en caso de que escriban una palabra clave con espacios (ej: "proyecto salvacion")
                val explicitTmdbIdOrQuery = if (args.size >= 3) args.drop(2).joinToString(" ") else null

                serviceScope.launch {
                    processUploadCommand(chatId, extension, explicitTmdbIdOrQuery)
                }
            }

            "/cancel" -> {
                val state = UploadTracker.currentState.value
                if (state is UploadTracker.UploadState.Uploading || state is UploadTracker.UploadState.Processing) {
                    UploadTracker.triggerCancellation()
                    sendTelegramMessage(chatId, "⏹️ *Señal de cancelación enviada.* Abortando la transmisión física de red de forma asíncrona...")
                } else {
                    sendTelegramMessage(chatId, "ℹ️ No hay ninguna subida activa en curso para cancelar.")
                }
            }

            "/create" -> {
                val token = settings.getHfBackupToken()
                if (token.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ No se ha configurado el Token de Hugging Face.")
                    return
                }
                if (args.size < 3) {
                    sendTelegramMessage(chatId, "⚠️ Modo de uso correcto: `/create <tipo> <nombre> [privado]`\nEjemplo: `/create dataset BOTtelegram true`\nTipos: `dataset`, `model` o `space`.")
                    return
                }
                val type = args[1].lowercase()
                val name = args[2]
                val private = if (args.size >= 4) args[3].toBoolean() else false

                if (type != "dataset" && type != "model" && type != "space") {
                    sendTelegramMessage(chatId, "❌ Tipo inválido. Debe ser `dataset`, `model` o `space`.")
                    return
                }

                sendTelegramMessage(chatId, "🆕 Creando un nuevo $type en Hugging Face: `$name`...")
                serviceScope.launch {
                    try {
                        val url = "https://huggingface.co/api/repos/create"
                        val jsonPayload = JSONObject().apply {
                            put("name", name)
                            put("type", type)
                            put("private", private)
                        }
                        val body = jsonPayload.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $token")
                            .post(body)
                            .build()

                        client.newCall(request).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                sendTelegramMessage(chatId, "❌ Error de conexión al crear repositorio: ${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val bodyStr = response.body?.string() ?: "{}"
                                if (response.isSuccessful) {
                                    val jsonRes = JSONObject(bodyStr)
                                    val fullId = jsonRes.optString("id", name)
                                    val repoUrl = jsonRes.optString("url", "")
                                    sendTelegramMessage(chatId, "✅ *¡Repositorio Creado con Éxito!*\n📍 ID: `$fullId`\n📂 Tipo: `$type`\n🔒 Privado: `$private`\n🌐 URL: $repoUrl")
                                    UploadTracker.addLog("SUCCESS: Creado repositorio remoto '$fullId' ($type)")
                                } else {
                                    sendTelegramMessage(chatId, "❌ Error al crear repositorio (${response.code}): $bodyStr")
                                }
                                response.close()
                            }
                        })
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Excepción al crear repositorio: ${e.message}")
                    }
                }
            }

            "/select" -> {
                if (args.size < 3) {
                    sendTelegramMessage(chatId, "⚠️ Modo de uso correcto: `/select <tipo> <user/repo_id>`\nEjemplo: `/select dataset Hachetv/BOTtelegram`\nTipos: `dataset` o `model`.")
                    return
                }
                val type = args[1].lowercase()
                val repoId = args[2]

                if (type != "dataset" && type != "model") {
                    sendTelegramMessage(chatId, "❌ Tipo de repositorio inválido en celular. Debe ser `dataset` o `model`.")
                    return
                }

                // 🛡️ CORRECCIÓN DE ARQUITECTURA HÍBRIDA:
                // El comando /select remoto configura la subida a Hugging Face sin corromper
                // las credenciales locales guardadas para Internet Archive (TARGET_REPO / TARGET_REPO_TYPE)
                settings.saveHfBackupRepo(repoId)
                uploader = null // Forzar re-creación con la nueva configuración

                val successMsg = """
                    🎯 *¡Sincronización de Hugging Face reconfigurada de forma remota!*
                    📍 Repositorio de Backup Activo (HF): `${settings.getHfBackupRepo()}`
                    📦 Tipo: `dataset (LFS)`
                    
                    💡 Puedes mandar el comando `/repair` para auditar esta nueva carpeta de forma remota.
                """.trimIndent()
                sendTelegramMessage(chatId, successMsg)
                UploadTracker.addLog("TELEGRAM: Repositorio activo de Hugging Face cambiado a '$repoId'")
            }

            "/repos" -> {
                val token = settings.getHfBackupToken()
                if (token.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ No se ha configurado el Token de Hugging Face.")
                    return
                }

                sendTelegramMessage(chatId, "🔍 Consultando tus repositorios de Hugging Face...")
                serviceScope.launch {
                    try {
                        val url = "https://huggingface.co/api/settings/repositories"
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $token")
                            .build()

                        client.newCall(request).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                sendTelegramMessage(chatId, "❌ Error al conectar con Hugging Face: ${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val bodyStr = response.body?.string() ?: "[]"
                                if (!response.isSuccessful) {
                                    sendTelegramMessage(chatId, "❌ Código de error del Hub: ${response.code}")
                                    return
                                }
                                val jsonArr = JSONArray(bodyStr)
                                val repoList = StringBuilder("📦 *Tus Repositorios en Hugging Face:*\\n")
                                val maxToShow = minOf(jsonArr.length(), 15)
                                for (i in 0 until maxToShow) {
                                    val repo = jsonArr.getJSONObject(i)
                                    val id = repo.getString("id")
                                    val type = repo.optString("type", "model")
                                    val private = repo.optBoolean("private", false)
                                    val privateIcon = if (private) "🔒" else "🌐"
                                    repoList.append("• `$id` ($type) $privateIcon\\n")
                                }
                                sendTelegramMessage(chatId, repoList.toString())
                                response.close()
                            }
                        })
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Error al obtener repositorios: ${e.message}")
                    }
                }
            }

            "/delete" -> {
                if (args.size < 2) {
                    sendTelegramMessage(chatId, "⚠️ Modo de uso correcto: `/delete <ruta_del_archivo_remoto>`\\nEjemplo: `/delete data/train.csv`")
                    return
                }
                val pathInRepo = args[1]
                val repoId = settings.getHfBackupRepo()
                val repoType = "dataset"
                val token = settings.getHfBackupToken()

                if (repoId.isEmpty() || token.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ Configura primero el Dataset y el Token en los ajustes de la app.")
                    return
                }

                sendTelegramMessage(chatId, "🗑️ Solicitando borrado del archivo `$pathInRepo` en `$repoId`...")

                serviceScope.launch {
                    try {
                        val typePath = if (repoType.lowercase() == "dataset") "datasets" else "models"
                        val url = "https://huggingface.co/api/$typePath/$repoId/commit/main"

                        val headerJson = JSONObject().apply {
                            put("key", "header")
                            put("value", JSONObject().apply {
                                put("summary", "Eliminado de forma remota via Telegram Bot: $pathInRepo")
                            })
                        }
                        val delOp = JSONObject().apply {
                            put("key", "deletedFile")
                            put("value", JSONObject().apply {
                                put("path", pathInRepo)
                            })
                        }
                        val ndjsonBody = headerJson.toString() + "\n" + delOp.toString() + "\n"
                        val ndjsonBytes = ndjsonBody.toByteArray(Charsets.UTF_8)

                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $token")
                            .addHeader("Content-Type", "application/x-ndjson")
                            .post(ndjsonBytes.toRequestBody("application/x-ndjson".toMediaType()))
                            .build()

                        client.newCall(request).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                sendTelegramMessage(chatId, "❌ Conexión fallida: ${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                if (response.isSuccessful) {
                                    sendTelegramMessage(chatId, "✅ Archivo `$pathInRepo` eliminado del Hub con éxito.")
                                } else {
                                    sendTelegramMessage(chatId, "❌ Error al eliminar archivo (${response.code}): ${response.body?.string()}")
                                }
                                response.close()
                            }
                        })
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Excepción durante borrado: ${e.message}")
                    }
                }
            }

            "/rename" -> {
                if (args.size < 3) {
                    sendTelegramMessage(
                        chatId,
                        "⚠️ Modo de uso correcto: `/rename <id_incorrecto> <id_correcto>`\n" +
                                "Ejemplo: `/rename 687163 1368337` -> Corrige el ID de la película tanto en Hugging Face como en tu celular local."
                    )
                    return
                }
                val idIncorrecto = args[1]
                val idCorrecto = args[2]
                val repoId = settings.getHfBackupRepo()
                val repoType = "dataset"
                val token = settings.getHfBackupToken()

                if (repoId.isEmpty() || token.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ Configura primero el Dataset y el Token en los ajustes de la app.")
                    return
                }

                sendTelegramMessage(chatId, "🔍 Buscando archivo con ID incorrecto `$idIncorrecto` en tu dataset de Hugging Face...")
                UploadTracker.addLog("TELEGRAM: Iniciando proceso de renombrado remoto de $idIncorrecto a $idCorrecto")

                serviceScope.launch {
                    try {
                        val typePath = if (repoType.lowercase() == "dataset") "datasets" else "models"
                        // 1. Consultar el árbol de archivos para encontrar el nombre completo del archivo incorrecto
                        val treeUrl = "https://huggingface.co/api/$typePath/$repoId/tree/main/videos"
                        val treeRequest = Request.Builder()
                            .url(treeUrl)
                            .addHeader("Authorization", "Bearer $token")
                            .build()

                        client.newCall(treeRequest).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                sendTelegramMessage(chatId, "❌ Fallo al consultar Hugging Face: ${e.message}")
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val bodyStr = response.body?.string() ?: "[]"
                                if (!response.isSuccessful) {
                                    sendTelegramMessage(chatId, "❌ Error del servidor HF (${response.code}) al listar archivos.")
                                    return
                                }
                                val filesArray = JSONArray(bodyStr)
                                val items = mutableListOf<JSONObject>()
                                for (j in 0 until filesArray.length()) {
                                    items.add(filesArray.getJSONObject(j))
                                }
                                val foundFile = items.find { it.getString("path").contains("tmdb_${idIncorrecto}_") }

                                if (foundFile == null) {
                                    sendTelegramMessage(chatId, "❌ No encontré ningún archivo en Hugging Face que corresponda al ID incorrecto `$idIncorrecto`.")
                                    return
                                }

                                val pathInRepo = foundFile.getString("path")
                                val lfs = foundFile.optJSONObject("lfs")
                                if (lfs == null) {
                                    sendTelegramMessage(chatId, "❌ El archivo `$pathInRepo` no es de tipo Git LFS. No se puede renombrar de forma segura.")
                                    return
                                }

                                val sha256 = lfs.getString("sha256")
                                val size = lfs.getLong("size")
                                val newPath = pathInRepo.replace("tmdb_${idIncorrecto}_", "tmdb_${idCorrecto}_")

                                sendTelegramMessage(chatId, "🔄 Encontré película: `$pathInRepo`.\n" +
                                        "Aplicando renombrado seguro en Hugging Face a:\n" +
                                        "`$newPath`...")

                                // 2. Realizar Commit con operación 'add' LFS + 'del' del archivo anterior
                                val commitUrl = "https://huggingface.co/api/$typePath/$repoId/commit/main"

                                val headerJson = JSONObject().apply {
                                    put("key", "header")
                                    put("value", JSONObject().apply {
                                        put("summary", "Renombrado automatico via Telegram Bot: de $idIncorrecto a $idCorrecto")
                                        put("description", "Correccion de ID de TMDB incorrecto de forma remota sin transferencia fisica")
                                    })
                                }
                                val addOp = JSONObject().apply {
                                    put("key", "lfsFile")
                                    put("value", JSONObject().apply {
                                        put("path", newPath)
                                        put("algo", "sha256")
                                        put("oid", sha256)
                                        put("size", size)
                                    })
                                }
                                val delOp = JSONObject().apply {
                                    put("key", "deletedFile")
                                    put("value", JSONObject().apply {
                                        put("path", pathInRepo)
                                    })
                                }
                                val ndjsonBody = headerJson.toString() + "\n" + addOp.toString() + "\n" + delOp.toString() + "\n"
                                val ndjsonBytes = ndjsonBody.toByteArray(Charsets.UTF_8)

                                val commitRequest = Request.Builder()
                                    .url(commitUrl)
                                    .addHeader("Authorization", "Bearer $token")
                                    .addHeader("Content-Type", "application/x-ndjson")
                                    .post(ndjsonBytes.toRequestBody("application/x-ndjson".toMediaType()))
                                    .build()

                                client.newCall(commitRequest).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: IOException) {
                                        sendTelegramMessage(chatId, "❌ Conexión fallida al realizar commit: ${e.message}")
                                    }

                                    override fun onResponse(call: Call, response: Response) {
                                        if (response.isSuccessful) {
                                            sendTelegramMessage(chatId, "✅ *¡Renombrado Exitoso en Hugging Face!*\n" +
                                                    "• Viejo: `$pathInRepo` 🗑️\n" +
                                                    "• Nuevo: `$newPath` ✨")
                                            UploadTracker.addLog("SUCCESS: Archivo renombrado remotamente de '$pathInRepo' a '$newPath'")

                                            // 3. Renombrado Local Físico opcional en el celular si el archivo está guardado localmente
                                            val folderUriStrLocal = settings.getTargetFolderUri()
                                            if (folderUriStrLocal.isNotEmpty()) {
                                                val folderUriLocal = folderUriStrLocal.toUri()
                                                val documentFileLocal = DocumentFile.fromTreeUri(applicationContext, folderUriLocal)
                                                if (documentFileLocal != null && documentFileLocal.exists()) {
                                                    val localTargetFile = documentFileLocal.listFiles()
                                                        .firstOrNull { it.name?.contains("tmdb_${idIncorrecto}_") == true }
                                                    if (localTargetFile != null) {
                                                        val oldLocalName = localTargetFile.name ?: ""
                                                        val newLocalName = oldLocalName.replace("tmdb_${idIncorrecto}_", "tmdb_${idCorrecto}_")
                                                        if (localTargetFile.renameTo(newLocalName)) {
                                                            sendTelegramMessage(chatId, "📱 *¡Sincronización Local Completa!*\n" +
                                                                    "También renombré físicamente el archivo de tu celular a:\n" +
                                                                    "`$newLocalName` ✓")
                                                            UploadTracker.addLog("PROCESS: Archivo local renombrado físicamente de '$oldLocalName' a '$newLocalName'")
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            sendTelegramMessage(chatId, "❌ Error al confirmar renombrado (${response.code}): ${response.body?.string()}")
                                        }
                                        response.close()
                                    }
                                })
                            }
                        })
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Excepción durante proceso de renombrado: ${e.message}")
                    }
                }
            }

            "/repair", "/reparar" -> {
                val folderUriStr = settings.getTargetFolderUri()
                val repoId = settings.getHfBackupRepo()
                val repoType = "dataset"
                val token = settings.getHfBackupToken()

                if (folderUriStr.isEmpty() || repoId.isEmpty() || token.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ Configura primero el Dataset, el Token y la Carpeta en la app.")
                    return
                }

                sendTelegramMessage(chatId, "🔍 *Iniciando Auditoría y Sincronización Automática...*\nEscaneando tus archivos locales y consultando Hugging Face en segundo plano...")
                UploadTracker.addLog("TELEGRAM: Comando /repair recibido. Iniciando auditoría.")

                serviceScope.launch {
                    try {
                        val folderUri = folderUriStr.toUri()
                        val documentFile = DocumentFile.fromTreeUri(applicationContext, folderUri)
                        if (documentFile == null || !documentFile.exists()) {
                            sendTelegramMessage(chatId, "❌ Error de Scoped Storage: No se pudo leer la carpeta local.")
                            return@launch
                        }

                        // 1. Obtener archivos locales (.mp4 y .mkv)
                        val localFiles = documentFile.listFiles()
                            .filter { it.name?.endsWith("mp4", ignoreCase = true) == true || it.name?.endsWith("mkv", ignoreCase = true) == true }

                        if (localFiles.isEmpty()) {
                            sendTelegramMessage(chatId, "📂 Tu carpeta local está vacía o no tiene archivos de video (.mp4/.mkv).")
                            return@launch
                        }

                        // 2. Obtener lista remota de Hugging Face
                        val typePath = if (repoType.lowercase() == "dataset") "datasets" else "models"
                        val treeUrl = "https://huggingface.co/api/$typePath/$repoId/tree/main/videos"
                        val treeRequest = Request.Builder()
                            .url(treeUrl)
                            .addHeader("Authorization", "Bearer $token")
                            .build()

                        var remoteFilesList = listOf<String>()
                        try {
                            client.newCall(treeRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: "[]"
                                    val filesArray = JSONArray(bodyStr)
                                    val list = mutableListOf<String>()
                                    for (j in 0 until filesArray.length()) {
                                        list.add(filesArray.getJSONObject(j).getString("path"))
                                    }
                                    remoteFilesList = list
                                } else if (response.code == 404) {
                                    UploadTracker.addLog("PROCESS: La carpeta /videos no existe aún en Hugging Face (repositorio nuevo o vacío). Se considerará que todos los archivos locales son nuevos.")
                                } else {
                                    UploadTracker.addLog("PROCESS_WARN: Hugging Face retornó código ${response.code} al listar archivos remotos.")
                                }
                            }
                        } catch (e: Exception) {
                            UploadTracker.addLog("PROCESS_WARN: No se pudo listar videos remotos: ${e.message}")
                        }

                        var totalChecked = 0
                        var alreadySynced = 0
                        var repairedCount = 0
                        var errors = 0

                        val summaryList = StringBuilder("📊 *Resultados detallados:*\n")

                        for (file in localFiles) {
                            if (UploadTracker.isCancelled.value) {
                                sendTelegramMessage(chatId, "🛑 Reparación cancelada por el usuario.")
                                return@launch
                            }

                            val oldName = file.name ?: ""
                            totalChecked++

                            try {
                                val extension = oldName.substringAfterLast(".", "mp4")

                                // Usar extractor inteligente multidiseño
                                var tmdbId = extractTmdbId(oldName)
                                if (tmdbId.isEmpty()) {
                                    // Es un archivo raw. Lo limpiamos y buscamos en TMDB
                                    val cleanedName = getUploader().cleanMovieName(oldName)
                                    try {
                                        tmdbId = getUploader().fetchMovieIdFromTmdb(cleanedName)
                                    } catch (e: Exception) {
                                        UploadTracker.addLog("PROCESS_WARN: No se pudo identificar '$cleanedName' en TMDB: ${e.message}")
                                    }
                                }

                                if (tmdbId.isEmpty()) {
                                    summaryList.append("• ❌ `$oldName`: No identificado en TMDB\n")
                                    errors++
                                    continue
                                }

                                // Calcular metadatos
                                val metadata = getUploader().calculateMetadata(file.uri, tmdbId, extension)

                                // Si el archivo local no tenía el nombre correcto, renombrarlo físicamente
                                val localNewName = metadata.newName.substringAfterLast("/")
                                if (oldName != localNewName) {
                                    file.renameTo(localNewName)
                                    UploadTracker.addLog("PROCESS: Renombrado físico local: '$oldName' -> '$localNewName'")
                                }

                                // Verificar si ya existe en Hugging Face (metadata.newName ya contiene "videos/")
                                val remoteExpectedPath = metadata.newName
                                if (remoteFilesList.contains(remoteExpectedPath)) {
                                    alreadySynced++
                                    UploadTracker.addLog("PROCESS: '${metadata.newName}' ya existe en Hugging Face. Omitido.")
                                } else {
                                    // ¡Es un archivo fantasma o nuevo! Lo subimos / confirmamos commit
                                    UploadTracker.addLog("PROCESS: Reparando '${metadata.newName}'. Creando commit remoto...")
                                    val commitResult = getUploader().uploadLfsFile(
                                        repoId = repoId,
                                        repoType = repoType,
                                        uri = file.uri,
                                        metadata = metadata
                                    )
                                    repairedCount++
                                    UploadTracker.addLog("SUCCESS: Archivo reparado: ${metadata.newName} ($commitResult)")
                                    summaryList.append("• 🛠️ `${metadata.newName.substringAfterLast("/")}`: $commitResult\n")
                                }
                            } catch (e: Exception) {
                                errors++
                                summaryList.append("• ⚠️ `$oldName`: ${e.message}\n")
                                UploadTracker.addLog("PROCESS_ERROR: Fallo al auditar `$oldName`: ${e.message}")
                            }
                        }

                        val finalReport = """
                            ✅ *¡Auditoría y Reparación Completada!*
                            
                            📈 *Estadísticas de tu catálogo:*
                            • Total de archivos locales: $totalChecked
                            • Sincronizados previamente: $alreadySynced
                            • Reparados (Commit enviado en 2s): $repairedCount
                            • Errores o no identificados: $errors
                            
                            ${if (errors > 0 || repairedCount > 0) summaryList.toString() else "_¡Tus películas están perfectamente sincronizadas e indexadas en Hugging Face! ✓_"}
                        """.trimIndent()

                        sendTelegramMessage(chatId, finalReport)
                        UploadTracker.updateState(UploadTracker.UploadState.Idle)
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Error crítico durante la reparación: ${e.message}")
                        UploadTracker.updateState(UploadTracker.UploadState.Idle)
                    }
                }
            }

            "/sync" -> {
                val folderUriStr = settings.getTargetFolderUri()
                val hfRepoId = settings.getHfBackupRepo()
                val iaItemId = settings.getTargetRepo()
                val hfToken = settings.getHfBackupToken()
                val hfRepoType = "dataset"

                if (folderUriStr.isEmpty() || hfRepoId.isEmpty() || hfToken.isEmpty() || iaItemId.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ Configura primero tu Dataset, Token, Carpeta e Item de Internet Archive.")
                    return
                }

                if (args.size < 2) {
                    sendTelegramMessage(chatId, "⚠️ Modo de uso correcto: `/sync <ext>` (Ej: `/sync mp4`)")
                    return
                }
                val ext = args[1]

                sendTelegramMessage(chatId, "🔍 *Iniciando Sincronizacion Dual (IA + HF)...*\nEscaneando archivos locales y consultando repositorios remotos...")
                UploadTracker.addLog("TELEGRAM: Comando /sync recibido.")

                serviceScope.launch {
                    try {
                        val folderUri = folderUriStr.toUri()
                        val documentFile = DocumentFile.fromTreeUri(applicationContext, folderUri)
                        if (documentFile == null || !documentFile.exists()) {
                            sendTelegramMessage(chatId, "❌ Error de Scoped Storage: No se pudo leer la carpeta local.")
                            return@launch
                        }

                        // 1. Obtener archivos locales con la extension indicada
                        val localFiles = documentFile.listFiles()
                            .filter { it.name?.endsWith(ext, ignoreCase = true) == true }

                        if (localFiles.isEmpty()) {
                            sendTelegramMessage(chatId, "📂 Tu carpeta local no tiene archivos .$ext.")
                            return@launch
                        }

                        // 2. Obtener lista remota de Internet Archive
                        sendTelegramMessage(chatId, "⏳ Consultando catalogo de Internet Archive...")
                        val iaFilesList = getArchiveUploader().listArchiveItemFiles(iaItemId)

                        // 3. Obtener lista remota de Hugging Face
                        sendTelegramMessage(chatId, "⏳ Consultando catalogo de Hugging Face...")
                        val typePath = if (hfRepoType.lowercase() == "dataset") "datasets" else "models"
                        val treeUrl = "https://huggingface.co/api/$typePath/$hfRepoId/tree/main/videos"
                        val treeRequest = Request.Builder()
                            .url(treeUrl)
                            .addHeader("Authorization", "Bearer $hfToken")
                            .build()

                        var remoteHfFilesList = listOf<String>()
                        try {
                            client.newCall(treeRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: "[]"
                                    val filesArray = JSONArray(bodyStr)
                                    val list = mutableListOf<String>()
                                    for (j in 0 until filesArray.length()) {
                                        list.add(filesArray.getJSONObject(j).getString("path"))
                                    }
                                    remoteHfFilesList = list
                                }
                            }
                        } catch (e: Exception) {
                            UploadTracker.addLog("PROCESS_WARN: No se pudo listar videos remotos de HF: ${e.message}")
                        }

                        var totalChecked = 0
                        var renamedCount = 0
                        var uploadedToIa = 0
                        var uploadedToHf = 0
                        var skippedCount = 0
                        var errors = 0
                        val summaryList = StringBuilder("📊 *Resultados detallados de Sincronizacion:*\\n")

                        for (file in localFiles) {
                            if (UploadTracker.isCancelled.value) {
                                sendTelegramMessage(chatId, "🛑 Sincronizacion cancelada por el usuario.")
                                return@launch
                            }

                            val oldName = file.name ?: ""
                            totalChecked++

                            try {
                                var tmdbId = extractTmdbId(oldName)
                                if (tmdbId.isEmpty()) {
                                    val cleanedName = getArchiveUploader().cleanMovieName(oldName)
                                    try {
                                        tmdbId = getArchiveUploader().fetchMovieIdFromTmdb(cleanedName)
                                    } catch (_: Exception) {}
                                }

                                if (tmdbId.isEmpty()) {
                                    summaryList.append("• ❌ `$oldName`: No identificado en TMDB\\n")
                                    errors++
                                    continue
                                }

                                // Calcular metadatos para ambas plataformas
                                val iaMetadata = getArchiveUploader().calculateMetadata(file.uri, tmdbId, ext)
                                val hfMetadata = getUploader().calculateMetadata(file.uri, tmdbId, ext)

                                // Renombrado local fisico si es necesario
                                val localNewName = hfMetadata.newName.substringAfterLast("/")
                                val finalFile = if (oldName != localNewName) {
                                    if (file.renameTo(localNewName)) {
                                        renamedCount++
                                        UploadTracker.addLog("PROCESS: Renombrado local: '$oldName' -> '$localNewName'")
                                        val found = documentFile.findFile(localNewName)
                                        found ?: file
                                    } else {
                                        file
                                    }
                                } else {
                                    file
                                }

                                val existsInIa = iaFilesList.contains(iaMetadata.newName)
                                val existsInHf = remoteHfFilesList.contains(hfMetadata.newName)

                                if (existsInIa && existsInHf) {
                                    skippedCount++
                                    continue
                                }

                                if (!existsInIa) {
                                    UploadTracker.addLog("PROCESS: Subiendo '${iaMetadata.newName}' a Internet Archive...")
                                    getArchiveUploader().uploadFile(iaItemId, finalFile.uri, iaMetadata)
                                    uploadedToIa++
                                    summaryList.append("• 🌐 `${iaMetadata.newName}`: Subido a IA\\n")
                                }

                                if (!existsInHf) {
                                    UploadTracker.addLog("PROCESS: Sincronizando respaldo '${hfMetadata.newName}' a Hugging Face...")
                                    getUploader().uploadLfsFile(hfRepoId, hfRepoType, finalFile.uri, hfMetadata)
                                    uploadedToHf++
                                    summaryList.append("• 🤗 `${hfMetadata.newName}`: Respaldado en HF\\n")
                                }

                            } catch (e: Exception) {
                                errors++
                                summaryList.append("• ⚠️ `$oldName`: ${e.message}\\n")
                                UploadTracker.addLog("PROCESS_ERROR: Fallo al sincronizar `$oldName`: ${e.message}")
                            }
                        }

                        val finalReport = """
                            ✅ *¡Sincronizacion Dual Completada!*
                            
                            📈 *Estadisticas:*
                            • Total procesados: $totalChecked
                            • Renombrados localmente: $renamedCount
                            • Subidos a Internet Archive: $uploadedToIa
                            • Respaldados en Hugging Face: $uploadedToHf
                            • Omitidos (ya sincronizados): $skippedCount
                            • Errores: $errors
                            
                            ${if (errors > 0 || uploadedToIa > 0 || uploadedToHf > 0) summaryList.toString() else "_¡Tu catalogo local esta perfectamente sincronizado en ambas nubes! ✓_"}
                        """.trimIndent()

                        sendTelegramMessage(chatId, finalReport)
                        UploadTracker.updateState(UploadTracker.UploadState.Idle)
                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Error critico en sincronizacion dual: ${e.message}")
                        UploadTracker.updateState(UploadTracker.UploadState.Idle)
                    }
                }
            }

            "/m3u" -> {
                val hfRepoId = settings.getHfBackupRepo()
                val hfToken = settings.getHfBackupToken()
                val iaItemId = settings.getTargetRepo()
                val hfRepoType = "dataset"
                val tmdbApiKey = settings.getTmdbKey()

                if (hfRepoId.isEmpty() || hfToken.isEmpty() || iaItemId.isEmpty()) {
                    sendTelegramMessage(chatId, "❌ Configura primero tus repositorios de Internet Archive y Hugging Face.")
                    return
                }

                sendTelegramMessage(chatId, "📝 *Generando lista de reproduccion IPTV M3U unificada...*\nEsto consultara Internet Archive y Hugging Face para armar tu catalogo con logos y detalles desde TMDB...")
                UploadTracker.addLog("TELEGRAM: Comando /m3u recibido.")

                serviceScope.launch {
                    try {
                        // 1. Obtener lista de Internet Archive
                        val iaFiles = getArchiveUploader().listArchiveItemFiles(iaItemId)
                            .filter { it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".mkv", ignoreCase = true) }

                        // 2. Obtener lista de Hugging Face
                        val typePath = if (hfRepoType.lowercase() == "dataset") "datasets" else "models"
                        val treeUrl = "https://huggingface.co/api/$typePath/$hfRepoId/tree/main/videos"
                        val treeRequest = Request.Builder()
                            .url(treeUrl)
                            .addHeader("Authorization", "Bearer $hfToken")
                            .build()

                        var hfFiles = listOf<String>()
                        try {
                            client.newCall(treeRequest).execute().use { response ->
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: "[]"
                                    val filesArray = JSONArray(bodyStr)
                                    val list = mutableListOf<String>()
                                    for (j in 0 until filesArray.length()) {
                                        list.add(filesArray.getJSONObject(j).getString("path"))
                                    }
                                    hfFiles = list
                                }
                            }
                        } catch (e: Exception) {
                            UploadTracker.addLog("PROCESS_WARN: No se pudieron obtener archivos de Hugging Face: ${e.message}")
                        }

                        if (iaFiles.isEmpty() && hfFiles.isEmpty()) {
                            sendTelegramMessage(chatId, "⚠️ No se encontraron archivos de video en ninguna de tus nubes.")
                            return@launch
                        }

                        // 3. Identificar todas las peliculas unicas por TMDB ID
                        val uniqueMovies = mutableMapOf<String, MovieEntry>()
                        val movieRegexIa = java.util.regex.Pattern.compile("\\[tmdb-(\\d+)]")
                        val movieRegexHf = java.util.regex.Pattern.compile("tmdb_(\\d+)_")

                        // Procesar archivos de Internet Archive
                        for (file in iaFiles) {
                            val matcher = movieRegexIa.matcher(file)
                            if (matcher.find()) {
                                val tmdbId = matcher.group(1) ?: ""
                                val entry = uniqueMovies.getOrPut(tmdbId) { MovieEntry(tmdbId) }
                                entry.iaFile = file
                            }
                        }

                        // Procesar archivos de Hugging Face
                        for (file in hfFiles) {
                            val filename = file.substringAfterLast("/")
                            val matcher = movieRegexHf.matcher(filename)
                            if (matcher.find()) {
                                val tmdbId = matcher.group(1) ?: ""
                                val entry = uniqueMovies.getOrPut(tmdbId) { MovieEntry(tmdbId) }
                                entry.hfPath = file
                            }
                        }                        // Fallback: Escanear la carpeta local en segundo plano para mapear IDs a nombres reales locales
                        val localMoviesMap = mutableMapOf<String, String>()
                        val localFolderUriStr = settings.getTargetFolderUri()
                        if (localFolderUriStr.isNotEmpty()) {
                            try {
                                val folderUri = localFolderUriStr.toUri()
                                val documentFile = DocumentFile.fromTreeUri(applicationContext, folderUri)
                                if (documentFile != null && documentFile.exists()) {
                                    for (file in documentFile.listFiles()) {
                                        val name = file.name ?: ""
                                        val tid = extractTmdbId(name)
                                        if (tid.isNotEmpty()) {
                                            val nameWithoutExt = name.substringBeforeLast(".")
                                            val baseName = nameWithoutExt.substringBefore(" [tmdb-").trim()
                                            val cleanTitle = if (baseName.endsWith(")") && baseName.contains(" (")) {
                                                baseName.substringBeforeLast(" (").trim()
                                            } else {
                                                baseName
                                            }
                                            localMoviesMap[tid] = cleanTitle
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                UploadTracker.addLog("PROCESS_WARN: Error al mapear titulos locales: ${e.message}")
                            }
                        }

                        // 4. Consultar TMDB para cada pelicula unica y armar el M3U
                        val m3uContent = StringBuilder("#EXTM3U\n")
                        var resolvedCount = 0

                        for ((tmdbId, entry) in uniqueMovies) {
                            var movieTitle = "Pelicula $tmdbId"
                            var year = ""
                            var posterUrl = ""
                            var overview = "Sin descripcion disponible."
                            var genreGroup = "Peliculas"
                            var ratingStr = ""
                            var director = ""
                            val castList = mutableListOf<String>()

                            // 1. Extraer nombre real y año de estreno directamente desde el archivo de Internet Archive (si existe)
                            if (entry.iaFile != null) {
                                try {
                                    val nameWithoutExt = entry.iaFile!!.substringBeforeLast(".")
                                    val baseName = nameWithoutExt.substringBefore(" [tmdb-").trim()
                                    if (baseName.endsWith(")") && baseName.contains(" (")) {
                                        movieTitle = baseName.substringBeforeLast(" (").trim()
                                        year = " (" + baseName.substringAfterLast(" (").substringBefore(")").trim() + ")"
                                    } else {
                                        movieTitle = baseName
                                    }
                                } catch (e: Exception) {
                                    UploadTracker.addLog("PROCESS_WARN: No se pudo extraer titulo de archivo IA: ${e.message}")
                                }
                            }

                            // 2. Si hay API Key de TMDB, consultamos en vivo para obtener poster, sinopsis, generos, reparto y puntuacion
                            if (tmdbApiKey.isNotEmpty()) {
                                try {
                                    val req = Request.Builder()
                                        .url("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=es-MX&append_to_response=credits")
                                        .build()
                                    client.newCall(req).execute().use { tRes ->
                                        if (tRes.isSuccessful) {
                                            val tBody = tRes.body?.string() ?: ""
                                            val tJson = JSONObject(tBody)
                                            val tmdbTitle = tJson.optString("title", "")
                                            if (tmdbTitle.isNotEmpty()) {
                                                movieTitle = tmdbTitle
                                            }
                                            val releaseDate = tJson.optString("release_date", "")
                                            if (releaseDate.length >= 4) {
                                                year = " (" + releaseDate.take(4) + ")"
                                            }
                                            val posterPath = tJson.optString("poster_path", "")
                                            if (posterPath.isNotEmpty() && posterPath != "null") {
                                                posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                                            }
                                            overview = tJson.optString("overview", "Sin descripcion disponible.")

                                            // Parsear generos (hasta 2) para el group-title de IPTV
                                            val genresList = mutableListOf<String>()
                                            val genresArr = tJson.optJSONArray("genres")
                                            if (genresArr != null) {
                                                for (g in 0 until minOf(genresArr.length(), 2)) {
                                                    genresList.add(genresArr.getJSONObject(g).optString("name", ""))
                                                }
                                            }
                                            if (genresList.isNotEmpty()) {
                                                genreGroup = genresList.joinToString(" / ")
                                            }

                                            // Parsear puntuacion (Rating)
                                            val rating = tJson.optDouble("vote_average", 0.0)
                                            val voteCount = tJson.optInt("vote_count", 0)
                                            if (rating > 0.0) {
                                                ratingStr = "⭐ %.1f/10 (%d votos)".format(Locale.US, rating, voteCount)
                                            }

                                            // Parsear credits (director y reparto)
                                            val creditsObj = tJson.optJSONObject("credits")
                                            if (creditsObj != null) {
                                                val castArr = creditsObj.optJSONArray("cast")
                                                if (castArr != null) {
                                                    for (c in 0 until minOf(castArr.length(), 3)) {
                                                        castList.add(castArr.getJSONObject(c).optString("name", ""))
                                                    }
                                                }
                                                val crewArr = creditsObj.optJSONArray("crew")
                                                if (crewArr != null) {
                                                    for (cr in 0 until crewArr.length()) {
                                                        val crewItem = crewArr.getJSONObject(cr)
                                                        if (crewItem.optString("job", "") == "Director") {
                                                            director = crewItem.optString("name", "")
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    UploadTracker.addLog("PROCESS_WARN: Error consultando TMDB para M3U de ID $tmdbId: ${e.message}")
                                }
                            }

                            // 3. Aplicar fallback local si el titulo sigue siendo generico y existe localmente
                            if (movieTitle.startsWith("Pelicula ") && localMoviesMap.containsKey(tmdbId)) {
                                val fallbackTitle = localMoviesMap[tmdbId]
                                if (fallbackTitle != null) {
                                    movieTitle = fallbackTitle
                                }
                            }

                            // Sanitizar comillas dobles y saltos de linea para no romper el parser del reproductor IPTV
                            val cleanMovieTitle = movieTitle.replace("\"", "'").trim()
                            val cleanOverviewBody = overview.replace("\"", "'").replace("\n", " ").replace("\r", " ").trim()

                            // Construir descripcion detallada para la ficha tecnica de la app IPTV
                            val technicalDetails = StringBuilder()
                            if (ratingStr.isNotEmpty()) {
                                technicalDetails.append("[$ratingStr] ")
                            }
                            if (director.isNotEmpty()) {
                                technicalDetails.append("Dir: $director | ")
                            }
                            if (castList.isNotEmpty()) {
                                technicalDetails.append("Reparto: ${castList.joinToString(", ")} | ")
                            }
                            val cleanOverview = "$technicalDetails$cleanOverviewBody"

                            // Entrada para Internet Archive
                            if (entry.iaFile != null) {
                                val directUrl = "https://archive.org/download/$iaItemId/${Uri.encode(entry.iaFile)}"
                                val logoAttr = if (posterUrl.isNotEmpty()) " tvg-logo=\"$posterUrl\"" else ""
                                m3uContent.append("#EXTINF:-1$logoAttr group-title=\"$genreGroup\" description=\"$cleanOverview\",$cleanMovieTitle$year [IA]\n")
                                m3uContent.append("$directUrl\n")
                            }

                            // Entrada para Hugging Face
                            if (entry.hfPath != null) {
                                val directUrl = "https://huggingface.co/datasets/$hfRepoId/resolve/main/${Uri.encode(entry.hfPath)}"
                                val logoAttr = if (posterUrl.isNotEmpty()) " tvg-logo=\"$posterUrl\"" else ""
                                m3uContent.append("#EXTINF:-1$logoAttr group-title=\"$genreGroup\" description=\"$cleanOverview\",$cleanMovieTitle$year [HF]\n")
                                m3uContent.append("$directUrl\n")
                            }

                            resolvedCount++
                        }
                        // 5. Guardar la lista M3U en Hugging Face en segundo plano
                        sendTelegramMessage(chatId, "💾 Guardando lista unificada en Hugging Face como `peliculas.m3u`...")
                        val commitUrl = "https://huggingface.co/api/$typePath/$hfRepoId/commit/main"

                        val headerJson = JSONObject().apply {
                            put("key", "header")
                            put("value", JSONObject().apply {
                                put("summary", "Actualizada lista de reproduccion IPTV peliculas.m3u")
                            })
                        }
                        val fileJson = JSONObject().apply {
                            put("key", "file")
                            put("value", JSONObject().apply {
                                put("path", "peliculas.m3u")
                                put("content", android.util.Base64.encodeToString(m3uContent.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
                                put("encoding", "base64")
                            })
                        }

                        val ndjsonBody = headerJson.toString() + "\n" + fileJson.toString() + "\n"
                        val ndjsonBytes = ndjsonBody.toByteArray(Charsets.UTF_8)

                        val commitRequest = Request.Builder()
                            .url(commitUrl)
                            .addHeader("Authorization", "Bearer $hfToken")
                            .addHeader("Content-Type", "application/x-ndjson")
                            .post(ndjsonBytes.toRequestBody("application/x-ndjson".toMediaType()))
                            .build()

                        client.newCall(commitRequest).execute().use { commitResponse ->
                            if (commitResponse.isSuccessful) {
                                val rawUrl = "https://huggingface.co/datasets/$hfRepoId/raw/main/peliculas.m3u"
                                val successText = """
                                    🎉 *¡Lista de reproduccion IPTV Generada con Exito!*
                                    
                                    🍿 Peliculas indexadas: $resolvedCount
                                    💾 Archivo: `peliculas.m3u`
                                    🌐 *Enlace RAW directo para tu app IPTV:*
                                    `$rawUrl`
                                    
                                    💡 Copia y pega este enlace en tu reproductor IPTV (VLC, Televizo) para disfrutar de tus peliculas en streaming inmediato con portadas y descripciones desde TMDB.
                                """.trimIndent()
                                sendTelegramMessage(chatId, successText)
                            } else {
                                val errBody = commitResponse.body?.string() ?: ""
                                sendTelegramMessage(chatId, "❌ Error al subir lista M3U a Hugging Face (${commitResponse.code}): $errBody")
                            }
                        }

                    } catch (e: Exception) {
                        sendTelegramMessage(chatId, "❌ Error critico al generar lista M3U: ${e.message}")
                    }
                }
            }

            else -> {
                sendTelegramMessage(chatId, "❓ Comando no reconocido. Usa /help para ver la lista de comandos disponibles.")
            }
        }
    }

    /**
     * Procesa, selecciona inteligentemente por título o palabra clave, busca ID de TMDB,
     * RENOMBRA EL ARCHIVO LOCAL FÍSICAMENTE en el celular, y luego lo sube asíncronamente a Hugging Face.
     */
    private suspend fun processUploadCommand(chatId: String, ext: String, explicitTmdbIdOrQuery: String?) {
        val folderUriStr = settings.getTargetFolderUri()
        val hfRepoId = settings.getHfBackupRepo()
        val iaItemId = settings.getTargetRepo()

        if (folderUriStr.isEmpty() || iaItemId.isEmpty()) {
            sendTelegramMessage(chatId, "❌ Carpeta local o identificador de Internet Archive no configurados en la App.")
            UploadTracker.addLog("PROCESS_ERROR: Falta configurar la carpeta o el identificador de Internet Archive.")
            return
        }

        val folderUri = folderUriStr.toUri()
        val documentFile = DocumentFile.fromTreeUri(applicationContext, folderUri)
        if (documentFile == null || !documentFile.exists()) {
            sendTelegramMessage(chatId, "❌ Error de Scoped Storage: No se pudo leer la carpeta local.")
            UploadTracker.addLog("PROCESS_ERROR: No se puede leer la carpeta de origen local.")
            return
        }

        // Reseteamos el flag de cancelación al iniciar un proceso nuevo
        UploadTracker.resetCancellation()

        UploadTracker.addLog("PROCESS: Escaneando carpeta local buscando archivos .$ext...")

        val allFiles = documentFile.listFiles()
            .filter { it.name?.endsWith(ext, ignoreCase = true) == true }

        if (allFiles.isEmpty()) {
            sendTelegramMessage(chatId, "❌ No encontré ningún archivo con la extensión `$ext` en tu carpeta local.")
            UploadTracker.addLog("PROCESS_ERROR: Ningún archivo .$ext encontrado.")
            return
        }

        var targetFile: DocumentFile? = null
        var tmdbId = ""

        if (explicitTmdbIdOrQuery != null) {
            val isNumericId = explicitTmdbIdOrQuery.all { it.isDigit() }
            if (isNumericId) {
                tmdbId = explicitTmdbIdOrQuery
                sendTelegramMessage(chatId, "ℹ️ ID de TMDB provisto: `$tmdbId`\nBuscando archivo local que coincida...")
                UploadTracker.addLog("PROCESS: ID de TMDB provisto: $tmdbId")

                try {
                    // Obtener título oficial de TMDB para buscar de manera inteligente
                    val movieTitle = getArchiveUploader().fetchMovieTitleFromTmdb(tmdbId)
                    val cleanedMovieTitle = getArchiveUploader().cleanMovieName(movieTitle).lowercase()
                    UploadTracker.addLog("PROCESS: Título oficial de TMDB: '$movieTitle'. Buscando archivos coincidentes...")

                    val keywords = cleanedMovieTitle.split(" ")
                        .filter { it.length > 2 && it != "the" && it != "los" && it != "las" && it != "del" }

                    if (keywords.isNotEmpty()) {
                        targetFile = allFiles.firstOrNull { file ->
                            val cleanFileName = getArchiveUploader().cleanMovieName(file.name ?: "").lowercase()
                            val fileWords = cleanFileName.split(" ")
                            keywords.any { keyword -> fileWords.contains(keyword) }
                        }
                    }
                } catch (e: Exception) {
                    UploadTracker.addLog("PROCESS_WARN: No se pudo verificar título en TMDB: ${e.message}")
                }

                // Fallback por ID si no coincidió por título
                if (targetFile == null) {
                    targetFile = allFiles.firstOrNull { it.name?.contains(tmdbId) == true }
                }
            } else {
                // Es una palabra clave de búsqueda local (ej: "salvacion")
                val queryClean = explicitTmdbIdOrQuery.lowercase()
                targetFile = allFiles.firstOrNull { file ->
                    val cleanFileName = file.name?.lowercase() ?: ""
                    cleanFileName.contains(queryClean)
                }

                if (targetFile != null) {
                    UploadTracker.addLog("PROCESS: Seleccionado archivo por búsqueda local: '${targetFile.name}'")
                    sendTelegramMessage(chatId, "🎯 Archivo coincidente encontrado: `${targetFile.name}`\nBuscando ID de película en TMDB...")
                } else {
                    sendTelegramMessage(chatId, "❌ No encontré ningún archivo local que coincida con `$explicitTmdbIdOrQuery`.")
                    UploadTracker.addLog("PROCESS_ERROR: Sin coincidencias para palabra clave local '$explicitTmdbIdOrQuery'.")
                    return
                }
            }
        }

        // Fallback final: si no se encontró por búsqueda o no se especificó, tomar el más reciente
        if (targetFile == null) {
            if (explicitTmdbIdOrQuery != null) {
                UploadTracker.addLog("PROCESS_WARN: No se halló archivo coincidente para '$explicitTmdbIdOrQuery'. Tomando el más reciente.")
                sendTelegramMessage(chatId, "⚠️ No hallé coincidencia exacta para `$explicitTmdbIdOrQuery`. Usando el archivo más reciente...")
            }
            targetFile = allFiles.maxByOrNull { it.lastModified() }
        }

        if (targetFile == null) {
            sendTelegramMessage(chatId, "❌ No se pudo seleccionar ningún archivo para subir.")
            return
        }

        val fileSize = targetFile.length()
        val minSize = 200L * 1024 * 1024 // 200 MB de seguridad para móviles
        if (fileSize < minSize) {
            val sizeMb = fileSize / (1024 * 1024)
            sendTelegramMessage(
                chatId,
                "⚠️ El archivo local `${targetFile.name}` pesa $sizeMb MB.\n" +
                        "Para proteger tu repositorio, los archivos renombrados deben pesar más de 200 MB (Límite de seguridad)."
            )
            UploadTracker.addLog("PROCESS_ERROR: El archivo seleccionado (${sizeMb} MB) is menor al límite mínimo de 200 MB.")
            return
        }

        val originalName = targetFile.name ?: "unknown_movie"

        // Si tmdbId está vacío, intentamos extraerlo con nuestro motor inteligente
        if (tmdbId.isEmpty()) {
            tmdbId = extractTmdbId(originalName)
        }

        // Si sigue vacío (porque se buscó por palabra clave local o no se pasó nada)
        if (tmdbId.isEmpty()) {
            sendTelegramMessage(chatId, "🧼 Limpiando el nombre del archivo original para buscar en TMDB...")
            val cleanedName = getArchiveUploader().cleanMovieName(originalName)
            sendTelegramMessage(chatId, "🔍 Nombre limpio para búsqueda: `$cleanedName`\nConectando asíncronamente con la API de TMDB...")
            UploadTracker.addLog("PROCESS: Nombre de búsqueda limpio: '$cleanedName'")

            try {
                tmdbId = getArchiveUploader().fetchMovieIdFromTmdb(cleanedName)
                sendTelegramMessage(chatId, "🎯 Película identificada con éxito.\nID obtenido de TMDB: `$tmdbId`")
            } catch (e: Exception) {
                sendTelegramMessage(
                    chatId,
                    "❌ Error de TMDB: ${e.message}\n" +
                            "No logramos identificar la película automáticamente a partir del nombre limpio. " +
                            "Por favor, renombra el archivo para que sea más claro o sube usando el ID manual:\n" +
                            "`/upload $ext <id_tmdb>`"
                )
                UploadTracker.updateState(UploadTracker.UploadState.Error(originalName, "Error de búsqueda en TMDB: ${e.message}"))
                return
            }
        }

        UploadTracker.addLog("PROCESS: Iniciando cálculo de metadatos y renombrado de archivo...")

        var progressMessageId: Int? = null
        try {
            // 1. Calcular metadatos para Internet Archive e iniciar proceso de renombrado
            val iaMetadata = getArchiveUploader().calculateMetadata(targetFile.uri, tmdbId, ext)

            // ------------------ RENOMBRADO LOCAL FÍSICO ------------------
            val cleanExtension = if (ext.startsWith(".")) ext else ".$ext"

            // Calculamos SHA-256 e info de Hugging Face para el renombrado local y respaldo posterior
            val hfMetadata = getUploader().calculateMetadata(targetFile.uri, tmdbId, ext)
            val shortHash = hfMetadata.sha256.take(8)
            val localNewName = "tmdb_${tmdbId}_$shortHash$cleanExtension"

            val finalLocalFile = if (targetFile.name == localNewName) {
                UploadTracker.addLog("PROCESS: El archivo local ya se encuentra renombrado de forma correcta: '$localNewName'")
                targetFile
            } else {
                UploadTracker.addLog("PROCESS: Renombrando físicamente en el celular de '${targetFile.name}' a '$localNewName'...")
                val renameSuccess = targetFile.renameTo(localNewName)
                if (renameSuccess) {
                    val found = documentFile.findFile(localNewName)
                    if (found != null) {
                        UploadTracker.addLog("PROCESS: ¡Renombrado local exitoso! El archivo local físico ahora es: '${found.name}'")
                        found
                    } else {
                        UploadTracker.addLog("PROCESS_WARN: El renombrado local reportó éxito, pero no se pudo hallar el archivo usando findFile. Usando referencia original.")
                        targetFile
                    }
                } else {
                    UploadTracker.addLog("PROCESS_WARN: No se pudo renombrar el archivo local físicamente. Puede deberse a restricciones de Scoped Storage. Continuando con la referencia original.")
                    targetFile
                }
            }
            // -------------------------------------------------------------

            val prepText = """
                📝 *Procesando doble sincronización (IA + HF Backups):*
                📁 original: `${iaMetadata.originalName}`
                ✨ Local renombrado: `${finalLocalFile.name}`
                🌐 Destino (Internet Archive): `${iaMetadata.newName}`
                🤗 Destino (Hugging Face): `${hfMetadata.newName}`
                ⚖️ Peso: `${iaMetadata.size / (1024 * 1024)}` MB
                🔑 SHA-256: `${hfMetadata.sha256}`
                
                🚀 [1/2] Iniciando transmisión directa a Internet Archive S3 sin consumo de RAM...
            """.trimIndent()
            sendTelegramMessage(chatId, prepText)
            updateNotification("Subiendo a Internet Archive: ${iaMetadata.newName}...")

            var lastProgressNotificationTime = 0L

            // 📡 Enviamos el primer mensaje de progreso 0% de Internet Archive para capturar su ID
            val initId = sendTelegramMessageSync(chatId, "📡 [1/2] Progreso de subida (Internet Archive): *0%* de `${iaMetadata.newName}`")
            if (initId != null) {
                progressMessageId = initId
            }

            // --- FASE 1: SUBIR A INTERNET ARCHIVE ---
            getArchiveUploader().uploadFile(
                itemIdentifier = iaItemId,
                uri = finalLocalFile.uri,
                metadata = iaMetadata,
                progressListener = object : ArchiveUploader.ProgressListener {
                    override fun onProgress(bytesWritten: Long, totalBytes: Long) {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressNotificationTime > 8000) {
                            lastProgressNotificationTime = now
                            val pct = (bytesWritten * 100) / totalBytes
                            val speedText = UploadTracker.currentState.value.let { 
                                if (it is UploadTracker.UploadState.Uploading && it.speed.isNotEmpty()) " [${it.speed}]" else ""
                            }
                            val progressText = "📡 [1/2] Progreso de subida (Internet Archive): *$pct%*$speedText de `${iaMetadata.newName}`"
                            updateNotification("Subiendo a IA: $pct% completado...")

                            serviceScope.launch {
                                val currentId = progressMessageId
                                if (currentId != null) {
                                    val success = editTelegramMessageSync(chatId, currentId, progressText)
                                    if (!success) {
                                        val newId = sendTelegramMessageSync(chatId, progressText)
                                        if (newId != null) {
                                            progressMessageId = newId
                                        }
                                    }
                                } else {
                                    val newId = sendTelegramMessageSync(chatId, progressText)
                                    if (newId != null) {
                                        progressMessageId = newId
                                    }
                                }
                            }
                        }
                    }
                }
            )

            // Confirmar éxito de Fase 1
            val finalIaId = progressMessageId
            if (finalIaId != null) {
                editTelegramMessageSync(chatId, finalIaId, "✅ [1/2] ¡Subido con éxito a Internet Archive! ✓\n`https://archive.org/details/$iaItemId`")
            }

            // --- FASE 2: SINCRONIZAR RESPALDO EN SEGUNDO PLANO A HUGGING FACE ---
            if (hfRepoId.isNotEmpty() && settings.getHfBackupToken().isNotEmpty()) {
                sendTelegramMessage(chatId, "🔄 [2/2] Iniciando sincronización de respaldo en Hugging Face en segundo plano...")
                updateNotification("Sincronizando a Hugging Face: ${hfMetadata.newName}...")

                // Enviamos mensaje de progreso inicial para Hugging Face LFS
                val hfInitId = sendTelegramMessageSync(chatId, "📡 [2/2] Sincronizando respaldo (Hugging Face): *0%* de `${hfMetadata.newName}`")
                if (hfInitId != null) {
                    progressMessageId = hfInitId
                }

                lastProgressNotificationTime = 0L // Reset timer de notificaciones

                val hfResult = getUploader().uploadLfsFile(
                    repoId = hfRepoId,
                    repoType = "dataset",
                    uri = finalLocalFile.uri,
                    metadata = hfMetadata,
                    progressListener = object : HfLfsUploader.ProgressListener {
                        override fun onProgress(bytesWritten: Long, totalBytes: Long) {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressNotificationTime > 8000) {
                                lastProgressNotificationTime = now
                                val pct = (bytesWritten * 100) / totalBytes
                                val speedText = UploadTracker.currentState.value.let { 
                                    if (it is UploadTracker.UploadState.Uploading && it.speed.isNotEmpty()) " [${it.speed}]" else ""
                                }
                                val progressText = "📡 [2/2] Sincronizando respaldo (Hugging Face): *$pct%*$speedText de `${hfMetadata.newName}`"
                                updateNotification("Sincronizando a HF: $pct% completado...")

                                serviceScope.launch {
                                    val currentId = progressMessageId
                                    if (currentId != null) {
                                        val success = editTelegramMessageSync(chatId, currentId, progressText)
                                        if (!success) {
                                            val newId = sendTelegramMessageSync(chatId, progressText)
                                            if (newId != null) {
                                                progressMessageId = newId
                                            }
                                        }
                                    } else {
                                        val newId = sendTelegramMessageSync(chatId, progressText)
                                        if (newId != null) {
                                            progressMessageId = newId
                                        }
                                    }
                                }
                            }
                        }
                    }
                )

                // Confirmar éxito de Fase 2
                val finalHfId = progressMessageId
                if (finalHfId != null) {
                    editTelegramMessageSync(chatId, finalHfId, "✅ [2/2] ¡Respaldo completado en Hugging Face! ✓\n$hfResult")
                }

                sendTelegramMessage(chatId, "🎉 *¡Doble Sincronización Exitosa!*\nPelícula publicada de manera segura en Internet Archive y respaldada con commit verificado en Hugging Face.")
            } else {
                sendTelegramMessage(chatId, "⚠️ *Advertencia:* Sincronización de Hugging Face omitida porque no has configurado tu token o repositorio de backup.")
            }

            updateNotification("Bot de Telegram listo")
        } catch (e: Exception) {
            val wasCancelled = UploadTracker.isCancelled.value
            if (wasCancelled || e.message?.contains("cancelled") == true) {
                UploadTracker.addLog("SYSTEM: Operación cancelada con éxito por solicitud del usuario.")
                if (progressMessageId != null) {
                    editTelegramMessageSync(chatId, progressMessageId, "🛑 *Subida cancelada por el usuario.* Transmisión de red detenida.")
                }
                sendTelegramMessage(chatId, "⏹️ *Subida cancelada:* La transmisión física de bytes se detuvo correctamente.")
                UploadTracker.updateState(UploadTracker.UploadState.Idle)
            } else {
                sendTelegramMessage(chatId, "❌ *Error durante la subida:* ${e.message}\nEl servicio mantendrá la conexión persistente para reintentar.")
                UploadTracker.updateState(UploadTracker.UploadState.Error(originalName, e.message ?: "Fallo inesperado"))
            }
            UploadTracker.resetCancellation()
            updateNotification("Bot listo")
        }
    }

    private fun sendTelegramMessage(chatId: String, text: String) {
        val botToken = settings.getTelegramToken()
        if (botToken.isEmpty() || chatId.isEmpty()) return

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                UploadTracker.addLog("TELEGRAM_SEND_FAIL: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    /**
     * Envía un mensaje de Telegram de forma síncrona/suspending y retorna el ID del mensaje creado.
     */
    private suspend fun sendTelegramMessageSync(chatId: String, text: String): Int? = withContext(Dispatchers.IO) {
        val botToken = settings.getTelegramToken()
        if (botToken.isEmpty() || chatId.isEmpty()) return@withContext null

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val obj = JSONObject(body)
                        if (obj.optBoolean("ok", false)) {
                            return@withContext obj.getJSONObject("result").getInt("message_id")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    /**
     * Edita un mensaje de Telegram existente de forma síncrona/suspending.
     */
    private suspend fun editTelegramMessageSync(chatId: String, messageId: Int, text: String): Boolean = withContext(Dispatchers.IO) {
        val botToken = settings.getTelegramToken()
        if (botToken.isEmpty() || chatId.isEmpty()) return@withContext false

        val url = "https://api.telegram.org/bot$botToken/editMessageText"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            put("parse_mode", "Markdown")
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }
}

private data class MovieEntry(
    val tmdbId: String,
    var iaFile: String? = null,
    var hfPath: String? = null
)
