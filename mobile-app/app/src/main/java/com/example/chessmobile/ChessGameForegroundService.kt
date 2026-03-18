package com.example.chessmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process alive while a chess game is active.
 *
 * Responsibilities:
 * - Shows a persistent notification with current game status
 * - Holds a PARTIAL_WAKE_LOCK so coroutines and network I/O keep running when the screen is off
 * - Exposes acquireScreenWakeLock() / releaseScreenWakeLock() for Chess.com gesture execution
 *   (FULL_WAKE_LOCK wakes the screen momentarily so the accessibility gestures can land)
 * - Handles "Stop Game" action from the notification
 */
class ChessGameForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "chess_game_status"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_GAME = "com.example.chessmobile.ACTION_STOP_GAME"

        /** Direct reference so ChessAccessibilityService can acquire the screen wake lock. */
        var instance: ChessGameForegroundService? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var partialWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquirePartialWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_GAME) {
            MoveRepository.endGame(this)
            stopSelf()
            return START_NOT_STICKY
        }

        // Show the notification immediately to satisfy the 5-second deadline
        startForeground(NOTIFICATION_ID, buildNotification(MoveRepository.gameInfo.value))

        // Keep notification content in sync with game state
        scope.launch {
            MoveRepository.gameInfo.collect { info ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(info))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        releasePartialWakeLock()
        releaseScreenWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------
    // Wake locks
    // ---------------------------------------------------------------------------

    private fun acquirePartialWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chessmobile:game").also {
            it.acquire()
        }
    }

    private fun releasePartialWakeLock() {
        partialWakeLock?.takeIf { it.isHeld }?.release()
        partialWakeLock = null
    }

    /**
     * Wakes the screen so that accessibility gestures (Chess.com board taps) can be dispatched
     * even when the phone is locked. Call before dispatching gestures; release shortly after.
     */
    fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        screenWakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "chessmobile:screen"
        ).also { it.acquire(5_000L) } // auto-release after 5 s as a safety net
    }

    fun releaseScreenWakeLock() {
        screenWakeLock?.takeIf { it.isHeld }?.release()
        screenWakeLock = null
    }

    // ---------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chess game status",
            NotificationManager.IMPORTANCE_LOW  // no sound, no heads-up
        ).apply {
            description = "Shown while a chess game is active"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(gameInfo: GameInfo?): Notification {
        val stopIntent = Intent(this, ChessGameForegroundService::class.java).apply {
            action = ACTION_STOP_GAME
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (gameInfo != null) {
            val colorName = gameInfo.playerColor.name.lowercase().replaceFirstChar { it.uppercase() }
            "${gameInfo.providerName} \u00b7 Playing as $colorName"
        } else {
            "Game active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Chess game in progress")
            .setContentText(contentText)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_delete, "Stop Game", stopPendingIntent)
            .build()
    }
}
