package com.example.chessmobile

import android.content.Context
import kotlin.random.Random

enum class ColorChoice { WHITE, BLACK, RANDOM }

sealed class ProviderConfig {
    data class RandomMoves(val color: ColorChoice) : ProviderConfig()
    data class Lichess(val difficulty: Int) : ProviderConfig()
}

interface GameProvider {
    val name: String
    fun generateOpponentMove(): String?
    fun onGameStart(config: ProviderConfig, context: Context) {}
    fun onGameEnd() {}
}

object RandomMovesProvider : GameProvider {
    override val name = "Random Moves"

    override fun generateOpponentMove(): String {
        val file = ('a' + Random.nextInt(8))
        val rank = Random.nextInt(8) + 1
        return "$file$rank"
    }

    override fun onGameStart(config: ProviderConfig, context: Context) {
        val cfg = config as? ProviderConfig.RandomMoves ?: return
        val playerColor = resolveColor(cfg.color)
        if (playerColor == ChessColor.BLACK) {
            MoveRepository.triggerRandomFirstMove(context)
        }
    }
}

object LichessProvider : GameProvider {
    override val name = "Lichess"
    override fun generateOpponentMove(): String? = null  // stub — no real logic yet
}

fun resolveColor(choice: ColorChoice): ChessColor = when (choice) {
    ColorChoice.WHITE -> ChessColor.WHITE
    ColorChoice.BLACK -> ChessColor.BLACK
    ColorChoice.RANDOM -> if (Random.nextBoolean()) ChessColor.WHITE else ChessColor.BLACK
}
