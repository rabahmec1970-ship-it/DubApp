package com.dubapp.services

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dubapp.models.Language
import com.dubapp.models.SubtitleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class TTSService(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    /**
     * Initialize the TextToSpeech engine. Must be called before synthesizing.
     */
    suspend fun init(): Boolean = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            cont.resume(isInitialized)
        }
    }

    /**
     * Shut down the TTS engine and release resources.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * Synthesize a single text string to an audio file.
     *
     * @param text       Text to synthesize.
     * @param language   Locale for the voice.
     * @param outputPath File path for the output WAV file.
     * @return Result.success with outputPath, or Result.failure.
     */
    suspend fun synthesizeToFile(
        text: String,
        language: Locale,
        outputPath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isInitialized || tts == null) {
            return@withContext Result.failure(IllegalStateException("TTS not initialized"))
        }

        val engine = tts!!
        val langResult = engine.setLanguage(language)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return@withContext Result.failure(
                UnsupportedOperationException("Language not supported: $language")
            )
        }

        // Set appropriate speech rate
        engine.setSpeechRate(getSpeechRateForLocale(language))
        engine.setPitch(getPitchForLocale(language))

        try {
            val result = speakToFileInternal(engine, text, outputPath)
            if (result) {
                Result.success(outputPath)
            } else {
                Result.failure(RuntimeException("TTS synthesis failed for: ${text.take(30)}..."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synthesize all subtitle entries to individual audio files.
     *
     * @param subtitles List of subtitles (uses translatedText if available, else text).
     * @param language  Target language for TTS voice.
     * @param outputDir Directory to write audio files.
     * @param onProgress Callback with progress fraction [0..1].
     * @return Result with list of generated audio file paths.
     */
    suspend fun synthesizeSubtitles(
        subtitles: List<SubtitleEntry>,
        language: Language,
        outputDir: String,
        onProgress: (Float) -> Unit
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (!isInitialized || tts == null) {
            return@withContext Result.failure(IllegalStateException("TTS not initialized"))
        }

        val dir = File(outputDir)
        if (!dir.exists()) dir.mkdirs()

        val locale = languageToLocale(language)
        val engine = tts!!

        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return@withContext Result.failure(
                UnsupportedOperationException("Language not supported: ${language.displayName}")
            )
        }

        engine.setSpeechRate(getSpeechRateForLocale(locale))
        engine.setPitch(getPitchForLocale(locale))

        val audioPaths = mutableListOf<String>()
        onProgress(0f)

        for ((i, entry) in subtitles.withIndex()) {
            val textToSpeak = entry.translatedText.ifEmpty { entry.text }
            if (textToSpeak.isBlank()) {
                audioPaths.add("")
                onProgress((i + 1).toFloat() / subtitles.size)
                continue
            }

            val outputPath = File(dir, "tts_${i + 1}.wav").absolutePath

            try {
                val success = speakToFileInternal(engine, textToSpeak, outputPath)
                audioPaths.add(if (success) outputPath else "")
            } catch (e: Exception) {
                audioPaths.add("")
            }

            onProgress((i + 1).toFloat() / subtitles.size)
        }

        Result.success(audioPaths)
    }

    // ─── Private helpers ───────────────────────────────────────────────

    private suspend fun speakToFileInternal(
        engine: TextToSpeech,
        text: String,
        outputPath: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        val utteranceId = UUID.randomUUID().toString()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    cont.resume(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    cont.resume(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                cont.resume(false)
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val file = File(outputPath)
        val result = engine.synthesizeToFile(text, params, file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            cont.resume(false)
        }
    }

    private fun languageToLocale(language: Language): Locale {
        return when (language) {
            Language.ENGLISH -> Locale.ENGLISH
            Language.FRENCH -> Locale.FRENCH
            Language.SPANISH -> Locale("es")
            Language.GERMAN -> Locale.GERMAN
            Language.TURKISH -> Locale("tr")
            Language.ARABIC -> Locale("ar")
            Language.CHINESE -> Locale.CHINESE
            Language.JAPANESE -> Locale.JAPANESE
            Language.KOREAN -> Locale.KOREAN
            Language.PORTUGUESE -> Locale("pt")
            Language.RUSSIAN -> Locale("ru")
            Language.HINDI -> Locale("hi")
            Language.ITALIAN -> Locale.ITALIAN
        }
    }

    /**
     * Returns an appropriate speech rate for the given locale.
     * Some languages sound better slightly slower or faster.
     */
    private fun getSpeechRateForLocale(locale: Locale): Float {
        return when (locale.language) {
            "ar" -> 0.9f
            "zh" -> 0.85f
            "ja" -> 0.9f
            "ko" -> 0.9f
            "hi" -> 0.95f
            "de" -> 0.95f
            else -> 1.0f
        }
    }

    /**
     * Returns an appropriate pitch for the given locale.
     */
    private fun getPitchForLocale(locale: Locale): Float {
        return when (locale.language) {
            "ja" -> 1.1f
            "zh" -> 1.05f
            else -> 1.0f
        }
    }
}

