package com.dubapp.services

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioExtractor {

    /**
     * Extract audio from a video file using FFmpegKit.
     * Outputs a 16kHz mono WAV file suitable for speech recognition.
     *
     * @param inputVideoPath  Path to the input video file.
     * @param outputAudioPath Path for the output .wav file.
     * @param onProgress      Callback with progress fraction [0..1].
     * @return Result.success with outputAudioPath, or Result.failure with an error.
     */
    suspend fun extractAudio(
        inputVideoPath: String,
        outputAudioPath: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f)

            val command = "-y -i \"$inputVideoPath\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"$outputAudioPath\""

            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                onProgress(1f)
                Result.success(outputAudioPath)
            } else {
                val logs = session.allLogsAsString ?: "Unknown FFmpeg error"
                Result.failure(RuntimeException("Audio extraction failed (rc=${returnCode}): $logs"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

