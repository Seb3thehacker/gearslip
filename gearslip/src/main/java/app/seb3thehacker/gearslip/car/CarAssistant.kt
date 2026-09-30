package app.seb3thehacker.gearslip.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A voice assistant built entirely on Android's own speech APIs - [SpeechRecognizer] to listen,
 * [TextToSpeech] to answer - rather than a bundled recognition engine (see the conversation this
 * was scoped from: a project like Dicio ships its own STT model and isn't built to be embedded
 * as a library, which cuts against how the rest of Gearslip is built). Both APIs just talk to
 * whatever the system has configured, so this works unchanged on GrapheneOS against GrapheneOS
 * Speech Services, or on stock Android against whatever the phone shipped with - Gearslip never
 * names an engine.
 *
 * Listening and speaking live here; deciding what a command *means* is [AssistantCommands], kept
 * separate because it needs a [CarNavigator] and a frame to act on, neither of which this object
 * has - see [pendingCommand].
 */
object CarAssistant {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val heard: String = "",
        val reply: String = "",
        val missingPermission: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Set once a recognition result comes in; [reply] clears it once handled. */
    private val _pendingCommand = MutableStateFlow<String?>(null)
    val pendingCommand: StateFlow<String?> = _pendingCommand

    private var appContext: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts != null) return
        tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Starts listening, or does nothing if the assistant is already busy with the last request.
     * [SpeechRecognizer] must be driven from a thread with a Looper, but this is also called from
     * the raw wire-protocol worker thread ([GearslipRunner]'s key-event handling for the head
     * unit's own PTT button) which has none - so this always hops to the main thread itself
     * rather than asking every caller to remember to.
     */
    fun start() {
        if (Looper.myLooper() == Looper.getMainLooper()) startOnMainThread() else mainHandler.post(::startOnMainThread)
    }

    private fun startOnMainThread() {
        val context = appContext ?: return
        if (_state.value.phase == Phase.LISTENING || _state.value.phase == Phase.THINKING) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = State(Phase.ERROR, reply = "Needs the microphone permission.", missingPermission = true)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = State(Phase.ERROR, reply = "No speech recognizer is set up on this phone.")
            return
        }

        tts?.stop()
        _state.value = State(Phase.LISTENING)

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(phase = Phase.THINKING)
            }

            override fun onError(error: Int) {
                releaseRecognizer()
                _state.value = State(Phase.ERROR, reply = errorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                releaseRecognizer()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    _state.value = State(Phase.ERROR, reply = "Didn't catch that.")
                } else {
                    _state.value = _state.value.copy(phase = Phase.THINKING, heard = text)
                    _pendingCommand.value = text
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text != null) _state.value = _state.value.copy(heard = text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            },
        )
    }

    /** Closes the overlay from wherever it's showing: mid-listen, mid-think, or after speaking. */
    fun cancel() {
        releaseRecognizer()
        tts?.stop()
        _pendingCommand.value = null
        _state.value = State(Phase.IDLE)
    }

    /**
     * Called once [pendingCommand] has been turned into an answer - from [CarUi], the one place
     * that actually has a [CarNavigator] to have acted on it with.
     */
    fun reply(text: String) {
        _pendingCommand.value = null
        _state.value = _state.value.copy(phase = Phase.SPEAKING, reply = text)

        val engine = tts
        if (engine == null || !ttsReady || text.isBlank()) {
            _state.value = _state.value.copy(phase = Phase.IDLE)
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                _state.value = _state.value.copy(phase = Phase.IDLE)
            }
            @Deprecated("Deprecated in Java, no replacement on this API level")
            override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(phase = Phase.IDLE)
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gearslip-assistant")
    }

    private fun releaseRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; it.destroy() }
        recognizer = null
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Needs the microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech recognizer needs a network it doesn't have."
        else -> "Speech recognition failed."
    }

    fun shutdown() {
        releaseRecognizer()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
