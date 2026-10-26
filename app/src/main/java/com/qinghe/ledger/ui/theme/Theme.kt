package com.qinghe.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val QingheGreen = Color(0xFF2F6B4F)
val QingheGreenDark = Color(0xFF1F4A36)
val QingheAmber = Color(0xFFB07A3C)
val QingheCream = Color(0xFFF7F4EC)
val ExpenseRed = Color(0xFFC05A5A)
val IncomeGreen = Color(0xFF2A9D8F)

private val LightColors = lightColorScheme(
    primary = QingheGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9DE),
    onPrimaryContainer = Color(0xFF153A28),
    secondary = QingheAmber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E5CB),
    onSecondaryContainer = Color(0xFF4A3115),
    tertiary = Color(0xFF577590),
    background = QingheCream,
    onBackground = Color(0xFF1C2822),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF1C2822),
    surfaceVariant = Color(0xFFEAE4D7),
    onSurfaceVariant = Color(0xFF5C665F),
    outline = Color(0xFFC9C2B4),
    outlineVariant = Color(0xFFDED8CA),
    error = Color(0xFFB3403C),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC9A8),
    onPrimary = Color(0xFF0D2B1C),
    primaryContainer = Color(0xFF1F4733),
    onPrimaryContainer = Color(0xFFD8E9DE),
    secondary = Color(0xFFDDAF75),
    onSecondary = Color(0xFF3E2A10),
    secondaryContainer = Color(0xFF533B1C),
    onSecondaryContainer = Color(0xFFF5E5CB),
    tertiary = Color(0xFF9DB8D2),
    background = Color(0xFF111713),
    onBackground = Color(0xFFE5EBE6),
    surface = Color(0xFF19201B),
    onSurface = Color(0xFFE5EBE6),
    surfaceVariant = Color(0xFF2B332D),
    onSurfaceVariant = Color(0xFFB8C2BA),
    outline = Color(0xFF828C84),
    outlineVariant = Color(0xFF3B443E),
    error = Color(0xFFE79A96),
    onError = Color(0xFF3F0A08)
)

private val QingheTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun QingheTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = QingheTypography,
        content = content
    )
}
