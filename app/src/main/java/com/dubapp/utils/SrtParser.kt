package com.dubapp.utils

import com.dubapp.models.SubtitleEntry
import java.io.File

object SrtParser {

    /**
     * Parse SRT formatted content into a list of SubtitleEntry.
     * Handles index, timecodes (HH:MM:SS,mmm --> HH:MM:SS,mmm), and multi-line text.
     */
    fun parseSrt(content: String): List<SubtitleEntry> {
        val entries = mutableListOf<SubtitleEntry>()
        // Split on blank lines to get individual subtitle blocks
        val blocks = content.trim().replace("\r\n", "\n").split(Regex("\n\\s*\n"))

        for (block in blocks) {
            val lines = block.trim().split("\n")
            if (lines.size < 3) continue

            val index = lines[0].trim().toIntOrNull() ?: continue

            val timeLine = lines[1].trim()
            val timeMatch = Regex(
                "(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})"
            ).find(timeLine) ?: continue

            val startTime = timeMatch.groupValues[1].replace('.', ',')
            val endTime = timeMatch.groupValues[2].replace('.', ',')

            // Remaining lines are subtitle text (may be multi-line)
            val text = lines.subList(2, lines.size).joinToString("\n").trim()

            entries.add(
                SubtitleEntry(
                    index = index,
                    startTime = startTime,
                    endTime = endTime,
                    startTimeMs = timeToMs(startTime),
                    endTimeMs = timeToMs(endTime),
                    text = text
                )
            )
        }
        return entries
    }

    /**
     * Convert SRT time format "HH:MM:SS,mmm" to milliseconds.
     */
    fun timeToMs(time: String): Long {
        val normalized = time.replace(',', '.')
        val parts = normalized.split(":", ".")
        if (parts.size != 4) return 0L
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val seconds = parts[2].toLongOrNull() ?: 0L
        val millis = parts[3].toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    /**
     * Convert milliseconds back to SRT time format "HH:MM:SS,mmm".
     */
    fun msToTime(ms: Long): String {
        val totalMs = if (ms < 0) 0L else ms
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val millis = totalMs % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * Generate SRT content string from a list of SubtitleEntry.
     * @param useTranslated if true, uses translatedText instead of text for each entry.
     */
    fun generateSrt(entries: List<SubtitleEntry>, useTranslated: Boolean = false): String {
        val sb = StringBuilder()
        for ((i, entry) in entries.withIndex()) {
            sb.appendLine(i + 1)
            sb.appendLine("${entry.startTime} --> ${entry.endTime}")
            sb.appendLine(if (useTranslated && entry.translatedText.isNotEmpty()) entry.translatedText else entry.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * Save SRT content string to a file.
     */
    fun saveSrtToFile(content: String, filePath: String) {
        File(filePath).writeText(content, Charsets.UTF_8)
    }
}

