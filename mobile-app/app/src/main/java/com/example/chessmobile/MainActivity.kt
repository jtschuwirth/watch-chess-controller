package com.example.chessmobile

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.Wearable

enum class AppScreen { PROVIDER_SELECTION, PROVIDER_CONFIG, IN_GAME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check initial watch connection state
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                MoveRepository.setWatchConnected(nodes.isNotEmpty())
            }

        setContent {
            MaterialTheme {
                ChessMobileScreen(
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessMobileScreen(onOpenAccessibilitySettings: () -> Unit) {
    val moves by MoveRepository.moves.collectAsStateWithLifecycle()
    val lastStatus by MoveRepository.lastStatus.collectAsStateWithLifecycle()
    val watchConnected by MoveRepository.watchConnected.collectAsStateWithLifecycle()
    val gameInfo by MoveRepository.gameInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var currentScreen by remember { mutableStateOf(AppScreen.PROVIDER_SELECTION) }
    var selectedProvider by remember { mutableStateOf<GameProvider?>(null) }

    // If game was ended externally, return to selection
    LaunchedEffect(gameInfo) {
        if (gameInfo == null && currentScreen == AppScreen.IN_GAME) {
            currentScreen = AppScreen.PROVIDER_SELECTION
        }
    }

    // Scroll to bottom when a new move arrives
    LaunchedEffect(moves.size) {
        if (moves.isNotEmpty()) listState.animateScrollToItem(moves.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Chess Move Log") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Watch connection status card
            WatchConnectionCard(watchConnected)

            // Accessibility service status card
            AccessibilityStatusCard(onOpenAccessibilitySettings)

            when (currentScreen) {
                AppScreen.PROVIDER_SELECTION -> {
                    ProviderSelectionScreen(
                        onProviderSelected = { provider ->
                            selectedProvider = provider
                            currentScreen = AppScreen.PROVIDER_CONFIG
                        }
                    )
                }

                AppScreen.PROVIDER_CONFIG -> {
                    when (selectedProvider) {
                        is RandomMovesProvider -> RandomMovesConfigScreen(
                            onStart = { config ->
                                MoveRepository.startGame(config, RandomMovesProvider, context)
                                currentScreen = AppScreen.IN_GAME
                            },
                            onBack = { currentScreen = AppScreen.PROVIDER_SELECTION }
                        )
                        is LichessProvider -> LichessConfigScreen(
                            onStart = { config ->
                                MoveRepository.startGame(config, LichessProvider, context)
                                currentScreen = AppScreen.IN_GAME
                            },
                            onBack = { currentScreen = AppScreen.PROVIDER_SELECTION }
                        )
                        else -> { currentScreen = AppScreen.PROVIDER_SELECTION }
                    }
                }

                AppScreen.IN_GAME -> {
                    if (gameInfo != null) {
                        // Game in progress banner
                        GameInfoBanner(
                            gameInfo = gameInfo!!,
                            onEndGame = {
                                MoveRepository.endGame(context)
                                currentScreen = AppScreen.PROVIDER_SELECTION
                            }
                        )

                        // Last status message
                        lastStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Move log header
                        MoveLogHeader()

                        // Move list
                        if (moves.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val hint = if (gameInfo!!.playerColor == ChessColor.WHITE)
                                    "You go first — send your opening move from the watch."
                                else
                                    "Waiting for opponent's first move…"
                                Text(
                                    text = hint,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(moves) { entry ->
                                    MoveRow(entry)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun ProviderSelectionScreen(onProviderSelected: (GameProvider) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Choose Game Provider",
            style = MaterialTheme.typography.titleMedium
        )

        // Random Moves card
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onProviderSelected(RandomMovesProvider) }
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Random Moves", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The phone plays random moves as your opponent. Good for testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Lichess card
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onProviderSelected(LichessProvider) }
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Lichess", style = MaterialTheme.typography.titleSmall)
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Coming soon",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Text(
                    "Play against a Lichess bot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun RandomMovesConfigScreen(
    onStart: (ProviderConfig.RandomMoves) -> Unit,
    onBack: () -> Unit
) {
    var colorChoice by remember { mutableStateOf(ColorChoice.WHITE) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Random Moves — Setup", style = MaterialTheme.typography.titleMedium)

        Text("Choose your color:", style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ColorChoice.entries.forEach { choice ->
                val label = choice.name.lowercase().replaceFirstChar { it.uppercase() }
                val selected = colorChoice == choice
                if (selected) {
                    Button(
                        onClick = { colorChoice = choice },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { colorChoice = choice },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = { onStart(ProviderConfig.RandomMoves(colorChoice)) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Game")
            }
        }
    }
}

@Composable
fun LichessConfigScreen(
    onStart: (ProviderConfig.Lichess) -> Unit,
    onBack: () -> Unit
) {
    val difficulties = listOf(1500, 1750, 2000)
    var selectedDifficulty by remember { mutableStateOf(1500) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lichess — Setup", style = MaterialTheme.typography.titleMedium)

        Text(
            "Note: Lichess integration is not yet implemented. This is a stub.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Bot difficulty:", style = MaterialTheme.typography.bodyMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            difficulties.forEach { difficulty ->
                val selected = selectedDifficulty == difficulty
                if (selected) {
                    Button(
                        onClick = { selectedDifficulty = difficulty },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("$difficulty")
                    }
                } else {
                    OutlinedButton(
                        onClick = { selectedDifficulty = difficulty },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("$difficulty")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = { onStart(ProviderConfig.Lichess(selectedDifficulty)) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Game")
            }
        }
    }
}

@Composable
fun GameInfoBanner(gameInfo: GameInfo, onEndGame: () -> Unit) {
    val playerColorName = gameInfo.playerColor.name.lowercase().replaceFirstChar { it.uppercase() }
    val opponentColorName = if (gameInfo.playerColor == ChessColor.WHITE) "Black" else "White"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Game in Progress",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "You: $playerColorName  •  Opponent: $opponentColorName  •  ${gameInfo.providerName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
            TextButton(onClick = onEndGame) {
                Text("End Game")
            }
        }
    }
}

@Composable
fun MoveLogHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#",
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "White",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Black",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Status",
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun MoveRow(entry: MoveEntry) {
    val isEven = entry.moveNumber % 2 == 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isEven)
                    MaterialTheme.colorScheme.surface
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Move number
        Text(
            text = "${entry.moveNumber}.",
            modifier = Modifier.width(36.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // White move (from watch)
        Text(
            text = entry.move,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        // Black move (opponent response or placeholder)
        Text(
            text = entry.response ?: "—",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = if (entry.response != null)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )

        // Status indicator
        StatusBadge(entry.status)
    }
}

@Composable
fun StatusBadge(status: MoveStatus) {
    val (label, color) = when (status) {
        MoveStatus.PENDING -> "●" to MaterialTheme.colorScheme.onSurfaceVariant
        MoveStatus.EXECUTED -> "✓" to MaterialTheme.colorScheme.primary
        MoveStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        modifier = Modifier.width(64.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.End,
        fontSize = 16.sp
    )
}

@Composable
fun WatchConnectionCard(connected: Boolean) {
    val containerColor = if (connected) Color(0xFF1B5E20) else Color(0xFFB71C1C)
    val contentColor = Color.White
    val icon = if (connected) "⌚" else "⌚"
    val title = if (connected) "Watch Connected" else "No Connection"
    val subtitle = if (connected)
        "Receiving moves from your Galaxy Watch"
    else
        "Watch not found — ensure it is paired and the watch app is open"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = icon, fontSize = 24.sp)
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = contentColor)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
fun AccessibilityStatusCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Accessibility Service", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Required to execute moves in Chess.com / Lichess",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("Enable")
            }
        }
    }
}
