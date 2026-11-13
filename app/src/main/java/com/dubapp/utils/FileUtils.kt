package com.dubapp.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object FileUtils {

    /**
     * Returns app-specific output directory in external files, creates it if needed.
     */
    fun getOutputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "DubApp_Output")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Generates a unique filename in the given directory.
     * If "video.mp4" already exists, tries "video_1.mp4", "video_2.mp4", etc.
     */
    fun getUniqueFileName(dir: File, baseName: String, extension: String): File {
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        var file = File(dir, "$baseName$ext")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${baseName}_$counter$ext")
            counter++
        }
        return file
    }

    /**
     * Copy content URI to a local file using ContentResolver.
     */
    fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(destFile).use { outputStream ->
                inputStream.copyTo(outputStream, bufferSize = 8192)
            }
        } ?: throw IllegalArgumentException("Cannot open input stream for URI: $uri")
    }

    /**
     * Get display name from a content URI using ContentResolver.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "unknown_file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    /**
     * Human-readable file size (e.g., "1.5 MB", "300 KB").
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
            else -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        }
    }
}

