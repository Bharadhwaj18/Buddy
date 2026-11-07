package com.shrey.buddy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shrey.buddy.R

/** Plus Jakarta Sans (OFL, docs/licenses). One variable font file; weights map onto its wght axis. */
val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold),
)

private val base = Typography()

private fun TextStyle.j(weight: FontWeight, tracking: Float? = null) =
    copy(fontFamily = Jakarta, fontWeight = weight, letterSpacing = tracking?.sp ?: letterSpacing)

val BuddyTypography = Typography(
    displayLarge = base.displayLarge.j(FontWeight.ExtraBold, -1.5f),
    displayMedium = base.displayMedium.j(FontWeight.ExtraBold, -1f),
    displaySmall = base.displaySmall.j(FontWeight.ExtraBold, -0.5f),
    headlineLarge = base.headlineLarge.j(FontWeight.Bold, -0.5f),
    headlineMedium = base.headlineMedium.j(FontWeight.Bold, -0.25f),
    headlineSmall = base.headlineSmall.j(FontWeight.Bold),
    titleLarge = base.titleLarge.j(FontWeight.Bold),
    titleMedium = base.titleMedium.j(FontWeight.SemiBold),
    titleSmall = base.titleSmall.j(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.j(FontWeight.Normal),
    bodyMedium = base.bodyMedium.j(FontWeight.Normal),
    bodySmall = base.bodySmall.j(FontWeight.Normal),
    labelLarge = base.labelLarge.j(FontWeight.SemiBold),
    labelMedium = base.labelMedium.j(FontWeight.SemiBold),
    labelSmall = base.labelSmall.j(FontWeight.Medium),
)

/** Tabular figures so amounts and counts don't jitter as they change. */
val Numeric = TextStyle(fontFeatureSettings = "tnum")
