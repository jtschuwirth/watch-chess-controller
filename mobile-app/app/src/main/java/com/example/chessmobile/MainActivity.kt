package com.example.chessmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.wearable.Wearable

enum class AppScreen { PROVIDER_SELECTION, PROVIDER_CONFIG, IN_GAME, GAME_RESULT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ requires runtime permission to show notifications (needed for foreground service notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        // Check initial watch connection state
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                MoveRepository.setWatchConnected(nodes.isNotEmpty())
            }

        // Restore any Lichess session that survived a previous app process death
        MoveRepository.loadPersistedSessions(this)

        // Initialize TTS engine early so it's ready when a game starts
        MoveRepository.initTts(this)

        setContent {
            MaterialTheme {
                ChessMobileScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessMobileScreen() {
    val moves by MoveRepository.moves.collectAsStateWithLifecycle()
    val lastStatus by MoveRepository.lastStatus.collectAsStateWithLifecycle()
    val watchConnected by MoveRepository.watchConnected.collectAsStateWithLifecycle()
    val gameInfo by MoveRepository.gameInfo.collectAsStateWithLifecycle()
    val gameResult by MoveRepository.gameResult.collectAsStateWithLifecycle()
    val phoneInputError by MoveRepository.phoneInputError.collectAsStateWithLifecycle()
    val navigateToHome by MoveRepository.navigateToHome.collectAsStateWithLifecycle()
    val resumableSessions by MoveRepository.resumableSessions.collectAsStateWithLifecycle()
    val isTtsEnabled by MoveRepository.isTtsEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val keyboardViewModel: MobileKeyboardViewModel = viewModel()
    val currentInput by keyboardViewModel.currentInput.collectAsStateWithLifecycle()

    var currentScreen by remember { mutableStateOf(AppScreen.PROVIDER_SELECTION) }
    var selectedProvider by remember { mutableStateOf<GameProvider?>(null) }
    var showKeyboard by remember { mutableStateOf(false) }

    // When Lichess ends the game with a result, move to the result screen
    LaunchedEffect(gameResult) {
        if (gameResult != null && currentScreen == AppScreen.IN_GAME) {
            currentScreen = AppScreen.GAME_RESULT
        }
    }

    // If watch requested home navigation, reset to provider selection
    LaunchedEffect(navigateToHome) {
        if (navigateToHome) {
            currentScreen = AppScreen.PROVIDER_SELECTION
            MoveRepository.clearNavigateToHome()
        }
    }

    // If game was ended externally (no result), return to selection
    LaunchedEffect(gameInfo) {
        if (gameInfo == null && gameResult == null && currentScreen == AppScreen.IN_GAME) {
            currentScreen = AppScreen.PROVIDER_SELECTION
        }
    }

    // Close keyboard when leaving the in-game screen
    LaunchedEffect(currentScreen) {
        if (currentScreen != AppScreen.IN_GAME) showKeyboard = false
    }

    // Scroll to bottom when a new move arrives
    LaunchedEffect(moves.size) {
        if (moves.isNotEmpty()) listState.animateScrollToItem(moves.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chess Move Log") },
                actions = {
                    if (currentScreen == AppScreen.IN_GAME) {
                        TextButton(onClick = { MoveRepository.toggleTts() }) {
                            Text(
                                text = if (isTtsEnabled) "Audio: ON" else "Audio: OFF",
                                color = if (isTtsEnabled) Color(0xFF2E7D32) else Color(0xFFB71C1C)
                            )
                        }
                    }
                }
            )
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

            // Watch connection status card — hidden when the controller panel is open
            if (!(showKeyboard && currentScreen == AppScreen.IN_GAME)) {
                WatchConnectionCard(watchConnected)
            }

            when (currentScreen) {
                AppScreen.PROVIDER_SELECTION -> {
                    ProviderSelectionScreen(
                        resumableSessions = resumableSessions,
                        onProviderSelected = { provider ->
                            selectedProvider = provider
                            currentScreen = AppScreen.PROVIDER_CONFIG
                        },
                        onResumeGame = { providerName ->
                            MoveRepository.resumeGame(providerName, context)
                            currentScreen = AppScreen.IN_GAME
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
                        is LichessBotProvider -> {
                            val provider = selectedProvider as LichessBotProvider
                            LichessConfigScreen(
                                onStart = { config ->
                                    MoveRepository.startGame(config, provider, context)
                                    currentScreen = AppScreen.IN_GAME
                                },
                                onBack = { currentScreen = AppScreen.PROVIDER_SELECTION }
                            )
                        }
                        else -> { currentScreen = AppScreen.PROVIDER_SELECTION }
                    }
                }

                AppScreen.GAME_RESULT -> {
                    GameResultScreen(
                        result = gameResult ?: "Game over.",
                        moves = moves,
                        playerColor = gameInfo?.playerColor,
                        onNewGame = {
                            MoveRepository.clearGameResult(context)
                            currentScreen = AppScreen.PROVIDER_SELECTION
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                AppScreen.IN_GAME -> {
                    if (gameInfo != null) {
                        // Game in progress banner — hidden when controller panel is open
                        if (!showKeyboard) {
                            GameInfoBanner(gameInfo = gameInfo!!)

                            lastStatus?.let { status ->
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Move log header
                        MoveLogHeader(playerColor = gameInfo!!.playerColor)

                        // Move list
                        if (moves.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val hint = if (gameInfo!!.playerColor == ChessColor.WHITE)
                                    "You go first — type your move below or send from the watch."
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
                                contentPadding = PaddingValues(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(moves) { entry ->
                                    MoveRow(entry)
                                }
                            }
                        }

                        // Controller panel or open button
                        if (showKeyboard) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Controller Panel",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        TextButton(onClick = { showKeyboard = false }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close panel"
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Close")
                                        }
                                    }
                                    ChessKeyboardPanel(
                                        currentInput = currentInput,
                                        onAppend = keyboardViewModel::onAppend,
                                        onBackspace = keyboardViewModel::onBackspace,
                                        onClear = keyboardViewModel::onClear,
                                        onSend = { keyboardViewModel.onSend() },
                                        onResign = { keyboardViewModel.onResign() },
                                        onDrawOffer = { keyboardViewModel.onDrawOffer() },
                                        errorMessage = phoneInputError,
                                        modifier = Modifier
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showKeyboard = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Controller Panel")
                                }
                                OutlinedButton(
                                    onClick = { MoveRepository.pauseGame(context) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Leave Game")
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
fun ProviderSelectionScreen(
    resumableSessions: Map<String, ResumableSession>,
    onProviderSelected: (GameProvider) -> Unit,
    onResumeGame: (providerName: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Choose Game Provider",
            style = MaterialTheme.typography.titleMedium
        )

        // Random Moves card
        ProviderCard(
            title = "Random Moves",
            description = "The phone plays random moves as your opponent. Good for testing.",
            resumableSession = resumableSessions["Random Moves"],
            onNewGame = { onProviderSelected(RandomMovesProvider) },
            onResume = { onResumeGame("Random Moves") }
        )

        // Lichess Bots card
        ProviderCard(
            title = "Lichess Bots",
            description = "Play against a Lichess Stockfish bot via the Board API.",
            resumableSession = resumableSessions["Lichess Bots"],
            onNewGame = { onProviderSelected(LichessBotProvider()) },
            onResume = { onResumeGame("Lichess Bots") }
        )

        // Lichess Challenge card
        ProviderCard(
            title = "Lichess Challenge",
            description = "Challenge a human player on Lichess. (Coming soon)",
            resumableSession = null,
            onNewGame = { onProviderSelected(LichessChallengeProvider()) },
            onResume = { onResumeGame("Lichess Challenge") }
        )
    }
}

@Composable
private fun ProviderCard(
    title: String,
    description: String,
    resumableSession: ResumableSession?,
    onNewGame: () -> Unit,
    onResume: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onNewGame
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (resumableSession != null) {
                val colorLabel = resumableSession.playerColor.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
                OutlinedButton(
                    onClick = onResume,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Resume game (playing $colorLabel)")
                }
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
    val context = LocalContext.current

    // API key state — pre-populate from secure storage
    var apiKeyInput by remember {
        mutableStateOf(LichessApiKeyManager.getApiKey(context) ?: "")
    }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeySaved by remember {
        mutableStateOf(LichessApiKeyManager.hasApiKey(context))
    }

    // Game options
    val levels = listOf(3 to "Easy", 5 to "Medium", 7 to "Hard")
    var selectedLevel by remember { mutableStateOf(3) }
    var colorChoice by remember { mutableStateOf(ColorChoice.WHITE) }
    var timeControl by remember { mutableStateOf(TimeControl.TEN_MIN) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lichess — Setup", style = MaterialTheme.typography.titleMedium)

        // API key section
        Text("API Token:", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it; apiKeySaved = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Lichess personal access token") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Text(if (apiKeyVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        )
        Button(
            onClick = {
                LichessApiKeyManager.saveApiKey(context, apiKeyInput)
                apiKeySaved = true
            },
            enabled = apiKeyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Token")
        }
        if (apiKeySaved) {
            Text(
                "✓ Token saved",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32)
            )
        }
        Text(
            "Generate a token at lichess.org → Settings → API access tokens. " +
            "No special scopes are required for the Board API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // Bot difficulty
        Text("Bot difficulty:", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEach { (level, label) ->
                val selected = selectedLevel == level
                if (selected) {
                    Button(
                        onClick = { selectedLevel = level },
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { selectedLevel = level },
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
        }

        // Time control
        Text("Time format:", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeControl.entries.forEach { tc ->
                val selected = timeControl == tc
                if (selected) {
                    Button(
                        onClick = { timeControl = tc },
                        modifier = Modifier.weight(1f)
                    ) { Text(tc.label) }
                } else {
                    OutlinedButton(
                        onClick = { timeControl = tc },
                        modifier = Modifier.weight(1f)
                    ) { Text(tc.label) }
                }
            }
        }

        // Color choice
        Text("Your color:", style = MaterialTheme.typography.bodyMedium)
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
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { colorChoice = choice },
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = { onStart(ProviderConfig.Lichess(selectedLevel, colorChoice, timeControl)) },
                enabled = apiKeySaved,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Game")
            }
        }
        if (!apiKeySaved && apiKeyInput.isBlank()) {
            Text(
                "Save your API token to enable Start Game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun GameInfoBanner(gameInfo: GameInfo) {
    val playerColorName = gameInfo.playerColor.name.lowercase().replaceFirstChar { it.uppercase() }
    val opponentColorName = if (gameInfo.playerColor == ChessColor.WHITE) "Black" else "White"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
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
    }
}

@Composable
fun MoveLogHeader(playerColor: ChessColor? = null) {
    val whiteLabel = if (playerColor == ChessColor.WHITE) "White (You)" else "White"
    val blackLabel = if (playerColor == ChessColor.BLACK) "Black (You)" else "Black"
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
            text = whiteLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = blackLabel,
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
    // Entry model invariant: move=White's half, response=Black's half (for both colors).
    val whiteText = entry.move
    val blackText = entry.response ?: "—"
    val blackAvailable = entry.response != null

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

        // White move
        Text(
            text = whiteText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        // Black move
        Text(
            text = blackText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = if (blackAvailable)
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
fun GameResultScreen(
    result: String,
    moves: List<MoveEntry>,
    playerColor: ChessColor?,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Result banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Game Over",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    result,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Button(
                    onClick = onNewGame,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back to Home")
                }
            }
        }

        // Move log
        if (moves.isNotEmpty()) {
            MoveLogHeader(playerColor = playerColor)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(moves) { entry -> MoveRow(entry) }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

