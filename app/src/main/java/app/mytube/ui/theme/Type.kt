package app.mytube.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mytube.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

// Tight tracking on large sizes and generous line height on body text.
val AppTypography = Typography(
    displayLarge = style(52, 56, FontWeight.Bold, -0.035),
    displayMedium = style(42, 46, FontWeight.Bold, -0.03),
    displaySmall = style(34, 40, FontWeight.SemiBold, -0.025),
    headlineLarge = style(30, 36, FontWeight.SemiBold, -0.022),
    headlineMedium = style(26, 32, FontWeight.SemiBold, -0.02),
    headlineSmall = style(22, 28, FontWeight.SemiBold, -0.015),
    titleLarge = style(19, 25, FontWeight.SemiBold, -0.012),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.008),
    titleSmall = style(14, 20, FontWeight.SemiBold, -0.004),
    bodyLarge = style(16, 24, FontWeight.Normal, -0.004),
    bodyMedium = style(14, 21, FontWeight.Normal, 0.0),
    bodySmall = style(12, 17, FontWeight.Normal, 0.004),
    labelLarge = style(14, 18, FontWeight.Medium, 0.0),
    labelMedium = style(12, 16, FontWeight.Medium, 0.01),
    labelSmall = style(11, 14, FontWeight.Medium, 0.02),
)
