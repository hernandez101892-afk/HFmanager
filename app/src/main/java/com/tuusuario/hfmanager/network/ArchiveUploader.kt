package com.tuusuario.hfmanager.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.tuusuario.hfmanager.data.UploadTracker

class ArchiveUploader(
    private val context: Context,
    private val accessKey: String,
    private val secretKey: String,
    tmdbApiKey: String
) : BaseUploader(tmdbApiKey, createClient()) {

    companion object {
        private fun createClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.MINUTES)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(ArchiveRetryInterceptor(maxRetries = 5, baseDelayMillis = 2000))
                .build()
        }
    }

    class ArchiveRetryInterceptor(
        private val maxRetries: Int = 5,
        private val baseDelayMillis: Long = 2000
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var lastException: IOException? = null
            var tryCount = 0

            while (tryCount < maxRetries) {
                try {
                    if (UploadTracker.isCancelled.value) throw IOException("Operation cancelled by user")
                    if (tryCount > 0) {
                        val sleepTime = baseDelayMillis * tryCount
                        UploadTracker.addLog("NETWORK [Reintento $tryCount/$maxRetries]: Reconectando con Archive S3 en ${sleepTime}ms...")
                        Thread.sleep(sleepTime)
                    }
                    val response = chain.proceed(request)
                    if (response.isSuccessful) return response
                    
                    val code = response.code
                    if (code == 502 || code == 503 || code == 504 || code == 408) {
                        tryCount++
                        response.close()
                        continue
                    }
                    return response
                } catch (e: IOException) {
                    if (UploadTracker.isCancelled.value || e.message?.contains("cancelled") == true) {
                        throw IOException("Operation cancelled by user")
                    }
                    lastException = e
                    tryCount++
                    UploadTracker.addLog("NETWORK_ALERT: Interrupción de señal detectada (${e.message}). Reintentando...")
                }
            }
            throw lastException ?: IOException("Error crítico de red en Archive S3 tras $maxRetries intentos.")
        }
    }

    data class FileMetadata(
        val originalName: String,
        val newName: String,
        val size: Long
    )

    interface ProgressListener {
        fun onProgress(bytesWritten: Long, totalBytes: Long)
    }

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

        UploadTracker.updateState(UploadTracker.UploadState.Processing(originalName, "Preparando metadatos para Internet Archive..."))

        // Optimize: If size is unknown, we'll calculate it during streaming or here if strictly needed
        if (size == 0L) {
             resolver.openInputStream(uri)?.use { it.available().toLong().also { size = it } }
             if (size == 0L) { // Fallback manual count if available() is not reliable
                 resolver.openInputStream(uri)?.use { input ->
                     val buffer = ByteArray(1024 * 1024)
                     var read: Int
                     while (input.read(buffer).also { read = it } != -1) {
                         size += read
                     }
                 }
             }
        }

        val cleanExtension = if (extension.startsWith(".")) extension else ".${extension}"
        var cleanTitle = "Pelicula"
        var yearSuffix = ""

        try {
            if (tmdbApiKey.isNotEmpty() && tmdbId.isNotEmpty()) {
                cleanTitle = fetchMovieTitleFromTmdb(tmdbId)
                // To get the year, we'd need another call or extend BaseUploader to handle year
                // For now, let's just use the title to keep it simple as a refactor
            }
        } catch (e: Exception) {
            cleanTitle = "Pelicula_TMDB_$tmdbId"
        }

        val safeTitle = cleanTitle.replace(Regex("[\\\\/:*?\"<>|]"), "")
        val newName = "$safeTitle$yearSuffix [tmdb-$tmdbId]$cleanExtension"

        return@withContext FileMetadata(originalName, newName, size)
    }

    suspend fun uploadFile(
        itemIdentifier: String,
        uri: Uri,
        metadata: FileMetadata,
        progressListener: ProgressListener? = null
    ): String = withContext(Dispatchers.IO) {
        val url = "https://s3.us.archive.org/$itemIdentifier/${Uri.encode(metadata.newName)}"

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
                } ?: throw IOException("No se pudo abrir el archivo local")
            }
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "LOW $accessKey:$secretKey")
            .addHeader("x-archive-queue-derive", "1")
            .addHeader("x-archive-meta-mediatype", "movies")
            .addHeader("x-amz-auto-make-bucket", "1")
            .put(streamingBody)
            .build()

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Transmitiendo a Internet Archive S3..."))

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Error de subida (Código ${response.code}): ${response.body?.string()}")
            }
        }

        delay(1000)
        UploadTracker.updateState(UploadTracker.UploadState.Success(metadata.originalName, "https://archive.org/details/$itemIdentifier"))
        return@withContext "¡Publicado exitosamente! ✓"
    }

    suspend fun listArchiveItemFiles(itemIdentifier: String): List<String> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<String>()
        val url = "https://archive.org/metadata/$itemIdentifier"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    json.optJSONArray("files")?.let { filesArray ->
                        for (i in 0 until filesArray.length()) {
                            filesArray.getJSONObject(i).optString("name", "").takeIf { it.isNotEmpty() }?.let { resultList.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            UploadTracker.addLog("ARCHIVE_LIST_ERROR: ${e.message}")
        }
        return@withContext resultList
    }
}
