# CLAUDE.md — Watch Chess Controller

## Project Overview

A two-component system for hands-free chess play:
- **Watch App** (Wear OS / Kotlin): user inputs chess moves in algebraic notation on a Samsung Galaxy Watch
- **Mobile App** (Android / Kotlin): receives moves from the watch and executes them inside an existing chess app (Chess.com or Lichess)

Planned: the mobile app listens for the opponent's move and reads it aloud via TTS so the user never needs to look at the phone.

## Repository Structure

```
watch-chess-controller/
├── watch-app/        # Jetpack Compose Wear OS app (Kotlin)
└── mobile-app/       # Android companion app (Kotlin)
```

## Tech Stack

| Area | Choice | Notes |
|------|--------|-------|
| Watch app | Kotlin + Jetpack Compose for Wear OS | Samsung Galaxy Watch 4+ only |
| Mobile app | Kotlin + Jetpack Compose | Android-first |
| Communication | Wearable Data Layer API (`MessageClient`) | BLE, requires both apps installed |
| Move execution | Android Accessibility Service | Automates Chess.com / Lichess app |
| Audio feedback | Android TTS | Planned feature |

## Architecture & Data Flow

```
User types move on watch
       │
       ▼
Watch App sends via MessageClient (path: "/chess/move")
       │
       ▼
Mobile App receives move string (e.g., "Ne4")
       │
       ├──▶ Validate notation
       │
       └──▶ Execute in chess app via Accessibility Service
                    │
                    ▼ (planned)
             Detect opponent move from screen
                    │
                    ▼
             Read aloud via TTS
```

## Key Implementation Notes

### Watch ↔ Phone Communication
- Use `MessageClient` (not `DataClient`) — moves are fire-and-forget events, not persistent state
- Message path convention: `/chess/move`
- Payload: UTF-8 encoded algebraic notation string
- The watch app must use the `com.google.android.wearable:wearable` dependency
- The mobile app uses the Wearable Data Layer API directly via `com.google.android.gms:play-services-wearable`

### Move Input on Watch
- Input should be fast and thumb-friendly on a small screen
- Support standard algebraic notation: pieces (`K`, `Q`, `R`, `B`, `N`), squares (`a1`–`h8`), captures (`x`), check (`+`), checkmate (`#`), castling (`O-O`, `O-O-O`), promotion (`=Q`)
- Consider a custom keyboard layout optimized for chess notation

### Accessibility Service (Move Execution)
- The mobile app needs an Android Accessibility Service to interact with Chess.com / Lichess
- This requires `BIND_ACCESSIBILITY_SERVICE` permission
- The user must manually enable it in Android Settings → Accessibility
- Be specific about which app is being automated — screen content may differ between Chess.com and Lichess

### TTS Feedback (Planned)
- Use Android's built-in `TextToSpeech` API
- Read the opponent's move in plain English, e.g., "Knight to e4" not "Ne4"
- Trigger on detecting a new move in the chess app via accessibility events

## Development Guidelines

- **Watch app language**: Kotlin only (no Java)
- **Mobile app language**: Kotlin only (no Java)
- **Min SDK**: Android API 30 (Wear OS 3.0) for the watch; Android API 26 for the phone
- **Notation validation**: validate move strings before sending from the watch — don't send garbage to the phone
- **Error handling**: if a move fails to execute (e.g., illegal move, wrong turn), the mobile app should send an error back to the watch via `MessageClient`
- **No chess engine embedded**: the mobile app does not run its own chess engine; it automates an existing app

## Testing Strategy

- Watch app: test on an emulator (Wear OS) or a physical Galaxy Watch 4+
- Communication: test with both devices on the same Android account and paired via Bluetooth
- Accessibility automation: test against specific app versions — UI hierarchies can change with app updates
- TTS: test with the phone's speaker and with Bluetooth audio

## Permissions Required

### Watch App
- `com.google.android.permission.PROVIDE_BACKGROUND`

### Mobile App
- `BIND_ACCESSIBILITY_SERVICE`
- `FOREGROUND_SERVICE` (to keep the listener alive while playing)
