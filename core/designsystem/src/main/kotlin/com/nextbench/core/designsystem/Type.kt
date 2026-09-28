package com.nextbench.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs,
)

private fun gf(name: String) = GoogleFont(name, bestEffort = true)

val InterFamily = androidx.compose.ui.text.font.FontFamily(
    Font(gf("Inter"), provider, FontWeight.Light),
    Font(gf("Inter"), provider, FontWeight.Normal),
    Font(gf("Inter"), provider, FontWeight.Medium),
    Font(gf("Inter"), provider, FontWeight.SemiBold),
    Font(gf("Inter"), provider, FontWeight.Bold),
)

val PlayfairFamily = androidx.compose.ui.text.font.FontFamily(
    Font(gf("Playfair Display"), provider, FontWeight.Normal),
    Font(gf("Playfair Display"), provider, FontWeight.Bold),
    Font(gf("Playfair Display"), provider, FontWeight.Normal, FontStyle.Italic),
)

fun nbTypography() = Typography(
    displayLarge   = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Bold,     fontSize = 57.sp),
    displayMedium  = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Bold,     fontSize = 45.sp),
    displaySmall   = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Normal,   fontSize = 36.sp),
    headlineLarge  = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Bold,     fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Bold,     fontSize = 28.sp),
    headlineSmall  = TextStyle(fontFamily = PlayfairFamily, fontWeight = FontWeight.Normal,   fontSize = 24.sp),
    titleLarge     = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium    = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall     = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Medium,   fontSize = 14.sp),
    bodyLarge      = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Normal,   fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Normal,   fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Light,    fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.sp),
    labelMedium    = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Medium,   fontSize = 12.sp, letterSpacing = 0.sp),
    labelSmall     = TextStyle(fontFamily = InterFamily,    fontWeight = FontWeight.Medium,   fontSize = 11.sp, letterSpacing = 0.sp),
)
