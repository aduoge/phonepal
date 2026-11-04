package dev.phonepal.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1E6B52)

/** Stable PhonePal colors; readable light/dark surfaces independent of the phone wallpaper. */
@Composable
fun PhonePalTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF8BD6B5), primaryContainer = Color(0xFF204D3C),
        background = Color(0xFF121815), surface = Color(0xFF1B231F),
        surfaceContainerLow = Color(0xFF202A24), outlineVariant = Color(0xFF35463C),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White,
        primaryContainer = Color(0xFFE1F0E8), onPrimaryContainer = Color(0xFF174332),
        secondaryContainer = Color(0xFFEDF3EE), onSecondaryContainer = Color(0xFF254735),
        background = Color(0xFFF7F9F6), surface = Color.White,
        surfaceContainerLow = Color(0xFFF0F4EF), surfaceVariant = Color(0xFFF0F4EF),
        onSurface = Color(0xFF202C25), onSurfaceVariant = Color(0xFF637168),
        outlineVariant = Color(0xFFE0E7DF),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    ), content = content)
}
