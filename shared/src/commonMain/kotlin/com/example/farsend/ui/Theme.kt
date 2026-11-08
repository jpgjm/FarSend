package com.example.farsend.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

/**
 * App-wide theme.
 *
 * One theme for Android, desktop and iOS: M3 Expressive's `MaterialExpressiveTheme`, with
 * expressive motion (slightly bouncy springs).
 * Colors come from the platform (dynamic color on Android, standard light/dark on desktop and iOS).
 * Shapes and typography keep the MaterialTheme defaults (Shapes() / Typography()).
 *
 * `MaterialExpressiveTheme` is `@ExperimentalMaterial3ExpressiveApi` in material3 1.9.0-alpha04 (checked in the jar).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
