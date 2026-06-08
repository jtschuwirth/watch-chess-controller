# Board View Feature

## Overview

The **Board View** feature lets you check the current position of every piece on the board directly from your Galaxy Watch — no need to look at your phone. After tapping the **BOARD** button, the watch fetches the live board state from the phone and displays all pieces grouped by color and type with their squares.

This is especially useful mid-game when you want to recall where a specific piece ended up after a sequence of exchanges or a tactical sequence you've lost track of.

---

## How to Use

1. During an active game, scroll down past the move keyboard to the **Controls** section.
2. Tap **BOARD**.
3. The watch screen switches to the Board View with a "Fetching…" indicator.
4. Within ~1 second, all pieces appear grouped as **White** and **Black** sections.
5. Each group shows the piece type and the square(s) it occupies, e.g.:
   ```
   ☐ White
   King       e1
   Queen      d1
   Rooks      a1, h1
   Bishops    c1, f1
   Knights    b1, g1
   Pawns      a2, b2, c2, d2, e4, f2, g2, h2

   ■ Black
   King       e8
   Queen      d8
   Rooks      a8, h8
   ...
   ```
6. Tap **↻** (refresh) at the top right to re-fetch the latest position.
7. Tap **← Back** at the bottom to return to the move input screen.

---

## Technical Architecture

### Message Protocol

| Path | Direction | Payload |
|------|-----------|---------|
| `/chess/board/request` | Watch → Phone | Empty |
| `/chess/board/response` | Phone → Watch | Serialized piece list (see below) |

### Payload Format

The phone serializes the board into a compact string:

```
W:Ke1,Qd1,Ra1,Rh1,Bc1,Bf1,Nb1,Ng1,Pa2,Pb2,Pc2,Pd2,Pe4,Pf2,Pg2,Ph2|B:Ke8,Qd8,Ra8,Rh8,Bc8,Bf8,Nb8,Ng8,Pa7,Pb7,Pc7,Pd7,Pe7,Pf7,Pg7,Ph7
```

- Two sections separated by `|`: `W:` (white pieces) and `B:` (black pieces)
- Each token is `{PieceChar}{square}` — piece chars match SAN notation (`K Q R B N P`)
- Within each color section, pieces are ordered: King, Queen, Rook, Bishop, Knight, Pawn
- If board state is not available: `UNAVAILABLE`

### How Board State Is Computed

The `ChessBoard` class (`mobile-app/.../ChessBoard.kt`) maintains an internal 8×8 board array that is updated via `applyUci()` on every move from the Lichess game stream. The new `getBoardStateSerialized()` method iterates this array and produces the wire format string.

`LichessBotProvider` holds a `ChessBoard` instance and exposes it via `getBoardState()`. When the watch requests the board, `WearMessageListenerService` calls `MoveRepository.activeProvider.value?.getBoardState()` and sends the result back.

### Component Map

| Component | File | Role |
|-----------|------|------|
| `ChessBoard.getBoardStateSerialized()` | `mobile-app/.../ChessBoard.kt` | Produces the wire format string from the internal board array |
| `GameProvider.getBoardState()` | `mobile-app/.../GameProvider.kt` | Interface method; default returns `"UNAVAILABLE"` |
| `LichessBotProvider.getBoardState()` | `mobile-app/.../GameProvider.kt` | Delegates to `ChessBoard.getBoardStateSerialized()` (thread-safe) |
| `WearMessageListenerService.handleBoardRequest()` | `mobile-app/.../WearMessageListenerService.kt` | Handles `/chess/board/request`, sends `/chess/board/response` |
| `ChessMoveViewModel.onRequestBoard()` | `watch-app/.../ChessMoveScreen.kt` | Sends request, sets `showBoardView = true`, clears stale `boardState` |
| `ChessMoveViewModel.onDismissBoardView()` | `watch-app/.../ChessMoveScreen.kt` | Clears `showBoardView` flag to return to main screen |
| `MoveInputState.boardState` | `watch-app/.../MoveInputState.kt` | Holds the received payload; `null` while loading |
| `MoveInputState.showBoardView` | `watch-app/.../MoveInputState.kt` | Controls screen routing |
| `BoardViewScreen` | `watch-app/.../BoardViewScreen.kt` | Wear OS composable that parses and displays the board state |
| `parseBoardState()` | `watch-app/.../BoardViewScreen.kt` | Pure function; parses wire format into `PieceGroup` lists |

---

## Provider Support

| Provider | Board View Support |
|----------|--------------------|
| **Lichess Bot** (`LichessBotProvider`) | Full — uses the live `ChessBoard` instance updated from the game stream |
| **Random Moves** (`RandomMovesProvider`) | Not available — returns `"UNAVAILABLE"` |
| **Lichess Challenge** (`LichessChallengeProvider`) | Not implemented — returns `"UNAVAILABLE"` |

---

## Known Limitations

- **Lichess only**: Board view reflects the state maintained by `LichessBotProvider`. When playing against the Random Moves provider, the watch shows "Not available for this game mode".
- **One request at a time**: Tapping BOARD sends a new request immediately; any previously loading state is discarded.
- **No game active**: If the phone has no active provider (no game running), the response is `"UNAVAILABLE"`.
- **Stale data**: The board state is a snapshot at the moment the request reaches the phone. If the opponent's move arrives between your request and the response, tap ↻ to refresh.
- **No interactive board**: Board view is read-only — it is a piece list, not a visual board diagram.
