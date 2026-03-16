package com.watchchess.controller.presentation

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.watchchess.controller.model.MoveInputState
import com.watchchess.controller.model.MovePair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

private const val PATH_CHESS_MOVE = "/chess/move"
private const val PATH_CHESS_ERROR = "/chess/error"
private const val PATH_CHESS_SYNC = "/chess/sync"
private const val PATH_CHESS_SYNC_RESPONSE = "/chess/sync/response"
private const val PATH_CHESS_GAME_START = "/chess/game/start"
private const val PATH_CHESS_GAME_END = "/chess/game/end"

class ChessMoveViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(MoveInputState())
    val uiState: StateFlow<MoveInputState> = _uiState.asStateFlow()

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        when (event.path) {
            PATH_CHESS_GAME_START -> {
                val color = String(event.data, Charsets.UTF_8).trim()
                Log.d("ChessMoveViewModel", "Game started, player color: $color")
                _uiState.update { it.copy(playerColor = color, moveHistory = emptyList(), currentInput = "") }
                // Wake the screen immediately when playing White — it's our turn from move 1
                if (color == "W") wakeScreen()
            }
            PATH_CHESS_MOVE -> {
                val response = String(event.data, Charsets.UTF_8).trim()
                Log.d("ChessMoveViewModel", "Phone response move: '$response'")
                _uiState.update { it.receiveResponse(response, isError = false) }
                // An incoming move always means it is now the player's turn — wake the screen
                wakeScreen()
            }
            PATH_CHESS_ERROR -> {
                val error = String(event.data, Charsets.UTF_8).trim()
                Log.w("ChessMoveViewModel", "Phone error: '$error'")
                _uiState.update { it.receiveResponse(error, isError = true) }
            }
            PATH_CHESS_SYNC_RESPONSE -> {
                val syncPayload = String(event.data, Charsets.UTF_8).trim()
                Log.d("ChessMoveViewModel", "Sync response: '$syncPayload'")
                _uiState.update { it.applySync(syncPayload) }
            }
            PATH_CHESS_GAME_END -> {
                val payload = String(event.data, Charsets.UTF_8).trim()
                if (payload.isBlank()) {
                    // Manual end from phone — reset immediately
                    Log.d("ChessMoveViewModel", "Game ended by phone (manual)")
                    _uiState.update { MoveInputState() }
                } else {
                    // Game ended with a result — show game-over screen
                    Log.d("ChessMoveViewModel", "Game ended with result: $payload")
                    _uiState.update { it.copy(playerColor = null, gameResult = payload) }
                    wakeScreen()
                }
            }
        }
    }

    init {
        Wearable.getMessageClient(app).addListener(messageListener)
    }

    override fun onCleared() {
        super.onCleared()
        Wearable.getMessageClient(getApplication<Application>()).removeListener(messageListener)
    }

    fun onAppend(char: String) = _uiState.update { it.append(char) }
    fun onBackspace() = _uiState.update { it.backspace() }
    fun onClear() = _uiState.update { it.clear() }
    fun onDismissResult() = _uiState.update { MoveInputState() }

    fun onSend() {
        val move = _uiState.value.currentInput.trim()
        if (move.isBlank()) return
        _uiState.update { it.send() }
        viewModelScope.launch { sendMoveToPhone(move) }
    }

    fun onSync() {
        viewModelScope.launch { sendSyncRequest() }
    }

    /**
     * Wakes the watch screen so the user can immediately input a move.
     *
     * Uses a timed [PowerManager] wake lock with [PowerManager.ACQUIRE_CAUSES_WAKEUP] so the
     * screen lights up even if the watch was in ambient/sleep mode.  The lock auto-releases after
     * 10 seconds — no manual cleanup needed.
     */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK is the only API that wakes a sleeping screen
    private fun wakeScreen() {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WatchChess:WakeScreen"
        )
        wl.acquire(10_000L) // auto-releases after 10 s
        Log.d("ChessMoveViewModel", "Screen wake lock acquired")
    }

    private suspend fun sendMoveToPhone(move: String) {
        try {
            val nodes = Wearable.getNodeClient(getApplication<Application>())
                .connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("ChessMoveViewModel", "No connected nodes found")
                return
            }
            val payload = move.toByteArray(Charsets.UTF_8)
            nodes.forEach { node ->
                Wearable.getMessageClient(getApplication<Application>())
                    .sendMessage(node.id, PATH_CHESS_MOVE, payload)
                    .await()
                Log.d("ChessMoveViewModel", "Sent '$move' to ${node.displayName}")
            }
        } catch (e: Exception) {
            Log.e("ChessMoveViewModel", "Failed to send move '$move': ${e.message}")
        }
    }

    private suspend fun sendSyncRequest() {
        try {
            val nodes = Wearable.getNodeClient(getApplication<Application>())
                .connectedNodes.await()
            if (nodes.isEmpty()) {
                Log.w("ChessMoveViewModel", "No connected nodes for sync")
                return
            }
            nodes.forEach { node ->
                Wearable.getMessageClient(getApplication<Application>())
                    .sendMessage(node.id, PATH_CHESS_SYNC, ByteArray(0))
                    .await()
                Log.d("ChessMoveViewModel", "Sync request sent to ${node.displayName}")
            }
        } catch (e: Exception) {
            Log.e("ChessMoveViewModel", "Sync request failed: ${e.message}")
        }
    }
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
private val ColorSync = Color(0xFF1A5276)        // dark navy       – sync
private val ColorText = Color(0xFFEEEEEE)
private val ColorInputBg = Color(0xFF111111)
private val ColorHistory = Color(0xFF252525)
private val ColorWhiteMove = Color(0xFFEEEEEE)
private val ColorBlackMove = Color(0xFFAAAAAA)
private val ColorPending = Color(0xFF555555)
private val ColorStatusActive = Color(0xFF1B5E20)    // dark green – game active
private val ColorStatusIdle = Color(0xFF212121)      // near-black – no game
private val ColorMyTurn = Color(0xFF1B5E20)          // green – your turn
private val ColorOpponentTurn = Color(0xFF37474F)    // slate – opponent's turn

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
            // ---- Game status banner ----
            item {
                GameStatusBanner(
                    playerColor = state.playerColor,
                    gameActive = state.gameActive,
                    gameOver = state.gameOver
                )
            }

            if (state.gameOver) {
                item {
                    GameOverContent(
                        result = state.gameResult!!,
                        onDismiss = viewModel::onDismissResult
                    )
                }
            } else if (state.gameActive) {
                // ---- Turn / last-move status ----
                item {
                    TurnStatusBar(isMyTurn = state.isMyTurn, lastMove = state.lastMove)
                }

                // ---- Current input display + send ----
                item {
                    CurrentInputDisplay(input = state.currentInput, onSend = viewModel::onSend)
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
            }

            // Row – sync (always visible)
            item {
                WideChipButton(
                    label = "SYNC",
                    color = ColorSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) { viewModel.onSync() }
            }

            if (state.gameActive) {
                // Row – backspace / clear
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
                    }
                }

                // ---- Move history ----
                if (state.moveHistory.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    item {
                        MoveHistoryHeader(playerColor = state.playerColor)
                    }
                    items(
                        items = state.moveHistory.reversed(),
                        key = { it.number }
                    ) { pair ->
                        MoveHistoryItem(pair = pair)
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Game status banner
// ---------------------------------------------------------------------------

private val ColorStatusGameOver = Color(0xFF4A148C)   // deep purple – game over

@Composable
private fun GameStatusBanner(playerColor: String?, gameActive: Boolean, gameOver: Boolean = false) {
    val bgColor = when {
        gameOver -> ColorStatusGameOver
        gameActive -> ColorStatusActive
        else -> ColorStatusIdle
    }
    val text = when {
        gameOver -> "Game Over"
        playerColor == "W" -> "Game active \u25A1 White"   // □ White
        playerColor == "B" -> "Game active \u25A0 Black"   // ■ Black
        else -> "No game active"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .background(bgColor, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (gameActive || gameOver) Color.White else ColorText.copy(alpha = 0.45f),
            fontSize = 12.sp,
            fontWeight = if (gameActive || gameOver) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Game over content (result message + back-to-home button)
// ---------------------------------------------------------------------------

@Composable
private fun GameOverContent(result: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = result,
            color = ColorText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(ColorSurface, shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
        WideChipButton(
            label = "Back to Home",
            color = ColorStatusActive,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss
        )
    }
}

// ---------------------------------------------------------------------------
// Turn / last-move status bar
// ---------------------------------------------------------------------------

@Composable
private fun TurnStatusBar(isMyTurn: Boolean, lastMove: String?) {
    val bgColor = if (isMyTurn) ColorMyTurn else ColorOpponentTurn
    val turnLabel = if (isMyTurn) "Your turn" else "Opponent's turn"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(bgColor, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = turnLabel,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        if (lastMove != null) {
            Text(
                text = lastMove,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Current input display
// ---------------------------------------------------------------------------

@Composable
private fun CurrentInputDisplay(input: String, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
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
        Button(
            onClick = onSend,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(backgroundColor = ColorSend)
        ) {
            Text(
                text = "\u27A4",   // ➤
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
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
// Move history header
// ---------------------------------------------------------------------------

@Composable
private fun MoveHistoryHeader(playerColor: String? = null) {
    val whiteLabel = if (playerColor == "W") "White (You)" else "White"
    val blackLabel = if (playerColor == "B") "Black (You)" else "Black"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#",
            color = ColorText.copy(alpha = 0.35f),
            fontSize = 10.sp,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = whiteLabel,
            color = ColorText.copy(alpha = 0.45f),
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = blackLabel,
            color = ColorText.copy(alpha = 0.45f),
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// Move history row  (#N  white  black)
// ---------------------------------------------------------------------------

@Composable
private fun MoveHistoryItem(pair: MovePair) {
    val blackColor = when {
        pair.black == null -> ColorPending
        else -> ColorBlackMove
    }
    val blackText = pair.black ?: "…"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(ColorHistory, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${pair.number}.",
            color = ColorText.copy(alpha = 0.45f),
            fontSize = 11.sp,
            modifier = Modifier.width(22.dp)
        )
        Text(
            text = pair.white,
            color = ColorWhiteMove,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = blackText,
            color = blackColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
