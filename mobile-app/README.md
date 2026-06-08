# Mobile App

Android companion app for Watch Chess Controller. Receives moves from the Galaxy Watch and manages chess games via the Lichess Board API.

## Structure

```
mobile-app/
└── app/src/main/java/com/example/chessmobile/
    ├── MainActivity.kt              # UI — screen navigation, game screens, Lichess config
    ├── MoveRepository.kt            # Central state: game lifecycle, moves, polling, TTS
    ├── GameProvider.kt              # Provider interface + LichessBotProvider + RandomMovesProvider
    ├── LichessService.kt            # HTTP client for the Lichess Board API
    ├── LichessApiKeyManager.kt      # Secure token storage (EncryptedSharedPreferences + fallback)
    ├── GameSessionStore.kt          # SharedPreferences persistence for cold resume
    ├── ChessBoard.kt                # Local board: UCI ↔ SAN conversion, move application
    ├── ChessGameForegroundService.kt# Foreground service + wake lock
    ├── WearMessageListenerService.kt# Receives /chess/move messages from the watch
    └── ChessAccessibilityService.kt # Accessibility service (future Chess.com path)
```

## Game Flow

```
User taps Start Game (LichessConfigScreen)
        │
        ▼
MoveRepository.startGame()
  → sets _gameInfo, navigates to IN_GAME screen
  → calls LichessBotProvider.onGameStart()
        │
        ▼ (background coroutine)
LichessService.challengeAi()  [POST /api/challenge/ai]
  → success: saves gameId, starts NDJSON stream
  → failure: onGameStartFailed() → error dialog + navigate home
        │
        ▼
LichessService.streamGame()  [GET /api/board/game/stream/{id}]
  → gameFull event:  board state on connect / reconnect
  → gameState event: new moves → opponent move detected → TTS + watch notification
  → game-end event:  result screen shown
```

## Key Design Decisions

**Provider pattern** — `GameProvider` is an interface with `onGameStart`, `onPlayerMove`, `onGameEnd`, `onGamePause`, `onGameResume`. `MoveRepository` owns all UI state; providers communicate back via `GameProviderCallbacks`. This keeps providers stateless w.r.t. UI.

**Stream as source of truth** — `LichessBotProvider` never speculatively applies moves to the local board. Every `gameState` event triggers a full board rebuild from the authoritative UCI move list. This prevents drift between local state and Lichess.

**Polling fallback** — A 5-second polling loop in `MoveRepository` calls `provider.requestMoveUpdate()` if an opponent move hasn't arrived. `LichessBotProvider` responds by reconnecting the stream (which re-delivers a `gameFull` event with the current position).

**Session persistence** — `GameSessionStore` writes the `gameId` and player colour to SharedPreferences on pause. On cold restart, `MoveRepository.loadPersistedSessions()` surfaces the stored session for the user to resume.

**Token storage** — `LichessApiKeyManager` tries `EncryptedSharedPreferences` (AES-256). If the Android KeyStore is unavailable (some emulators, corrupted keystore), it falls back to plain `SharedPreferences` with a warning log.

## Lichess API Endpoints Used

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/api/challenge/ai` | Create a game vs Stockfish |
| POST | `/api/board/game/{id}/move/{uci}` | Submit a player move |
| GET  | `/api/board/game/stream/{id}` | NDJSON stream of game events |
| POST | `/api/board/game/{id}/resign` | Resign the game |
| POST | `/api/board/game/{id}/draw/yes` | Offer or accept a draw |

All requests use `Authorization: Bearer <token>`. No special OAuth scopes are required — a standard personal access token from lichess.org/account/oauth/token is sufficient.

## Building

Open `mobile-app/` in Android Studio and run on a physical device or emulator (API 26+).

The app requires the paired watch app to be installed for move input via the watch. The phone keyboard panel (Controller Panel button in-game) works without a watch for testing.
