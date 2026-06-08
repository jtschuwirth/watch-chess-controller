package com.watchchess.controller.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Text

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

private data class PieceGroup(val label: String, val squares: List<String>)

/**
 * Parses the board state payload from the phone into white and black piece groups.
 *
 * Payload format: "W:Ke1,Qd1,Ra1,...|B:Ke8,Qd8,Ra8,..."
 * Returns a map of "W" and "B" to their respective piece groups (ordered K Q R B N P).
 */
private fun parseBoardState(raw: String): Map<String, List<PieceGroup>> {
    val pieceNames = mapOf(
        'K' to "King", 'Q' to "Queen", 'R' to "Rook",
        'B' to "Bishop", 'N' to "Knight", 'P' to "Pawn"
    )
    val result = mutableMapOf<String, List<PieceGroup>>()

    for (section in raw.split("|")) {
        if (!section.contains(":")) continue
        val colorKey = section.substringBefore(":").trim()          // "W" or "B"
        val piecesStr = section.substringAfter(":").trim()          // "Ke1,Qd1,..."

        if (piecesStr.isEmpty()) {
            result[colorKey] = emptyList()
            continue
        }

        // Group piece tokens by piece character
        val byType = mutableMapOf<Char, MutableList<String>>()
        for (token in piecesStr.split(",")) {
            if (token.length < 3) continue
            val typeChar = token[0]
            val square = token.substring(1)
            byType.getOrPut(typeChar) { mutableListOf() }.add(square)
        }

        // Emit groups in canonical order
        val groups = mutableListOf<PieceGroup>()
        for (typeChar in listOf('K', 'Q', 'R', 'B', 'N', 'P')) {
            val squares = byType[typeChar] ?: continue
            val name = pieceNames[typeChar] ?: continue
            val label = if (squares.size == 1) name else "${name}s"
            groups += PieceGroup(label, squares)
        }
        result[colorKey] = groups
    }
    return result
}

// ---------------------------------------------------------------------------
// Colors (shared palette from ChessMoveScreen; re-declared here to avoid coupling)
// ---------------------------------------------------------------------------

private val BvColorBackground = Color(0xFF000000)
private val BvColorSurface    = Color(0xFF1A1A1A)
private val BvColorText       = Color(0xFFEEEEEE)
private val BvColorMuted      = Color(0xFF888888)
private val BvColorWhiteHdr   = Color(0xFF2D5A27)   // forest green – white pieces header
private val BvColorBlackHdr   = Color(0xFF37474F)   // slate – black pieces header
private val BvColorBack       = Color(0xFF8B0000)   // dark red – back button
private val BvColorRefresh    = Color(0xFF1A5276)   // dark navy – refresh button

// ---------------------------------------------------------------------------
// Screen composable
// ---------------------------------------------------------------------------

/**
 * Displays the current position of all pieces on the board.
 *
 * @param boardState Raw payload from the phone (null = loading, "UNAVAILABLE" = not supported).
 * @param onBack     Called when the user taps the back button.
 * @param onRefresh  Called when the user taps the refresh button to re-fetch board state.
 */
@Composable
fun BoardViewScreen(
    boardState: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BvColorBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header row: title + refresh
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Board",
                        color = BvColorText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    WideChipButton(
                        label = "\u21BB",   // ↻
                        color = BvColorRefresh,
                        modifier = Modifier.weight(0.6f)
                    ) { onRefresh() }
                }
            }

            when {
                boardState == null -> {
                    // Loading
                    item {
                        Text(
                            text = "Fetching\u2026",
                            color = BvColorMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                boardState == "UNAVAILABLE" -> {
                    item {
                        Text(
                            text = "Not available\nfor this game mode",
                            color = BvColorMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                else -> {
                    val parsed = parseBoardState(boardState)
                    val whiteGroups = parsed["W"] ?: emptyList()
                    val blackGroups = parsed["B"] ?: emptyList()

                    // White pieces section
                    item {
                        SectionHeader(label = "\u25A1 White", color = BvColorWhiteHdr)
                    }
                    if (whiteGroups.isEmpty()) {
                        item { EmptyGroupRow() }
                    } else {
                        items(whiteGroups) { group ->
                            PieceGroupRow(group)
                        }
                    }

                    item { Spacer(modifier = Modifier.height(4.dp)) }

                    // Black pieces section
                    item {
                        SectionHeader(label = "\u25A0 Black", color = BvColorBlackHdr)
                    }
                    if (blackGroups.isEmpty()) {
                        item { EmptyGroupRow() }
                    } else {
                        items(blackGroups) { group ->
                            PieceGroupRow(group)
                        }
                    }
                }
            }

            // Back button
            item { Spacer(modifier = Modifier.height(4.dp)) }
            item {
                WideChipButton(
                    label = "\u2190 Back",
                    color = BvColorBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { onBack() }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(label: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(color, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PieceGroupRow(group: PieceGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = group.label,
            color = BvColorMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = group.squares.joinToString(", "),
            color = BvColorText,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.55f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun EmptyGroupRow() {
    Text(
        text = "(none)",
        color = BvColorMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}
