package com.keystone.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The Keystone design language is "hardened terminal":
 *
 *  - Dark-first, near-black backgrounds with a single muted teal accent
 *  - Monospace for anything cryptographic (fingerprints, public keys,
 *    cert IDs) so two devices side by side compare character-for-
 *    character without optical ambiguity
 *  - Generous spacing, no decorative gradients, no shadows that imply
 *    elevation we don't actually have
 *  - Dynamic color (Material You) is intentionally disabled — letting
 *    the user's wallpaper bleed into Keystone surfaces would be a
 *    fingerprinting vector and a visual distraction.
 *
 * Light mode exists as a fallback for outdoor scanning but the design
 * targets dark mode first.
 */
@Composable
fun KeystoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = KeystoneTypography,
        content = content,
    )
}

/** Single source of truth for accent colors used outside the M3 scheme. */
object KeystoneAccent {
    val Verified = Color(0xFF80E0C0)        // teal-green — trust established
    val VerifiedDeep = Color(0xFF143A2B)    // background panel for verified
    val Quarantine = Color(0xFFE08080)      // muted red — aborted / blocked
    val QuarantineDeep = Color(0xFF3A1414)
    val Pending = Color(0xFFE0B080)         // amber — in-flight
    val Inviter = Color(0xFF80E0C0)
    val Invitee = Color(0xFF80B0E0)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E3A5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E2F0),
    onPrimaryContainer = Color(0xFF0A1929),
    secondary = Color(0xFF4A6B8A),
    onSecondary = Color.White,
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE7EAF0),
    onSurfaceVariant = Color(0xFF3A4350),
    outline = Color(0xFF7A8290),
    error = Color(0xFF8B1A1A),
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF80E0C0),
    onPrimary = Color(0xFF06150F),
    primaryContainer = Color(0xFF1A3A2E),
    onPrimaryContainer = Color(0xFFC0F0E0),
    secondary = Color(0xFF9DB5CC),
    onSecondary = Color(0xFF0A1929),
    background = Color(0xFF06080B),
    onBackground = Color(0xFFE5EAEF),
    surface = Color(0xFF0C1014),
    onSurface = Color(0xFFE5EAEF),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFFA8B2BD),
    outline = Color(0xFF2A323D),
    error = Color(0xFFE57373),
    onError = Color(0xFF1A0A0A),
)

/**
 * Single typography ramp. Cryptographic identifiers (fingerprints, keys,
 * cert hashes) MUST use the monospace face — visual ambiguity between
 * 0/O or 1/l in a proportional face is how peers miss a one-character
 * relay-attack tell.
 */
private val KeystoneTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
    ),
)
