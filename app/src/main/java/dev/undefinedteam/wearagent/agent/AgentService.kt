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
import dev.undefinedteam.wearagent.presentation.MainActivity
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException

/**
 * Owns one model turn. The activity only renders [state]; the transcript is
 * appended here so a killed UI can replay it from [SessionLog].
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = ChatClient()
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
        client.cancel()
        turn?.cancel()
        turn = null
        val partial = stateFlow.value.text
        if (partial.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                val config = settings.settings.first()
                val id = (log.load(config.sessionId).maxOfOrNull { it.id } ?: 0L) + 1L
                log.append(
                    config.sessionId,
                    ChatMessage(
                        id,
                        fromUser = false,
                        text = partial,
                        reasoning = stateFlow.value.reasoning
                    )
                )
            }
        }
        stateFlow.value = TurnState(running = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun startTurn() {
        if (turn?.isActive == true) return
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        stateFlow.value = TurnState(running = true)
        turn = scope.launch {
            val result = withContext(Dispatchers.IO) { runTurn() }
            stateFlow.value = result
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private suspend fun runTurn(): TurnState {
        val config = settings.settings.first()
        val history = log.load(config.sessionId)
        if (config.endpoint.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
            return finish("", error = getString(R.string.missing_endpoint))
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var totalPrompt = 0
        var totalCached = 0
        var totalCompletion = 0
        var totalMs = 0L
        val providerHistory =
            ArrayList<TranscriptItem>(history.map { TranscriptItem.Text(it.fromUser, it.text) })
        // TODO(tools): single round only; re-add the multi-round tool loop with Harness.
        val reply = StringBuilder()
        val turn = streamInto(config, providerHistory, reply, reasoning)
        if (turn.error != CANCELED && turn.error == null && reply.isEmpty()) return finish(
            "",
            sessionId = config.sessionId,
            reasoning = reasoning.toString()
        )
        text.append(reply)
        turn.usage?.let { u ->
            totalPrompt += u.prompt
            totalCached += u.cached
            totalCompletion += u.completion
        }
        totalMs += turn.elapsedMs
        return finish(
            text.toString(),
            turn.error,
            config.sessionId,
            reasoning.toString(),
            totalPrompt,
            totalCached,
            totalCompletion,
            totalMs
        )
    }

    private data class StreamResult(
        val error: String? = null,
        val usage: ChatClient.Usage? = null,
        val elapsedMs: Long = 0,
    )

    private fun streamInto(
        config: dev.undefinedteam.wearagent.session.AgentSettings,
        history: List<TranscriptItem>,
        reply: StringBuilder,
        reasoning: StringBuilder,
    ): StreamResult {
        return try {
            val outcome = client.stream(
                config,
                history,
                onText = { delta ->
                    reply.append(delta)
                    publish(reply.toString(), reasoning.toString())
                },
                onReasoning = { delta ->
                    reasoning.append(delta)
                    publish(reply.toString(), reasoning.toString())
                },
            )
            StreamResult(usage = outcome.usage, elapsedMs = outcome.elapsedMs)
        } catch (error: InterruptedIOException) {
            StreamResult(error = CANCELED)
        } catch (error: Exception) {
            StreamResult(error = error.message ?: error.javaClass.simpleName)
        }
    }

    private fun publish(text: String, reasoning: String) {
        scope.launch {
            stateFlow.value = TurnState(running = true, text = text, reasoning = reasoning)
        }
    }

    private fun finish(
        text: String,
        error: String? = null,
        sessionId: String = "",
        reasoning: String = "",
        promptTokens: Int = 0,
        cachedTokens: Int = 0,
        completionTokens: Int = 0,
        elapsedMs: Long = 0,
    ): TurnState {
        if (text.isNotBlank() && error != CANCELED) {
            val id = (log.load(sessionId).maxOfOrNull { it.id } ?: 0L) + 1L
            log.append(
                sessionId, ChatMessage(
                    id, fromUser = false, text = text, reasoning = reasoning,
                    promptTokens = promptTokens, cachedTokens = cachedTokens,
                    completionTokens = completionTokens, elapsedMs = elapsedMs,
                )
            )
        }
        return TurnState(running = false, text = text, error = error?.takeUnless { it == CANCELED })
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
        private const val CANCELED = "canceled"
        private const val CHANNEL = "turns"
        private const val NOTIFICATION_ID = 1
    }
}
