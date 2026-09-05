// build.gradle.kts (Raíz del Proyecto - Project: HFManager)
plugins {
    id("com.android.application") version "9.2.1" apply false
    //  SE ELIMINA 'org.jetbrains.kotlin.android' ya que AGP 9.x lo gestiona nativamente por defecto
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}