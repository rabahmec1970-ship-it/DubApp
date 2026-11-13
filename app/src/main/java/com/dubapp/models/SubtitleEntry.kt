package com.dubapp.models

data class SubtitleEntry(
    val index: Int,
    val startTime: String,        // "00:00:01,000"
    val endTime: String,          // "00:00:03,500"
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val translatedText: String = ""
)

