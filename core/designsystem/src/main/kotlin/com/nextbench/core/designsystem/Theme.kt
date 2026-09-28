package com.nextbench.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private fun nbMaterialLight(c: NbColors) = lightColorScheme(
    primary            = c.brandPink,
    onPrimary          = Color.White,
    secondary          = c.brandTeal,
    onSecondary        = Color.White,
    tertiary           = c.brandMint,
    background         = c.surfaceBase,
    onBackground       = c.ink,
    surface            = c.surfaceCard,
    onSurface          = c.ink,
    surfaceVariant     = c.surfaceSoft,
    onSurfaceVariant   = c.inkMuted,
    outline            = c.border,
    outlineVariant     = c.borderStrong,
    scrim              = c.overlayHeavy,
)

private fun nbMaterialDark(c: NbColors) = darkColorScheme(
    primary            = c.brandPink,
    onPrimary          = Color.White,
    secondary          = c.brandTeal,
    onSecondary        = Color.White,
    tertiary           = c.brandMint,
    background         = c.surfaceBase,
    onBackground       = c.ink,
    surface            = c.surfaceCard,
    onSurface          = c.ink,
    surfaceVariant     = c.surfaceSoft,
    onSurfaceVariant   = c.inkMuted,
    outline            = c.border,
    outlineVariant     = c.borderStrong,
    scrim              = c.overlayHeavy,
)

object NbTheme {
    val colors: NbColors
        @Composable @ReadOnlyComposable get() = LocalNbColors.current
}

@Composable
fun NbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) nbDarkColors() else nbLightColors()
    val materialScheme = if (darkTheme) nbMaterialDark(colors) else nbMaterialLight(colors)

    CompositionLocalProvider(LocalNbColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography  = nbTypography(),
            content     = content,
        )
    }
}
