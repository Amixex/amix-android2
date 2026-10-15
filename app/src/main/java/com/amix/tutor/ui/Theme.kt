package com.amix.tutor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF050505),
    background = Color(0xFF050505),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF181818),
    outline = Color(0xFF2A2A2A)
)

@Composable
fun Theme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme() || true) Dark else Dark
    MaterialTheme(colorScheme = scheme, content = content)
}