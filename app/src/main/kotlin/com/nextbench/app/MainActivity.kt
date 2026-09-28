package com.nextbench.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import com.nextbench.core.designsystem.NbTheme
import dagger.hilt.android.AndroidEntryPoint

private const val PreferencesName = "nextbench_preferences"
private const val DarkThemeKey = "dark_theme"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pendingExternalIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingExternalIntent = intent.takeIf(Intent::isNextBenchExternalIntent)
        val preferences = getSharedPreferences(PreferencesName, MODE_PRIVATE)
        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            var darkThemeOverride by rememberSaveable {
                mutableStateOf(
                    preferences.getBoolean(DarkThemeKey, false)
                        .takeIf { preferences.contains(DarkThemeKey) },
                )
            }
            val darkTheme = darkThemeOverride ?: systemDarkTheme

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            NbTheme(darkTheme = darkTheme) {
                NbAppShell(
                    onToggleTheme = {
                        val next = !darkTheme
                        darkThemeOverride = next
                        preferences.edit().putBoolean(DarkThemeKey, next).apply()
                    },
                    pendingExternalIntent = pendingExternalIntent,
                    onExternalIntentHandled = { handledIntent ->
                        if (pendingExternalIntent === handledIntent) pendingExternalIntent = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.isNextBenchExternalIntent()) pendingExternalIntent = intent
    }
}

internal fun Intent.isNextBenchExternalIntent(): Boolean =
    data != null || action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
