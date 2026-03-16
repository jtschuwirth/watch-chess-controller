package com.watchchess.controller.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchchess.controller.model.MoveInputState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class ChessMoveViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MoveInputState())
    val uiState: StateFlow<MoveInputState> = _uiState.asStateFlow()

    fun onAppend(char: String) = _uiState.update { it.append(char) }
    fun onBackspace() = _uiState.update { it.backspace() }
    fun onClear() = _uiState.update { it.clear() }
    fun onSend() = _uiState.update { it.send() }
}

// ---------------------------------------------------------------------------
// Colour constants (AMOLED-friendly)
// ---------------------------------------------------------------------------

private val ColorBackground = Color(0xFF000000)
private val ColorSurface = Color(0xFF1A1A1A)
private val ColorPiece = Color(0xFF2D5A27)       // forest green  – piece keys
private val ColorFile = Color(0xFF1E3A5F)        // dark blue      – file keys
private val ColorRank = Color(0xFF3D2B1F)        // dark brown     – rank keys
private val ColorSpecial = Color(0xFF4A2C6B)     // purple         – special keys
private val ColorAction = Color(0xFF8B0000)      // dark red        – backspace/clear
private val ColorSend = Color(0xFF1565C0)        // blue            – send
private val ColorText = Color(0xFFEEEEEE)
private val ColorInputBg = Color(0xFF111111)
private val ColorHistory = Color(0xFF252525)

// ---------------------------------------------------------------------------
// Root screen
// ---------------------------------------------------------------------------

@Composable
fun ChessMoveScreen(viewModel: ChessMoveViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---- Current input display ----
            item {
                CurrentInputDisplay(input = state.currentInput)
            }

            // ---- Button grid ----
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // Row 1 – piece selectors
            item {
                ButtonRow(
                    labels = listOf("K", "Q", "R", "B", "N"),
                    color = ColorPiece,
                    onTap = viewModel::onAppend
                )
            }

            // Row 2 – file letters
            item {
                ButtonRow(
                    labels = listOf("a", "b", "c", "d", "e", "f", "g", "h"),
                    color = ColorFile,
                    onTap = viewModel::onAppend
                )
            }

            // Row 3 – rank numbers
            item {
                ButtonRow(
                    labels = listOf("1", "2", "3", "4", "5", "6", "7", "8"),
                    color = ColorRank,
                    onTap = viewModel::onAppend
                )
            }

            // Row 4 – special symbols
            item {
                ButtonRow(
                    labels = listOf("x", "="),
                    color = ColorSpecial,
                    onTap = viewModel::onAppend
                )
            }

            // Row 5 – castling shortcuts (wide buttons)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    WideChipButton(
                        label = "O-O",
                        color = ColorSpecial,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.onAppend("O-O") }

                    WideChipButton(
                        label = "O-O-O",
                        color = ColorSpecial,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.onAppend("O-O-O") }
                }
            }

            // Row 6 – backspace / clear / send
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    WideChipButton(
                        label = "\u232B",          // ⌫
                        color = ColorAction,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.onBackspace() }

                    WideChipButton(
                        label = "CLR",
                        color = ColorAction,
                        modifier = Modifier.weight(1f)
                    ) { viewModel.onClear() }

                    WideChipButton(
                        label = "\u2713 SEND",     // ✓ SEND
                        color = ColorSend,
                        modifier = Modifier.weight(1.4f)
                    ) { viewModel.onSend() }
                }
            }

            // ---- Move history ----
            if (state.moveHistory.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                item {
                    Text(
                        text = "Move History",
                        color = ColorText.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(
                    items = state.moveHistory.reversed(),
                    key = { "${it}_${state.moveHistory.indexOf(it)}" }
                ) { move ->
                    MoveHistoryItem(
                        move = move,
                        index = state.moveHistory.size - state.moveHistory.reversed().indexOf(move)
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Current input display
// ---------------------------------------------------------------------------

@Composable
private fun CurrentInputDisplay(input: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(ColorInputBg, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (input.isEmpty()) {
            Text(
                text = "e.g. Ne4",
                color = ColorText.copy(alpha = 0.35f),
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = input,
                color = ColorText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable button row
// ---------------------------------------------------------------------------

@Composable
private fun ButtonRow(
    labels: List<String>,
    color: Color,
    maxButtonSize: androidx.compose.ui.unit.Dp = 32.dp,
    onTap: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth()
    ) {
        val n = labels.size
        val spacing = 3.dp
        val buttonSize = ((maxWidth - spacing * (n - 1)) / n).coerceAtMost(maxButtonSize)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
        ) {
            labels.forEach { label ->
                Button(
                    onClick = { onTap(label) },
                    modifier = Modifier.size(buttonSize),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(backgroundColor = color)
                ) {
                    Text(
                        text = label,
                        color = ColorText,
                        fontSize = if (label.length > 1) 8.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Wide chip-style button (fills available width)
// ---------------------------------------------------------------------------

@Composable
private fun WideChipButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = color)
    ) {
        Text(
            text = label,
            color = ColorText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Move history row
// ---------------------------------------------------------------------------

@Composable
private fun MoveHistoryItem(move: String, index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(ColorHistory, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$index.",
            color = ColorText.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = move,
            color = ColorText,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
