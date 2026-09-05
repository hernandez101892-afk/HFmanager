package com.tuusuario.hfmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.tuusuario.hfmanager.service.TelegramBotService
import com.tuusuario.hfmanager.ui.SettingsScreen
import com.tuusuario.hfmanager.ui.theme.HFManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

        val serviceIntent = Intent(this, TelegramBotService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            HFManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SettingsScreen()
                }
            }
        }
    }
}
