# Watch Chess Controller

Play chess hands-free. Enter moves on your Samsung Galaxy Watch; the phone handles everything else.

## Overview

**Watch Chess Controller** is a two-component Android system that bridges a Samsung Galaxy Watch and an Android phone for hands-free chess play.

1. You type a move in standard algebraic notation on the watch (e.g. `Ne4`, `Bxf7+`, `O-O`).
2. The watch sends it to the companion mobile app via the Wearable Data Layer.
3. The mobile app submits the move to Lichess via the Board API.
4. When your opponent plays, the phone reads the move aloud via Text-to-Speech so you never need to look at the screen.

## Architecture

```
┌──────────────────────────┐         ┌─────────────────────────────────────┐
│   Samsung Galaxy Watch   │         │           Android Phone              │
│   (Wear OS / Kotlin)     │         │     (Kotlin + Jetpack Compose)       │
│                          │         │                                      │
│  ┌──────────────────┐    │  BLE /  │  ┌─────────────────────────────┐    │
│  │  Watch App       │────┼─────────┼─▶│  Mobile App                 │    │
│  │  - Move input UI │    │  Data   │  │  - Receives move from watch  │    │
│  │  - SAN notation  │    │  Layer  │  │  - Submits to Lichess API    │    │
│  │  - MessageClient │    │  API    │  │  - Streams opponent moves    │    │
│  └──────────────────┘    │         │  │  - TTS readback              │    │
│                          │         │  └─────────────────────────────┘    │
└──────────────────────────┘         │               │                      │
                                     │               ▼                      │
                                     │  ┌─────────────────────────────┐    │
                                     │  │  Lichess Board API           │    │
                                     │  │  (POST /api/challenge/ai)    │    │
                                     │  │  (POST /api/board/game/…)    │    │
                                     │  │  (GET  /api/board/game/      │    │
                                     │  │        stream/{id})          │    │
                                     │  └─────────────────────────────┘    │
                                     └─────────────────────────────────────┘
```

## Components

### Watch App (`/watch-app`)
- Kotlin + Jetpack Compose for Wear OS
- Targets Samsung Galaxy Watch 4+ (Wear OS 3.0+)
- Chess-optimised move input UI for algebraic notation
- Sends moves to the phone via `MessageClient` on path `/chess/move`
- Receives game state updates (start, end, pause, opponent move) from the phone

### Mobile App (`/mobile-app`)
- Kotlin + Jetpack Compose (Android API 26+)
- **Game Providers** — pluggable backends:
  - **Lichess Bots** — plays against Stockfish via `POST /api/challenge/ai`; streams events via NDJSON
  - **Random Moves** — offline opponent for testing (no internet needed)
  - **Lichess Challenge** *(stub — not yet implemented)*
- Foreground service + wake lock keeps the game session alive while the screen is off
- Game session survives app restarts (persisted to SharedPreferences)
- TTS reads each opponent move aloud (toggle in-game with the Audio button)
- Polling fallback reconnects the Lichess stream if a move event is missed

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Watch App | Kotlin, Jetpack Compose for Wear OS |
| Mobile App | Kotlin, Jetpack Compose |
| Watch ↔ Phone | Wearable Data Layer API (`MessageClient`) |
| Chess Backend | Lichess Board API (REST + NDJSON stream) |
| Token Storage | `EncryptedSharedPreferences` (AES-256), plain fallback |
| Audio Feedback | Android `TextToSpeech` |
| Background | `ForegroundService` + `WakeLock` |

## Features

### Implemented
- Move input on the watch in standard algebraic notation
- Watch ↔ Phone communication via Wearable Data Layer
- Lichess bot games (Stockfish level 1–8, white/black/random, 10 min / 30 min / unlimited)
- Move submission and validation via Lichess Board API
- Real-time opponent move streaming (NDJSON) with automatic reconnection
- TTS readback of opponent moves (e.g. "Knight to e4, check")
- Game pause and resume — including cold resume after app restart
- Game result screen (checkmate, resign, draw, timeout, etc.)
- Game start validation — if Lichess rejects the game, error is shown and app returns home
- Secure API token storage with plain-SharedPreferences fallback

### Planned
- Lichess Challenge mode (accept/send challenges to human players)
- Move validation feedback on the watch (legal / illegal indicator)
- Accessibility Service path for Chess.com automation

## Getting Started

### Prerequisites
- Samsung Galaxy Watch 4 or later (Wear OS 3.0+)
- Android phone (API 26+)
- Android Studio (Hedgehog or later recommended)
- A [Lichess personal access token](https://lichess.org/account/oauth/token) — no special scopes required for the Board API

### Watch App Setup
```bash
cd watch-app
# Open in Android Studio → Run on paired Galaxy Watch or Wear OS emulator
```

### Mobile App Setup
```bash
cd mobile-app
# Open in Android Studio → Run on connected Android phone
```

On first launch:
1. Tap **Lichess Bots** → enter your Lichess API token → tap **Save Token**
2. Choose difficulty, time control, and colour → tap **Start Game**
3. On your watch, type moves and send them

### Required Permissions

**Watch App**
- `com.google.android.permission.PROVIDE_BACKGROUND`

**Mobile App**
- `FOREGROUND_SERVICE` — keeps the game session alive while the screen is off
- `POST_NOTIFICATIONS` (Android 13+) — foreground service notification
- *(Optional)* `BIND_ACCESSIBILITY_SERVICE` — needed for future Chess.com automation

## Move Notation

The watch app accepts standard algebraic notation:

| Notation | Meaning |
|----------|---------|
| `e4` | Pawn to e4 |
| `Ne4` | Knight to e4 |
| `Bxf7` | Bishop captures f7 |
| `O-O` | Kingside castling |
| `O-O-O` | Queenside castling |
| `e8=Q` | Pawn promotes to Queen |
| `Ne4+` | Knight to e4, check |
| `Qh7#` | Queen to h7, checkmate |

## License

MIT
