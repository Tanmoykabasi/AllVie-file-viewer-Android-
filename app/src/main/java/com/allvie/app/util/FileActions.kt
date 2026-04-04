package com.allvie.app.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import com.allvie.app.domain.model.FileItem
import java.text.DateFormat
import java.util.Date

fun openFileWithSystem(context: Context, file: FileItem): Boolean {
    val uri = Uri.parse(file.uriString)
    val mimeType = resolvedMimeType(file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    if (intent.resolveActivity(context.packageManager) == null) {
        return false
    }

    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse { false }
}

fun shareFile(context: Context, file: FileItem): Boolean {
    val uri = Uri.parse(file.uriString)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
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
        "xml" -> "application/xml"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }
}
