package dev.insua.jellycast.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * JellyCast 是播客/音乐播放器,不是 Jellyfin 官方客户端的视频 UI。
 * 主色刻意选用暖色調(琥珀橙),避开 Material 默认蓝紫色系,
 * 呼应"听剧"场景(对标小宇宙的橙、Spotify 的绿——都不是系统默认色)。
 * 取色渐变(封面主色 → 播放页背景)留到 Task 20,本 Task 只搭主题骨架。
 */
private val SeedAmber = Color(0xFFFF7A33)

private val JellyCastLightColorScheme = lightColorScheme(
    primary = SeedAmber,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC7),
    onPrimaryContainer = Color(0xFF3A1400),
    secondary = Color(0xFF75574B),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF2E0D8),
    onSurfaceVariant = Color(0xFF52443C),
)

private val JellyCastDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB68C),
    onPrimary = Color(0xFF5A2100),
    primaryContainer = Color(0xFF7D3300),
    onPrimaryContainer = Color(0xFFFFDBC7),
    secondary = Color(0xFFE4BEAE),
    surface = Color(0xFF18120F),
    onSurface = Color(0xFFEDE0DB),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C3B8),
)

/**
 * 标题字重上调,贴近音乐/播客播放器"大字号曲名"的排版基调,
 * 与默认 Material Typography 拉开区分度。
 */
private val JellyCastTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}

@Composable
fun JellyCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> JellyCastDarkColorScheme
        else -> JellyCastLightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JellyCastTypography,
        content = content,
    )
}
