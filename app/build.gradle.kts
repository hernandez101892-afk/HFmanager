// =====================================================================
// 1. IMPORTACIONES: VAN AL INICIO ABSOLUTO
// =====================================================================
import java.util.Properties
import java.io.FileInputStream

// =====================================================================
// 2. PLUGINS (Heredados de la Raíz - SIN DECLARAR VERSIONES AQUÍ)
// =====================================================================
plugins {
    id("com.android.application")             // Hereda la versión 9.2.1 global
    //  SE ELIMINA 'org.jetbrains.kotlin.android' ya que AGP 9.x compila Kotlin nativamente
    id("org.jetbrains.kotlin.plugin.compose") // Hereda la versión 2.2.10 para el compilador de Compose
}

// =====================================================================
// 3. CARGA SEGURA Y AUTOMÁTICA DEL ARCHIVO LOCAL.PROPERTIES
// =====================================================================
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        load(FileInputStream(propertiesFile))
    }
}

// Función auxiliar para formatear de forma segura las cadenas de texto
fun getSafeProperty(key: String): String {
    val rawValue = localProperties.getProperty(key) ?: ""
    val cleanValue = rawValue.trim()
    return if (cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) {
        cleanValue
    } else {
        "\"$cleanValue\""
    }
}

// =====================================================================
// 4. CONFIGURACIÓN DEL MOTOR ANDROID (Sintaxis Universal 100% Compatible)
// =====================================================================
@Suppress("DEPRECATION")
android {
    namespace = "com.tuusuario.hfmanager" // <-- Asegúrate de que coincida con tu paquete real
    compileSdk = 34 // 🚀 SDK 34 para estabilidad garantizada del Bot 24/7 (Evita restricciones de Background en Android 15+)

    defaultConfig {
        applicationId = "com.tuusuario.hfmanager"
        minSdk = 26
        targetSdk = 34 // 🚀 Evita cierres forzados del sistema en segundo plano
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 🚀 INYECCIÓN SEGURA DE CREDENCIALES AUTOMÁTICAS (BUILDCONFIG)
        buildConfigField("String", "IA_ACCESS_KEY", getSafeProperty("ia.access.key"))
        buildConfigField("String", "IA_SECRET_KEY", getSafeProperty("ia.secret.key"))
        buildConfigField("String", "IA_ITEM_ID", getSafeProperty("ia.item.id"))
        buildConfigField("String", "TMDB_API_KEY", getSafeProperty("tmdb.api.key"))
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", getSafeProperty("telegram.bot.token"))
        buildConfigField("String", "TELEGRAM_CHAT_ID", getSafeProperty("telegram.chat.id"))
        buildConfigField("String", "HF_BACKUP_TOKEN", getSafeProperty("hf.backup.token"))
        buildConfigField("String", "HF_BACKUP_REPO", getSafeProperty("hf.backup.repo"))
    }

    // Activa la autogeneración de la clase BuildConfig y habilita Jetpack Compose
    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// =====================================================================
// 5. CONFIGURACIÓN DEL COMPILADOR DE KOTLIN (Estándar Kotlin 2.x)
// =====================================================================
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// =====================================================================
// 6. DEPENDENCIAS (Actualizadas con Compose BOM 2024 para evitar errores)
// =====================================================================
dependencies {
    // BOM de Compose moderno compatible con tu versión de Kotlin
    implementation(platform(libs.androidx.compose.bom)) 
    
    // UI de Jetpack Compose (Adquieren automáticamente la versión correcta desde el BOM)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // 🚀 La integración correcta de Compose con las actividades
    implementation(libs.androidx.activity.compose) 
    
    // Core y ciclo de vida de Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Red, archivos y utilidades
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation("org.json:json:20260814")

    // Cifrado y seguridad
    implementation(libs.androidx.security.crypto) // Resuelve EncryptedSharedPreferences y MasterKey de forma segura

    // Íconos del sistema extendidos
    implementation(libs.androidx.compose.material.icons.extended) // Resuelve Movie, CloudSync, VpnKey, Terminal, etc.

    // Pruebas unitarias e instrumentación
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}