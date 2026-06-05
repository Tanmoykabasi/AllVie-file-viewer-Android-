package com.allvie.app.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.LruCache
import android.widget.Toast
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFPicture
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell

class DocxRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val renderDir: File = File(context.cacheDir, "docx_render_cache").apply { mkdirs() }
    private val legacyHandoffKeys = Collections.synchronizedSet(mutableSetOf<String>())

    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    suspend fun readPageCount(
        uriString: String,
        displayName: String,
        mimeType: String,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ): Int {
        if (!ensureDocxOrHandOff(uriString, displayName, mimeType)) return 0

        return withContext(Dispatchers.IO) {
            val document = parseDocx(Uri.parse(uriString))
            paginate(document, targetWidth.coerceAtLeast(MIN_PAGE_WIDTH), targetHeight.coerceAtLeast(MIN_PAGE_HEIGHT)).size
        }
    }

    suspend fun renderPageBitmap(
        uriString: String,
        displayName: String,
        mimeType: String,
        position: Int,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT,
        darkMode: Boolean = isDarkModeEnabled()
    ): Bitmap? {
        if (!ensureDocxOrHandOff(uriString, displayName, mimeType)) return null

        return withContext(Dispatchers.IO) {
            val safePosition = position.coerceAtLeast(0)
            val safeWidth = targetWidth.coerceAtLeast(MIN_PAGE_WIDTH)
            val safeHeight = targetHeight.coerceAtLeast(MIN_PAGE_HEIGHT)
            val sourceKey = sourceCacheKey(uriString, displayName, mimeType)
            val key = cacheKey(sourceKey, safePosition, safeWidth, safeHeight, darkMode)

            bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

            readBitmapFromDisk(sourceKey, safePosition, safeWidth, safeHeight, darkMode)?.let { bitmap ->
                bitmapCache.put(key, bitmap)
                return@withContext bitmap
            }

            val rendered = runCatching {
                val document = parseDocx(Uri.parse(uriString))
                val pages = paginate(document, safeWidth, safeHeight)
                val page = pages.getOrNull(safePosition) ?: return@withContext null
                renderPage(page, safeWidth, safeHeight, darkMode)
            }.getOrNull() ?: return@withContext null

            runCatching { writeBitmapToDisk(sourceKey, safePosition, safeWidth, safeHeight, darkMode, rendered) }
            bitmapCache.put(key, rendered)
            rendered
        }
    }

    suspend fun preloadAround(
        uriString: String,
        displayName: String,
        mimeType: String,
        centerPosition: Int,
        pageCount: Int,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT,
        darkMode: Boolean = isDarkModeEnabled()
    ) = withContext(Dispatchers.IO) {
        val first = (centerPosition - PRELOAD_RADIUS).coerceAtLeast(0)
        val last = (centerPosition + PRELOAD_RADIUS).coerceAtMost(pageCount - 1)
        for (index in first..last) {
            renderPageBitmap(uriString, displayName, mimeType, index, targetWidth, targetHeight, darkMode)
        }
    }

    fun clear() {
        bitmapCache.evictAll()
        renderDir.listFiles()?.forEach { file -> runCatching { file.delete() } }
    }

    private suspend fun ensureDocxOrHandOff(
        uriString: String,
        displayName: String,
        mimeType: String
    ): Boolean {
        if (isLegacyDoc(displayName, mimeType)) {
            val key = sourceCacheKey(uriString, displayName, mimeType)
            if (legacyHandoffKeys.add(key)) {
                withContext(Dispatchers.Main) {
                    openLegacyDocExternal(Uri.parse(uriString), mimeType)
                }
            }
            return false
        }

        require(isDocx(displayName, mimeType)) { "Only .docx files are supported by the native document renderer." }
        return true
    }

    private fun openLegacyDocExternal(uri: Uri, mimeType: String): Boolean {
        Toast.makeText(context, LEGACY_DOC_MESSAGE, Toast.LENGTH_LONG).show()

        val viewUri = shareableUri(uri)
        val resolvedMimeType = mimeType.ifBlank { "application/msword" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewUri, resolvedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val externalComponent = findExternalViewer(intent) ?: return false
        intent.component = externalComponent

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun findExternalViewer(intent: Intent): ComponentName? {
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { resolveInfo -> resolveInfo.activityInfo.packageName != context.packageName }
            ?.activityInfo
            ?.let { activityInfo -> ComponentName(activityInfo.packageName, activityInfo.name) }
    }

    private fun shareableUri(uri: Uri): Uri {
        if (!uri.scheme.equals("file", ignoreCase = true)) return uri
        val path = uri.path ?: return uri
        val file = File(path)
        if (!file.exists()) return uri

        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrDefault(uri)
    }

    private fun parseDocx(uri: Uri): ParsedDocx {
        val input = openInputStream(uri) ?: throw IllegalStateException("Unable to open DOCX file.")
        var document: XWPFDocument? = null

        return try {
            val openedDocument = XWPFDocument(input)
            document = openedDocument
            val blocks = mutableListOf<DocxBlock>()

            for (element in openedDocument.bodyElements.take(MAX_BLOCKS)) {
                when (element) {
                    is XWPFParagraph -> blocks += DocxBlock.Paragraph(parseParagraph(element))
                    is XWPFTable -> blocks += DocxBlock.Table(parseTable(element))
                }
            }

            ParsedDocx(blocks = blocks)
        } finally {
            runCatching { document?.close() }
            runCatching { input.close() }
        }
    }

    private fun parseParagraph(paragraph: XWPFParagraph): DocxParagraph {
        val runs = paragraph.runs
            .take(MAX_RUNS_PER_PARAGRAPH)
            .mapNotNull(::parseRun)

        return DocxParagraph(
            runs = runs,
            alignment = paragraph.alignment.toDocxTextAlignMode()
        )
    }

    private fun parseRun(run: XWPFRun): DocxRun? {
        val text = run.toString().take(MAX_RUN_TEXT)
        val images = run.embeddedPictures
            .take(MAX_IMAGES_PER_RUN)
            .mapNotNull(::parsePicture)

        if (text.isBlank() && images.isEmpty()) return null

        val fontSize = run.fontSize.takeIf { it > 0 }?.toFloat() ?: DEFAULT_FONT_SIZE_SP

        return DocxRun(
            text = text,
            fontSizeSp = fontSize.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
            fontFamily = run.fontFamily?.takeIf { it.isNotBlank() },
            isBold = run.isBold,
            isItalic = run.isItalic,
            color = run.color.toAndroidColorOrNull(),
            images = images
        )
    }

    private fun parsePicture(picture: XWPFPicture): DocxImage? {
        val data = runCatching { picture.pictureData?.data }.getOrNull() ?: return null
        if (data.isEmpty() || data.size > MAX_IMAGE_BYTES) return null

        val contentType = runCatching { picture.pictureData?.packagePart?.contentType }.getOrNull()
        return DocxImage(bytes = data, contentType = contentType)
    }

    private fun parseTable(table: XWPFTable): DocxTable {
        val rows = table.rows
            .take(MAX_TABLE_ROWS)
            .map { row ->
                DocxTableRow(
                    cells = row.tableCells
                        .take(MAX_TABLE_COLUMNS)
                        .map(::parseTableCell)
                )
            }
            .filter { it.cells.isNotEmpty() }

        return DocxTable(rows)
    }

    private fun parseTableCell(cell: XWPFTableCell): DocxTableCell {
        return DocxTableCell(
            paragraphs = cell.paragraphs
                .take(MAX_CELL_PARAGRAPHS)
                .map(::parseParagraph),
            fillColor = cell.color.toAndroidColorOrNull()
        )
    }

    private fun paginate(document: ParsedDocx, targetWidth: Int, targetHeight: Int): List<DocxPage> {
        val metrics = PageMetrics(targetWidth, targetHeight)
        val pages = mutableListOf(MutableDocxPage())
        var cursorY = metrics.marginTop

        fun currentPage(): MutableDocxPage = pages.last()

        fun newPage() {
            pages += MutableDocxPage()
            cursorY = metrics.marginTop
        }

        fun ensureSpace(height: Float) {
            if (cursorY + height > metrics.contentBottom && currentPage().items.isNotEmpty()) {
                newPage()
            }
        }

        fun addItem(item: PageItem, height: Float) {
            ensureSpace(height)
            currentPage().items += item.withY(cursorY)
            cursorY += height
        }

        for (block in document.blocks) {
            when (block) {
                is DocxBlock.Paragraph -> {
                    val text = buildStyledText(block.paragraph, darkMode = false)
                    if (text.isNotBlank()) {
                        val layout = buildStaticLayout(text, block.paragraph.alignment, metrics.contentWidth.roundToInt())
                        addParagraphLayout(
                            layout = layout,
                            text = text,
                            alignment = block.paragraph.alignment,
                            metrics = metrics,
                            currentY = { cursorY },
                            currentPage = ::currentPage,
                            newPage = ::newPage,
                            advance = { usedHeight -> cursorY += usedHeight }
                        )
                        cursorY += metrics.paragraphSpacing
                    }

                    block.paragraph.runs.flatMap { it.images }.forEach { image ->
                        val size = image.scaledSize(metrics.contentWidth, metrics.maxImageHeight) ?: return@forEach
                        addItem(
                            PageItem.Image(
                                y = 0f,
                                image = image,
                                rect = RectF(metrics.marginLeft, 0f, metrics.marginLeft + size.width, size.height)
                            ),
                            size.height + metrics.paragraphSpacing
                        )
                    }
                }

                is DocxBlock.Table -> {
                    addTable(
                        table = block.table,
                        metrics = metrics,
                        currentY = { cursorY },
                        currentPage = ::currentPage,
                        newPage = ::newPage,
                        advance = { usedHeight -> cursorY += usedHeight }
                    )
                    cursorY += metrics.tableSpacing
                }
            }
        }

        return pages
            .filter { it.items.isNotEmpty() }
            .take(MAX_PAGES)
            .map { DocxPage(it.items) }
            .ifEmpty { listOf(DocxPage(emptyList())) }
    }

    private fun addParagraphLayout(
        layout: StaticLayout,
        text: SpannableStringBuilder,
        alignment: DocxTextAlignMode,
        metrics: PageMetrics,
        currentY: () -> Float,
        currentPage: () -> MutableDocxPage,
        newPage: () -> Unit,
        advance: (Float) -> Unit
    ) {
        if (layout.lineCount == 0) return

        var line = 0
        while (line < layout.lineCount) {
            var remaining = metrics.contentBottom - currentY()
            if (remaining < metrics.minTextSliceHeight && currentPage().items.isNotEmpty()) {
                newPage()
                remaining = metrics.contentBottom - currentY()
            }

            val startLine = line
            var endLine = line
            while (endLine < layout.lineCount) {
                val sliceHeight = layout.getLineBottom(endLine) - layout.getLineTop(startLine)
                if (sliceHeight > remaining && endLine > startLine) break
                if (sliceHeight > metrics.contentHeight && endLine > startLine) break
                endLine++
                if (sliceHeight >= remaining) break
            }

            val safeEndLine = endLine.coerceAtLeast(startLine + 1).coerceAtMost(layout.lineCount)
            val charStart = layout.getLineStart(startLine)
            val charEnd = layout.getLineEnd(safeEndLine - 1).coerceAtMost(text.length)
            val slice = SpannableStringBuilder(text.subSequence(charStart, charEnd)).trimTrailingNewlines()
            val sliceLayout = buildStaticLayout(slice, alignment, metrics.contentWidth.roundToInt())
            val y = currentY()
            currentPage().items += PageItem.Text(
                y = y,
                text = slice,
                alignment = alignment,
                width = metrics.contentWidth.roundToInt(),
                height = sliceLayout.height.toFloat()
            )
            advance(sliceLayout.height.toFloat())
            line = safeEndLine
        }
    }

    private fun addTable(
        table: DocxTable,
        metrics: PageMetrics,
        currentY: () -> Float,
        currentPage: () -> MutableDocxPage,
        newPage: () -> Unit,
        advance: (Float) -> Unit
    ) {
        val columnCount = table.rows.maxOfOrNull { it.cells.size }?.coerceAtLeast(1) ?: return
        val columnWidth = metrics.contentWidth / columnCount

        table.rows.forEach { row ->
            var rowHeight = metrics.minTableRowHeight
            val cells = (0 until columnCount).map { index ->
                val cell = row.cells.getOrNull(index) ?: DocxTableCell(emptyList(), null)
                val text = buildCellText(cell)
                val textWidth = (columnWidth - (metrics.cellPadding * 2)).roundToInt().coerceAtLeast(1)
                val layout = buildStaticLayout(text, DocxTextAlignMode.START, textWidth)
                rowHeight = max(rowHeight, layout.height + (metrics.cellPadding * 2))
                PageTableCell(text = text, fillColor = cell.fillColor, layoutWidth = textWidth)
            }

            if (currentY() + rowHeight > metrics.contentBottom && currentPage().items.isNotEmpty()) {
                newPage()
            }

            val y = currentY()
            currentPage().items += PageItem.TableRow(
                y = y,
                cells = cells,
                columnWidth = columnWidth,
                rowHeight = min(rowHeight, metrics.contentHeight)
            )
            advance(min(rowHeight, metrics.contentHeight))
        }
    }

    private fun renderPage(page: DocxPage, targetWidth: Int, targetHeight: Int, darkMode: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val metrics = PageMetrics(targetWidth, targetHeight)

        canvas.drawColor(themeColor(Color.WHITE, darkMode))

        page.items.forEach { item ->
            when (item) {
                is PageItem.Text -> drawTextItem(canvas, item, metrics, darkMode)
                is PageItem.Image -> drawImageItem(canvas, item, darkMode)
                is PageItem.TableRow -> drawTableRow(canvas, item, metrics, darkMode)
            }
        }

        return bitmap
    }

    private fun drawTextItem(canvas: Canvas, item: PageItem.Text, metrics: PageMetrics, darkMode: Boolean) {
        val text = recolorText(item.text, darkMode)
        val layout = buildStaticLayout(text, item.alignment, item.width)
        canvas.save()
        canvas.translate(metrics.marginLeft, item.y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImageItem(canvas: Canvas, item: PageItem.Image, darkMode: Boolean) {
        val rect = RectF(
            item.rect.left,
            item.y,
            item.rect.right,
            item.y + item.rect.height()
        )
        val bitmap = decodeImage(item.image, rect.width().roundToInt(), rect.height().roundToInt()) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (darkMode) colorFilter = INVERT_FILTER
        }

        try {
            canvas.drawBitmap(bitmap, null, rect, paint)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun drawTableRow(canvas: Canvas, item: PageItem.TableRow, metrics: PageMetrics, darkMode: Boolean) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor(Color.rgb(190, 190, 190), darkMode)
            style = Paint.Style.STROKE
            strokeWidth = metrics.borderWidth
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        item.cells.forEachIndexed { index, cell ->
            val left = metrics.marginLeft + (index * item.columnWidth)
            val right = left + item.columnWidth
            val rect = RectF(left, item.y, right, item.y + item.rowHeight)
            fillPaint.color = themeColor(cell.fillColor ?: Color.TRANSPARENT, darkMode)
            if (fillPaint.color != Color.TRANSPARENT) {
                canvas.drawRect(rect, fillPaint)
            }
            canvas.drawRect(rect, borderPaint)

            val text = recolorText(cell.text, darkMode)
            val layout = buildStaticLayout(text, DocxTextAlignMode.START, cell.layoutWidth)
            canvas.save()
            canvas.clipRect(rect)
            canvas.translate(left + metrics.cellPadding, item.y + metrics.cellPadding)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildStyledText(paragraph: DocxParagraph, darkMode: Boolean): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        paragraph.runs.forEach { run ->
            if (run.text.isBlank()) return@forEach

            val start = builder.length
            builder.append(run.text)
            val end = builder.length
            builder.setSpan(AbsoluteSizeSpan(run.fontSizeSp.roundToInt(), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(themeColor(run.color ?: Color.rgb(24, 24, 24), darkMode)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.isBold || run.isItalic) {
                val style = when {
                    run.isBold && run.isItalic -> android.graphics.Typeface.BOLD_ITALIC
                    run.isBold -> android.graphics.Typeface.BOLD
                    else -> android.graphics.Typeface.ITALIC
                }
                builder.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            run.fontFamily?.let { family ->
                builder.setSpan(TypefaceSpan(family), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return builder.trimTrailingNewlines()
    }

    private fun buildCellText(cell: DocxTableCell): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        cell.paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0 && builder.isNotEmpty()) builder.append('\n')
            builder.append(buildStyledText(paragraph, darkMode = false))
        }
        return builder.trimTrailingNewlines()
    }

    private fun recolorText(text: SpannableStringBuilder, darkMode: Boolean): SpannableStringBuilder {
        if (!darkMode) return text

        val copy = SpannableStringBuilder(text)
        copy.getSpans(0, copy.length, ForegroundColorSpan::class.java).forEach { span ->
            val start = copy.getSpanStart(span)
            val end = copy.getSpanEnd(span)
            val flags = copy.getSpanFlags(span)
            copy.removeSpan(span)
            copy.setSpan(ForegroundColorSpan(invertColor(span.foregroundColor)), start, end, flags)
        }
        return copy
    }

    private fun buildStaticLayout(
        text: CharSequence,
        alignment: DocxTextAlignMode,
        width: Int
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 24, 24)
            textSize = DEFAULT_FONT_SIZE_SP * context.resources.displayMetrics.scaledDensity
        }

        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment.toLayoutAlignment())
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun openInputStream(uri: Uri): InputStream? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            return runCatching { File(path).inputStream() }.getOrNull()
        }
        return context.contentResolver.openInputStream(uri)
    }

    private fun readBitmapFromDisk(
        sourceKey: String,
        position: Int,
        width: Int,
        height: Int,
        darkMode: Boolean
    ): Bitmap? {
        val file = cacheFile(sourceKey, position, width, height, darkMode)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun writeBitmapToDisk(
        sourceKey: String,
        position: Int,
        width: Int,
        height: Int,
        darkMode: Boolean,
        bitmap: Bitmap
    ) {
        val file = cacheFile(sourceKey, position, width, height, darkMode)
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun cacheFile(sourceKey: String, position: Int, width: Int, height: Int, darkMode: Boolean): File {
        val name = "${sourceKey}_${position}_${width}x${height}_${if (darkMode) "dark" else "light"}.png"
        return File(renderDir, name)
    }

    private fun sourceCacheKey(uriString: String, displayName: String, mimeType: String): String {
        return (uriString + "|" + displayName + "|" + mimeType)
            .hashCode()
            .toUInt()
            .toString(16)
    }

    private fun cacheKey(sourceKey: String, position: Int, width: Int, height: Int, darkMode: Boolean): String {
        return "$sourceKey:$position:$width:$height:$darkMode"
    }

    private fun isDarkModeEnabled(): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isDocx(displayName: String, mimeType: String): Boolean {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        return extension == "docx" || "wordprocessingml.document" in normalizedMime
    }

    private fun isLegacyDoc(displayName: String, mimeType: String): Boolean {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        return extension == "doc" || normalizedMime == "application/msword"
    }

    private fun decodeImage(image: DocxImage, maxWidth: Int, maxHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
    }

    private fun calculateInSampleSize(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Int {
        var sample = 1
        while ((width / sample) > requestedWidth * 2 || (height / sample) > requestedHeight * 2) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun DocxImage.scaledSize(maxWidth: Float, maxHeight: Float): ImageSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val scale = min(maxWidth / bounds.outWidth.toFloat(), maxHeight / bounds.outHeight.toFloat())
            .coerceAtMost(1.75f)
            .coerceAtLeast(0.05f)
        return ImageSize(
            width = bounds.outWidth * scale,
            height = bounds.outHeight * scale
        )
    }
}

private data class ParsedDocx(
    val blocks: List<DocxBlock>
)

private sealed class DocxBlock {
    data class Paragraph(val paragraph: DocxParagraph) : DocxBlock()
    data class Table(val table: DocxTable) : DocxBlock()
}

private data class DocxParagraph(
    val runs: List<DocxRun>,
    val alignment: DocxTextAlignMode
)

private data class DocxRun(
    val text: String,
    val fontSizeSp: Float,
    val fontFamily: String?,
    val isBold: Boolean,
    val isItalic: Boolean,
    val color: Int?,
    val images: List<DocxImage>
)

private data class DocxImage(
    val bytes: ByteArray,
    val contentType: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocxImage) return false
        return bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
}

private data class DocxTable(
    val rows: List<DocxTableRow>
)

private data class DocxTableRow(
    val cells: List<DocxTableCell>
)

private data class DocxTableCell(
    val paragraphs: List<DocxParagraph>,
    val fillColor: Int?
)

private data class DocxPage(
    val items: List<PageItem>
)

private data class MutableDocxPage(
    val items: MutableList<PageItem> = mutableListOf()
) {
    fun bottom(defaultTop: Float): Float {
        return items.maxOfOrNull { item -> item.y + item.height } ?: defaultTop
    }
}

private sealed class PageItem {
    abstract val y: Float
    abstract val height: Float

    data class Text(
        override val y: Float,
        val text: SpannableStringBuilder,
        val alignment: DocxTextAlignMode,
        val width: Int,
        override val height: Float
    ) : PageItem()

    data class Image(
        override val y: Float,
        val image: DocxImage,
        val rect: RectF
    ) : PageItem() {
        override val height: Float = rect.height()
    }

    data class TableRow(
        override val y: Float,
        val cells: List<PageTableCell>,
        val columnWidth: Float,
        val rowHeight: Float
    ) : PageItem() {
        override val height: Float = rowHeight
    }

    fun withY(newY: Float): PageItem {
        return when (this) {
            is Text -> copy(y = newY)
            is Image -> copy(y = newY)
            is TableRow -> copy(y = newY)
        }
    }
}

private data class PageTableCell(
    val text: SpannableStringBuilder,
    val fillColor: Int?,
    val layoutWidth: Int
)

private data class PageMetrics(
    val pageWidth: Int,
    val pageHeight: Int
) {
    private val scale = pageWidth / BASE_PAGE_WIDTH.toFloat()
    val marginLeft: Float = 72f * scale
    val marginRight: Float = 72f * scale
    val marginTop: Float = 78f * scale
    val marginBottom: Float = 78f * scale
    val contentWidth: Float = pageWidth - marginLeft - marginRight
    val contentBottom: Float = pageHeight - marginBottom
    val contentHeight: Float = pageHeight - marginTop - marginBottom
    val paragraphSpacing: Float = 16f * scale
    val tableSpacing: Float = 20f * scale
    val cellPadding: Float = 10f * scale
    val borderWidth: Float = max(1f, scale)
    val minTableRowHeight: Float = 44f * scale
    val minTextSliceHeight: Float = 48f * scale
    val maxImageHeight: Float = contentHeight * 0.55f
}

private data class ImageSize(
    val width: Float,
    val height: Float
)

private enum class DocxTextAlignMode {
    START,
    CENTER,
    END
}

private fun ParagraphAlignment?.toDocxTextAlignMode(): DocxTextAlignMode {
    return when (this) {
        ParagraphAlignment.CENTER -> DocxTextAlignMode.CENTER
        ParagraphAlignment.RIGHT -> DocxTextAlignMode.END
        else -> DocxTextAlignMode.START
    }
}

private fun DocxTextAlignMode.toLayoutAlignment(): Layout.Alignment {
    return when (this) {
        DocxTextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
        DocxTextAlignMode.END -> Layout.Alignment.ALIGN_OPPOSITE
        DocxTextAlignMode.START -> Layout.Alignment.ALIGN_NORMAL
    }
}

private fun String?.toAndroidColorOrNull(): Int? {
    val raw = this?.trim()?.removePrefix("#") ?: return null
    if (raw.isBlank() || raw.equals("auto", ignoreCase = true)) return null
    if (raw.length != 6 && raw.length != 8) return null
    return runCatching { Color.parseColor("#$raw") }.getOrNull()
}

private fun themeColor(color: Int, darkMode: Boolean): Int {
    if (!darkMode) return color
    if (Color.alpha(color) == 0) return color
    return invertColor(color)
}

private fun invertColor(color: Int): Int {
    return Color.argb(
        Color.alpha(color),
        255 - Color.red(color),
        255 - Color.green(color),
        255 - Color.blue(color)
    )
}

private fun SpannableStringBuilder.trimTrailingNewlines(): SpannableStringBuilder {
    while (isNotEmpty() && (last() == '\n' || last() == '\r')) {
        delete(length - 1, length)
    }
    return this
}

private fun bitmapCacheKb(): Int {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    return (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
}

private val INVERT_FILTER = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

private const val LEGACY_DOC_MESSAGE = "Legacy .doc format: Open with external app for best compatibility."
private const val TARGET_WIDTH = 1440
private const val TARGET_HEIGHT = 1864
private const val MIN_PAGE_WIDTH = 320
private const val MIN_PAGE_HEIGHT = 480
private const val BASE_PAGE_WIDTH = 1440
private const val PRELOAD_RADIUS = 1
private const val MAX_BLOCKS = 2_000
private const val MAX_PAGES = 500
private const val MAX_RUNS_PER_PARAGRAPH = 300
private const val MAX_RUN_TEXT = 8_000
private const val MAX_IMAGES_PER_RUN = 8
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_TABLE_ROWS = 500
private const val MAX_TABLE_COLUMNS = 20
private const val MAX_CELL_PARAGRAPHS = 24
private const val DEFAULT_FONT_SIZE_SP = 15f
private const val MIN_FONT_SIZE_SP = 7f
private const val MAX_FONT_SIZE_SP = 72f
