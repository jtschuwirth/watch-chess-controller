package com.example.chessmobile

/**
 * Minimal chess board tracker used to convert between SAN (algebraic notation from the watch)
 * and UCI notation (required by the Lichess Board API).
 *
 * Supports: pawn moves/captures/promotions, all piece moves with disambiguation, castling,
 * en passant, and applies UCI moves to keep state current.
 *
 * Does NOT enforce legality (check, pins, etc.) — inputs are assumed to be valid SAN.
 */

enum class PieceType(val char: Char) {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K')
}

data class Piece(val type: PieceType, val color: ChessColor)

data class Square(val file: Int, val rank: Int) {
    val name: String get() = "${'a' + file}${rank + 1}"

    companion object {
        fun of(name: String) = Square(name[0] - 'a', name[1] - '1')
    }
}

class ChessBoard {

    // board[rank][file], rank 0 = rank 1, file 0 = 'a'
    private val board: Array<Array<Piece?>> = Array(8) { arrayOfNulls(8) }
    var activeColor: ChessColor = ChessColor.WHITE
    private var enPassantSquare: Square? = null

    init { reset() }

    fun reset() {
        val backRank = listOf(
            PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
            PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        )
        for (f in 0..7) {
            board[0][f] = Piece(backRank[f], ChessColor.WHITE)
            board[1][f] = Piece(PieceType.PAWN, ChessColor.WHITE)
            board[6][f] = Piece(PieceType.PAWN, ChessColor.BLACK)
            board[7][f] = Piece(backRank[f], ChessColor.BLACK)
            for (r in 2..5) board[r][f] = null
        }
        activeColor = ChessColor.WHITE
        enPassantSquare = null
    }

    fun get(file: Int, rank: Int): Piece? = board[rank][file]

    // -------------------------------------------------------------------------
    // Apply a UCI move to update board state
    // -------------------------------------------------------------------------

    fun applyUci(uci: String) {
        if (uci.length < 4) return
        val fromFile = uci[0] - 'a'
        val fromRank = uci[1] - '1'
        val toFile = uci[2] - 'a'
        val toRank = uci[3] - '1'
        val promotionChar = if (uci.length > 4) uci[4] else null

        if (fromFile !in 0..7 || fromRank !in 0..7 || toFile !in 0..7 || toRank !in 0..7) return
        val piece = board[fromRank][fromFile] ?: return

        // Compute new en passant square (double pawn push)
        val newEnPassant: Square? = if (
            piece.type == PieceType.PAWN && kotlin.math.abs(toRank - fromRank) == 2
        ) {
            Square(fromFile, (fromRank + toRank) / 2)
        } else null

        // En passant capture: pawn moves diagonally to an empty square
        if (piece.type == PieceType.PAWN && toFile != fromFile && board[toRank][toFile] == null) {
            board[fromRank][toFile] = null
        }

        // Castling: also move the rook
        if (piece.type == PieceType.KING && kotlin.math.abs(toFile - fromFile) == 2) {
            if (toFile == 6) { // kingside
                board[fromRank][5] = board[fromRank][7]
                board[fromRank][7] = null
            } else { // queenside
                board[fromRank][3] = board[fromRank][0]
                board[fromRank][0] = null
            }
        }

        // Move piece (with optional promotion)
        val movedPiece = if (promotionChar != null) {
            val promType = when (promotionChar.lowercaseChar()) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> PieceType.QUEEN
            }
            Piece(promType, piece.color)
        } else piece

