package com.allvie.app.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.SortedMap
import java.util.TreeMap
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private enum class OfficeReader {
        DOC,
        DOCX,
        XLS,
        XLSX,
        PPT,
        PPTX,
        UNKNOWN
    }

    private data class PresentationSize(
        val widthEmu: Long = DEFAULT_SLIDE_WIDTH_EMU,
        val heightEmu: Long = DEFAULT_SLIDE_HEIGHT_EMU
    )

    private data class MutableSlideShape(
        val kind: PresentationElementKind,
        var xEmu: Long = 0,
        var yEmu: Long = 0,
        var widthEmu: Long = 0,
        var heightEmu: Long = 0,
        var text: StringBuilder = StringBuilder(),
        var imageUri: String? = null,
        var placeholderType: String? = null,
        var fontSizeSp: Float? = null,
        var isBold: Boolean = false,
        var textAlign: PresentationTextAlign = PresentationTextAlign.START,
        var textColorHex: String? = null,
        var fillColorHex: String? = null
    )
    private val supportedScanExtensions = setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
    private val supportedScanMimeTypes = listOf(
        "application/pdf",
        "text/plain",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    suspend fun scanFiles(): List<FileItem> = withContext(Dispatchers.IO) {
        val mediaStoreFiles = runCatching { scanFilesFromMediaStore() }
            .getOrElse { emptyList() }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return@withContext mediaStoreFiles
        }

        val legacyFiles = scanLegacyFiles()
        if (legacyFiles.isEmpty()) {
            return@withContext mediaStoreFiles
        }

        val merged = LinkedHashMap<String, FileItem>()
        mediaStoreFiles.forEach { file ->
            merged[fileIdentity(file)] = file
        }
        legacyFiles.forEach { file ->
            val key = fileIdentity(file)
            if (!merged.containsKey(key)) {
                merged[key] = file
            }
        }

        merged.values.sortedWith(
            compareByDescending<FileItem> { it.lastModified }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun scanFilesFromMediaStore(): List<FileItem> {
        val filesUri = MediaStore.Files.getContentUri("external")
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.FileColumns.RELATIVE_PATH
        } else {
            MediaStore.Files.FileColumns.DATA
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            pathColumn
        )

        val mimePlaceholders = supportedScanMimeTypes.joinToString(separator = ",") { "?" }
        val extensionSelection = supportedScanExtensions.joinToString(separator = " OR ") {
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        }
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} IN ($mimePlaceholders) OR $extensionSelection)"
        val selectionArgs = supportedScanMimeTypes.toTypedArray() +
            supportedScanExtensions.map { "%.$it" }.toTypedArray()

        val results = mutableListOf<FileItem>()
        context.contentResolver.query(
            filesUri,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndex(pathColumn)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType = cursor.getString(mimeIndex).orEmpty()
                val category = FileCategory.from(mimeType, name) ?: continue
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(filesUri, id)
                val size = cursor.getLong(sizeIndex)
                val modifiedSeconds = cursor.getLong(modifiedIndex)
                val rawPath = if (pathIndex >= 0) cursor.getString(pathIndex) else null

                results.add(
                    FileItem(
                        uriString = uri.toString(),
                        displayName = name,
                        mimeType = mimeType,
                        category = category,
                        size = size,
                        lastModified = if (modifiedSeconds > 0) modifiedSeconds * 1000 else 0,
                        pathLabel = normalizePathLabel(rawPath)
                    )
                )
            }
        }

        return results.sortedWith(
            compareByDescending<FileItem> { it.lastModified }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun scanLegacyFiles(maxResults: Int = 40000): List<FileItem> {
        val queue = ArrayDeque<File>()
        val visitedDirs = HashSet<String>()
        val results = mutableListOf<FileItem>()

        legacyScanRoots().forEach { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
            if (visitedDirs.add(rootPath)) {
                queue.addLast(root)
            }
        }

        while (queue.isNotEmpty() && results.size < maxResults) {
            val dir = queue.removeFirst()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue

            children.forEach { child ->
                if (results.size >= maxResults) return@forEach

                if (child.isDirectory) {
                    if (child.name.startsWith(".") || child.name.equals("Android", ignoreCase = true)) {
                        return@forEach
                    }
                    val canonical = runCatching { child.canonicalPath }.getOrElse { child.absolutePath }
                    if (visitedDirs.add(canonical)) {
                        queue.addLast(child)
                    }
                    return@forEach
                }

                if (!child.isFile) return@forEach

                val extension = child.extension.lowercase(Locale.ROOT)
                if (extension !in supportedScanExtensions) return@forEach

                val name = child.name
                val category = FileCategory.from(mimeTypeFromExtension(extension), name) ?: return@forEach
                val path = child.absolutePath
                results.add(
                    FileItem(
                        uriString = Uri.fromFile(child).toString(),
                        displayName = name,
                        mimeType = mimeTypeFromExtension(extension),
                        category = category,
                        size = child.length().coerceAtLeast(0),
                        lastModified = child.lastModified().coerceAtLeast(0),
                        pathLabel = normalizePathLabel(path)
                    )
                )
            }
        }

        return results.sortedWith(
            compareByDescending<FileItem> { it.lastModified }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun legacyScanRoots(): List<File> {
        val roots = LinkedHashSet<String>()
        runCatching {
            Environment.getExternalStorageDirectory()?.absolutePath
        }.getOrNull()?.let { path ->
            if (path.isNotBlank()) roots.add(path)
        }

        context.getExternalFilesDirs(null)
            .mapNotNull { it?.absolutePath }
            .forEach { path ->
                val rootPath = path.substringBefore("/Android/", path)
                if (rootPath.isNotBlank()) {
                    roots.add(rootPath)
                }
            }

        return roots
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
    }

    private fun fileIdentity(file: FileItem): String {
        val uri = Uri.parse(file.uriString)
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path.orEmpty().lowercase(Locale.ROOT)
        }
        return "${file.displayName.lowercase(Locale.ROOT)}|${file.size}|${file.lastModified}"
    }

    private fun mimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
    suspend fun loadTextContent(uriString: String, maxBytes: Int = 512 * 1024): String = withContext(Dispatchers.IO) {
        val bytes = openInputStream(Uri.parse(uriString))?.use { input ->
            input.readPreviewBytes(maxBytes + 1)
        } ?: return@withContext "Unable to read file."

        val truncated = bytes.size > maxBytes
        val safeBytes = bytes.copyOf(min(bytes.size, maxBytes))
        val text = decodeTextBytes(safeBytes)
        if (truncated) {
            "$text\n\n[Preview truncated at ${maxBytes / 1024} KB]"
        } else {
            text
        }
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun loadOfficeContent(
        uriString: String,
        displayName: String,
        mimeType: String,
        maxBytes: Int = 2 * 1024 * 1024
    ): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val reader = resolveOfficeReader(displayName, mimeType)

        runCatching {
            when (reader) {
                OfficeReader.DOCX -> readDocxPreview(uri, maxBytes)
                OfficeReader.PPTX -> readPptxPreview(uri, maxBytes)
                OfficeReader.XLSX -> readXlsxPreview(uri, maxBytes)
                OfficeReader.DOC,
                OfficeReader.XLS,
                OfficeReader.PPT,
                OfficeReader.UNKNOWN -> readLegacyBinaryOfficePreview(uri, maxBytes)
            }
        }.getOrThrow()
    }

    suspend fun loadPresentationSlides(
        uriString: String,
        displayName: String,
        mimeType: String,
        maxSlides: Int = 120,
        maxImagesPerSlide: Int = 2,
        maxImageBytes: Int = 1_250_000,
        maxTextBytes: Int = 2 * 1024 * 1024
    ): List<PresentationSlideData> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val reader = resolveOfficeReader(displayName, mimeType)
        if (reader != OfficeReader.PPTX && reader != OfficeReader.PPT) {
            return@withContext emptyList()
        }

        val cacheDir = File(
            context.cacheDir,
            "presentation_slides/${(uriString + displayName).hashCode().toUInt().toString(16)}"
        ).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val slides = runCatching {
            when (reader) {
                OfficeReader.PPTX -> readPptxSlidesWithLayout(
                    uri = uri,
                    cacheDir = cacheDir,
                    maxSlides = maxSlides,
                    maxImagesPerSlide = maxImagesPerSlide,
                    maxImageBytes = maxImageBytes
                )
                OfficeReader.PPT -> parsePresentationPreviewToSlides(readLegacyBinaryOfficePreview(uri, maxTextBytes))
                    .take(maxSlides)
                    .map { slide -> slide.withFallbackLayout() }
                else -> emptyList()
            }
        }.recoverCatching {
            when (reader) {
                OfficeReader.PPTX -> {
                    parsePresentationPreviewToSlides(readPptxPreview(uri, maxTextBytes))
                        .map { slide -> slide.withFallbackLayout() }
                }

                OfficeReader.PPT -> {
                    parsePresentationPreviewToSlides(readLegacyBinaryOfficePreview(uri, maxTextBytes))
                        .map { slide -> slide.withFallbackLayout() }
                }

                else -> emptyList()
            }
        }.getOrElse { emptyList() }

        renderPresentationSlidesToCache(
            cacheDir = cacheDir,
            slides = slides,
            maxSlides = maxSlides
        )
    }
    suspend fun renameFile(file: FileItem, requestedName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanedName = requestedName.trim()
            require(cleanedName.isNotBlank()) { "Name cannot be empty." }

            val targetName = normalizeName(cleanedName, file.displayName)
            if (targetName == file.displayName) {
                return@runCatching file
            }

            val uri = Uri.parse(file.uriString)
            when {
                uri.scheme.equals("file", ignoreCase = true) -> {
                    val sourcePath = uri.path ?: throw IllegalStateException("File path is invalid.")
                    val sourceFile = File(sourcePath)
                    check(sourceFile.exists()) { "File is no longer available." }

                    val parent = sourceFile.parentFile ?: throw IllegalStateException("Cannot rename this file.")
                    val targetFile = File(parent, targetName)
                    if (targetFile.absolutePath.equals(sourceFile.absolutePath, ignoreCase = true)) {
                        return@runCatching file
                    }
                    check(!targetFile.exists()) { "A file with this name already exists." }
                    check(sourceFile.renameTo(targetFile)) { "Rename failed." }

                    file.copy(
                        uriString = Uri.fromFile(targetFile).toString(),
                        displayName = targetFile.name,
                        mimeType = mimeTypeFromExtension(targetFile.extension.lowercase(Locale.ROOT)),
                        size = targetFile.length(),
                        lastModified = targetFile.lastModified(),
                        pathLabel = normalizePathLabel(targetFile.absolutePath)
                    )
                }

                isMediaStoreUri(uri) -> {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                    }
                    val updatedRows = context.contentResolver.update(uri, values, null, null)
                    check(updatedRows > 0) { "Rename failed." }

                    queryFileItem(uri, file.pathLabel)
                        ?: file.copy(displayName = targetName)
                }

                else -> {
                    val document = DocumentFile.fromSingleUri(context, uri)
                        ?: throw IllegalStateException("File is no longer available.")
                    check(document.renameTo(targetName)) { "Rename failed." }

                    val refreshed = DocumentFile.fromSingleUri(context, document.uri)
                    documentToFileItem(refreshed ?: document, file.pathLabel)
                        ?: file.copy(
                            uriString = document.uri.toString(),
                            displayName = targetName,
                            mimeType = document.type.orEmpty(),
                            lastModified = document.lastModified()
                        )
                }
            }
        }
    }

    suspend fun deleteFile(uriString: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            when {
                uri.scheme.equals("file", ignoreCase = true) -> {
                    val path = uri.path ?: throw IllegalStateException("File path is invalid.")
                    val localFile = File(path)
                    check(localFile.exists()) { "File is no longer available." }
                    check(localFile.delete()) { "Delete failed." }
                }

                isMediaStoreUri(uri) -> {
                    val deletedRows = context.contentResolver.delete(uri, null, null)
                    check(deletedRows > 0) { "Delete failed." }
                }

                else -> {
                    val document = DocumentFile.fromSingleUri(context, uri)
                        ?: throw IllegalStateException("File is no longer available.")
                    check(document.delete()) { "Delete failed." }
                }
            }
        }
    }
    private fun resolveOfficeReader(displayName: String, mimeType: String): OfficeReader {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        val normalizedMime = mimeType.lowercase(Locale.ROOT)

        return when {
            extension == "docx" || "wordprocessingml.document" in normalizedMime -> OfficeReader.DOCX
            extension == "doc" || normalizedMime == "application/msword" -> OfficeReader.DOC
            extension == "pptx" || "presentationml.presentation" in normalizedMime -> OfficeReader.PPTX
            extension == "ppt" || normalizedMime == "application/vnd.ms-powerpoint" -> OfficeReader.PPT
            extension == "xlsx" || "spreadsheetml.sheet" in normalizedMime -> OfficeReader.XLSX
            extension == "xls" || normalizedMime == "application/vnd.ms-excel" -> OfficeReader.XLS
            else -> OfficeReader.UNKNOWN
        }
    }

    private fun readPptxSlidesWithLayout(
        uri: Uri,
        cacheDir: File,
        maxSlides: Int,
        maxImagesPerSlide: Int,
        maxImageBytes: Int
    ): List<PresentationSlideData> {
        val slideXmlEntries = readZipEntries(
            uri = uri,
            maxBytes = 2 * 1024 * 1024,
            maxEntries = (maxSlides * 2) + 1,
            include = { entryName ->
                entryName == "ppt/presentation.xml" ||
                    (entryName.startsWith("ppt/slides/slide") && entryName.endsWith(".xml")) ||
                    (entryName.startsWith("ppt/slides/_rels/slide") && entryName.endsWith(".xml.rels"))
            }
        )

        val slideSize = parsePresentationSize(slideXmlEntries["ppt/presentation.xml"])
        val slideEntryNames = slideXmlEntries.keys
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy(::slideOrder)
            .take(maxSlides)
        val imageRelationshipsBySlide = slideEntryNames.associate { entryName ->
            val relsEntryName = relationshipEntryNameForSlide(entryName)
            entryName to parsePptxImageRelationships(
                xmlBytes = slideXmlEntries[relsEntryName],
                sourceEntryName = entryName,
                maxImages = maxImagesPerSlide
            )
        }
        val referencedMediaPaths = imageRelationshipsBySlide.values
            .flatMap { it.values }
            .distinct()
            .toSet()
        val persistedMediaUris = persistReferencedPresentationMedia(
            uri = uri,
            cacheDir = cacheDir,
            referencedEntryNames = referencedMediaPaths,
            maxImageBytes = maxImageBytes
        )

        val laidOutSlides = slideEntryNames.mapNotNull { entryName ->
            val slideIndex = slideOrder(entryName)
            val xmlBytes = slideXmlEntries[entryName] ?: return@mapNotNull null
            val imageTargets = imageRelationshipsBySlide[entryName].orEmpty()
            val resolvedImageUris = LinkedHashMap<String, String>()
            imageTargets.forEach { (relationshipId, mediaEntryName) ->
                val imageUri = persistedMediaUris[mediaEntryName]
                if (!imageUri.isNullOrBlank()) {
                    resolvedImageUris[relationshipId] = imageUri
                }
            }

            runCatching {
                val orderedImageUris = imageTargets.values
                    .mapNotNull { persistedMediaUris[it] }
                    .distinct()
                    .take(maxImagesPerSlide)
                val extractedText = runCatching { extractPresentationXmlText(xmlBytes) }.getOrDefault("")
                val safeText = if (extractedText.isBlank() &&
                    imageTargets.isNotEmpty() &&
                    orderedImageUris.isEmpty()
                ) {
                    "Slide contains unsupported elements."
                } else {
                    extractedText
                }
                PresentationSlideData(
                    index = slideIndex,
                    text = safeText,
                    imageUris = orderedImageUris,
                    widthEmu = slideSize.widthEmu,
                    heightEmu = slideSize.heightEmu,
                    backgroundColorHex = "#FFFFFF",
                    elements = parsePptxSlideElements(
                        xmlBytes = xmlBytes,
                        imageUrisByRelationshipId = resolvedImageUris
                    )
                ).withFallbackLayout()
            }.getOrElse {
                val fallbackText = runCatching { extractPresentationXmlText(xmlBytes) }.getOrDefault("")
                val safeFallbackText = if (fallbackText.isBlank() && imageTargets.isNotEmpty()) {
                    "Slide contains unsupported elements."
                } else {
                    fallbackText
                }
                if (safeFallbackText.isBlank()) {
                    null
                } else {
                    PresentationSlideData(
                        index = slideIndex,
                        text = safeFallbackText,
                        widthEmu = slideSize.widthEmu,
                        heightEmu = slideSize.heightEmu,
                        backgroundColorHex = "#FFFFFF"
                    ).withFallbackLayout()
                }
            }
        }

        if (laidOutSlides.isNotEmpty()) {
            return laidOutSlides
        }

        return parsePresentationPreviewToSlides(readPptxPreview(uri, 512 * 1024))
            .take(maxSlides)
            .map {
                it.copy(
                    widthEmu = slideSize.widthEmu,
                    heightEmu = slideSize.heightEmu,
                    backgroundColorHex = "#FFFFFF"
                ).withFallbackLayout()
            }
    }

    private fun parsePresentationSize(xmlBytes: ByteArray?): PresentationSize {
        if (xmlBytes == null) {
            return PresentationSize()
        }

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && localName(parser.name) == "sldSz") {
                val width = getAttributeByLocalName(parser, "cx")?.toLongOrNull() ?: DEFAULT_SLIDE_WIDTH_EMU
                val height = getAttributeByLocalName(parser, "cy")?.toLongOrNull() ?: DEFAULT_SLIDE_HEIGHT_EMU
                return PresentationSize(widthEmu = width, heightEmu = height)
            }
            event = parser.next()
        }

        return PresentationSize()
    }

    private fun relationshipEntryNameForSlide(slideEntryName: String): String {
        val fileName = slideEntryName.substringAfterLast('/')
        return "${slideEntryName.substringBeforeLast('/')}/_rels/$fileName.rels"
    }

    private fun parsePptxImageRelationships(
        xmlBytes: ByteArray?,
        sourceEntryName: String,
        maxImages: Int
    ): LinkedHashMap<String, String> {
        if (xmlBytes == null) return linkedMapOf()

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val relationships = LinkedHashMap<String, String>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                localName(parser.name) == "Relationship"
            ) {
                val id = getAttributeByLocalName(parser, "Id").orEmpty()
                val type = getAttributeByLocalName(parser, "Type").orEmpty()
                val target = getAttributeByLocalName(parser, "Target").orEmpty()
                if (id.isNotBlank() &&
                    target.isNotBlank() &&
                    type.contains("/image", ignoreCase = true)
                ) {
                    relationships[id] = resolveZipEntryTarget(sourceEntryName, target)
                    if (relationships.size >= maxImages) {
                        break
                    }
                }
            }
            event = parser.next()
        }

        return relationships
    }

    private fun resolveZipEntryTarget(sourceEntryName: String, rawTarget: String): String {
        val baseSegments = sourceEntryName
            .substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter { it.isNotBlank() }
            .toMutableList()
        val targetSegments = rawTarget
            .substringBefore('#')
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }

        targetSegments.forEach { segment ->
            if (segment == "..") {
                if (baseSegments.isNotEmpty()) {
                    baseSegments.removeAt(baseSegments.lastIndex)
                }
            } else {
                baseSegments.add(segment)
            }
        }

        return baseSegments.joinToString(separator = "/")
    }

    private fun persistReferencedPresentationMedia(
        uri: Uri,
        cacheDir: File,
        referencedEntryNames: Set<String>,
        maxImageBytes: Int
    ): Map<String, String> {
        if (referencedEntryNames.isEmpty()) return emptyMap()

        val persisted = mutableMapOf<String, String>()
        val maxSourceImageBytes = (maxImageBytes * 4)
            .coerceAtLeast(maxImageBytes)
            .coerceAtMost(5 * 1024 * 1024)
        openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    if (!entry.isDirectory &&
                        !entryName.isNullOrBlank() &&
                        referencedEntryNames.contains(entryName)
                    ) {
                        val declaredSize = entry.size
                        if (declaredSize <= maxSourceImageBytes || declaredSize < 0) {
                            val bytes = zip.readPreviewBytes(maxSourceImageBytes + 1)
                            if (bytes.size <= maxSourceImageBytes) {
                                val persistedUri = persistPresentationImage(
                                    cacheDir = cacheDir,
                                    slideIndex = persisted.size + 1,
                                    imageIndex = 0,
                                    bytes = bytes,
                                    contentType = contentTypeFromEntryName(entryName),
                                    maxOutputBytes = maxImageBytes
                                )
                                if (!persistedUri.isNullOrBlank()) {
                                    persisted[entryName] = persistedUri
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("Unable to open file.")

        return persisted
    }

    private fun parsePptxSlideElements(
        xmlBytes: ByteArray,
        imageUrisByRelationshipId: Map<String, String>
    ): List<PresentationElementData> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val elements = mutableListOf<PresentationElementData>()
        var currentShape: MutableSlideShape? = null
        var captureText = false
        var insideTransform = false
        var insideTextStyle = false

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (localName(parser.name)) {
                        "sp" -> currentShape = MutableSlideShape(kind = PresentationElementKind.TEXT)
                        "pic" -> currentShape = MutableSlideShape(kind = PresentationElementKind.IMAGE)
                        "xfrm" -> if (currentShape != null) insideTransform = true
                        "off" -> {
                            if (insideTransform) {
                                currentShape?.xEmu = getAttributeByLocalName(parser, "x")?.toLongOrNull() ?: 0L
                                currentShape?.yEmu = getAttributeByLocalName(parser, "y")?.toLongOrNull() ?: 0L
                            }
                        }
                        "ext" -> {
                            if (insideTransform) {
                                currentShape?.widthEmu = getAttributeByLocalName(parser, "cx")?.toLongOrNull() ?: 0L
                                currentShape?.heightEmu = getAttributeByLocalName(parser, "cy")?.toLongOrNull() ?: 0L
                            }
                        }
                        "ph" -> {
                            if (currentShape?.kind == PresentationElementKind.TEXT) {
                                currentShape.placeholderType = getAttributeByLocalName(parser, "type")
                            }
                        }
                        "pPr" -> {
                            if (currentShape?.kind == PresentationElementKind.TEXT) {
                                currentShape.textAlign = parsePresentationTextAlign(
                                    getAttributeByLocalName(parser, "algn"),
                                    currentShape.placeholderType
                                )
                            }
                        }
                        "rPr", "defRPr", "endParaRPr" -> {
                            if (currentShape?.kind == PresentationElementKind.TEXT) {
                                insideTextStyle = true
                                currentShape.fontSizeSp = currentShape.fontSizeSp
                                    ?: getAttributeByLocalName(parser, "sz")?.toFloatOrNull()?.div(100f)
                                val bold = getAttributeByLocalName(parser, "b")
                                if (bold == "1" || bold.equals("true", ignoreCase = true)) {
                                    currentShape.isBold = true
                                }
                            }
                        }
                        "srgbClr" -> {
                            if (currentShape?.kind == PresentationElementKind.TEXT && insideTextStyle) {
                                val rawColor = getAttributeByLocalName(parser, "val")
                                if (!rawColor.isNullOrBlank() && currentShape.textColorHex.isNullOrBlank()) {
                                    currentShape.textColorHex = "#$rawColor"
                                }
                            }
                        }
                        "blip" -> {
                            if (currentShape?.kind == PresentationElementKind.IMAGE) {
                                val relationshipId = getAttributeByLocalName(parser, "embed")
                                if (!relationshipId.isNullOrBlank()) {
                                    currentShape.imageUri = imageUrisByRelationshipId[relationshipId]
                                }
                            }
                        }
                        "t" -> if (currentShape?.kind == PresentationElementKind.TEXT) captureText = true
                        "br" -> if (currentShape?.kind == PresentationElementKind.TEXT) appendLineBreak(currentShape.text)
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (captureText && currentShape?.kind == PresentationElementKind.TEXT) {
                        currentShape.text.append(parser.text.orEmpty())
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (localName(parser.name)) {
                        "t" -> captureText = false
                        "p" -> if (currentShape?.kind == PresentationElementKind.TEXT) appendLineBreak(currentShape.text)
                        "xfrm" -> insideTransform = false
                        "rPr", "defRPr", "endParaRPr" -> insideTextStyle = false
                        "sp" -> {
                            currentShape?.toPresentationElement()?.let(elements::add)
                            currentShape = null
                            captureText = false
                            insideTransform = false
                            insideTextStyle = false
                        }
                        "pic" -> {
                            currentShape?.toPresentationElement()?.let(elements::add)
                            currentShape = null
                            insideTransform = false
                            insideTextStyle = false
                        }
                    }
                }
            }
            event = parser.next()
        }

        return elements
    }

    private fun MutableSlideShape.toPresentationElement(): PresentationElementData? {
        val safeWidth = if (widthEmu > 0) widthEmu else DEFAULT_SLIDE_WIDTH_EMU - (DEFAULT_SLIDE_WIDTH_EMU / 10)
        val safeHeight = if (heightEmu > 0) heightEmu else DEFAULT_SLIDE_HEIGHT_EMU / 5
        val safeX = xEmu.coerceAtLeast(0)
        val safeY = yEmu.coerceAtLeast(0)

        return when (kind) {
            PresentationElementKind.TEXT -> {
                val normalizedText = normalizeWhitespace(text.toString())
                if (normalizedText.isBlank()) {
                    null
                } else {
                    PresentationElementData(
                        kind = PresentationElementKind.TEXT,
                        xEmu = safeX,
                        yEmu = safeY,
                        widthEmu = safeWidth,
                        heightEmu = safeHeight,
                        text = normalizedText,
                        fontSizeSp = fontSizeSp ?: defaultFontSizeForPlaceholder(placeholderType, safeHeight),
                        isBold = isBold || isTitlePlaceholder(placeholderType),
                        textAlign = textAlign,
                        textColorHex = textColorHex ?: "#111111",
                        fillColorHex = fillColorHex
                    )
                }
            }

            PresentationElementKind.IMAGE -> {
                val uri = imageUri
                if (uri.isNullOrBlank()) {
                    null
                } else {
                    PresentationElementData(
                        kind = PresentationElementKind.IMAGE,
                        xEmu = safeX,
                        yEmu = safeY,
                        widthEmu = safeWidth,
                        heightEmu = if (heightEmu > 0) heightEmu else DEFAULT_SLIDE_HEIGHT_EMU / 2,
                        imageUri = uri
                    )
                }
            }
        }
    }

    private fun PresentationSlideData.withFallbackLayout(): PresentationSlideData {
        if (elements.isNotEmpty()) {
            return this
        }

        return copy(
            elements = buildFallbackPresentationElements(
                text = text,
                imageUris = imageUris,
                slideWidthEmu = widthEmu,
                slideHeightEmu = heightEmu
            )
        )
    }

    private fun buildFallbackPresentationElements(
        text: String,
        imageUris: List<String>,
        slideWidthEmu: Long,
        slideHeightEmu: Long
    ): List<PresentationElementData> {
        val elements = mutableListOf<PresentationElementData>()
        val horizontalMargin = slideWidthEmu / 14
        val topMargin = slideHeightEmu / 16
        val bottomMargin = slideHeightEmu / 16
        val usableWidth = slideWidthEmu - (horizontalMargin * 2)
        val verticalGap = slideHeightEmu / 30
        var currentTop = topMargin

        if (text.isNotBlank()) {
            val hasImages = imageUris.isNotEmpty()
            val textHeight = if (hasImages) slideHeightEmu / 3 else slideHeightEmu - topMargin - bottomMargin
            elements += PresentationElementData(
                kind = PresentationElementKind.TEXT,
                xEmu = horizontalMargin,
                yEmu = currentTop,
                widthEmu = usableWidth,
                heightEmu = textHeight,
                text = text,
                fontSizeSp = if (text.length <= 80) 28f else 18f,
                isBold = ((text.lineSequence().firstOrNull()?.length ?: 0) in 1..42),
                textColorHex = "#111111"
            )
            currentTop += textHeight + verticalGap
        }

        if (imageUris.isNotEmpty()) {
            val visibleImages = imageUris.take(3)
            val remainingHeight = (slideHeightEmu - currentTop - bottomMargin).coerceAtLeast(slideHeightEmu / 4)
            val slotHeight = (remainingHeight / visibleImages.size).coerceAtLeast(slideHeightEmu / 5)
            visibleImages.forEachIndexed { index, imageUri ->
                elements += PresentationElementData(
                    kind = PresentationElementKind.IMAGE,
                    xEmu = horizontalMargin,
                    yEmu = currentTop + (index * slotHeight),
                    widthEmu = usableWidth,
                    heightEmu = (slotHeight - (verticalGap / 2)).coerceAtLeast(slideHeightEmu / 6),
                    imageUri = imageUri
                )
            }
        }

        return elements
    }

    private fun parsePresentationTextAlign(
        rawAlignment: String?,
        placeholderType: String?
    ): PresentationTextAlign {
        return when (rawAlignment?.lowercase(Locale.ROOT)) {
            "ctr", "dist", "just" -> PresentationTextAlign.CENTER
            "r" -> PresentationTextAlign.END
            else -> {
                when (placeholderType?.lowercase(Locale.ROOT)) {
                    "ctrtitle", "subtitle" -> PresentationTextAlign.CENTER
                    else -> PresentationTextAlign.START
                }
            }
        }
    }

    private fun defaultFontSizeForPlaceholder(
        placeholderType: String?,
        heightEmu: Long
    ): Float {
        return when (placeholderType?.lowercase(Locale.ROOT)) {
            "title", "ctrtitle" -> 28f
            "subtitle" -> 20f
            else -> if (heightEmu >= DEFAULT_SLIDE_HEIGHT_EMU / 3) 20f else 16f
        }
    }

    private fun isTitlePlaceholder(placeholderType: String?): Boolean {
        return when (placeholderType?.lowercase(Locale.ROOT)) {
            "title", "ctrtitle" -> true
            else -> false
        }
    }

    private fun appendLineBreak(buffer: StringBuilder) {
        if (buffer.isNotEmpty() && buffer.last() != '\n') {
            buffer.append('\n')
        }
    }

    private fun renderPresentationSlidesToCache(
        cacheDir: File,
        slides: List<PresentationSlideData>,
        maxSlides: Int
    ): List<PresentationSlideData> {
        if (slides.isEmpty()) return emptyList()

        val renderDir = File(cacheDir, "rendered").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        return slides.take(maxSlides).map { slide ->
            runCatching {
                val imageUri = renderSlideToJpeg(renderDir, slide)
                if (imageUri.isNullOrBlank()) slide else slide.copy(renderedImageUri = imageUri)
            }.getOrDefault(slide)
        }
    }

    private fun renderSlideToJpeg(
        renderDir: File,
        slide: PresentationSlideData
    ): String? {
        val aspectRatio = (slide.widthEmu.toFloat() / slide.heightEmu.coerceAtLeast(1L).toFloat())
            .takeIf { it.isFinite() && it > 0f } ?: (4f / 3f)
        val targetWidth = 1280
        val targetHeight = (targetWidth / aspectRatio).roundToInt().coerceIn(720, 1280)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val backgroundColor = slide.backgroundColorHex.toAndroidColorOrNull() ?: Color.WHITE
        canvas.drawColor(backgroundColor)

        val slideWidth = slide.widthEmu.coerceAtLeast(1L).toFloat()
        val slideHeight = slide.heightEmu.coerceAtLeast(1L).toFloat()
        slide.elements.forEach { element ->
            val rect = RectF(
                targetWidth * (element.xEmu.toFloat() / slideWidth),
                targetHeight * (element.yEmu.toFloat() / slideHeight),
                targetWidth * ((element.xEmu + element.widthEmu).toFloat() / slideWidth),
                targetHeight * ((element.yEmu + element.heightEmu).toFloat() / slideHeight)
            )
            if (rect.width() <= 1f || rect.height() <= 1f) return@forEach

            when (element.kind) {
                PresentationElementKind.TEXT -> drawSlideText(canvas, rect, element, targetWidth)
                PresentationElementKind.IMAGE -> drawSlideImage(canvas, rect, element.imageUri)
            }
        }

        if (slide.elements.isEmpty() && slide.text.isNotBlank()) {
            val fallbackElement = PresentationElementData(
                kind = PresentationElementKind.TEXT,
                xEmu = slide.widthEmu / 12,
                yEmu = slide.heightEmu / 10,
                widthEmu = slide.widthEmu - (slide.widthEmu / 6),
                heightEmu = slide.heightEmu - (slide.heightEmu / 5),
                text = slide.text,
                fontSizeSp = if (slide.text.length <= 80) 30f else 20f,
                textColorHex = "#111111"
            )
            drawSlideText(
                canvas = canvas,
                rect = RectF(
                    targetWidth / 12f,
                    targetHeight / 10f,
                    targetWidth - (targetWidth / 12f),
                    targetHeight - (targetHeight / 10f)
                ),
                element = fallbackElement,
                renderWidth = targetWidth
            )
        }

        val output = File(renderDir, "slide_${slide.index.coerceAtLeast(1)}.jpg")
        return try {
            output.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)
            }
            Uri.fromFile(output).toString()
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawSlideText(
        canvas: Canvas,
        rect: RectF,
        element: PresentationElementData,
        renderWidth: Int
    ) {
        val fill = element.fillColorHex.toAndroidColorOrNull()
        if (fill != null) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                style = Paint.Style.FILL
                alpha = 44
                canvas.drawRect(rect, this)
            }
        }

        val fontPx = ((element.fontSizeSp ?: 18f) * (renderWidth / 960f)).coerceIn(16f, 72f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.textColorHex.toAndroidColorOrNull() ?: Color.rgb(17, 17, 17)
            textSize = fontPx
            typeface = if (element.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = when (element.textAlign) {
                PresentationTextAlign.CENTER -> Paint.Align.CENTER
                PresentationTextAlign.END -> Paint.Align.RIGHT
                PresentationTextAlign.START -> Paint.Align.LEFT
            }
        }
        val padding = (fontPx * 0.35f).coerceAtLeast(6f)
        val maxTextWidth = (rect.width() - (padding * 2)).coerceAtLeast(1f)
        val lines = wrapSlideText(element.text, paint, maxTextWidth)
        val lineHeight = fontPx * 1.22f
        var baseline = rect.top + padding + fontPx
        val anchorX = when (element.textAlign) {
            PresentationTextAlign.CENTER -> rect.centerX()
            PresentationTextAlign.END -> rect.right - padding
            PresentationTextAlign.START -> rect.left + padding
        }

        for (line in lines) {
            if (baseline > rect.bottom - padding) break
            canvas.drawText(line, anchorX, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrapSlideText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { paragraph ->
            var current = ""
            paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    if (current.isNotBlank()) lines.add(current)
                    current = word
                }
            }
            if (current.isNotBlank()) {
                lines.add(current)
            }
        }
        return lines
    }

    private fun drawSlideImage(
        canvas: Canvas,
        rect: RectF,
        imageUri: String?
    ) {
        if (imageUri.isNullOrBlank()) return

        val bitmap = decodeSlideImage(imageUri, rect.width().roundToInt(), rect.height().roundToInt())
            ?: return
        try {
            canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSlideImage(
        imageUri: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val uri = Uri.parse(imageUri)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        var sample = 1
        while ((bounds.outWidth / sample) > targetWidth * 2 ||
            (bounds.outHeight / sample) > targetHeight * 2
        ) {
            sample *= 2
        }

        return openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        }
    }

    private fun String?.toAndroidColorOrNull(): Int? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Color.parseColor(if (raw.startsWith("#")) raw else "#$raw") }
            .getOrNull()
    }

    private fun parsePresentationPreviewToSlides(content: String): List<PresentationSlideData> {
        if (content.isBlank()) return emptyList()

        val normalized = content.replace("\r\n", "\n")
        val markerRegex = Regex("(?m)^Slide\\s+\\d+\\b.*$")
        val markers = markerRegex.findAll(normalized).toList()

        if (markers.isEmpty()) {
            return normalized
                .split("\n\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(120)
                .mapIndexed { index, body ->
                    PresentationSlideData(index = index + 1, text = body)
                }
        }

        val slides = mutableListOf<PresentationSlideData>()
        markers.forEachIndexed { markerIndex, match ->
            val start = match.range.first
            val end = if (markerIndex + 1 < markers.size) {
                markers[markerIndex + 1].range.first
            } else {
                normalized.length
            }
            val chunk = normalized.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                val label = match.value
                val number = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: (markerIndex + 1)
                val body = chunk.replaceFirst(Regex("^Slide\\s+\\d+\\s*\\n?"), "").trim()
                slides.add(PresentationSlideData(index = number, text = body.ifBlank { chunk }))
            }
        }

        return slides.take(120)
    }

    private fun persistPresentationImage(
        cacheDir: File,
        slideIndex: Int,
        imageIndex: Int,
        bytes: ByteArray,
        contentType: String?,
        maxOutputBytes: Int = Int.MAX_VALUE
    ): String? {
        if (bytes.isEmpty()) return null
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val optimized = optimizePresentationImage(
            bytes = bytes,
            contentType = contentType,
            maxOutputBytes = maxOutputBytes
        ) ?: return null
        val hash = optimized.bytes.contentHashCode().toUInt().toString(16)
        val extension = optimized.extension
        val file = File(cacheDir, "slide_${slideIndex}_${imageIndex}_$hash.$extension")
        runCatching {
            if (!file.exists()) {
                file.writeBytes(optimized.bytes)
            }
        }.getOrElse {
            return null
        }
        return Uri.fromFile(file).toString()
    }

    private data class OptimizedPresentationImage(
        val bytes: ByteArray,
        val extension: String
    )

    private fun optimizePresentationImage(
        bytes: ByteArray,
        contentType: String?,
        maxOutputBytes: Int
    ): OptimizedPresentationImage? {
        val defaultExtension = extensionFromContentType(contentType)
        if (bytes.isEmpty()) return null
        if (bytes.size <= maxOutputBytes) {
            return OptimizedPresentationImage(bytes = bytes, extension = defaultExtension)
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        }.getOrElse {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var inSampleSize = 1
        while ((bounds.outWidth / inSampleSize) > 1600 ||
            (bounds.outHeight / inSampleSize) > 1600 ||
            (bytes.size / inSampleSize) > maxOutputBytes
        ) {
            inSampleSize *= 2
        }

        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        }.getOrNull() ?: return null

        return try {
            val prefersAlpha = bitmap.hasAlpha()
            val output = ByteArrayOutputStream()
            val extension = if (prefersAlpha) "png" else "jpg"
            val format = if (prefersAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            var quality = 88

            do {
                output.reset()
                bitmap.compress(format, quality, output)
                if (prefersAlpha) {
                    break
                }
                quality -= 10
            } while (output.size() > maxOutputBytes && quality >= 58)

            if (output.size() > maxOutputBytes) {
                null
            } else {
                OptimizedPresentationImage(
                    bytes = output.toByteArray(),
                    extension = extension
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun extensionFromContentType(contentType: String?): String {
        val normalized = contentType.orEmpty().lowercase(Locale.ROOT)
        return when {
            "png" in normalized -> "png"
            "jpeg" in normalized || "jpg" in normalized -> "jpg"
            "gif" in normalized -> "gif"
            "webp" in normalized -> "webp"
            "bmp" in normalized -> "bmp"
            else -> "png"
        }
    }

    private fun contentTypeFromEntryName(entryName: String): String? {
        return when (entryName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> null
        }
    }

    private fun openInputStream(uri: Uri): InputStream? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            return runCatching { File(path).inputStream() }.getOrNull()
        }
        return context.contentResolver.openInputStream(uri)
    }

    private fun readDocxPreview(uri: Uri, maxBytes: Int): String {
        val xmlEntries = readZipEntries(
            uri = uri,
            maxBytes = maxBytes,
            include = { name ->
                name == "word/document.xml" ||
                    name.startsWith("word/header") ||
                    name.startsWith("word/footer")
            }
        )

        if (xmlEntries.isEmpty()) {
            throw IllegalStateException("Unable to read DOCX content.")
        }

        val orderedNames = xmlEntries.keys.sortedWith(
            compareBy<String> {
                when {
                    it == "word/document.xml" -> 0
                    it.startsWith("word/header") -> 1
                    else -> 2
                }
            }.thenBy { it }
        )

        val merged = buildString {
            orderedNames.forEachIndexed { index, name ->
                val text = extractWordXmlText(xmlEntries.getValue(name))
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(text)
                }
                if (index >= 4) return@forEachIndexed
            }
        }.trim()

        if (merged.isBlank()) {
            throw IllegalStateException("DOCX contains no readable text.")
        }

        return truncatePreview(merged, maxBytes)
    }

    private fun readPptxPreview(uri: Uri, maxBytes: Int): String {
        val slideEntries = readZipEntries(
            uri = uri,
            maxBytes = maxBytes,
            maxEntries = 120,
            include = { name ->
                name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
            }
        )

        if (slideEntries.isEmpty()) {
            throw IllegalStateException("Unable to read PPTX slides.")
        }

        val ordered = slideEntries.keys.sortedBy { slideOrder(it) }
        val preview = buildString {
            ordered.forEachIndexed { index, name ->
                val text = extractPresentationXmlText(slideEntries.getValue(name))
                if (text.isNotBlank()) {
                    append("Slide ${index + 1}\n")
                    append(text)
                    append("\n\n")
                }
            }
        }.trim()

        if (preview.isBlank()) {
            throw IllegalStateException("PPTX contains no readable text.")
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun readXlsxPreview(uri: Uri, maxBytes: Int): String {
        val xlsxEntries = readZipEntries(
            uri = uri,
            maxBytes = maxBytes,
            include = { name ->
                name == "xl/sharedStrings.xml" ||
                    (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
            }
        )

        val sheetNames = xlsxEntries.keys
            .filter { it.startsWith("xl/worksheets/sheet") }
            .sortedBy { sheetOrder(it) }

        if (sheetNames.isEmpty()) {
            throw IllegalStateException("Unable to read XLSX sheets.")
        }

        val sharedStrings = xlsxEntries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) }.orEmpty()

        val preview = buildString {
            sheetNames.forEachIndexed { sheetIndex, sheetName ->
                val rows = parseSheetRows(xlsxEntries.getValue(sheetName), sharedStrings)
                    .take(120)
                if (rows.isNotEmpty()) {
                    append("Sheet ${sheetIndex + 1}\n")
                    rows.forEach { row ->
                        append(row)
                        append('\n')
                    }
                    append('\n')
                }
            }
        }.trim()

        if (preview.isBlank()) {
            throw IllegalStateException("XLSX contains no readable rows.")
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun readLegacyBinaryOfficePreview(uri: Uri, maxBytes: Int): String {
        val bytes = openInputStream(uri)?.use { input ->
            input.readPreviewBytes(maxBytes)
        } ?: throw IllegalStateException("Unable to read file.")

        val ascii = extractAsciiRuns(bytes)
        val utf16 = extractUtf16LeRuns(bytes)

        val merged = LinkedHashSet<String>()
        ascii.forEach { merged.add(it) }
        utf16.forEach { merged.add(it) }

        val lines = merged
            .map { it.trim() }
            .filter { it.length >= 3 }
            .take(240)

        if (lines.isEmpty()) {
            throw IllegalStateException("Unable to extract readable text from this legacy Office file.")
        }

        return buildString {
            append("Legacy Office preview (best effort)\n\n")
            lines.forEach { line ->
                append(line)
                append('\n')
            }
        }.trimEnd()
    }

    private fun readZipEntries(
        uri: Uri,
        maxBytes: Int,
        maxEntries: Int = Int.MAX_VALUE,
        include: (String) -> Boolean
    ): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val perEntryLimit = min(maxBytes, 512 * 1024)

        openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name
                    if (!entry.isDirectory &&
                        !entryName.isNullOrBlank() &&
                        include(entryName)
                    ) {
                        result[entryName] = zip.readPreviewBytes(perEntryLimit)
                        if (result.size >= maxEntries) {
                            zip.closeEntry()
                            break
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("Unable to open file.")

        return result
    }

    private fun extractWordXmlText(xmlBytes: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val sb = StringBuilder()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (localName(parser.name)) {
                        "tab" -> sb.append('\t')
                        "br", "cr" -> sb.append('\n')
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty()
                    if (text.isNotBlank()) {
                        sb.append(text)
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (localName(parser.name) == "p") {
                        if (sb.isNotEmpty() && sb.last() != '\n') {
                            sb.append('\n')
                        }
                    }
                }
            }
            event = parser.next()
        }

        return normalizeWhitespace(sb.toString())
    }

    private fun extractPresentationXmlText(xmlBytes: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val sb = StringBuilder()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty().trim()
                    if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    val tag = localName(parser.name)
                    if (tag == "p" || tag == "txBody") {
                        if (sb.isNotEmpty() && sb.last() != '\n') {
                            sb.append('\n')
                        }
                    }
                }
            }
            event = parser.next()
        }

        return normalizeWhitespace(sb.toString())
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val values = mutableListOf<String>()
        val current = StringBuilder()
        var insideSi = false
        var event = parser.eventType

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    if (localName(parser.name) == "si") {
                        insideSi = true
                        current.clear()
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (insideSi) {
                        current.append(parser.text.orEmpty())
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (localName(parser.name) == "si") {
                        values.add(current.toString())
                        insideSi = false
                    }
                }
            }
            event = parser.next()
        }

        return values
    }

    private fun getAttributeByLocalName(
        parser: org.xmlpull.v1.XmlPullParser,
        expectedName: String
    ): String? {
        for (index in 0 until parser.attributeCount) {
            val rawName = parser.getAttributeName(index)
            val local = rawName.substringAfter(':', rawName)
            if (rawName.equals(expectedName, ignoreCase = true) ||
                local.equals(expectedName, ignoreCase = true)
            ) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun parseSheetRows(xmlBytes: ByteArray, sharedStrings: List<String>): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val rows = mutableListOf<String>()
        var currentRow: SortedMap<Int, String> = TreeMap()
        var cellRef: String? = null
        var cellType: String? = null
        var capture = false
        val valueBuffer = StringBuilder()

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (localName(parser.name)) {
                        "row" -> {
                            currentRow = TreeMap()
                        }

                        "c" -> {
                            cellRef = parser.getAttributeValue(null, "r")
                            cellType = parser.getAttributeValue(null, "t")
                            valueBuffer.clear()
                        }

                        "v" -> {
                            capture = true
                            valueBuffer.clear()
                        }

                        "t" -> {
                            if (cellType == "inlineStr") {
                                capture = true
                                valueBuffer.clear()
                            }
                        }
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (capture) {
                        valueBuffer.append(parser.text.orEmpty())
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (localName(parser.name)) {
                        "v" -> capture = false
                        "t" -> if (cellType == "inlineStr") capture = false
                        "c" -> {
                            val rawValue = valueBuffer.toString().trim()
                            if (rawValue.isNotEmpty()) {
                                val resolved = when (cellType) {
                                    "s" -> rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: rawValue
                                    "b" -> if (rawValue == "1") "TRUE" else "FALSE"
                                    else -> rawValue
                                }
                                val index = cellRef?.let(::cellColumnIndex) ?: currentRow.size
                                currentRow[index] = resolved
                            }
                            valueBuffer.clear()
                        }

                        "row" -> {
                            if (currentRow.isNotEmpty()) {
                                val first = currentRow.firstKey()
                                val last = currentRow.lastKey()
                                val line = buildString {
                                    for (idx in first..last) {
                                        append(currentRow[idx].orEmpty())
                                        if (idx < last) append('\t')
                                    }
                                }.trimEnd()
                                if (line.isNotBlank()) {
                                    rows.add(line)
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        return rows
    }

    private fun cellColumnIndex(reference: String): Int {
        val col = reference.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
        if (col.isEmpty()) return 0

        var value = 0
        col.forEach { char ->
            value = value * 26 + (char.code - 'A'.code + 1)
        }
        return (value - 1).coerceAtLeast(0)
    }

    private fun extractAsciiRuns(bytes: ByteArray, minRun: Int = 5): List<String> {
        val lines = mutableListOf<String>()
        val run = StringBuilder()

        fun flush() {
            if (run.length >= minRun) {
                lines.add(run.toString())
            }
            run.clear()
        }

        bytes.forEach { byte ->
            val code = byte.toInt() and 0xFF
            val printable = code in 32..126 || code == 9 || code == 10 || code == 13
            if (printable) {
                run.append(code.toChar())
            } else {
                flush()
            }
        }
        flush()

        return lines.flatMap { it.split("\n", "\r") }
            .map { it.trim() }
            .filter { it.length >= minRun }
    }

    private fun extractUtf16LeRuns(bytes: ByteArray, minRun: Int = 5): List<String> {
        val lines = mutableListOf<String>()
        val run = StringBuilder()
        var index = 0

        fun flush() {
            if (run.length >= minRun) {
                lines.add(run.toString())
            }
            run.clear()
        }

        while (index + 1 < bytes.size) {
            val lo = bytes[index].toInt() and 0xFF
            val hi = bytes[index + 1].toInt() and 0xFF
            val printable = hi == 0 && (lo in 32..126 || lo == 9 || lo == 10 || lo == 13)

            if (printable) {
                run.append(lo.toChar())
                index += 2
            } else {
                flush()
                index += 1
            }
        }
        flush()

        return lines.flatMap { it.split("\n", "\r") }
            .map { it.trim() }
            .filter { it.length >= minRun }
    }

    private fun localName(name: String): String {
        return name.substringAfter(':', name)
    }

    private fun slideOrder(entryName: String): Int {
        return entryName.removePrefix("ppt/slides/slide")
            .removeSuffix(".xml")
            .toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun sheetOrder(entryName: String): Int {
        return entryName.removePrefix("xl/worksheets/sheet")
            .removeSuffix(".xml")
            .toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun normalizeWhitespace(value: String): String {
        return value.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
    }

    private fun truncatePreview(content: String, maxBytes: Int): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return content

        val clipped = bytes.copyOf(maxBytes)
            .toString(Charsets.UTF_8)
        return "$clipped\n\n[Preview truncated at ${maxBytes / 1024} KB]"
    }

    private fun queryFileItem(uri: Uri, fallbackPathLabel: String): FileItem? {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            pathColumn
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndex(pathColumn)

            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            if (name.isNullOrBlank()) return null

            val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
            val category = FileCategory.from(mimeType, name) ?: return null
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            val modifiedSeconds = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
            val rawPath = if (pathIndex >= 0) cursor.getString(pathIndex) else null

            return FileItem(
                uriString = uri.toString(),
                displayName = name,
                mimeType = mimeType,
                category = category,
                size = size,
                lastModified = if (modifiedSeconds > 0) modifiedSeconds * 1000 else 0,
                pathLabel = normalizePathLabel(rawPath, fallbackPathLabel)
            )
        }

        return null
    }

    private fun normalizePathLabel(rawPath: String?, fallback: String = "Device storage"): String {
        val candidate = when {
            rawPath.isNullOrBlank() -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> rawPath.trimEnd('/')
            else -> rawPath.substringBeforeLast('/', missingDelimiterValue = rawPath).trimEnd('/')
        }

        return candidate?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    private fun documentToFileItem(document: DocumentFile, pathLabel: String): FileItem? {
        val name = document.name ?: return null
        val category = FileCategory.from(document.type, name) ?: return null
        return FileItem(
            uriString = document.uri.toString(),
            displayName = name,
            mimeType = document.type.orEmpty(),
            category = category,
            size = document.length(),
            lastModified = document.lastModified(),
            pathLabel = pathLabel
        )
    }

    private fun normalizeName(requestedName: String, originalName: String): String {
        val originalExtension = originalName.substringAfterLast('.', missingDelimiterValue = "")
        val requestedHasExtension = requestedName.contains('.')
        return if (originalExtension.isNotBlank() && !requestedHasExtension) {
            "$requestedName.$originalExtension"
        } else {
            requestedName
        }
    }
}

private fun InputStream.readPreviewBytes(limit: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8_192)
    var total = 0

    while (true) {
        val bytesRead = read(chunk)
        if (bytesRead <= 0) break

        val allowed = min(bytesRead, limit - total)
        if (allowed > 0) {
            buffer.write(chunk, 0, allowed)
            total += allowed
        }
        if (total >= limit) break
    }

    return buffer.toByteArray()
}


