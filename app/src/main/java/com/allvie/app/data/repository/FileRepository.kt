package com.allvie.app.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.SortedMap
import java.util.TreeMap
import java.util.zip.ZipInputStream
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
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

    suspend fun scanFiles(): List<FileItem> = withContext(Dispatchers.IO) {
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

        val results = mutableListOf<FileItem>()
        context.contentResolver.query(
            filesUri,
            projection,
            null,
            null,
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

        results.sortedWith(
            compareByDescending<FileItem> { it.lastModified }
                .thenBy { it.displayName.lowercase() }
        )
    }

    suspend fun loadTextContent(uriString: String, maxBytes: Int = 512 * 1024): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            input.readPreviewBytes(maxBytes + 1)
        } ?: return@withContext "Unable to read file."

        val truncated = bytes.size > maxBytes
        val safeBytes = bytes.copyOf(min(bytes.size, maxBytes))
        val text = safeBytes.toString(Charsets.UTF_8)
        if (truncated) {
            "$text\n\n[Preview truncated at ${maxBytes / 1024} KB]"
        } else {
            text
        }
    }

    suspend fun loadXmlSpreadsheetRows(
        uriString: String,
        maxBytes: Int = 2 * 1024 * 1024,
        maxRows: Int = 240,
        maxCols: Int = 30
    ): List<List<String>>? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
            input.readPreviewBytes(maxBytes)
        } ?: return@withContext null

        runCatching {
            parseSpreadsheetXmlRows(bytes, maxRows, maxCols)
        }.getOrNull()
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
            readOfficeContentWithPoi(uri = uri, reader = reader, maxBytes = maxBytes)
        }.recoverCatching {
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


    suspend fun renameFile(file: FileItem, requestedName: String): Result<FileItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanedName = requestedName.trim()
            require(cleanedName.isNotBlank()) { "Name cannot be empty." }

            val targetName = normalizeName(cleanedName, file.displayName)
            if (targetName == file.displayName) {
                return@runCatching file
            }

            val uri = Uri.parse(file.uriString)
            if (isMediaStoreUri(uri)) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                }
                val updatedRows = context.contentResolver.update(uri, values, null, null)
                check(updatedRows > 0) { "Rename failed." }

                queryFileItem(uri, file.pathLabel)
                    ?: file.copy(displayName = targetName)
            } else {
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

    suspend fun deleteFile(uriString: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            if (isMediaStoreUri(uri)) {
                val deletedRows = context.contentResolver.delete(uri, null, null)
                check(deletedRows > 0) { "Delete failed." }
            } else {
                val document = DocumentFile.fromSingleUri(context, uri)
                    ?: throw IllegalStateException("File is no longer available.")
                check(document.delete()) { "Delete failed." }
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


    private fun readOfficeContentWithPoi(
        uri: Uri,
        reader: OfficeReader,
        maxBytes: Int
    ): String {
        return when (reader) {
            OfficeReader.DOCX -> readDocxWithPoi(uri, maxBytes)
            OfficeReader.DOC -> readDocWithPoi(uri, maxBytes)
            OfficeReader.XLSX -> readXlsxWithPoi(uri, maxBytes)
            OfficeReader.XLS -> readXlsWithPoi(uri, maxBytes)
            OfficeReader.PPTX -> readPptxWithPoi(uri, maxBytes)
            OfficeReader.PPT -> readPptWithPoi(uri, maxBytes)
            OfficeReader.UNKNOWN -> throw IllegalStateException("Unsupported Office format.")
        }
    }

    private fun readDocxWithPoi(uri: Uri, maxBytes: Int): String {
        val text = openInputStreamOrThrow(uri).use { input ->
            XWPFDocument(input).use { document ->
                XWPFWordExtractor(document).use { extractor ->
                    extractor.text.orEmpty()
                }
            }
        }

        val cleaned = normalizeWhitespace(text)
        if (cleaned.isBlank()) {
            throw IllegalStateException("DOCX contains no readable text.")
        }

        return truncatePreview(cleaned, maxBytes)
    }

    private fun readDocWithPoi(uri: Uri, maxBytes: Int): String {
        val text = openInputStreamOrThrow(uri).use { input ->
            HWPFDocument(input).use { document ->
                WordExtractor(document).use { extractor ->
                    extractor.text.orEmpty()
                }
            }
        }

        val cleaned = normalizeWhitespace(text)
        if (cleaned.isBlank()) {
            throw IllegalStateException("DOC contains no readable text.")
        }

        return truncatePreview(cleaned, maxBytes)
    }

    private fun readXlsxWithPoi(uri: Uri, maxBytes: Int): String {
        val preview = openInputStreamOrThrow(uri).use { input ->
            XSSFWorkbook(input).use { workbook ->
                workbookToPreview(workbook)
            }
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun readXlsWithPoi(uri: Uri, maxBytes: Int): String {
        val preview = openInputStreamOrThrow(uri).use { input ->
            HSSFWorkbook(input).use { workbook ->
                workbookToPreview(workbook)
            }
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun readPptxWithPoi(uri: Uri, maxBytes: Int): String {
        val preview = openInputStreamOrThrow(uri).use { input ->
            XMLSlideShow(input).use { slideShow ->
                val slides = slideShow.slides
                buildString {
                    slides.take(120).forEachIndexed { index, slide ->
                        val slideText = slide.shapes
                            .mapNotNull { shape -> (shape as? XSLFTextShape)?.text?.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(separator = "\n")

                        if (slideText.isNotBlank()) {
                            append("Slide ${index + 1}\n")
                            append(slideText)
                            append("\n\n")
                        }
                    }
                }.trim()
            }
        }

        if (preview.isBlank()) {
            throw IllegalStateException("PPTX contains no readable slides.")
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun readPptWithPoi(uri: Uri, maxBytes: Int): String {
        val preview = openInputStreamOrThrow(uri).use { input ->
            HSLFSlideShow(input).use { slideShow ->
                val slides = slideShow.slides
                buildString {
                    slides.take(120).forEachIndexed { index, slide ->
                        val slideText = slide.shapes
                            .mapNotNull { shape -> (shape as? org.apache.poi.hslf.usermodel.HSLFTextShape)?.text?.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(separator = "\n")

                        if (slideText.isNotBlank()) {
                            append("Slide ${index + 1}\n")
                            append(slideText)
                            append("\n\n")
                        }
                    }
                }.trim()
            }
        }

        if (preview.isBlank()) {
            throw IllegalStateException("PPT contains no readable slides.")
        }

        return truncatePreview(preview, maxBytes)
    }

    private fun workbookToPreview(
        workbook: Workbook,
        maxSheets: Int = 8,
        maxRows: Int = 240,
        maxCols: Int = 30
    ): String {
        val formatter = DataFormatter(Locale.ROOT)
        var emittedRows = 0

        val preview = buildString {
            val sheetCount = min(workbook.numberOfSheets, maxSheets)
            for (sheetIndex in 0 until sheetCount) {
                if (emittedRows >= maxRows) break

                val sheet = workbook.getSheetAt(sheetIndex)
                val header = "Sheet ${sheetIndex + 1}: ${sheet.sheetName}".trim()
                append(header)
                append('\n')

                val firstRow = sheet.firstRowNum.coerceAtLeast(0)
                val lastRow = sheet.lastRowNum.coerceAtLeast(firstRow)

                for (rowIndex in firstRow..lastRow) {
                    if (emittedRows >= maxRows) break

                    val row = sheet.getRow(rowIndex) ?: continue
                    val lastCell = row.lastCellNum.toInt().coerceAtLeast(0).coerceAtMost(maxCols)
                    if (lastCell <= 0) continue

                    val values = MutableList(lastCell) { columnIndex ->
                        val cell = row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
                        if (cell == null) {
                            ""
                        } else {
                            runCatching { formatter.formatCellValue(cell) }
                                .getOrElse { cell.toString() }
                                .trim()
                        }
                    }

                    var lastNonBlank = values.size - 1
                    while (lastNonBlank >= 0 && values[lastNonBlank].isBlank()) {
                        lastNonBlank -= 1
                    }
                    if (lastNonBlank < 0) continue

                    append(values.subList(0, lastNonBlank + 1).joinToString(separator = "\t"))
                    append('\n')
                    emittedRows += 1
                }

                append('\n')
            }

            if (emittedRows >= maxRows) {
                append("[Preview truncated at $maxRows rows]")
            }
        }.trim()

        if (preview.isBlank()) {
            throw IllegalStateException("Spreadsheet contains no readable cells.")
        }

        return preview
    }

    private fun openInputStreamOrThrow(uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open file stream.")
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
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
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
        include: (String) -> Boolean
    ): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val perEntryLimit = min(maxBytes, 512 * 1024)

        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name ?: continue
                    if (!entry.isDirectory && include(entryName)) {
                        result[entryName] = zip.readPreviewBytes(perEntryLimit)
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

    private fun parseSpreadsheetXmlRows(
        xmlBytes: ByteArray,
        maxRows: Int,
        maxCols: Int
    ): List<List<String>>? {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val rows = mutableListOf<List<String>>()
        var currentRow: MutableList<String>? = null
        var currentCellText: StringBuilder? = null
        var captureData = false

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && rows.size < maxRows) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (localName(parser.name).lowercase(Locale.ROOT)) {
                        "row" -> {
                            val row = mutableListOf<String>()
                            val rowIndex = getAttributeByLocalName(parser, "Index")?.toIntOrNull()
                            if (rowIndex != null && rowIndex > rows.size + 1) {
                                while (rows.size < rowIndex - 1 && rows.size < maxRows) {
                                    rows.add(emptyList())
                                }
                            }
                            currentRow = row
                        }

                        "cell" -> {
                            val row = currentRow
                            if (row != null) {
                                val index = getAttributeByLocalName(parser, "Index")?.toIntOrNull()
                                if (index != null && index > row.size + 1) {
                                    while (row.size < index - 1 && row.size < maxCols) {
                                        row.add("")
                                    }
                                }
                            }
                            currentCellText = StringBuilder()
                        }

                        "data" -> {
                            captureData = true
                            currentCellText?.clear()
                        }
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    if (captureData) {
                        currentCellText?.append(parser.text.orEmpty())
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (localName(parser.name).lowercase(Locale.ROOT)) {
                        "data" -> captureData = false

                        "cell" -> {
                            val row = currentRow
                            if (row != null && row.size < maxCols) {
                                row.add(currentCellText?.toString().orEmpty())
                            }
                            currentCellText = null
                        }

                        "row" -> {
                            val row = currentRow
                            if (row != null) {
                                val trimmed = row.take(maxCols).toMutableList()
                                while (trimmed.isNotEmpty() && trimmed.last().isBlank()) {
                                    trimmed.removeAt(trimmed.lastIndex)
                                }
                                if (trimmed.isNotEmpty() || rows.isNotEmpty()) {
                                    rows.add(trimmed)
                                }
                            }
                            currentRow = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        return rows.takeIf { it.isNotEmpty() }
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





