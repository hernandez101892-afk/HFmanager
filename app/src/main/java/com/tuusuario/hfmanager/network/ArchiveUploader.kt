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

        UploadTracker.updateState(UploadTracker.UploadState.Processing(originalName, "Preparando archivo y metadatos para Internet Archive..."))

        val cleanExtension = if (extension.startsWith(".")) extension else ".${extension}"
        var cleanTitle = "Pelicula"
        var yearSuffix = ""

        try {
            if (tmdbApiKey.isNotEmpty() && tmdbId.isNotEmpty()) {
                val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=es-MX"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { tRes ->
                    if (tRes.isSuccessful) {
                        val body = tRes.body?.string() ?: ""
                        val json = JSONObject(body)
                        cleanTitle = json.optString("title", "Pelicula")
                        val releaseDate = json.optString("release_date", "")
                        if (releaseDate.length >= 4) yearSuffix = " (${releaseDate.take(4)})"
                    }
                }
            }
        } catch (e: Exception) {
            cleanTitle = "Pelicula_TMDB_$tmdbId"
        }

        val safeTitle = cleanTitle.replace(Regex("[\\\\/:*?\"<>|]"), "")
        val newName = "$safeTitle$yearSuffix [tmdb-$tmdbId]$cleanExtension"

        if (size == 0L) {
            resolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    size += bytesRead
                }
            }
        }

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
            .addHeader("Accept-Encoding", "gzip")
            .addHeader("x-archive-queue-derive", "1")
            .addHeader("x-archive-meta-mediatype", "movies")
            .addHeader("x-amz-auto-make-bucket", "1")
            .addHeader("x-archive-meta-title", "Mi Catalogo de Peliculas Hachetv")
            .put(streamingBody)
            .build()

        UploadTracker.updateState(UploadTracker.UploadState.Processing(metadata.originalName, "Transmitiendo película a Internet Archive S3..."))

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw IOException("Error de subida en Internet Archive (Código ${response.code}): $errBody")
            }
        }

        delay(1000)
        UploadTracker.updateState(UploadTracker.UploadState.Success(metadata.originalName, "https://archive.org/details/$itemIdentifier"))
        return@withContext "¡Película publicada exitosamente! ✓"
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
                    val filesArray = json.optJSONArray("files")
                    if (filesArray != null) {
                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.getJSONObject(i)
                            val name = fileObj.optString("name", "")
                            if (name.isNotEmpty()) resultList.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            UploadTracker.addLog("ARCHIVE_LIST_ERROR: Error al recuperar árbol de metadatos: ${e.message}")
        }
        return@withContext resultList
    }
}
