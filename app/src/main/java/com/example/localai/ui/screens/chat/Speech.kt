package com.example.localai.ui.screens.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Voice input: the platform SpeechRecognizer. Audio is handled by the recognition service, never
// recorded or stored by LocalAI. An on-device recognizer is preferred; a service that may send
// audio to its provider is used only after the user has been told so.
// ---------------------------------------------------------------------------------------------

/** Which speech recognition this device offers. */
sealed interface VoiceAvailability {
    /** Recognition runs on the device (Android 13+ on-device service). */
    data object OnDevice : VoiceAvailability

    /** Only the default recognition service, which may transcribe on its provider's servers. */
    data class SystemService(val label: String) : VoiceAvailability

    data class Unavailable(val reason: String) : VoiceAvailability
}

/** What the voice sheet shows. */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data class Transcribing(val partial: String) : VoiceState
    data class Failed(val message: String, val canUseSystemService: Boolean = false) : VoiceState
}

fun voiceAvailability(context: Context): VoiceAvailability {
    if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return VoiceAvailability.OnDevice
    }
    return systemServiceOrUnavailable(context)
}

private fun systemServiceOrUnavailable(context: Context): VoiceAvailability =
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
        VoiceAvailability.SystemService(recognitionServiceLabel(context))
    } else {
        VoiceAvailability.Unavailable("No speech recognition service is installed on this device.")
    }

/** The name of the service that would transcribe the user's speech, for the disclosure. */
private fun recognitionServiceLabel(context: Context): String {
    val pm = context.packageManager
    val configured = runCatching {
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.packageName
    }.getOrNull()
    val pkg = configured ?: runCatching {
        pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).firstOrNull()?.serviceInfo?.packageName
    }.getOrNull()
    return pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        ?: "your device's speech service"
}

/** The system fallback, when the on-device recognizer cannot handle the user's language. */
fun systemVoiceFallback(context: Context): VoiceAvailability.SystemService? =
    systemServiceOrUnavailable(context) as? VoiceAvailability.SystemService

/** A user-facing explanation of a SpeechRecognizer error code. */
fun speechErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognised. Try again."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard. Try again."
    SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for voice input."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a network connection."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech service is busy. Try again in a moment."
    SpeechRecognizer.ERROR_SERVER, 11 /* SERVER_DISCONNECTED */ -> "The speech service failed."
    10 /* TOO_MANY_REQUESTS */ -> "The speech service is receiving too many requests. Try again later."
    12 /* LANGUAGE_NOT_SUPPORTED */ -> "Speech recognition on this device does not support your language."
    13 /* LANGUAGE_UNAVAILABLE */ -> "Speech recognition for your language is not installed on this device (a language pack is needed)."
    else -> "Voice input failed (error $code)."
}

/** Errors after which the default (possibly online) service may still work. */
private fun onDeviceLanguageError(code: Int) = code == 12 || code == 13

/** Adds a transcript to what is already typed, leaving the result editable in the message box. */
fun mergeTranscript(draft: String, transcript: String): String {
    val t = transcript.trim()
    return when {
        t.isEmpty() -> draft
        draft.isBlank() -> t
        else -> draft.trimEnd() + " " + t
    }
}

/** One recognition session at a time. Must be used from the main thread. */
class SpeechInput(context: Context) {

    private val context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    var state by mutableStateOf<VoiceState>(VoiceState.Idle)
        private set

