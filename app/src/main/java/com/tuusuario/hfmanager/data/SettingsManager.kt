package com.tuusuario.hfmanager.data

import android.content.Context
import androidx.core.content.edit // 🚀 Importación para la función de extensión KTX SharedPreferences.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import com.tuusuario.hfmanager.BuildConfig

class SettingsManager(context: Context) {
    private val tag = "SettingsManager"
    private var prefs = createEncryptedPrefs(context)

    /**
     * 🛡️ CREACIÓN SEGURA DE PREFERENCIAS CON AUTO-RECUPERACIÓN
     * Si el Keystore de Android reporta una falla de verificación (AEADBadTagException / VERIFICATION_FAILED),
     * este bloque atrapa la excepción, elimina el archivo físico corrupto y genera uno nuevo desde cero.
     */
    private fun createEncryptedPrefs(context: Context): EncryptedSharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "hf_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as EncryptedSharedPreferences
        } catch (e: Exception) {
            Log.e(tag, "💥 Error de descifrado en Keystore. Reconstruyendo preferencias...", e)
            try {
                // Borrar el archivo XML corrupto físicamente
                context.deleteSharedPreferences("hf_secure_prefs")

                // Intentar recrear de nuevo con una base limpia
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    "hf_secure_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ) as EncryptedSharedPreferences
            } catch (fatalException: Exception) {
                Log.e(tag, "🚨 Error crítico irrecuperable de Keystore", fatalException)
                throw fatalException
            }
        }
    }

    /**
     * 🧹 FUNCIÓN DE AUTO-LIMPIEZA DE CREDENCIALES (SANTIZADOR UNIVERSAL)
     */
    private fun cleanValue(value: String): String {
        return value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    init {
        // =========================================================================
        // 🛡️ AUTOCOMPLETADO SEGURO DE CREDENCIALES (PRIMERA INSTALACIÓN)
        // =========================================================================
        if (getToken().isEmpty() && BuildConfig.IA_ACCESS_KEY.isNotEmpty()) {
            saveToken(BuildConfig.IA_ACCESS_KEY)
        }
        if ((getTargetRepoType().isEmpty() || (getTargetRepoType() == "dataset")) && BuildConfig.IA_SECRET_KEY.isNotEmpty()) {
            saveTargetRepoType(BuildConfig.IA_SECRET_KEY)
        }
        if (getTargetRepo().isEmpty() && BuildConfig.IA_ITEM_ID.isNotEmpty()) {
            saveTargetRepo(BuildConfig.IA_ITEM_ID)
        }
        if (getTmdbKey().isEmpty() && BuildConfig.TMDB_API_KEY.isNotEmpty()) {
            saveTmdbKey(BuildConfig.TMDB_API_KEY)
        }
        if (getTelegramToken().isEmpty() && BuildConfig.TELEGRAM_BOT_TOKEN.isNotEmpty()) {
            saveTelegramToken(BuildConfig.TELEGRAM_BOT_TOKEN)
        }
        if (getTelegramChatId().isEmpty() && BuildConfig.TELEGRAM_CHAT_ID.isNotEmpty()) {
            saveTelegramChatId(BuildConfig.TELEGRAM_CHAT_ID)
        }
        if (getHfBackupToken().isEmpty() && BuildConfig.HF_BACKUP_TOKEN.isNotEmpty()) {
            saveHfBackupToken(BuildConfig.HF_BACKUP_TOKEN)
        }
        if (getHfBackupRepo().isEmpty() && BuildConfig.HF_BACKUP_REPO.isNotEmpty()) {
            saveHfBackupRepo(BuildConfig.HF_BACKUP_REPO)
        }
    }

    // =========================================================================
    // 🚀 SETTERS OPTIMIZADOS USANDO LA FUNCIÓN DE EXTENSIÓN KTX 'prefs.edit {}'
    // =========================================================================

    // --- INTERNET ARCHIVE (Histórico de nombres de variables) ---
    fun saveToken(token: String) = prefs.edit { putString("HF_TOKEN", cleanValue(token)) }
    fun getToken(): String = cleanValue(prefs.getString("HF_TOKEN", "") ?: "")

    fun saveTargetRepoType(type: String) = prefs.edit { putString("TARGET_REPO_TYPE", cleanValue(type)) }
    fun getTargetRepoType(): String {
        val raw = prefs.getString("TARGET_REPO_TYPE", "dataset") ?: "dataset"
        return if (raw == "dataset") raw else cleanValue(raw)
    }

    fun saveTargetRepo(repo: String) = prefs.edit { putString("TARGET_REPO", cleanValue(repo)) }
    fun getTargetRepo(): String = cleanValue(prefs.getString("TARGET_REPO", "") ?: "")

    // --- CONFIGURACIÓN GENERAL ---
    fun saveTmdbKey(key: String) = prefs.edit { putString("TMDB_API_KEY", cleanValue(key)) }
    fun getTmdbKey(): String = cleanValue(prefs.getString("TMDB_API_KEY", "") ?: "")

    fun saveTelegramToken(token: String) = prefs.edit { putString("TELEGRAM_BOT_TOKEN", cleanValue(token)) }
    fun getTelegramToken(): String = cleanValue(prefs.getString("TELEGRAM_BOT_TOKEN", "") ?: "")

    fun saveTelegramChatId(chatId: String) = prefs.edit { putString("TELEGRAM_CHAT_ID", cleanValue(chatId)) }
    fun getTelegramChatId(): String = cleanValue(prefs.getString("TELEGRAM_CHAT_ID", "") ?: "")

    fun saveTargetFolderUri(uriString: String) = prefs.edit { putString("TARGET_FOLDER_URI", uriString) }
    fun getTargetFolderUri(): String = prefs.getString("TARGET_FOLDER_URI", "") ?: ""

    // --- HUGGING FACE BACKUP STREAM ---
    fun saveHfBackupToken(token: String) = prefs.edit { putString("HF_BACKUP_TOKEN", cleanValue(token)) }
    fun getHfBackupToken(): String = cleanValue(prefs.getString("HF_BACKUP_TOKEN", "") ?: "")

    fun saveHfBackupRepo(repo: String) = prefs.edit { putString("HF_BACKUP_REPO", cleanValue(repo)) }
    fun getHfBackupRepo(): String = cleanValue(prefs.getString("HF_BACKUP_REPO", "") ?: "")
}
