package com.watchchess.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.watchchess.controller.presentation.ChessMoveScreen
import com.watchchess.controller.presentation.theme.ChessControllerTheme

/**
 * Single-activity entry point for the Watch Chess Controller app.
 *
 * All UI is driven by [ChessMoveScreen] via Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChessControllerTheme {
                ChessMoveScreen()
            }
        }
    }
}
