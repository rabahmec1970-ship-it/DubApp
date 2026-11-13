package com.dubapp.services

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoProcessor {

    /**
     * Process video by replacing its audio with TTS-generated audio segments.
     *
     * @param videoPath     Path to the original video file.
     * @param audioSegments List of (audioFilePath, startTimeMs, endTimeMs) triples.
     * @param outputPath    Path for the final output video.
     * @param onProgress    Callback with progress fraction [0..1].
     * @return Result.success with outputPath, or Result.failure.
     */
    suspend fun processVideo(
        videoPath: String,
        audioSegments: List<Triple<String, Long, Long>>,
        outputPath: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f)

            val tempDir = File(File(outputPath).parent, "temp_processing")
            tempDir.mkdirs()

            // Step 1: Remove original audio from video
            onProgress(0.1f)
            val noAudioPath = File(tempDir, "temp_noaudio.mp4").absolutePath
            val removeResult = removeAudio(videoPath, noAudioPath)
            if (removeResult.isFailure) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(
                    removeResult.exceptionOrNull() ?: RuntimeException("Failed to remove audio")
                )
            }

            // Step 2: Get video duration and create silent audio base
            onProgress(0.2f)
            val durationMs = getVideoDuration(videoPath)
            if (durationMs <= 0) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(RuntimeException("Could not determine video duration"))
            }

            val durationSeconds = durationMs / 1000.0
            val silentAudioPath = File(tempDir, "silent_base.wav").absolutePath
            val silentCmd = "-y -f lavfi -i anullsrc=r=44100:cl=stereo -t $durationSeconds \"$silentAudioPath\""
            val silentSession = FFmpegKit.execute(silentCmd)
            if (!ReturnCode.isSuccess(silentSession.returnCode)) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(RuntimeException("Failed to create silent audio track"))
            }

            // Step 3: Overlay each TTS audio segment at correct timestamp
            onProgress(0.4f)
            val validSegments = audioSegments.filter { (path, _, _) ->
                path.isNotEmpty() && File(path).exists()
            }

            val mixedAudioPath: String
            if (validSegments.isEmpty()) {
                // No TTS segments — use silent audio
                mixedAudioPath = silentAudioPath
            } else {
                mixedAudioPath = File(tempDir, "mixed_audio.wav").absolutePath
                val mixResult = mixAudioSegments(silentAudioPath, validSegments, mixedAudioPath)
                if (mixResult.isFailure) {
                    tempDir.deleteRecursively()
                    return@withContext Result.failure(
                        mixResult.exceptionOrNull() ?: RuntimeException("Failed to mix audio segments")
                    )
                }
            }

            // Step 4: Merge video (no audio) with the mixed audio track
            onProgress(0.7f)
            val mergeCmd = "-y -i \"$noAudioPath\" -i \"$mixedAudioPath\" " +
                    "-c:v copy -c:a aac -b:a 192k -shortest \"$outputPath\""
            val mergeSession = FFmpegKit.execute(mergeCmd)

            // Clean up temp files
            tempDir.deleteRecursively()

            if (ReturnCode.isSuccess(mergeSession.returnCode)) {
                onProgress(1f)
                Result.success(outputPath)
            } else {
                val logs = mergeSession.allLogsAsString ?: "Unknown error"
                Result.failure(RuntimeException("Failed to merge video and audio: $logs"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get video duration in milliseconds using FFprobeKit.
     */
    fun getVideoDuration(videoPath: String): Long {
        val session = FFprobeKit.getMediaInformation(videoPath)
        val info = session.mediaInformation ?: return 0L
        val durationStr = info.duration ?: return 0L
        return (durationStr.toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    }

    /**
     * Strip the audio track from a video file.
     */
    suspend fun removeAudio(
        videoPath: String,
        outputPath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val command = "-y -i \"$videoPath\" -an -c:v copy \"$outputPath\""
            val session = FFmpegKit.execute(command)

            if (ReturnCode.isSuccess(session.returnCode)) {
                Result.success(outputPath)
            } else {
                val logs = session.allLogsAsString ?: "Unknown error"
                Result.failure(RuntimeException("Failed to remove audio: $logs"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────

    /**
     * Mix multiple audio segments onto a silent base track using FFmpeg's
     * adelay and amix filters.
     */
    private fun mixAudioSegments(
        silentBasePath: String,
        segments: List<Triple<String, Long, Long>>,
        outputPath: String
    ): Result<String> {
        return try {
            // Build complex FFmpeg filter to overlay audio segments
            val inputs = StringBuilder()
            val filterParts = StringBuilder()

            // Input 0 is the silent base
            inputs.append("-i \"$silentBasePath\" ")

            for ((i, segment) in segments.withIndex()) {
                val (audioPath, startMs, _) = segment
                inputs.append("-i \"$audioPath\" ")
                // Delay each segment audio to start at the correct timestamp
                filterParts.append("[${i + 1}:a]adelay=${startMs}|${startMs}[delayed$i];")
            }

            // Mix all delayed tracks with the silent base
            val mixInputs = StringBuilder("[0:a]")
            for (i in segments.indices) {
                mixInputs.append("[delayed$i]")
            }
            val totalInputs = segments.size + 1
            filterParts.append("${mixInputs}amix=inputs=$totalInputs:duration=first:dropout_transition=0[out]")

            val command = "-y $inputs-filter_complex \"$filterParts\" -map \"[out]\" " +
                    "-acodec pcm_s16le -ar 44100 -ac 2 \"$outputPath\""

            val session = FFmpegKit.execute(command)
            if (ReturnCode.isSuccess(session.returnCode)) {
                Result.success(outputPath)
            } else {
                val logs = session.allLogsAsString ?: "Unknown error"
                Result.failure(RuntimeException("Failed to mix audio segments: $logs"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