    /** Starts listening; [onResult] receives the final transcript. */
    fun start(onDevice: Boolean, onResult: (String) -> Unit) {
        release()
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: RuntimeException) {
            state = VoiceState.Failed("Voice input could not start (${e.javaClass.simpleName}).")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onEndOfSpeech() {
                (state as? VoiceState.Listening)?.let { state = VoiceState.Transcribing(it.partial) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.firstResult() ?: return
                state = if (state is VoiceState.Transcribing) VoiceState.Transcribing(text) else VoiceState.Listening(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                release()
                if (text.isNullOrBlank()) {
                    state = VoiceState.Failed(speechErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
                } else {
                    state = VoiceState.Idle
                    onResult(text)
                }
            }

            override fun onError(error: Int) {
                release()
                state = VoiceState.Failed(speechErrorMessage(error), canUseSystemService = onDevice && onDeviceLanguageError(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        state = VoiceState.Listening("")
        r.startListening(intent)
    }

    /** Ends listening; the transcript of what was said so far is delivered. */
    fun finish() {
        val current = state
        if (current is VoiceState.Listening) {
            state = VoiceState.Transcribing(current.partial)
            recognizer?.stopListening()
        }
    }

    /** Stops without a result. */
    fun cancel() {
        recognizer?.cancel()
        release()
        state = VoiceState.Idle
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------------------------------
// Voice output: the platform TextToSpeech engine, restricted to voices that work offline.
// ---------------------------------------------------------------------------------------------

sealed interface ReadAloudStatus {
    data object Initializing : ReadAloudStatus
    data object Ready : ReadAloudStatus
    data class Unavailable(val reason: String) : ReadAloudStatus
}

/** Splits text into pieces the engine accepts, at sentence or word boundaries; code fences are dropped. */
fun speechChunks(text: String, maxLength: Int): List<String> {
    val clean = text.replace("```", " ").replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty() || maxLength <= 0) return emptyList()
    val chunks = mutableListOf<String>()
    var rest = clean
    while (rest.length > maxLength) {
        val window = rest.substring(0, maxLength)
        val cut = listOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "))
            .maxOrNull()?.takeIf { it > 0 }?.plus(1)
            ?: window.lastIndexOf(' ').takeIf { it > 0 }
            ?: maxLength
        chunks += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}

/** Reads assistant messages aloud, one at a time. Create and use on the main thread; call [shutdown]. */
class ReadAloud(context: Context) {

    private val main = Handler(Looper.getMainLooper())

    var status by mutableStateOf<ReadAloudStatus>(ReadAloudStatus.Initializing)
        private set

    /** The message being read, if any. */
    var speakingId by mutableStateOf<Long?>(null)
        private set

    private var released = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code -> main.post { onInit(code) } }

    private fun onInit(code: Int) {
        if (released) return
        if (code != TextToSpeech.SUCCESS) {
            status = ReadAloudStatus.Unavailable("No text-to-speech engine is available on this device.")
            return
        }
        val languageOk = tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE ||
            tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        if (!languageOk) {
            status = ReadAloudStatus.Unavailable("The text-to-speech engine has no voice installed for your language.")
            return
        }
        // Keep speech on the device: never use a voice that needs a network connection.
        val voice = runCatching { tts.voice }.getOrNull()
        if (voice == null || voice.isNetworkConnectionRequired) {
            val local = runCatching { tts.voices }.getOrNull().orEmpty().firstOrNull {
                !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features &&
                    it.locale.language == (voice?.locale ?: Locale.getDefault()).language
            }
            if (local == null || tts.setVoice(local) != TextToSpeech.SUCCESS) {
                status = ReadAloudStatus.Unavailable("No offline text-to-speech voice is installed for your language.")
                return
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId, lastOnly = true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId, lastOnly = false)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId, lastOnly = false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId, lastOnly = false)
        })
        status = ReadAloudStatus.Ready
    }

    private fun finished(utteranceId: String?, lastOnly: Boolean) {
        if (utteranceId == null || (lastOnly && !utteranceId.endsWith(LAST))) return
        val id = utteranceId.substringBefore(':').toLongOrNull()
        main.post { if (speakingId == id) speakingId = null }
    }

    fun speak(messageId: Long, text: String) {
        if (status != ReadAloudStatus.Ready) return
        tts.stop()
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)
        if (chunks.isEmpty()) return
        speakingId = messageId
        chunks.forEachIndexed { i, chunk ->
            val id = if (i == chunks.lastIndex) "$messageId$LAST" else "$messageId:$i"
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                tts.stop()
                speakingId = null
                return
            }
        }
    }

    fun stop() {
        if (!released) tts.stop()
        speakingId = null
    }

    fun shutdown() {
        if (released) return
        released = true
        tts.stop()
        tts.shutdown()
        speakingId = null
    }

    private companion object {
        const val LAST = ":last"
    }
}
