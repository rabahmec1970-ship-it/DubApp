package com.dubapp.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.dubapp.models.SubtitleEntry
import com.dubapp.utils.SrtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class SpeechToTextService(private val context: Context) {

    companion object {
        private const val CHUNK_DURATION_SECONDS = 30
    }

    /**
     * Transcribe audio file to subtitle entries.
     * Splits audio into 30-second chunks and processes each with SpeechRecognizer.
     * Falls back to silence detection if speech recognition fails.
     *
     * @param audioPath  Path to the WAV audio file (16kHz mono).
     * @param onProgress Callback with (progress 0..1, status message).
     * @return Result with list of SubtitleEntry.
     */
    suspend fun transcribeAudio(
        audioPath: String,
        onProgress: (Float, String) -> Unit
    ): Result<List<SubtitleEntry>> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f, "جاري تحليل الصوت...")

            // Get audio duration
            val durationMs = getAudioDurationMs(audioPath)
            if (durationMs <= 0) {
                return@withContext Result.failure(RuntimeException("Could not determine audio duration"))
            }

            val totalChunks = ((durationMs / 1000) / CHUNK_DURATION_SECONDS + 1).toInt()
            val chunkDir = File(File(audioPath).parent, "chunks")
            chunkDir.mkdirs()

            // Split audio into chunks
            onProgress(0.1f, "جاري تقسيم الصوت...")
            val chunkPaths = splitAudioIntoChunks(audioPath, chunkDir.absolutePath, totalChunks)

            if (chunkPaths.isEmpty()) {
                // Fallback to silence detection
                onProgress(0.3f, "جاري تحليل فترات الصمت...")
                return@withContext createSubtitlesFromSilenceDetection(audioPath, durationMs, onProgress)
            }

            // Try speech recognition on each chunk
            val allEntries = mutableListOf<SubtitleEntry>()
            var entryIndex = 1
            var recognitionFailed = false

            for ((chunkIndex, chunkPath) in chunkPaths.withIndex()) {
                val chunkProgress = 0.1f + (0.8f * chunkIndex / chunkPaths.size)
                onProgress(chunkProgress, "جاري التعرف على الكلام... (${chunkIndex + 1}/${chunkPaths.size})")

                val chunkOffsetMs = chunkIndex.toLong() * CHUNK_DURATION_SECONDS * 1000L

                try {
                    val text = withContext(Dispatchers.Main) {
                        recognizeSpeechFromFile(chunkPath)
                    }

                    if (text.isNotEmpty()) {
                        // Split recognized text into sentences and create entries
                        val sentences = splitIntoSentences(text)
                        val chunkDurationMs = CHUNK_DURATION_SECONDS * 1000L
                        val sentenceDurationMs = if (sentences.isNotEmpty()) chunkDurationMs / sentences.size else chunkDurationMs

                        for ((sentIndex, sentence) in sentences.withIndex()) {
                            val startMs = chunkOffsetMs + sentIndex * sentenceDurationMs
                            val endMs = (startMs + sentenceDurationMs).coerceAtMost(durationMs)

                            allEntries.add(
                                SubtitleEntry(
                                    index = entryIndex++,
                                    startTime = SrtParser.msToTime(startMs),
                                    endTime = SrtParser.msToTime(endMs),
                                    startTimeMs = startMs,
                                    endTimeMs = endMs,
                                    text = sentence.trim()
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    recognitionFailed = true
                    break
                }
            }

            // Clean up chunks
            chunkDir.deleteRecursively()

            if (recognitionFailed || allEntries.isEmpty()) {
                // Fallback to silence detection
                onProgress(0.5f, "جاري استخدام طريقة بديلة للتعرف...")
                return@withContext createSubtitlesFromSilenceDetection(audioPath, durationMs, onProgress)
            }

            onProgress(1f, "تم التعرف على الكلام بنجاح")
            Result.success(allEntries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Detect silence segments in an audio file using FFmpeg's silencedetect filter.
     * Returns a list of speech segments as (startMs, endMs) pairs.
     */
    fun detectSilence(audioPath: String): List<Pair<Long, Long>> {
        val command = "-i \"$audioPath\" -af silencedetect=noise=-30dB:d=0.5 -f null -"
        val session = FFmpegKit.execute(command)
        val logs = session.allLogsAsString ?: return emptyList()

        val silenceStarts = mutableListOf<Double>()
        val silenceEnds = mutableListOf<Double>()

        // Parse silencedetect output
        val startRegex = Regex("silence_start:\\s*([\\d.]+)")
        val endRegex = Regex("silence_end:\\s*([\\d.]+)")

        for (match in startRegex.findAll(logs)) {
            silenceStarts.add(match.groupValues[1].toDouble())
        }
        for (match in endRegex.findAll(logs)) {
            silenceEnds.add(match.groupValues[1].toDouble())
        }

        // Build speech segments (gaps between silences)
        val speechSegments = mutableListOf<Pair<Long, Long>>()
        var currentStart = 0L

        for (i in silenceStarts.indices) {
            val silStart = (silenceStarts[i] * 1000).toLong()
            if (silStart > currentStart + 200) { // Minimum 200ms segment
                speechSegments.add(Pair(currentStart, silStart))
            }
            if (i < silenceEnds.size) {
                currentStart = (silenceEnds[i] * 1000).toLong()
            }
        }

        return speechSegments
    }

    // ─── Private helpers ───────────────────────────────────────────────

    private fun getAudioDurationMs(audioPath: String): Long {
        val session = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(audioPath)
        val info = session.mediaInformation ?: return 0L
        val durationStr = info.duration ?: return 0L
        return (durationStr.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    }

    private fun splitAudioIntoChunks(
        audioPath: String,
        outputDir: String,
        expectedChunks: Int
    ): List<String> {
        val chunkPaths = mutableListOf<String>()
        val command = "-y -i \"$audioPath\" -f segment -segment_time $CHUNK_DURATION_SECONDS " +
                "-acodec pcm_s16le -ar 16000 -ac 1 \"$outputDir/chunk_%03d.wav\""

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            return emptyList()
        }

        // Collect generated chunk files
        val dir = File(outputDir)
        dir.listFiles()
            ?.filter { it.name.startsWith("chunk_") && it.extension == "wav" }
            ?.sortedBy { it.name }
            ?.forEach { chunkPaths.add(it.absolutePath) }

        return chunkPaths
    }

    private suspend fun recognizeSpeechFromFile(audioPath: String): String =
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resume("")
                return@suspendCancellableCoroutine
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            cont.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra("android.speech.extra.AUDIO_SOURCE", audioPath)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    recognizer.destroy()
                    cont.resume("")
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognizer.destroy()
                    cont.resume(matches?.firstOrNull() ?: "")
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
        }

    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation or at reasonable lengths
        val sentences = text.split(Regex("[.!?،؟]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (sentences.isEmpty()) return listOf(text)

        // Further split very long sentences (~10 words max)
        val result = mutableListOf<String>()
        for (sentence in sentences) {
            val words = sentence.split("\\s+".toRegex())
            if (words.size > 12) {
                words.chunked(10).forEach { chunk ->
                    result.add(chunk.joinToString(" "))
                }
            } else {
                result.add(sentence)
            }
        }
        return result
    }

    private suspend fun createSubtitlesFromSilenceDetection(
        audioPath: String,
        durationMs: Long,
        onProgress: (Float, String) -> Unit
    ): Result<List<SubtitleEntry>> {
        onProgress(0.6f, "جاري تحليل فترات الصمت...")
        val speechSegments = detectSilence(audioPath)

        val entries = if (speechSegments.isNotEmpty()) {
            speechSegments.mapIndexed { index, (startMs, endMs) ->
                SubtitleEntry(
                    index = index + 1,
                    startTime = SrtParser.msToTime(startMs),
                    endTime = SrtParser.msToTime(endMs),
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    text = "[مقطع صوتي ${index + 1}]"  // "[Audio segment N]"
                )
            }
        } else {
            // Ultimate fallback: create entries every 5 seconds
            val intervalMs = 5000L
            val count = (durationMs / intervalMs).toInt().coerceAtLeast(1)
            (0 until count).map { i ->
                val startMs = i * intervalMs
                val endMs = ((i + 1) * intervalMs).coerceAtMost(durationMs)
                SubtitleEntry(
                    index = i + 1,
                    startTime = SrtParser.msToTime(startMs),
                    endTime = SrtParser.msToTime(endMs),
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    text = "[مقطع صوتي ${i + 1}]"
                )
            }
        }

        onProgress(1f, "تم تحليل الصوت")
        return Result.success(entries)
    }
}

