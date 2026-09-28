package com.nextbench.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private const val PreferencesName = "nextbench_preferences"
private const val PushPermissionRequestedKey = "push_permission_requested"

/** Requests Android's notification permission once after a real signed-in session exists. */
@Composable
internal fun PushPermissionCoordinator(userId: String?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    var requested by remember {
        mutableStateOf(
            context.getSharedPreferences(PreferencesName, android.content.Context.MODE_PRIVATE)
                .getBoolean(PushPermissionRequestedKey, false),
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requested = true
    }

    LaunchedEffect(userId) {
        if (userId.isNullOrBlank() || requested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requested = true
            return@LaunchedEffect
        }
        delay(700)
        context.getSharedPreferences(PreferencesName, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PushPermissionRequestedKey, true)
            .apply()
        requested = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
