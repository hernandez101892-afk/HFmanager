package com.tuusuario.hfmanager.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

abstract class BaseUploader(
    protected val tmdbApiKey: String,
    protected val client: OkHttpClient
) {
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
     * Extrae el ID de TMDB del nombre del archivo si ya existe.
     */
    fun extractTmdbId(filename: String): String {
        val nameWithoutExt = filename.substringBeforeLast(".")
        
        // Patrón tmdb_123_
        val patternUnderscore = java.util.regex.Pattern.compile("tmdb_(\\d+)_", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcherUnderscore = patternUnderscore.matcher(nameWithoutExt)
        if (matcherUnderscore.find()) return matcherUnderscore.group(1) ?: ""

        // Patrón [tmdb-123]
        val patternBracket = java.util.regex.Pattern.compile("\\[tmdb-(\\d+)]", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcherBracket = patternBracket.matcher(nameWithoutExt)
        if (matcherBracket.find()) return matcherBracket.group(1) ?: ""

        // Patrón tmdb_123
        val patternSimple = java.util.regex.Pattern.compile("tmdb_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
        val matcherSimple = patternSimple.matcher(nameWithoutExt)
        if (matcherSimple.find()) return matcherSimple.group(1) ?: ""
        
        // Fallback: Cualquier secuencia de 5 a 8 dígitos (IDs típicos de TMDB)
        val patternFallback = java.util.regex.Pattern.compile("(\\d{5,8})")
        val matcherFallback = patternFallback.matcher(nameWithoutExt)
        if (matcherFallback.find()) return matcherFallback.group(1) ?: ""

        return ""
    }

    /**
     * Busca la película en TMDB usando el nombre limpio y retorna el ID.
     */
    suspend fun fetchMovieIdFromTmdb(movieName: String): String = withContext(Dispatchers.IO) {
        if (tmdbApiKey.isEmpty()) throw IllegalStateException("La API Key de TMDB no está configurada")
        val encodedName = Uri.encode(movieName)
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$encodedName&language=es-MX"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al conectar con TMDB (Código ${response.code})")
            val bodyStr = response.body?.string() ?: throw IOException("Respuesta vacía de TMDB")
            val json = JSONObject(bodyStr)
            val results = json.getJSONArray("results")
            if (results.length() == 0) throw IOException("No se encontró película para '$movieName'")
            return@withContext results.getJSONObject(0).getInt("id").toString()
        }
    }

    /**
     * Busca el título oficial de una película en TMDB por su ID.
     */
    suspend fun fetchMovieTitleFromTmdb(tmdbId: String): String = withContext(Dispatchers.IO) {
        if (tmdbApiKey.isEmpty()) throw IllegalStateException("La API Key de TMDB no está configurada")
        val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=es-MX"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al conectar con TMDB (Código ${response.code})")
            val bodyStr = response.body?.string() ?: throw IOException("Respuesta vacía de TMDB")
            val json = JSONObject(bodyStr)
            return@withContext json.getString("title")
        }
    }
}
