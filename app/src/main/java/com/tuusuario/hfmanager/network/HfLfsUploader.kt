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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import com.tuusuario.hfmanager.data.UploadTracker

class HfLfsUploader(
    private val context: Context,
    private val hfToken: String,
    tmdbApiKey: String
) : BaseUploader(tmdbApiKey, createClient()) {

    companion object {
        private fun createClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.MINUTES)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(LfsRetryInterceptor(maxRetries = 5, baseDelayMillis = 2000))
                .build()
        }
    }

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
     * Calcula el hash SHA-256 de un Uri de Android de forma secuencial y sin cargar en RAM.
     */
    suspend fun calculateMetadata(uri: Uri, tmdbId: String, extension: String): FileMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var originalName = "unknown_file"
        var size: Long = 0

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) originalName = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }

        UploadTracker.updateState(UploadTracker.UploadState.Processing(originalName, "Calculando hash SHA-256 de forma asíncrona..."))

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024) // Buffer de 128 KB para el hashing
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

        val batchUrl = "https://huggingface.co/$typePath$repoId.git/info/lfs/objects/batch"
        val batchJson = JSONObject().apply {
            put("operation", "upload")
            put("transports", JSONArray().apply { put("basic") })
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

        client.newCall(batchRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IOException("Fallo en LFS Batch de Hugging Face (${response.code}): $errBody")
            }

            val bodyStr = response.body?.string() ?: throw IOException("Respuesta LFS Batch vacía")
            val jsonResponse = JSONObject(bodyStr)
            val objectsArray = jsonResponse.getJSONArray("objects")
            if (objectsArray.length() == 0) throw IOException("Respuesta de objetos LFS vacía")

            val fileInfo = objectsArray.getJSONObject(0)
            if (fileInfo.has("error")) {
                val errorObj = fileInfo.getJSONObject("error")
                throw IOException("Error LFS devuelto por el Hub: ${errorObj.optString("message", "Desconocido")}")
            }

            val actions = fileInfo.optJSONObject("actions")
            val requiresUpload = actions != null && actions.has("upload")

            if (requiresUpload) {
                val uploadAction = actions.getJSONObject("upload")
                val uploadUrl = uploadAction.getString("href")
                val headersObj = uploadAction.optJSONObject("headers")

                if (UploadTracker.isCancelled.value) throw IOException("Operation cancelled by user")

                UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Transmitiendo bytes directamente a Amazon S3..."))

                val s3RequestBuilder = Request.Builder().url(uploadUrl)
                headersObj?.let {
                    val keys = it.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        s3RequestBuilder.addHeader(key, it.getString(key))
                    }
                }

                val streamingBody = object : RequestBody() {
                    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
                    override fun contentLength(): Long = metadata.size

                    override fun writeTo(sink: BufferedSink) {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            val buffer = ByteArray(128 * 1024)
                            var bytesWritten: Long = 0
                            var read: Int

                            while (inputStream.read(buffer).also { read = it } != -1) {
                                if (UploadTracker.isCancelled.value) throw IOException("Operation cancelled by user")
                                sink.write(buffer, 0, read)
                                bytesWritten += read
                                val pct = bytesWritten.toFloat() / metadata.size.toFloat()

                                UploadTracker.updateState(UploadTracker.UploadState.Uploading(
                                    metadata.originalName, metadata.newName, bytesWritten, metadata.size, pct
                                ))
                                progressListener?.onProgress(bytesWritten, metadata.size)
                            }
                        } ?: throw IOException("No se pudo abrir el flujo local")
                    }
                }

                client.newCall(s3RequestBuilder.put(streamingBody).build()).execute().use { s3Response ->
                    if (!s3Response.isSuccessful) {
                        val s3Err = s3Response.body?.string() ?: ""
                        throw IOException("Fallo en la transferencia a S3 (${s3Response.code}): $s3Err")
                    }
                }

                // Verify API call if provided
                actions.optJSONObject("verify")?.let { verifyAction ->
                    val verifyUrl = verifyAction.getString("href")
                    val verifyHeadersObj = verifyAction.optJSONObject("headers")
                    val verifyJson = JSONObject().apply {
                        put("oid", metadata.sha256)
                        put("size", metadata.size)
                    }

                    val verifyRequestBuilder = Request.Builder()
                        .url(verifyUrl)
                        .post(verifyJson.toString().toRequestBody("application/vnd.git-lfs+json".toMediaType()))

                    verifyHeadersObj?.let { vHeaders ->
                        val keys = vHeaders.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            verifyRequestBuilder.addHeader(key, vHeaders.getString(key))
                        }
                    }

                    if (verifyHeadersObj == null || !verifyHeadersObj.has("Authorization")) {
                        verifyRequestBuilder.addHeader("Authorization", "Bearer $hfToken")
                    }

                    client.newCall(verifyRequestBuilder.build()).execute().use { verifyResponse ->
                        if (!verifyResponse.isSuccessful) {
                            val verifyErr = verifyResponse.body?.string() ?: ""
                            throw IOException("Fallo en la verificación LFS (${verifyResponse.code}): $verifyErr")
                        }
                    }
                }
                UploadTracker.addLog("PROCESS: Verificación LFS completada.")
            } else {
                UploadTracker.addLog("PROCESS: Deduplicación LFS activa. El archivo ya existe.")
            }
        }

        if (UploadTracker.isCancelled.value) throw IOException("Operation cancelled by user")

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Confirmando commit en Hugging Face..."))

        val commitUrlPath = if (repoType.lowercase() == "dataset") "datasets" else "models"
        val commitUrl = "https://huggingface.co/api/$commitUrlPath/$repoId/commit/main"

        val ndjsonBody = JSONObject().apply {
            put("key", "header")
            put("value", JSONObject().apply {
                put("summary", "Subida LFS: ${metadata.newName}")
            })
        }.toString() + "\n" + JSONObject().apply {
            put("key", "lfsFile")
            put("value", JSONObject().apply {
                put("path", metadata.newName)
                put("algo", "sha256")
                put("oid", metadata.sha256)
                put("size", metadata.size)
            })
        }.toString() + "\n"

        val commitRequest = Request.Builder()
            .url(commitUrl)
            .addHeader("Authorization", "Bearer $hfToken")
            .addHeader("Content-Type", "application/x-ndjson")
            .post(ndjsonBody.toRequestBody("application/x-ndjson".toMediaType()))
            .build()

        client.newCall(commitRequest).execute().use { commitResponse ->
            if (!commitResponse.isSuccessful) {
                val commitErr = commitResponse.body?.string() ?: ""
                throw IOException("Fallo en commit final (${commitResponse.code}): $commitErr")
            }

            val bodyStr = commitResponse.body?.string() ?: ""
            val commitResponseJson = JSONObject(bodyStr)
            val finalUrl = commitResponseJson.optString("commitUrl", "https://huggingface.co/$repoId")

            UploadTracker.addLog("PROCESS: Verificando indexación...")
            delay(2000)
            val exists = verifyFileExistsOnHub(repoId, repoType, metadata.newName)
            
            val resultMessage = if (exists) {
                UploadTracker.addLog("SUCCESS: '${metadata.newName}' verificado en catálogo.")
                "$finalUrl (Verificado ✓)"
            } else {
                UploadTracker.addLog("WARNING: Commit enviado, pero aún no indexado.")
                "$finalUrl (Pendiente indexación ⚠️)"
            }

            UploadTracker.updateState(UploadTracker.UploadState.Success(metadata.originalName, finalUrl))
            return@withContext resultMessage
        }
    }

    suspend fun verifyFileExistsOnHub(repoId: String, repoType: String, pathInRepo: String): Boolean = withContext(Dispatchers.IO) {
        val typePath = if (repoType.lowercase() == "dataset") "datasets" else "models"
        val folderPath = pathInRepo.substringBeforeLast("/", "")
        val url = "https://huggingface.co/api/$typePath/$repoId/tree/main/$folderPath"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $hfToken")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "[]"
                    val filesArray = JSONArray(bodyStr)
                    for (i in 0 until filesArray.length()) {
                        if (filesArray.getJSONObject(i).getString("path") == pathInRepo) return@withContext true
                    }
                }
            }
        } catch (_: Exception) {}
        return@withContext false
    }
}
