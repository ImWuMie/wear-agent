package dev.undefinedteam.wearagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.presentation.chat.MainActivity
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one model turn. The activity only renders [state]; the transcript is
 * appended here so a killed UI can replay it from [SessionLog].
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: ToolLoop? = null
    private val stateFlow = MutableStateFlow(TurnState())
    private var turn: Job? = null
    private lateinit var log: SessionLog
    private lateinit var settings: SettingsStore

    val state: StateFlow<TurnState> = stateFlow

    override fun onCreate() {
        super.onCreate()
        log = SessionLog(this)
        settings = SettingsStore(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_turns),
                NotificationManager.IMPORTANCE_LOW
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopTurn() else startTurn()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    fun stopTurn() {
        // Let the IO worker pair canceled calls and persist its partial turn exactly once.
        runner?.cancel()
    }

    override fun onDestroy() {
        runner?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTurn() {
        if (turn?.isActive == true) return
        val currentRunner = ToolLoop()
        runner = currentRunner
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        stateFlow.value = TurnState(running = true)
        turn = scope.launch {
            try {
                stateFlow.value = withContext(Dispatchers.IO) { runTurn(currentRunner) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                stateFlow.value = stateFlow.value.copy(
                    running = false,
                    error = error.message ?: error.javaClass.simpleName,
                )
            } finally {
                runner = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun runTurn(currentRunner: ToolLoop): TurnState {
        val config = settings.settings.first()
        if (config.endpoint.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            return TurnState(error = getString(R.string.missing_endpoint))
        }
        val history = log.load(config.sessionId)
        val providerHistory = ArrayList<TranscriptItem>()
        for (message in history) {
            if (!message.fromUser && message.transcript.isNotEmpty()) {
                providerHistory.addAll(message.transcript)
            } else {
                providerHistory.add(TranscriptItem.Text(message.fromUser, message.text))
            }
        }
        val outcome = currentRunner.run(config, providerHistory) { stateFlow.value = it }
        val result = outcome.state
        if (result.text.isNotBlank() || result.reasoning.isNotBlank() || outcome.transcript.isNotEmpty()) {
            val id = (log.load(config.sessionId).maxOfOrNull { it.id } ?: 0L) + 1L
            log.append(
                config.sessionId,
                ChatMessage(
                    id,
                    fromUser = false,
                    text = result.text,
                    reasoning = result.reasoning,
                    promptTokens = outcome.tokens?.prompt ?: 0,
                    cachedTokens = outcome.tokens?.cached ?: 0,
                    completionTokens = outcome.tokens?.completion ?: 0,
                    elapsedMs = outcome.elapsedMs,
                    transcript = outcome.transcript,
                ),
            )
        }
        return result
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.thinking))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    inner class LocalBinder : Binder() {
        fun service(): AgentService = this@AgentService
    }

    companion object {
        const val ACTION_STOP = "dev.undefinedteam.wearagent.STOP"
        private const val CHANNEL = "turns"
        private const val NOTIFICATION_ID = 1
    }
}
