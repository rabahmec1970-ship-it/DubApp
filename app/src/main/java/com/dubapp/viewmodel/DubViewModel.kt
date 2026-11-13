package com.dubapp.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dubapp.models.AppState
import com.dubapp.models.Language
import com.dubapp.models.SubtitleEntry
import com.dubapp.services.AudioExtractor
import com.dubapp.services.SpeechToTextService
import com.dubapp.services.TTSService
import com.dubapp.services.TranslationService
import com.dubapp.services.VideoProcessor
import com.dubapp.utils.FileUtils
import com.dubapp.utils.SrtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DubViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val audioExtractor = AudioExtractor()
    private val speechToTextService = SpeechToTextService(application)
    private val translationService = TranslationService()
    private val ttsService = TTSService(application)
    private val videoProcessor = VideoProcessor()

    private var currentJob: Job? = null

    // ─── Step 1: Video → SRT ────────────────────────────────────────────

    fun setVideoUri(uri: Uri) {
        val context = getApplication<Application>()
        val fileName = FileUtils.getFileNameFromUri(context, uri)
        _state.update { it.copy(videoUri = uri, videoFileName = fileName, error = null) }
    }

    fun extractSubtitles() {
        val uri = _state.value.videoUri ?: return
        val context = getApplication<Application>()

        currentJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(isProcessing = true, progress = 0f, progressMessage = "جاري استخراج الصوت...")
                }

                // Copy video from content URI to a local file
                val outputDir = FileUtils.getOutputDir(context)
                val localVideo = FileUtils.getUniqueFileName(outputDir, "input_video", "mp4")
                withContext(Dispatchers.IO) {
                    FileUtils.copyUriToFile(context, uri, localVideo)
                }

                // Extract audio to WAV
                val audioFile = File(outputDir, "extracted_audio.wav")
                _state.update { it.copy(progress = 0.1f) }

                val audioResult = withContext(Dispatchers.IO) {
                    audioExtractor.extractAudio(
                        inputVideoPath = localVideo.absolutePath,
                        outputAudioPath = audioFile.absolutePath
                    ) { progress ->
                        _state.update { it.copy(progress = 0.1f + progress * 0.3f) }
                    }
                }

                audioResult.getOrThrow()

                // Transcribe audio to subtitle entries
                _state.update {
                    it.copy(progress = 0.4f, progressMessage = "جاري تحليل الكلام...")
                }

                val transcribeResult = withContext(Dispatchers.IO) {
                    speechToTextService.transcribeAudio(audioFile.absolutePath) { progress, _ ->
                        _state.update { it.copy(progress = 0.4f + progress * 0.4f) }
                    }
                }

                val subtitles = transcribeResult.getOrThrow()

                // Generate and save SRT file
                _state.update {
                    it.copy(progress = 0.85f, progressMessage = "جاري حفظ ملف الترجمة...")
                }

                val srtContent = SrtParser.generateSrt(subtitles)
                val baseName = _state.value.videoFileName.substringBeforeLast(".")
                val srtFile = FileUtils.getUniqueFileName(outputDir, baseName, "srt")

                withContext(Dispatchers.IO) {
                    SrtParser.saveSrtToFile(srtContent, srtFile.absolutePath)
                }

                _state.update {
                    it.copy(
                        subtitles = subtitles,
                        srtFilePath = srtFile.absolutePath,
                        isProcessing = false,
                        progress = 1f,
                        progressMessage = "تم استخراج النص بنجاح!"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = 0f,
                        progressMessage = "",
                        error = e.localizedMessage ?: "حدث خطأ أثناء استخراج النص"
                    )
                }
            }
        }
    }

    // ─── Step 2: Translate SRT ──────────────────────────────────────────

    fun loadSrtFile(path: String) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    File(path).readText()
                }
                val subtitles = SrtParser.parseSrt(content)
                _state.update {
                    it.copy(
                        subtitles = subtitles,
                        srtFilePath = path,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = e.localizedMessage ?: "حدث خطأ أثناء قراءة ملف SRT")
                }
            }
        }
    }

    fun setSelectedLanguage(language: Language) {
        _state.update { it.copy(selectedLanguage = language) }
    }

    fun translateSubtitles() {
        val subtitles = _state.value.subtitles
        if (subtitles.isEmpty()) return
        val targetLanguage = _state.value.selectedLanguage

        currentJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        isProcessing = true,
                        progress = 0f,
                        progressMessage = "جاري تحميل نموذج الترجمة..."
                    )
                }

                val result = translationService.translateSubtitles(
                    subtitles = subtitles,
                    sourceLanguage = "ar",
                    targetLanguage = targetLanguage
                ) { progress ->
                    _state.update {
                        it.copy(
                            progress = progress,
                            progressMessage = if (progress < 0.1f)
                                "جاري تحميل نموذج الترجمة..."
                            else
                                "جاري ترجمة النصوص... (${(progress * 100).toInt()}%)"
                        )
                    }
                }

                val translatedSubtitles = result.getOrThrow()

                // Save translated SRT
                val context = getApplication<Application>()
                val outputDir = FileUtils.getOutputDir(context)
                val srtContent = SrtParser.generateSrt(translatedSubtitles, useTranslated = true)
                val baseName = _state.value.videoFileName.substringBeforeLast(".")
                val translatedSrt = FileUtils.getUniqueFileName(
                    outputDir,
                    "${baseName}_${targetLanguage.code}",
                    "srt"
                )

                withContext(Dispatchers.IO) {
                    SrtParser.saveSrtToFile(srtContent, translatedSrt.absolutePath)
                }

                _state.update {
                    it.copy(
                        translatedSubtitles = translatedSubtitles,
                        translatedSrtPath = translatedSrt.absolutePath,
                        isProcessing = false,
                        progress = 1f,
                        progressMessage = "تمت الترجمة بنجاح!"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = 0f,
                        progressMessage = "",
                        error = e.localizedMessage ?: "حدث خطأ أثناء الترجمة"
                    )
                }
            }
        }
    }

    // ─── Step 3: Dubbing ────────────────────────────────────────────────

    fun startDubbing() {
        val translatedSubtitles = _state.value.translatedSubtitles
        if (translatedSubtitles.isEmpty()) return

        val context = getApplication<Application>()
        val targetLanguage = _state.value.selectedLanguage

        currentJob = viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        isProcessing = true,
                        progress = 0f,
                        progressMessage = "جاري تحويل النص إلى كلام..."
                    )
                }

                // Synthesize TTS audio for each translated subtitle
                val outputDir = FileUtils.getOutputDir(context)
                val ttsDir = File(outputDir, "tts_segments").apply { mkdirs() }

                val ttsResult = ttsService.synthesizeSubtitles(
                    subtitles = translatedSubtitles,
                    language = targetLanguage,
                    outputDir = ttsDir.absolutePath
                ) { progress ->
                    _state.update { it.copy(progress = progress * 0.5f) }
                }

                val audioSegmentPaths = ttsResult.getOrThrow()

                // Build audio segments with timing info
                val audioSegments = audioSegmentPaths.mapIndexed { index, path ->
                    val subtitle = translatedSubtitles[index]
                    Triple(path, subtitle.startTimeMs, subtitle.endTimeMs)
                }

                // Process video: strip original audio + overlay TTS segments
                _state.update {
                    it.copy(progress = 0.5f, progressMessage = "جاري معالجة الفيديو...")
                }

                val videoUri = _state.value.videoUri ?: throw IllegalStateException("لا يوجد فيديو محدد")
                val localVideo = File(outputDir, "input_video.mp4")
                if (!localVideo.exists()) {
                    withContext(Dispatchers.IO) {
                        FileUtils.copyUriToFile(context, videoUri, localVideo)
                    }
                }

                val baseName = _state.value.videoFileName.substringBeforeLast(".")
                val outputVideo = FileUtils.getUniqueFileName(
                    outputDir,
                    "${baseName}_dubbed_${targetLanguage.code}",
                    "mp4"
                )

                val videoResult = withContext(Dispatchers.IO) {
                    videoProcessor.processVideo(
                        videoPath = localVideo.absolutePath,
                        audioSegments = audioSegments,
                        outputPath = outputVideo.absolutePath
                    ) { progress ->
                        _state.update {
                            it.copy(
                                progress = 0.5f + progress * 0.45f,
                                progressMessage = if (progress < 0.8f)
                                    "جاري معالجة الفيديو..."
                                else
                                    "جاري تركيب الصوت النهائي..."
                            )
                        }
                    }
                }

                videoResult.getOrThrow()

                _state.update {
                    it.copy(
                        outputVideoPath = outputVideo.absolutePath,
                        isProcessing = false,
                        progress = 1f,
                        progressMessage = "تمت الدبلجة بنجاح!"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProcessing = false,
                        progress = 0f,
                        progressMessage = "",
                        error = e.localizedMessage ?: "حدث خطأ أثناء الدبلجة"
                    )
                }
            }
        }
    }

    // ─── Utility functions ──────────────────────────────────────────────

    fun cancelProcessing() {
        currentJob?.cancel()
        currentJob = null
        _state.update {
            it.copy(
                isProcessing = false,
                progress = 0f,
                progressMessage = ""
            )
        }
    }

    fun resetState() {
        currentJob?.cancel()
        currentJob = null
        _state.value = AppState()
    }

    fun setCurrentStep(step: Int) {
        _state.update { it.copy(currentStep = step) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun shareVideo(context: Context) {
        val path = _state.value.outputVideoPath
        if (path.isBlank()) return

        val file = File(path)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "مشاركة الفيديو")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun openVideo(context: Context) {
        val path = _state.value.outputVideoPath
        if (path.isBlank()) return

        val file = File(path)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(openIntent)
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        ttsService.shutdown()
    }
}
