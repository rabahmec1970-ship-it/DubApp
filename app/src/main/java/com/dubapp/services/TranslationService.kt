package com.dubapp.services

import com.dubapp.models.Language
import com.dubapp.models.SubtitleEntry
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationService {

    /**
     * Translate a list of subtitle entries from [sourceLanguage] to [targetLanguage].
     * Downloads the translation model if needed.
     *
     * @return A new list with translatedText filled in.
     */
    suspend fun translateSubtitles(
        subtitles: List<SubtitleEntry>,
        sourceLanguage: String,
        targetLanguage: Language,
        onProgress: (Float) -> Unit
    ): Result<List<SubtitleEntry>> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f)

            val sourceLang = mapToTranslateLanguage(sourceLanguage)
            val targetLang = mapToTranslateLanguage(targetLanguage.mlKitCode)

            if (sourceLang == null || targetLang == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("Unsupported language pair: $sourceLanguage -> ${targetLanguage.mlKitCode}")
                )
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            val translator = Translation.getClient(options)

            // Download model if needed
            onProgress(0.05f)
            try {
                downloadModelInternal(translator)
            } catch (e: Exception) {
                translator.close()
                return@withContext Result.failure(
                    RuntimeException("فشل تحميل نموذج الترجمة: ${e.message}", e)
                )
            }
            onProgress(0.2f)

            // Translate each subtitle
            val translated = mutableListOf<SubtitleEntry>()
            for ((i, entry) in subtitles.withIndex()) {
                val progress = 0.2f + (0.8f * (i + 1) / subtitles.size)

                if (entry.text.isBlank()) {
                    translated.add(entry.copy(translatedText = ""))
                    onProgress(progress)
                    continue
                }

                try {
                    val result = translateText(translator, entry.text)
                    translated.add(entry.copy(translatedText = result))
                } catch (e: Exception) {
                    // On individual entry failure, keep original text
                    translated.add(entry.copy(translatedText = entry.text))
                }
                onProgress(progress)
            }

            translator.close()
            onProgress(1f)
            Result.success(translated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Pre-download a language translation model.
     */
    suspend fun downloadModel(
        language: Language,
        onProgress: (Float) -> Unit
    ) {
        onProgress(0f)
        val targetLang = mapToTranslateLanguage(language.mlKitCode) ?: run {
            onProgress(1f)
            return
        }

        // Use English as source for model download
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()
        val translator = Translation.getClient(options)

        try {
            downloadModelInternal(translator)
            onProgress(1f)
        } finally {
            translator.close()
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────

    private suspend fun downloadModelInternal(
        translator: com.google.mlkit.nl.translate.Translator
    ) = suspendCancellableCoroutine { cont ->
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { cont.resume(Unit) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private suspend fun translateText(
        translator: com.google.mlkit.nl.translate.Translator,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { result -> cont.resume(result) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    private fun mapToTranslateLanguage(code: String): String? {
        return when (code) {
            "en" -> TranslateLanguage.ENGLISH
            "fr" -> TranslateLanguage.FRENCH
            "es" -> TranslateLanguage.SPANISH
            "de" -> TranslateLanguage.GERMAN
            "tr" -> TranslateLanguage.TURKISH
            "ar" -> TranslateLanguage.ARABIC
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "hi" -> TranslateLanguage.HINDI
            "it" -> TranslateLanguage.ITALIAN
            else -> null
        }
    }
}

