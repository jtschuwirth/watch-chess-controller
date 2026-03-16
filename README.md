# Watch Chess Controller

Play chess hands-free. Enter your moves on your Samsung Galaxy Watch and let your phone handle the rest.

## Overview

**Watch Chess Controller** is a two-component system that bridges a Samsung Galaxy Watch and an Android phone to let you play chess without ever looking at your phone screen.

1. On your watch, you type a chess move in standard algebraic notation (e.g., `Ne4`, `Bxf7+`, `O-O`).
2. The watch sends the move to the companion mobile app via the Wearable Data Layer.
3. The mobile app executes the move inside an existing chess app (Chess.com / Lichess).
4. *(Planned)* When your opponent moves, the phone reads the move aloud so you always know the game state.

## Architecture

```
┌─────────────────────────────┐         ┌──────────────────────────────────┐
│      Samsung Galaxy Watch   │         │         Android Phone            │
│      (Wear OS)              │         │         (Flutter)                │
│                             │         │                                  │
│  ┌───────────────────────┐  │  BLE /  │  ┌────────────────────────────┐ │
│  │  Watch App (Wear OS)  │──┼─────────┼─▶│  Mobile App (Flutter)      │ │
│  │  - Move input UI      │  │  Data   │  │  - Receives move           │ │
│  │  - Algebraic notation │  │  Layer  │  │  - Executes in chess app   │ │
│  │  - Send via           │  │  API    │  │  - (Planned) TTS feedback  │ │
│  │    MessageClient      │  │         │  └────────────────────────────┘ │
│  └───────────────────────┘  │         │              │                   │
│                             │         │              ▼                   │
└─────────────────────────────┘         │  ┌────────────────────────────┐ │
                                        │  │  Chess App                 │ │
                                        │  │  (Chess.com / Lichess)     │ │
                                        │  │  via Accessibility Service │ │
                                        │  └────────────────────────────┘ │
                                        └──────────────────────────────────┘
```

## Components

### Watch App (`/watch-app`)
- Built with **Jetpack Compose for Wear OS** (Kotlin)
- Targets **Samsung Galaxy Watch 4+** (Wear OS)
- UI for entering chess moves in algebraic notation
- Sends moves to the phone via the **Wearable Data Layer API** (`MessageClient`)

### Mobile App (`/mobile-app`)
- Built with **Flutter** (Dart)
- Receives moves from the watch
- Executes moves in an existing chess app via Android **Accessibility Service**
- *(Planned)* Listens for the opponent's move and reads it aloud using **Text-to-Speech (TTS)**

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Watch App | Kotlin, Jetpack Compose for Wear OS |
| Mobile App | Flutter (Dart) |
| Watch ↔ Phone | Wearable Data Layer API (`MessageClient`) |
| Move Execution | Android Accessibility Service |
| Audio Feedback | Android TTS *(planned)* |

## Features

### Current
- [ ] Enter chess moves on the watch using algebraic notation
- [ ] Send move from watch to phone
- [ ] Execute move in Chess.com / Lichess Android app

### Planned
- [ ] Audio readback of opponent's move via TTS
- [ ] Move validation feedback on watch (legal / illegal)
- [ ] Game state display on watch (whose turn, material, etc.)
- [ ] Support for multiple chess apps

## Getting Started

### Prerequisites
- Samsung Galaxy Watch 4 or later (Wear OS)
- Android phone with the companion app installed
- Chess.com or Lichess app installed on the phone
- Android Studio (for the watch app)
- Flutter SDK (for the mobile app)

### Watch App Setup
```bash
cd watch-app
# Open in Android Studio and run on a paired Galaxy Watch
```

### Mobile App Setup
```bash
cd mobile-app
flutter pub get
flutter run
```

## Move Notation

The watch app accepts standard **algebraic notation**:

| Notation | Meaning |
|----------|---------|
| `e4` | Pawn to e4 |
| `Ne4` | Knight to e4 |
| `Bxf7` | Bishop captures f7 |
| `O-O` | Kingside castling |
| `O-O-O` | Queenside castling |
| `e8=Q` | Pawn promotion to Queen |

## License

MIT
