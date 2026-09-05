package com.tuusuario.hfmanager.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.tuusuario.hfmanager.data.UploadTracker

class HfLfsUploader(
    private val context: Context,
    private val hfToken: String,
    private val tmdbApiKey: String
) {
    // Interceptor para reintentos agresivos ante inestabilidad de red en subidas masivas
    class LfsRetryInterceptor(
        private val maxRetries: Int = 5,
        private val baseDelayMillis: Long = 2000
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var lastException: IOException? = null
            var tryCount = 0

            while (tryCount < maxRetries) {
                try {
                    if (UploadTracker.isCancelled.value) {
                        throw IOException("Operation cancelled by user")
                    }
                    if (tryCount > 0) {
                        val sleepTime = baseDelayMillis * tryCount
                        UploadTracker.addLog("NETWORK [Reintento $tryCount/$maxRetries]: Reconectando con S3 en ${sleepTime}ms...")
                        Thread.sleep(sleepTime)
                    }
                    response = chain.proceed(request)
                    if (response.isSuccessful) {
                        return response
                    } else {
                        // Reintentar si el servidor devuelve errores de sobrecarga comunes (502, 503, 504)
                        val code = response.code
                        if (code == 502 || code == 503 || code == 504) {
                            tryCount++
                            response.close()
                            continue
                        }
                        return response
                    }
                } catch (e: IOException) {
                    if (UploadTracker.isCancelled.value || e.message?.contains("cancelled") == true) {
                        throw IOException("Operation cancelled by user")
                    }
                    lastException = e
                    tryCount++
                    UploadTracker.addLog("NETWORK_ALERT: Interrupción de señal detectada (${e.message}). Reintentando transmisión...")
                }
            }
            if (response != null) return response
            throw lastException ?: IOException("Error crítico de transmisión de red tras $maxRetries intentos.")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS) // Tiempo ampliado a 120 segundos para evitar Connection Timeout
        .writeTimeout(30, TimeUnit.MINUTES)   // Soporte para archivos masivos de hasta 4GB o más
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)       // Auto-reconexión a nivel de socket habilitada
        .addInterceptor(LfsRetryInterceptor(maxRetries = 5, baseDelayMillis = 2000))
        .build()

    data class FileMetadata(
        val originalName: String,
        val newName: String,
        val size: Long,
        val sha256: String
    )

    interface ProgressListener {
        fun onProgress(bytesWritten: Long, totalBytes: Long)
    }

    /**
     * Limpia el nombre del archivo para extraer el título de la película.
     */
    fun cleanMovieName(fileName: String): String {
        var cleaned = fileName.substringBeforeLast(".")
        cleaned = cleaned.replace(Regex("(?i)\\b\\d+x\\d+\\b"), "")
        cleaned = cleaned.replace(Regex("(?i)\\b\\d+p\\b"), "")
        cleaned = cleaned.replace(Regex("(?i)\\b\\d+k\\b"), "")
        cleaned = cleaned.replace("-", " ").replace("_", " ")
        cleaned = cleaned.replace(Regex("\\b\\d+\\b"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    /**
     * Busca la película en TMDB usando el nombre limpio y retorna el ID de la primera coincidencia.
     */
    suspend fun fetchMovieIdFromTmdb(movieName: String): String = withContext(Dispatchers.IO) {
        if (tmdbApiKey.isEmpty()) {
            throw IllegalStateException("La API Key de TMDB no está configurada")
        }
        UploadTracker.addLog("TMDB: Buscando coincidencias para '$movieName'...")
        val encodedName = Uri.encode(movieName)
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$encodedName&language=es-MX"
        val request = Request.Builder().url(url).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Error al conectar con TMDB (Código ${response.code})")
        }

        val bodyStr = response.body?.string() ?: throw IOException("Respuesta vacía de TMDB")
        val json = JSONObject(bodyStr)
        val results = json.getJSONArray("results")
        if (results.length() == 0) {
            throw IOException("No se encontró película para '$movieName'")
        }

        val firstMovie = results.getJSONObject(0)
        val movieId = firstMovie.getInt("id").toString()
        val title = firstMovie.getString("title")
        UploadTracker.addLog("TMDB: Encontrado '$title' -> ID: $movieId")
        return@withContext movieId
    }

    /**
     * Busca el título oficial de una película en TMDB por su ID.
     */
    suspend fun fetchMovieTitleFromTmdb(tmdbId: String): String = withContext(Dispatchers.IO) {
        if (tmdbApiKey.isEmpty()) {
            throw IllegalStateException("La API Key de TMDB no está configurada")
        }
        val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=es-MX"
        val request = Request.Builder().url(url).build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Error al conectar con TMDB al buscar ID (Código ${response.code})")
        }

        val bodyStr = response.body?.string() ?: throw IOException("Respuesta vacía de TMDB para búsqueda de ID")
        val json = JSONObject(bodyStr)
        return@withContext json.getString("title")
    }

    /**
     * Calcula el hash SHA-256 de un Uri de Android de forma secuencial y sin cargar en RAM.
     */
    suspend fun calculateMetadata(uri: Uri, tmdbId: String, extension: String): FileMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val cursor = resolver.query(uri, null, null, null, null)
        var originalName = "unknown_file"
        var size: Long = 0

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) originalName = it.getString(nameIndex)
                if (sizeIndex != -1) size = it.getLong(sizeIndex)
            }
        }

        UploadTracker.updateState(UploadTracker.UploadState.Processing(originalName, "Calculando hash SHA-256 de forma asíncrona..."))

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024) // Buffer de 1 MB
        var bytesRead: Int
        var calculatedSize: Long = 0

        resolver.openInputStream(uri)?.use { inputStream ->
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (UploadTracker.isCancelled.value) {
                    throw IOException("Operation cancelled by user")
                }
                digest.update(buffer, 0, bytesRead)
                calculatedSize += bytesRead
            }
        } ?: throw IOException("No se pudo abrir el flujo de entrada para el archivo local")

        if (size == 0L) size = calculatedSize
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        UploadTracker.updateState(UploadTracker.UploadState.Processing(originalName, "Renombrando archivo según metadatos..."))

        // Formateo visual y ruta oficial del repositorio g9/BOTtelegram
        val cleanExtension = if (extension.startsWith(".")) extension else ".$extension"
        val shortHash = sha256.take(8)
        val newName = "videos/tmdb_${tmdbId}_$shortHash$cleanExtension"

        UploadTracker.addLog("PROCESS: Ruta y nombre de destino: $newName")
        return@withContext FileMetadata(originalName, newName, size, sha256)
    }

    /**
     * Ejecuta el flujo completo de LFS (Batch API Oficial, PUT a S3, Commit final)
     */
    suspend fun uploadLfsFile(
        repoId: String,
        repoType: String,
        uri: Uri,
        metadata: FileMetadata,
        progressListener: ProgressListener? = null
    ): String = withContext(Dispatchers.IO) {
        val typePath = if (repoType.lowercase() == "dataset") "datasets/" else ""

        if (UploadTracker.isCancelled.value) {
            throw IOException("Operation cancelled by user")
        }

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Solicitando enlace de subida seguro (LFS Batch API)..."))

        // 1. Paso LFS Batch (Solicitar URL y Headers de Amazon S3 de forma estándar)
        val batchUrl = "https://huggingface.co/$typePath$repoId.git/info/lfs/objects/batch"
        val batchJson = JSONObject().apply {
            put("operation", "upload")
            put("transports", JSONArray().apply {
                put("basic")
            })
            put("objects", JSONArray().apply {
                put(JSONObject().apply {
                    put("oid", metadata.sha256)
                    put("size", metadata.size)
                })
            })
            put("hash_algo", "sha256")
        }

        val batchRequest = Request.Builder()
            .url(batchUrl)
            .addHeader("Authorization", "Bearer $hfToken")
            .addHeader("Accept", "application/vnd.git-lfs+json")
            .addHeader("Content-Type", "application/vnd.git-lfs+json")
            .post(batchJson.toString().toRequestBody("application/vnd.git-lfs+json".toMediaType()))
            .build()

        val batchResponse = client.newCall(batchRequest).execute()
        if (!batchResponse.isSuccessful) {
            val errBody = batchResponse.body?.string() ?: ""
            throw IOException("Fallo en LFS Batch de Hugging Face (${batchResponse.code}): $errBody")
        }

        val batchResponseBody = batchResponse.body?.string() ?: throw IOException("Respuesta LFS Batch vacía")
        val jsonResponse = JSONObject(batchResponseBody)
        val objectsArray = jsonResponse.getJSONArray("objects")
        if (objectsArray.length() == 0) {
            throw IOException("Hugging Face no retornó información del objeto en LFS Batch")
        }

        val fileInfo = objectsArray.getJSONObject(0)

        if (fileInfo.has("error")) {
            val errorObj = fileInfo.getJSONObject("error")
            throw IOException("Error LFS devuelto por el Hub: ${errorObj.optString("message", "Desconocido")}")
        }

        val actions = fileInfo.optJSONObject("actions")
        val requiresUpload = actions != null && actions.has("upload")

        if (requiresUpload) {
            val uploadAction = actions!!.getJSONObject("upload")
            val uploadUrl = uploadAction.getString("href")
            val headersObj = uploadAction.optJSONObject("headers")

            if (UploadTracker.isCancelled.value) {
                throw IOException("Operation cancelled by user")
            }

            UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Transmitiendo bytes directamente a Amazon S3..."))

            // 2. Paso de subida directa a Amazon S3 usando Streams sin ocupar RAM
            val s3RequestBuilder = Request.Builder().url(uploadUrl)
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    s3RequestBuilder.addHeader(key, headersObj.getString(key))
                }
            }

            val streamingBody = object : RequestBody() {
                override fun contentType(): MediaType? = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = metadata.size

                override fun writeTo(sink: BufferedSink) {
                    val resolver = context.contentResolver
                    val inputStream: InputStream = resolver.openInputStream(uri)
                        ?: throw IOException("No se pudo abrir el flujo para streaming a S3")

                    val source = inputStream.source()
                    val buffer = ByteArray(64 * 1024) // Buffer de alta velocidad de 64 KB
                    var bytesWritten: Long = 0
                    var read: Int

                    try {
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            if (UploadTracker.isCancelled.value) {
                                throw IOException("Operation cancelled by user")
                            }
                            sink.write(buffer, 0, read)
                            bytesWritten += read
                            val pct = bytesWritten.toFloat() / metadata.size.toFloat()

                            UploadTracker.updateState(UploadTracker.UploadState.Uploading(
                                originalName = metadata.originalName,
                                newName = metadata.newName,
                                bytesWritten = bytesWritten,
                                totalBytes = metadata.size,
                                progress = pct
                            ))
                            progressListener?.onProgress(bytesWritten, metadata.size)
                        }
                    } finally {
                        source.close()
                        inputStream.close()
                    }
                }
            }

            val s3Request = s3RequestBuilder.put(streamingBody).build()
            val s3Response = client.newCall(s3Request).execute()
            if (!s3Response.isSuccessful) {
                val s3Err = s3Response.body?.string() ?: ""
                throw IOException("Fallo en la transferencia de bytes a S3 (${s3Response.code}): $s3Err")
            }
            s3Response.close()

            // 2.b. Paso de Verificación (Verify LFS Object) - CRÍTICO PARA EL ÉXITO DEL COMMIT EN HUGGING FACE
            val verifyAction = actions.optJSONObject("verify")
            if (verifyAction != null) {
                val verifyUrl = verifyAction.getString("href")
                val verifyHeadersObj = verifyAction.optJSONObject("headers")

                val verifyJson = JSONObject().apply {
                    put("oid", metadata.sha256)
                    put("size", metadata.size)
                }

                val verifyRequestBuilder = Request.Builder()
                    .url(verifyUrl)
                    .post(verifyJson.toString().toRequestBody("application/vnd.git-lfs+json".toMediaType()))

                if (verifyHeadersObj != null) {
                    val keys = verifyHeadersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        verifyRequestBuilder.addHeader(key, verifyHeadersObj.getString(key))
                    }
                }

                // Asegurar encabezado de autorización
                if (verifyHeadersObj == null || !verifyHeadersObj.has("Authorization")) {
                    verifyRequestBuilder.addHeader("Authorization", "Bearer $hfToken")
                }

                val verifyRequest = verifyRequestBuilder.build()
                client.newCall(verifyRequest).execute().use { verifyResponse ->
                    if (!verifyResponse.isSuccessful) {
                        val verifyErr = verifyResponse.body?.string() ?: ""
                        throw IOException("Fallo en la verificación de LFS (Verify API) (${verifyResponse.code}): $verifyErr")
                    }
                }
                UploadTracker.addLog("PROCESS: Verificación LFS completada con éxito. Registrado en el pool de Hugging Face.")
            }
        } else {
            UploadTracker.addLog("PROCESS: Deduplicación LFS activa. Los bytes del archivo ya existen en Hugging Face S3. Saltando transmisión y procediendo a crear el enlace (Commit) directamente...")
        }

        if (UploadTracker.isCancelled.value) {
            throw IOException("Operation cancelled by user")
        }

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Completando commit en Hugging Face..."))

        // 3. Paso de Commit para registrar el puntero LFS en la rama 'main'
        val commitUrlPath = if (repoType.lowercase() == "dataset") "datasets" else "models"
        val commitUrl = "https://huggingface.co/api/$commitUrlPath/$repoId/commit/main"

        val headerJson = JSONObject().apply {
            put("key", "header")
            put("value", JSONObject().apply {
                put("summary", "Subida de pelicula LFS: ${metadata.newName}")
                put("description", "Enviado de forma segura mediante HF Manager Android")
            })
        }
        val operationJson = JSONObject().apply {
            put("key", "lfsFile")
            put("value", JSONObject().apply {
                put("path", metadata.newName)
                put("algo", "sha256")
                put("oid", metadata.sha256)
                put("size", metadata.size)
            })
        }
        val ndjsonBody = headerJson.toString() + "\n" + operationJson.toString() + "\n"

        val ndjsonBytes = ndjsonBody.toByteArray(Charsets.UTF_8)
        val commitRequest = Request.Builder()
            .url(commitUrl)
            .addHeader("Authorization", "Bearer $hfToken")
            .addHeader("Content-Type", "application/x-ndjson")
            .post(ndjsonBytes.toRequestBody("application/x-ndjson".toMediaType()))
            .build()

        val commitResponse = client.newCall(commitRequest).execute()
        if (!commitResponse.isSuccessful) {
            val commitErr = commitResponse.body?.string() ?: ""
            throw IOException("Fallo en commit LFS final (${commitResponse.code}): $commitErr")
        }

        val commitResponseBody = commitResponse.body?.string() ?: ""
        val commitResponseJson = JSONObject(commitResponseBody)
        val finalUrl = commitResponseJson.optString("commitUrl", "https://huggingface.co/$repoId")

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Verificando indexación remota..."))
        UploadTracker.addLog("PROCESS: Verificando de forma real si '${metadata.newName}' aparece en la lista remota de Hugging Face...")

        // Esperamos 2 segundos para dar tiempo de indexación en el backend
        delay(2000)

        val exists = verifyFileExistsOnHub(repoId, repoType, metadata.newName)
        val resultMessage = if (exists) {
            UploadTracker.addLog("VERIFICATION_SUCCESS: ¡Confirmado! '${metadata.newName}' ya aparece visible en la lista del repositorio.")
            "https://huggingface.co/$repoId (¡Verificado en catálogo! ✓)"
        } else {
            UploadTracker.addLog("VERIFICATION_ALERT: El commit fue enviado, pero '${metadata.newName}' todavía no se visualiza en la lista remota (retraso de indexación o caché).")
            "https://huggingface.co/$repoId (⚠️ Commit enviado pero aún no aparece listado en catálogo)"
        }

        UploadTracker.updateState(UploadTracker.UploadState.Success(metadata.originalName, finalUrl))
        return@withContext resultMessage
    }


    /**
     * Verifica de forma real si el archivo ya aparece en la lista de archivos de Hugging Face
     */
    suspend fun verifyFileExistsOnHub(repoId: String, repoType: String, pathInRepo: String): Boolean = withContext(Dispatchers.IO) {
        val typePath = if (repoType.lowercase() == "dataset") "datasets" else "models"
        val folderPath = pathInRepo.substringBeforeLast("/", "")

        val url = "https://huggingface.co/api/$typePath/$repoId/tree/main/$folderPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $hfToken")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: "[]"
                val filesArray = JSONArray(bodyStr)
                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.getJSONObject(i)
                    val path = fileObj.getString("path")
                    if (path == pathInRepo) {
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            UploadTracker.addLog("VERIFY_ERROR: Fallo al verificar existencia de '$pathInRepo': ${e.message}")
        }
        return@withContext false
    }

}
