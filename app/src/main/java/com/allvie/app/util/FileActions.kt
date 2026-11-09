package com.allvie.app.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.core.content.FileProvider
import com.allvie.app.domain.model.FileItem
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun resolveShareUri(context: Context, file: FileItem): Uri? {
    val originalUri = Uri.parse(file.uriString)
    return when {
        originalUri.scheme.equals("file", ignoreCase = true) -> {
            val path = originalUri.path ?: return null
            val localFile = File(path)
            if (!localFile.exists() || !localFile.isFile) return null
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    localFile
                )
            }.getOrNull()
        }

        else -> originalUri
    }
}

fun shareFile(context: Context, file: FileItem): Boolean {
    val shareUri = resolveShareUri(context, file) ?: return false

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, shareUri)
        clipData = ClipData.newUri(context.contentResolver, file.displayName, shareUri)
        type = resolvedMimeType(file)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    if (shareIntent.resolveActivity(context.packageManager) == null) {
        return false
    }

    val chooserIntent = Intent.createChooser(shareIntent, "Share ${file.displayName}").apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    return runCatching {
        context.startActivity(chooserIntent)
        true
    }.getOrElse { false }
}

fun shareFiles(context: Context, files: List<FileItem>): Boolean {
    if (files.isEmpty()) return false
    if (files.size == 1) return shareFile(context, files.first())

    val uris = ArrayList<Uri>(files.size)
    var clip: ClipData? = null
    val mimeTypes = mutableSetOf<String>()

    files.forEach { file ->
        val shareUri = resolveShareUri(context, file) ?: return@forEach
        uris.add(shareUri)
        mimeTypes.add(resolvedMimeType(file))
        if (clip == null) {
            clip = ClipData.newUri(context.contentResolver, file.displayName, shareUri)
        } else {
            clip?.addItem(ClipData.Item(shareUri))
        }
    }

    if (uris.isEmpty()) return false

    val commonMime = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = clip
        type = commonMime
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    if (shareIntent.resolveActivity(context.packageManager) == null) {
        return false
    }

    val chooserIntent = Intent.createChooser(shareIntent, "Share ${uris.size} files").apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    return runCatching {
        context.startActivity(chooserIntent)
        true
    }.getOrElse { false }
}

fun formatFileSize(context: Context, size: Long): String {
    return if (size > 0) {
        Formatter.formatShortFileSize(context, size)
    } else {
        "Unknown size"
    }
}

fun formatTimestamp(timestamp: Long): String {
    return if (timestamp > 0) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    } else {
        "Unknown date"
    }
}

private fun resolvedMimeType(file: FileItem): String {
    val explicit = file.mimeType.trim()
    if (explicit.isNotBlank() && explicit != "application/octet-stream") {
        return explicit
    }

    val extension = file.displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (extension) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }
}