        board[toRank][toFile] = movedPiece
        board[fromRank][fromFile] = null
        enPassantSquare = newEnPassant
        activeColor = if (activeColor == ChessColor.WHITE) ChessColor.BLACK else ChessColor.WHITE
    }

    // -------------------------------------------------------------------------
    // Convert a UCI move to SAN (call BEFORE applyUci — needs pre-move board state)
    // -------------------------------------------------------------------------

    fun uciToSan(uci: String): String {
        if (uci.length < 4) return uci
        val fromFile = uci[0] - 'a'
        val fromRank = uci[1] - '1'
        val toFile   = uci[2] - 'a'
        val toRank   = uci[3] - '1'
        val promotionChar = if (uci.length > 4) uci[4].uppercaseChar() else null

        if (fromFile !in 0..7 || fromRank !in 0..7 || toFile !in 0..7 || toRank !in 0..7) return uci

        val piece = board[fromRank][fromFile] ?: return uci
        val dest = Square(toFile, toRank)
        val isCapture = board[toRank][toFile] != null ||
            (piece.type == PieceType.PAWN && toFile != fromFile) // en passant

        // Castling: king moves two squares horizontally
        if (piece.type == PieceType.KING && kotlin.math.abs(toFile - fromFile) == 2) {
            return if (toFile == 6) "O-O" else "O-O-O"
        }

        return when (piece.type) {
            PieceType.PAWN -> {
                val capturePrefix = if (isCapture) "${'a' + fromFile}x" else ""
                val promSuffix = if (promotionChar != null) "=$promotionChar" else ""
                "$capturePrefix${dest.name}$promSuffix"
            }
            else -> {
                val pieceChar = piece.type.char
                val captureStr = if (isCapture) "x" else ""

                // Find other pieces of the same type/color that could also reach the destination
                val ambiguous = mutableListOf<Square>()
                for (rank in 0..7) {
                    for (file in 0..7) {
                        if (rank == fromRank && file == fromFile) continue
                        val p = board[rank][file] ?: continue
                        if (p.type == piece.type && p.color == piece.color &&
                            canReach(Square(file, rank), dest, p)) {
                            ambiguous.add(Square(file, rank))
                        }
                    }
                }

                val disambig = when {
                    ambiguous.isEmpty() -> ""
                    ambiguous.none { it.file == fromFile } -> "${'a' + fromFile}"
                    ambiguous.none { it.rank == fromRank } -> "${fromRank + 1}"
                    else -> "${'a' + fromFile}${fromRank + 1}"
                }

                "$pieceChar$disambig$captureStr${dest.name}"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Convert SAN to UCI (finds the source square for the active color's move)
    // -------------------------------------------------------------------------

    fun sanToUci(san: String): String? {
        val move = san.trimEnd('+', '#')

        // Castling
        if (move == "O-O") return if (activeColor == ChessColor.WHITE) "e1g1" else "e8g8"
        if (move == "O-O-O") return if (activeColor == ChessColor.WHITE) "e1c1" else "e8c8"

        // Promotion suffix (e.g., "=Q")
        val promotionSuffix = if ('=' in move) move.substringAfter('=').lowercase().take(1) else ""
        val moveBody = if ('=' in move) move.substringBefore('=') else move

        // Destination square: last two characters of the move body
        if (moveBody.length < 2) return null
        val destFile = moveBody[moveBody.length - 2] - 'a'
        val destRank = moveBody[moveBody.length - 1] - '1'
        if (destFile !in 0..7 || destRank !in 0..7) return null
        val dest = Square(destFile, destRank)

        // Piece type: uppercase letter at the start indicates a piece; otherwise pawn
        val pieceType = when {
            moveBody[0].isUpperCase() && moveBody[0] in "KQRBN" -> when (moveBody[0]) {
                'K' -> PieceType.KING
                'Q' -> PieceType.QUEEN
                'R' -> PieceType.ROOK
                'B' -> PieceType.BISHOP
                'N' -> PieceType.KNIGHT
                else -> PieceType.PAWN
            }
            else -> PieceType.PAWN
        }

        // Disambiguation: characters between the piece letter (or start) and the destination
        val prefix = if (pieceType == PieceType.PAWN) moveBody else moveBody.substring(1)
        val withoutCapture = prefix.replace("x", "")
        val disambig = if (withoutCapture.length > 2) withoutCapture.dropLast(2) else ""
        val disambigFile = disambig.firstOrNull { it in 'a'..'h' }?.let { it - 'a' } ?: -1
        val disambigRank = disambig.firstOrNull { it.isDigit() }?.let { it - '1' } ?: -1

        // Find all candidate source squares
        val candidates = mutableListOf<Square>()
        for (rank in 0..7) {
            for (file in 0..7) {
                val piece = board[rank][file] ?: continue
                if (piece.color != activeColor || piece.type != pieceType) continue
                val src = Square(file, rank)
                if (!canReach(src, dest, piece)) continue
                if (disambigFile != -1 && src.file != disambigFile) continue
                if (disambigRank != -1 && src.rank != disambigRank) continue
                candidates.add(src)
            }
        }

        if (candidates.isEmpty()) return null
        val src = candidates.first()
        return "${src.name}${dest.name}$promotionSuffix"
    }

    private fun canReach(src: Square, dest: Square, piece: Piece): Boolean {
        // Cannot capture own pieces
        val destPiece = board[dest.rank][dest.file]
        if (destPiece?.color == piece.color) return false

        val df = dest.file - src.file
        val dr = dest.rank - src.rank

        return when (piece.type) {
            PieceType.PAWN -> canPawnReach(src, dest, piece.color)
            PieceType.KNIGHT ->
                (kotlin.math.abs(df) == 2 && kotlin.math.abs(dr) == 1) ||
                (kotlin.math.abs(df) == 1 && kotlin.math.abs(dr) == 2)
            PieceType.BISHOP ->
                kotlin.math.abs(df) == kotlin.math.abs(dr) && df != 0 && isPathClear(src, dest)
            PieceType.ROOK ->
                (df == 0) xor (dr == 0) && isPathClear(src, dest)
            PieceType.QUEEN ->
                ((kotlin.math.abs(df) == kotlin.math.abs(dr) && df != 0) ||
                 (df == 0) xor (dr == 0)) && isPathClear(src, dest)
            PieceType.KING ->
                kotlin.math.abs(df) <= 1 && kotlin.math.abs(dr) <= 1 && (df != 0 || dr != 0)
        }
    }

    private fun canPawnReach(src: Square, dest: Square, color: ChessColor): Boolean {
        val dir = if (color == ChessColor.WHITE) 1 else -1
        val startRank = if (color == ChessColor.WHITE) 1 else 6
        val df = dest.file - src.file
        val dr = dest.rank - src.rank
        return when {
            // Single push
            df == 0 && dr == dir && board[dest.rank][dest.file] == null -> true
            // Double push from start rank
            df == 0 && dr == dir * 2 && src.rank == startRank &&
                board[src.rank + dir][src.file] == null &&
                board[dest.rank][dest.file] == null -> true
            // Capture (normal or en passant)
            kotlin.math.abs(df) == 1 && dr == dir &&
                (board[dest.rank][dest.file] != null || enPassantSquare == dest) -> true
            else -> false
        }
    }

    private fun isPathClear(src: Square, dest: Square): Boolean {
        val df = Integer.signum(dest.file - src.file)
        val dr = Integer.signum(dest.rank - src.rank)
        var f = src.file + df
        var r = src.rank + dr
        while (f != dest.file || r != dest.rank) {
            if (board[r][f] != null) return false
            f += df
            r += dr
        }
        return true
    }
}
