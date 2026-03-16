package com.example.chessmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Key layout defined as data — a single source of truth for what keys exist.
// Each app renders using its own toolkit (Wear Compose / Material3) but the structure is shared.
enum class KeyRole { PIECE, FILE, RANK, SPECIAL }
data class KeyGroup(val labels: List<String>, val role: KeyRole)

val CHESS_KEY_LAYOUT: List<KeyGroup> = listOf(
    KeyGroup(listOf("K", "Q", "R", "B", "N"), KeyRole.PIECE),
    KeyGroup(listOf("a", "b", "c", "d"), KeyRole.FILE),
    KeyGroup(listOf("e", "f", "g", "h"), KeyRole.FILE),
    KeyGroup(listOf("1", "2", "3", "4"), KeyRole.RANK),
    KeyGroup(listOf("5", "6", "7", "8"), KeyRole.RANK),
    KeyGroup(listOf("x", "=", "+", "#"), KeyRole.SPECIAL),
)

@Composable
private fun keyColor(role: KeyRole): Color = when (role) {
    KeyRole.PIECE   -> Color(0xFF1B5E20)
    KeyRole.FILE    -> Color(0xFF0D2B4E)
    KeyRole.RANK    -> Color(0xFF3E2723)
    KeyRole.SPECIAL -> Color(0xFF311B92)
}

@Composable
fun ChessKeyboardPanel(
    currentInput: String,
    onAppend: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
    onResign: () -> Unit,
    onDrawOffer: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Input display row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = currentInput,
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("e.g. Ne4", fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(
                onClick = onSend,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send move")
            }
        }

        // Error text
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(Modifier.height(2.dp))

        // Key rows from layout
        CHESS_KEY_LAYOUT.forEach { group ->
            val color = keyColor(group.role)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                group.labels.forEach { label ->
                    FilledTonalButton(
                        onClick = { onAppend(label) },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = color,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Castling row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("O-O", "O-O-O").forEach { label ->
                OutlinedButton(
                    onClick = { onAppend(label) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Edit row: backspace + clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalButton(
                onClick = onBackspace,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text("⌫", fontSize = 16.sp) }

            FilledTonalButton(
                onClick = onClear,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) { Text("CLR", fontWeight = FontWeight.Bold) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        // Game controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalButton(
                onClick = onResign,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("RESIGN", fontWeight = FontWeight.Bold, fontSize = 12.sp) }

            FilledTonalButton(
                onClick = onDrawOffer,
                modifier = Modifier.weight(1f).height(44.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFF4E6E00),
                    contentColor = Color.White
                )
            ) { Text("DRAW", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(8.dp))
    }
}
