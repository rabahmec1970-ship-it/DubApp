package com.dubapp.models

import android.net.Uri

data class AppState(
    val currentStep: Int = 1,
    val videoUri: Uri? = null,
    val videoFileName: String = "",
    val srtFilePath: String = "",
    val translatedSrtPath: String = "",
    val selectedLanguage: Language = Language.ENGLISH,
    val subtitles: List<SubtitleEntry> = emptyList(),
    val translatedSubtitles: List<SubtitleEntry> = emptyList(),
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val progressMessage: String = "",
    val outputVideoPath: String = "",
    val error: String? = null
)

enum class Language(val code: String, val displayName: String, val mlKitCode: String) {
    ENGLISH("en", "الإنجليزية", "en"),
    FRENCH("fr", "الفرنسية", "fr"),
    SPANISH("es", "الإسبانية", "es"),
    GERMAN("de", "الألمانية", "de"),
    TURKISH("tr", "التركية", "tr"),
    ARABIC("ar", "العربية", "ar"),
    CHINESE("zh", "الصينية", "zh"),
    JAPANESE("ja", "اليابانية", "ja"),
    KOREAN("ko", "الكورية", "ko"),
    PORTUGUESE("pt", "البرتغالية", "pt"),
    RUSSIAN("ru", "الروسية", "ru"),
    HINDI("hi", "الهندية", "hi"),
    ITALIAN("it", "الإيطالية", "it")
}

