package com.watchchess.controller.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

/**
 * Wear OS theme wrapper for the Chess Controller app.
 *
 * Wear Compose Material's [MaterialTheme] already ships with a dark-by-default
 * palette appropriate for AMOLED watch displays.  We use it as-is with no
 * colour overrides so that the system accent colour (blue on Wear OS) is
 * preserved for action buttons while the background stays true-black.
 */
@Composable
fun ChessControllerTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
