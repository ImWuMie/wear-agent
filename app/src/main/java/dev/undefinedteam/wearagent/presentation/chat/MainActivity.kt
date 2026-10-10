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
import dev.undefinedteam.wearagent.session.VoiceState
import kotlinx.coroutines.delay
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
    val voiceState = MutableStateFlow(VoiceState.IDLE)
    val voicePartial = MutableStateFlow("")
    private var appLanguage: String = ""

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
        appLanguage = lang
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
                    voiceState = voiceState,
                    voicePartial = voicePartial,
                    onSend = ::send,
                    onStop = { service?.stopTurn() },
                    onVoicePress = ::onVoicePress,
                    onVoiceRelease = ::onVoiceRelease,
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
        cancelVoice()
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
            log.append(sessionId, ChatMessage(id, userMessage = true, text = text.trim()))
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

    /** Press-and-hold entry point: start mic on press, release finalizes. */
    fun onVoicePress() {
        onSpeech = ::send
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onVoiceRelease() {
        if (voiceState.value == VoiceState.LISTENING) speech?.stopListening()
    }

    /** Briefly surfaces a recognition failure, then returns the row to idle. */
    private fun showErrorTemporarily() {
        voiceState.value = VoiceState.ERROR
        lifecycleScope.launch {
            delay(2500)
            if (voiceState.value == VoiceState.ERROR) voiceState.value = VoiceState.IDLE
        }
    }

    fun cancelVoice() {
        speech?.cancel()
        voiceState.value = VoiceState.IDLE
        voicePartial.value = ""
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showErrorTemporarily()
            return
        }
        voiceState.value = VoiceState.LISTENING
        voicePartial.value = ""
        val recognizer = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { created ->
            created.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    voiceState.value = VoiceState.IDLE
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    voicePartial.value = ""
                    if (text.isNotBlank()) onSpeech(text)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) showErrorTemporarily() else voiceState.value = VoiceState.IDLE
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { voicePartial.value = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech = created
        }
        voiceState.value = VoiceState.LISTENING
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .apply {
                    when (appLanguage) {
                        "zh" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        "en" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    }
                },
        )
    }
}