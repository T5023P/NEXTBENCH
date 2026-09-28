package com.nextbench.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class NbColors(
    val surfaceBase: Color,
    val surfaceSoft: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val border: Color,
    val borderStrong: Color,
    val glassBg: Color,
    val glassBorder: Color,
    val navBg: Color,
    val brandTeal: Color,
    val brandPink: Color,
    val brandPinkSoft: Color,
    val brandMint: Color,
    val shadowBrand: Color,
    val overlay: Color,
    val overlayHeavy: Color,
    val isDark: Boolean,
)

fun nbLightColors() = NbColors(
    surfaceBase      = Color(0xFFF5F5F7),
    surfaceSoft      = Color(0xFFEBEBED),
    surfaceCard      = Color(0xFFFFFFFF),
    surfaceElevated  = Color(0xFFFFFFFF),
    ink              = Color(0xFF1D1D1F),
    inkMuted         = Color(0x801D1D1F),
    inkFaint         = Color(0x141D1D1F),
    border           = Color(0x141D1D1F),
    borderStrong     = Color(0x261D1D1F),
    glassBg          = Color(0xB8FFFFFF),
    glassBorder      = Color(0x47FFFFFF),
    navBg            = Color(0xE0F5F5F7),
    brandTeal        = Color(0xFF0071E3),
    brandPink        = Color(0xFFFF375F),
    brandPinkSoft    = Color(0xFFFF6482),
    brandMint        = Color(0xFF34C759),
    shadowBrand      = Color(0x2E0071E3),
    overlay          = Color(0x2E1D1D1F),
    overlayHeavy     = Color(0x941D1D1F),
    isDark           = false,
)

fun nbDarkColors() = NbColors(
    surfaceBase      = Color(0xFF0D0F14),
    surfaceSoft      = Color(0xFF131722),
    surfaceCard      = Color(0xFF131722),
    surfaceElevated  = Color(0xFF171C28),
    ink              = Color(0xFFFFFFFF),
    inkMuted         = Color(0x8CFFFFFF),
    inkFaint         = Color(0x0FFFFFFF),
    border           = Color(0x0FFFFFFF),
    borderStrong     = Color(0x1FFFFFFF),
    glassBg          = Color(0xD9131722),
    glassBorder      = Color(0x0FFFFFFF),
    navBg            = Color(0xE60D0F14),
    brandTeal        = Color(0xFF0A84FF),
    brandPink        = Color(0xFFFF375F),
    brandPinkSoft    = Color(0xFFFF6482),
    brandMint        = Color(0xFF30D158),
    shadowBrand      = Color.Transparent,
    overlay          = Color(0x73000000),
    overlayHeavy     = Color(0xCC000000),
    isDark           = true,
)

val LocalNbColors = staticCompositionLocalOf { nbLightColors() }
