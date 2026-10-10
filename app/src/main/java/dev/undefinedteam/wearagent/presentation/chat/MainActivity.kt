package dev.undefinedteam.wearagent.presentation.chat

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dev.undefinedteam.wearagent.R
import dev.undefinedteam.wearagent.agent.AgentService
import dev.undefinedteam.wearagent.agent.TurnState
import dev.undefinedteam.wearagent.presentation.LocaleHelper
import dev.undefinedteam.wearagent.presentation.settings.SettingsActivity
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme
import dev.undefinedteam.wearagent.session.ChatMessage
import dev.undefinedteam.wearagent.session.SessionLog
import dev.undefinedteam.wearagent.session.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val turn = MutableStateFlow(TurnState())
    private val sent = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var currentSessionId: String = ""
    private var service: AgentService? = null
    private var speech: SpeechRecognizer? = null
    private var onSpeech: (String) -> Unit = {}

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as AgentService.LocalBinder).service()
            service = bound
            lifecycleScope.launch { bound.state.collectLatest { turn.value = it } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
        }

    override fun attachBaseContext(newBase: Context) {
        val lang = kotlinx.coroutines.runBlocking {
            newBase.dataStoreLanguage()
        }
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    private suspend fun Context.dataStoreLanguage(): String =
        runCatching { SettingsStore(this).settings.first().language }.getOrDefault("")

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        bindService(Intent(this, AgentService::class.java), connection, BIND_AUTO_CREATE)
        // React to session switches (new session, delete, switch in Settings) so the
        // chat always shows the active session instead of stale content.
        lifecycleScope.launch {
            SettingsStore(this@MainActivity).settings.map { it.sessionId }.distinctUntilChanged()
                .collect { id ->
                    if (id != currentSessionId) {
                        currentSessionId = id
                        sent.value =
                            if (id.isBlank()) emptyList() else SessionLog(this@MainActivity).load(id)
                    }
                }
        }
        setContent {
            WearAgentTheme {
                ChatScreen(
                    activity = this,
                    log = SessionLog(this),
                    settings = SettingsStore(this),
                    turn = turn,
                    sent = sent,
                    onSend = ::send,
                    onStop = { service?.stopTurn() },
                    onVoice = ::requestVoice,
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        speech?.destroy()
        unbindService(connection)
        super.onDestroy()
    }

    override fun onPause() {
        hideKeyboard()
        super.onPause()
    }

    private fun send(text: String) {
        if (text.isBlank() || service?.state?.value?.running == true) return
        val log = SessionLog(this)
        val settings = SettingsStore(this)
        lifecycleScope.launch {
            val config = settings.settings.first()
            if (config.endpoint.isBlank() || config.model.isBlank() || config.apiKey.isBlank()) {
                Toast.makeText(this@MainActivity, R.string.missing_endpoint, Toast.LENGTH_SHORT)
                    .show()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                return@launch
            }
            val sessionId =
                config.sessionId.ifBlank { log.create().also { settings.setSession(it) } }
            val id = (log.load(sessionId).maxOfOrNull { it.id } ?: 0L) + 1L
            log.append(sessionId, ChatMessage(id, fromUser = true, text = text.trim()))
            sent.value = log.load(sessionId)
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, AgentService::class.java)
            )
        }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        currentFocus?.let { view ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun requestVoice() {
        onSpeech = ::send
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val recognizer = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { created ->
            created.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onSpeech)
                }

                override fun onError(error: Int) = Unit
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech = created
        }
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                ),
        )
    }
}
