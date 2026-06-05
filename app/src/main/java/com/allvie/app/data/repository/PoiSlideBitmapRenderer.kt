package com.allvie.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePart
import org.apache.poi.openxml4j.opc.PackagingURIHelper
import org.apache.poi.openxml4j.opc.TargetMode
import org.xmlpull.v1.XmlPullParser

class PoiSlideBitmapRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val renderDir: File = File(context.cacheDir, "presentation_render_cache").apply { mkdirs() }

    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    suspend fun readSlideCount(
        uriString: String,
        displayName: String,
        mimeType: String
    ): Int = withContext(Dispatchers.IO) {
        bitmapCache.evictAll()
        clearDiskCache()
        val uri = Uri.parse(uriString)
        if (isLegacyPpt(displayName, mimeType)) {
            return@withContext 1
        }

        countPptxSlides(uri)
    }

    suspend fun renderSlideBitmap(
        uriString: String,
        displayName: String,
        mimeType: String,
        position: Int,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ): Bitmap? = withContext(Dispatchers.IO) {
        val safePosition = position.coerceAtLeast(0)
        val safeWidth = targetWidth.coerceAtLeast(320)
        val safeHeight = targetHeight.coerceAtLeast(240)
        val sourceKey = sourceCacheKey(uriString, displayName, mimeType)
        val key = cacheKey(sourceKey, safePosition, safeWidth, safeHeight)

        bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

        readBitmapFromDisk(sourceKey, safePosition, safeWidth, safeHeight)?.let { bitmap ->
            bitmapCache.put(key, bitmap)
            return@withContext bitmap
        }

        val rendered = try {
            val uri = Uri.parse(uriString)
            if (isLegacyPpt(displayName, mimeType)) {
                renderLegacyPptSlide(uri, safePosition, safeWidth, safeHeight)
            } else {
                renderPptxSlide(uri, safePosition, safeWidth, safeHeight)
            }
        } catch (_: Throwable) {
            runCatching {
                renderUnsupportedSlide(
                    position = safePosition,
                    targetWidth = safeWidth,
                    targetHeight = safeHeight,
                    detail = "Unsupported content"
                )
            }.getOrNull()
        } ?: return@withContext null

        try {
            writeBitmapToDisk(sourceKey, safePosition, safeWidth, safeHeight, rendered)
            bitmapCache.put(key, rendered)
            rendered
        } catch (_: Throwable) {
            bitmapCache.put(key, rendered)
            rendered
        }
    }

    suspend fun preloadAround(
        uriString: String,
        displayName: String,
        mimeType: String,
        centerPosition: Int,
        slideCount: Int,
        targetWidth: Int = TARGET_WIDTH,
        targetHeight: Int = TARGET_HEIGHT
    ) = withContext(Dispatchers.IO) {
        val first = (centerPosition - PRELOAD_RADIUS).coerceAtLeast(0)
        val last = (centerPosition + PRELOAD_RADIUS).coerceAtMost(slideCount - 1)
        for (index in first..last) {
            renderSlideBitmap(uriString, displayName, mimeType, index, targetWidth, targetHeight)
        }
    }

    fun clear() {
        bitmapCache.evictAll()
        clearDiskCache()
    }

    private fun clearDiskCache() {
        renderDir.listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun renderPptxSlide(
        uri: Uri,
        position: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val extraction = extractPptxSlideWithPoi(uri, position)
        return renderExtractionToBitmap(extraction, position, targetWidth, targetHeight)
    }

    private fun renderLegacyPptSlide(
        uri: Uri,
        position: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val extraction = extractLegacyPptWithPoi(uri)
        return renderExtractionToBitmap(extraction, position, targetWidth, targetHeight)
    }

    private fun extractPptxSlideWithPoi(uri: Uri, position: Int): SlideExtraction {
        val input = openInputStream(uri) ?: return SlideExtraction.unsupported()
        var packageHandle: OPCPackage? = null

        return try {
            val openedPackage = OPCPackage.open(input)
            packageHandle = openedPackage
            val slidePart = runCatching {
                openedPackage.getPart(
                    PackagingURIHelper.createPartName("/ppt/slides/slide${position + 1}.xml")
                )
            }.getOrNull() ?: return SlideExtraction.unsupported()

            val size = parsePresentationSize(
                readPartOrNull(openedPackage, "/ppt/presentation.xml", MAX_SLIDE_XML_BYTES)
            )
            val slideXml = readPartBytes(slidePart, MAX_SLIDE_XML_BYTES)
            val imagesByRelationship = extractPptxImages(slidePart)
            parsePptxSlideXml(
                xmlBytes = slideXml,
                imagesByRelationship = imagesByRelationship,
                slideSize = size
            )
        } catch (_: Throwable) {
            SlideExtraction.unsupported()
        } finally {
            runCatching { packageHandle?.close() }
            runCatching { input.close() }
        }
    }

    private fun extractLegacyPptWithPoi(uri: Uri): SlideExtraction {
        val input = openInputStream(uri) ?: return SlideExtraction.unsupported()
        var slideShow: HSLFSlideShow? = null

        return try {
            val openedSlideShow = HSLFSlideShow(input)
            slideShow = openedSlideShow
            val pictures = runCatching {
                openedSlideShow.pictureData
                    .take(MAX_IMAGES_PER_LEGACY_SLIDE)
                    .mapNotNull { picture ->
                        val bytes = runCatching { picture.data }.getOrNull()
                        if (bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            ImagePayload(bytes, picture.contentType)
                        }
                    }
            }.getOrDefault(emptyList())

            val elements = mutableListOf<RenderElement>()
            pictures.forEachIndexed { index, payload ->
                elements += RenderElement.Image(
                    rect = EmuRect(
                        x = DEFAULT_SLIDE_WIDTH_EMU / 10,
                        y = (DEFAULT_SLIDE_HEIGHT_EMU / 2) + (index * DEFAULT_SLIDE_HEIGHT_EMU / 7),
                        width = DEFAULT_SLIDE_WIDTH_EMU - (DEFAULT_SLIDE_WIDTH_EMU / 5),
                        height = DEFAULT_SLIDE_HEIGHT_EMU / 8
                    ),
                    image = payload
                )
            }

            SlideExtraction(
                slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU,
                slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU,
                backgroundColor = Color.WHITE,
                elements = elements,
                unsupported = elements.isEmpty()
            )
        } catch (_: Throwable) {
            SlideExtraction.unsupported()
        } finally {
            runCatching { slideShow?.close() }
            runCatching { input.close() }
        }
    }

    private fun extractPptxImages(slidePart: PackagePart): Map<String, ImagePayload> {
        val images = linkedMapOf<String, ImagePayload>()
        val relationships = runCatching { slidePart.relationships }.getOrNull() ?: return images

        for (relationship in relationships) {
            val type = relationship.relationshipType.orEmpty()
            if (!type.endsWith("/image", ignoreCase = true)) continue
            if (relationship.targetMode != TargetMode.INTERNAL) continue

            val relatedPart = runCatching { slidePart.getRelatedPart(relationship) }.getOrNull() ?: continue
            val bytes = runCatching { readPartBytes(relatedPart, MAX_IMAGE_BYTES) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                images[relationship.id] = ImagePayload(bytes, relatedPart.contentType)
            }
        }

        return images
    }

    private fun parsePptxSlideXml(
        xmlBytes: ByteArray,
        imagesByRelationship: Map<String, ImagePayload>,
        slideSize: SlideSize
    ): SlideExtraction {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val elements = mutableListOf<RenderElement>()
        val groupStack = mutableListOf<MutableGroupTransform>()
        var currentShape: MutableRenderShape? = null
        var currentGraphic: MutableGraphicFrame? = null
        var currentTable: MutableTable? = null
        var currentRow: MutableTableRow? = null
        var currentCell: MutableTableCell? = null
        var currentParagraphRuns = mutableListOf<TextRunData>()
        var currentParagraphAlign = TextAlignMode.START
        var currentRunStyle = MutableTextRunStyle()
        val currentRunText = StringBuilder()
        var captureText = false
        var insideTransform = false
        var insideTextStyle = false
        var insideBackground = false
        var insideLine = false
        var transformTarget = TransformTarget.NONE
        var colorTarget: ColorTarget? = null
        var backgroundColor: Int? = Color.WHITE
        var unsupported = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "bg" -> insideBackground = true
                        "grpSp" -> groupStack += MutableGroupTransform()
                        "sp" -> currentShape = MutableRenderShape(kind = MutableRenderKind.SHAPE)
                        "pic" -> currentShape = MutableRenderShape(kind = MutableRenderKind.PICTURE)
                        "cxnSp" -> currentShape = MutableRenderShape(kind = MutableRenderKind.CONNECTOR)
                        "graphicFrame" -> currentGraphic = MutableGraphicFrame()
                        "oleObj", "control", "videoFile", "audioFile" -> unsupported = true
                        "xfrm" -> {
                            insideTransform = true
                            transformTarget = when {
                                currentShape != null -> TransformTarget.SHAPE
                                currentGraphic != null -> TransformTarget.GRAPHIC
                                groupStack.isNotEmpty() -> TransformTarget.GROUP
                                else -> TransformTarget.NONE
                            }
                        }
                        "off" -> if (insideTransform) {
                            val x = parser.attr("x")?.toLongOrNull() ?: 0L
                            val y = parser.attr("y")?.toLongOrNull() ?: 0L
                            when (transformTarget) {
                                TransformTarget.SHAPE -> {
                                    currentShape?.x = x
                                    currentShape?.y = y
                                }
                                TransformTarget.GRAPHIC -> {
                                    currentGraphic?.x = x
                                    currentGraphic?.y = y
                                }
                                TransformTarget.GROUP -> groupStack.lastOrNull()?.apply {
                                    this.x = x
                                    this.y = y
                                }
                                TransformTarget.NONE -> Unit
                            }
                        }
                        "ext" -> if (insideTransform) {
                            val width = parser.attr("cx")?.toLongOrNull() ?: 0L
                            val height = parser.attr("cy")?.toLongOrNull() ?: 0L
                            when (transformTarget) {
                                TransformTarget.SHAPE -> {
                                    currentShape?.width = width
                                    currentShape?.height = height
                                }
                                TransformTarget.GRAPHIC -> {
                                    currentGraphic?.width = width
                                    currentGraphic?.height = height
                                }
                                TransformTarget.GROUP -> groupStack.lastOrNull()?.apply {
                                    this.width = width
                                    this.height = height
                                }
                                TransformTarget.NONE -> Unit
                            }
                        }
                        "chOff" -> if (transformTarget == TransformTarget.GROUP) {
                            groupStack.lastOrNull()?.childX = parser.attr("x")?.toLongOrNull() ?: 0L
                            groupStack.lastOrNull()?.childY = parser.attr("y")?.toLongOrNull() ?: 0L
                        }
                        "chExt" -> if (transformTarget == TransformTarget.GROUP) {
                            groupStack.lastOrNull()?.childWidth = parser.attr("cx")?.toLongOrNull() ?: DEFAULT_SLIDE_WIDTH_EMU
                            groupStack.lastOrNull()?.childHeight = parser.attr("cy")?.toLongOrNull() ?: DEFAULT_SLIDE_HEIGHT_EMU
                        }
                        "ph" -> currentShape?.placeholderType = parser.attr("type")
                        "prstGeom" -> currentShape?.shapePreset = parser.attr("prst")
                        "ln" -> {
                            insideLine = true
                            currentShape?.strokeWidthEmu = parser.attr("w")?.toLongOrNull()
                            colorTarget = ColorTarget.STROKE
                        }
                        "headEnd" -> currentShape?.hasEndArrow = parser.attr("type").orEmpty() != "none"
                        "tailEnd" -> currentShape?.hasStartArrow = parser.attr("type").orEmpty() != "none"
                        "tbl" -> if (currentGraphic != null) currentTable = MutableTable()
                        "gridCol" -> currentTable?.columnWidths?.add(parser.attr("w")?.toLongOrNull() ?: 0L)
                        "tr" -> currentRow = MutableTableRow(height = parser.attr("h")?.toLongOrNull() ?: 0L)
                        "tc" -> currentCell = MutableTableCell()
                        "tcPr" -> if (currentCell != null) colorTarget = ColorTarget.CELL_FILL
                        "solidFill" -> {
                            colorTarget = when {
                                insideBackground -> ColorTarget.BACKGROUND
                                insideTextStyle -> ColorTarget.TEXT
                                insideLine -> ColorTarget.STROKE
                                currentCell != null -> ColorTarget.CELL_FILL
                                currentShape != null -> ColorTarget.SHAPE_FILL
                                else -> null
                            }
                        }
                        "srgbClr" -> {
                            val color = parser.attr("val")?.toAndroidColorOrNull()
                            when (colorTarget) {
                                ColorTarget.BACKGROUND -> if (color != null) backgroundColor = color
                                ColorTarget.TEXT -> if (color != null) {
                                    currentRunStyle.color = color
                                    currentShape?.textColor = color
                                    currentCell?.textColor = color
                                }
                                ColorTarget.SHAPE_FILL -> currentShape?.fillColor = color
                                ColorTarget.STROKE -> currentShape?.strokeColor = color
                                ColorTarget.CELL_FILL -> currentCell?.fillColor = color
                                null -> Unit
                            }
                        }
                        "schemeClr", "prstClr" -> {
                            val color = parser.attr("val").toThemeColorOrNull()
                            when (colorTarget) {
                                ColorTarget.BACKGROUND -> if (color != null) backgroundColor = color
                                ColorTarget.TEXT -> if (color != null) {
                                    currentRunStyle.color = color
                                    currentShape?.textColor = color
                                    currentCell?.textColor = color
                                }
                                ColorTarget.SHAPE_FILL -> currentShape?.fillColor = color
                                ColorTarget.STROKE -> currentShape?.strokeColor = color
                                ColorTarget.CELL_FILL -> currentCell?.fillColor = color
                                null -> Unit
                            }
                        }
                        "p" -> {
                            currentParagraphRuns = mutableListOf()
                            currentParagraphAlign = currentShape?.textAlign ?: currentCell?.textAlign ?: TextAlignMode.START
                        }
                        "pPr" -> {
                            currentParagraphAlign = parseTextAlign(parser.attr("algn"), currentShape?.placeholderType)
                            currentShape?.textAlign = currentParagraphAlign
                            currentCell?.textAlign = currentParagraphAlign
                        }
                        "r" -> {
                            currentRunText.clear()
                            currentRunStyle = MutableTextRunStyle(
                                fontSize = currentShape?.fontSize ?: currentCell?.fontSize,
                                fontFamily = currentShape?.fontFamily ?: currentCell?.fontFamily,
                                isBold = currentShape?.isBold ?: false,
                                isItalic = false,
                                color = currentShape?.textColor ?: currentCell?.textColor
                            )
                        }
                        "rPr", "defRPr", "endParaRPr" -> {
                            insideTextStyle = true
                            val runSize = parser.attr("sz")?.toFloatOrNull()?.div(100f)
                            currentRunStyle.fontSize = runSize ?: currentRunStyle.fontSize
                            currentShape?.fontSize = currentShape?.fontSize ?: runSize
                            currentCell?.fontSize = currentCell?.fontSize ?: runSize
                            val bold = parser.attr("b")
                            if (bold == "1" || bold.equals("true", ignoreCase = true)) {
                                currentRunStyle.isBold = true
                                currentShape?.isBold = true
                            }
                            val italic = parser.attr("i")
                            if (italic == "1" || italic.equals("true", ignoreCase = true)) currentRunStyle.isItalic = true
                        }
                        "latin", "ea", "cs" -> {
                            val typeface = parser.attr("typeface")
                            if (!typeface.isNullOrBlank()) {
                                currentRunStyle.fontFamily = typeface
                                currentShape?.fontFamily = typeface
                                currentCell?.fontFamily = typeface
                            }
                        }
                        "blip" -> if (currentShape?.kind == MutableRenderKind.PICTURE) {
                            currentShape?.imageRelationshipId = parser.attr("embed") ?: parser.attr("link")
                        }
                        "t" -> if (currentShape?.kind == MutableRenderKind.SHAPE || currentCell != null) captureText = true
                        "br" -> if (currentShape?.kind == MutableRenderKind.SHAPE || currentCell != null) {
                            currentParagraphRuns += currentRunStyle.toTextRun("\n")
                            appendLineBreak(currentShape?.text ?: currentCell?.text)
                        }
                    }
                }

                XmlPullParser.TEXT -> if (captureText) {
                    val text = parser.text.orEmpty()
                    currentRunText.append(text)
                    (currentShape?.text ?: currentCell?.text)?.append(text)
                }

                XmlPullParser.END_TAG -> {
                    when (name) {
                        "bg" -> insideBackground = false
                        "solidFill" -> colorTarget = null
                        "t" -> captureText = false
                        "r" -> {
                            if (currentRunText.isNotEmpty()) {
                                currentParagraphRuns += currentRunStyle.toTextRun(currentRunText.toString())
                                currentRunText.clear()
                            }
                        }
                        "p" -> {
                            if (currentRunText.isNotEmpty()) {
                                currentParagraphRuns += currentRunStyle.toTextRun(currentRunText.toString())
                                currentRunText.clear()
                            }
                            if (currentParagraphRuns.isNotEmpty()) {
                                val paragraph = TextParagraphData(currentParagraphRuns.toList(), currentParagraphAlign)
                                currentShape?.paragraphs?.add(paragraph)
                                currentCell?.paragraphs?.add(paragraph)
                            }
                            if (currentShape?.kind == MutableRenderKind.SHAPE || currentCell != null) {
                                appendLineBreak(currentShape?.text ?: currentCell?.text)
                            }
                        }
                        "xfrm" -> {
                            insideTransform = false
                            transformTarget = TransformTarget.NONE
                        }
                        "ln" -> {
                            insideLine = false
                            colorTarget = null
                        }
                        "rPr", "defRPr", "endParaRPr" -> insideTextStyle = false
                        "sp" -> {
                            currentShape?.toRenderElement(imagesByRelationship, groupStack)?.let(elements::add)
                            currentShape = null
                            captureText = false
                            insideTransform = false
                            insideTextStyle = false
                        }
                        "pic" -> {
                            val element = currentShape?.toRenderElement(imagesByRelationship, groupStack)
                            if (element == null) unsupported = true else elements += element
                            currentShape = null
                            insideTransform = false
                            insideTextStyle = false
                        }
                        "cxnSp" -> {
                            currentShape?.toRenderElement(imagesByRelationship, groupStack)?.let(elements::add)
                            currentShape = null
                            insideTransform = false
                            insideTextStyle = false
                        }
                        "tc" -> {
                            currentCell?.let { currentRow?.cells?.add(it) }
                            currentCell = null
                        }
                        "tr" -> {
                            currentRow?.let { currentTable?.rows?.add(it) }
                            currentRow = null
                        }
                        "graphicFrame" -> {
                            val table = currentTable
                            val graphic = currentGraphic
                            if (table != null && graphic != null && table.rows.isNotEmpty()) {
                                elements += RenderElement.Table(
                                    rect = transformEmuRect(graphic.toRect().withFallback(), groupStack),
                                    rows = table.rows.toList(),
                                    columnWidths = table.columnWidths.toList()
                                )
                            } else if (graphic != null) {
                                elements += RenderElement.Placeholder(
                                    rect = transformEmuRect(graphic.toRect().withFallback(), groupStack)
                                )
                            }
                            currentTable = null
                            currentGraphic = null
                        }
                        "grpSp" -> if (groupStack.isNotEmpty()) groupStack.removeAt(groupStack.lastIndex)
                    }
                }
            }
            event = parser.next()
        }

        return SlideExtraction(
            slideWidthEmu = slideSize.widthEmu,
            slideHeightEmu = slideSize.heightEmu,
            backgroundColor = backgroundColor,
            elements = elements,
            unsupported = unsupported && elements.isEmpty()
        )
    }

    private fun MutableRenderShape.toRenderElement(
        imagesByRelationship: Map<String, ImagePayload>,
        groupStack: List<MutableGroupTransform>
    ): RenderElement? {
        val rect = transformEmuRect(EmuRect(
            x = x.coerceAtLeast(0L),
            y = y.coerceAtLeast(0L),
            width = width,
            height = height
        ).withFallback(), groupStack)

        return when (kind) {
            MutableRenderKind.PICTURE -> {
                val payload = imageRelationshipId?.let(imagesByRelationship::get) ?: return null
                RenderElement.Image(rect = rect, image = payload)
            }

            MutableRenderKind.CONNECTOR -> RenderElement.Connector(
                rect = rect,
                color = strokeColor ?: Color.rgb(60, 60, 60),
                strokeWidthEmu = strokeWidthEmu ?: 18_000L,
                hasStartArrow = hasStartArrow,
                hasEndArrow = hasEndArrow
            )

            MutableRenderKind.SHAPE -> {
                val cleanText = normalizeText(text.toString())
                if (cleanText.isNotBlank()) {
                    RenderElement.Text(
                        rect = rect,
                        text = cleanText,
                        paragraphs = paragraphs.toList(),
                        fontSize = fontSize ?: defaultFontSize(placeholderType, rect.height),
                        fontFamily = fontFamily,
                        isBold = isBold || isTitlePlaceholder(placeholderType),
                        textColor = textColor ?: Color.rgb(20, 20, 20),
                        fillColor = fillColor,
                        align = textAlign
                    )
                } else if (fillColor != null) {
                    RenderElement.Shape(
                        rect = rect,
                        fillColor = fillColor ?: Color.TRANSPARENT,
                        preset = shapePreset,
                        strokeColor = strokeColor,
                        strokeWidthEmu = strokeWidthEmu
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun renderExtractionToBitmap(
        extraction: SlideExtraction,
        position: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)

        return try {
            canvas.drawColor(extraction.backgroundColor ?: Color.WHITE)
            if (extraction.unsupported && extraction.elements.isEmpty()) {
                drawUnsupportedContent(canvas, position, targetWidth, targetHeight)
                return bitmap
            }

            extraction.elements.forEach { element ->
                val rect = scaleEmuRect(
                    rect = element.rect,
                    sourceWidthEmu = extraction.slideWidthEmu,
                    sourceHeightEmu = extraction.slideHeightEmu,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
                if (rect.width() <= 1f || rect.height() <= 1f) return@forEach

                when (element) {
                    is RenderElement.Connector -> runCatching { drawConnector(canvas, rect, element, targetWidth) }
                        .onFailure { drawSimplifiedPlaceholder(canvas, rect) }
                    is RenderElement.Image -> runCatching { drawImagePayload(canvas, rect, element.image) }
                        .onFailure { drawSimplifiedPlaceholder(canvas, rect) }
                    is RenderElement.Shape -> runCatching { drawShape(canvas, rect, element) }
                        .onFailure { drawSimplifiedPlaceholder(canvas, rect) }
                    is RenderElement.Table -> runCatching { drawTable(canvas, rect, element, targetWidth) }
                        .onFailure { drawSimplifiedPlaceholder(canvas, rect) }
                    is RenderElement.Text -> runCatching { drawTextBlock(canvas, rect, element, targetWidth) }
                        .onFailure { drawSimplifiedPlaceholder(canvas, rect) }
                    is RenderElement.Placeholder -> drawSimplifiedPlaceholder(canvas, rect)
                }
            }

            if (extraction.elements.isEmpty()) {
                drawUnsupportedContent(canvas, position, targetWidth, targetHeight)
            }
            bitmap
        } catch (throwable: Throwable) {
            bitmap.recycle()
            throw throwable
        }
    }

    private fun renderUnsupportedSlide(
        position: Int,
        targetWidth: Int,
        targetHeight: Int,
        detail: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawUnsupportedContent(canvas, position, targetWidth, targetHeight, detail)
        return bitmap
    }

    private fun drawUnsupportedContent(
        canvas: Canvas,
        position: Int,
        targetWidth: Int,
        targetHeight: Int,
        detail: String = "Unsupported content"
    ) {
        val rect = RectF(
            targetWidth * 0.08f,
            targetHeight * 0.34f,
            targetWidth * 0.92f,
            targetHeight * 0.66f
        )
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(248, 245, 237)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 211, 176)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(rect, 28f, 28f, panelPaint)
        canvas.drawRoundRect(rect, 28f, 28f, strokePaint)
        drawCenteredText(
            canvas = canvas,
            text = "Slide ${position + 1}",
            y = rect.top + rect.height() * 0.38f,
            targetWidth = targetWidth,
            textSize = 30f,
            color = Color.rgb(20, 20, 20),
            bold = true
        )
        drawCenteredText(
            canvas = canvas,
            text = detail,
            y = rect.top + rect.height() * 0.58f,
            targetWidth = targetWidth,
            textSize = 20f,
            color = Color.rgb(86, 76, 58),
            bold = false
        )
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        y: Float,
        targetWidth: Int,
        textSize: Float,
        color: Int,
        bold: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            this.textSize = textSize * (targetWidth / 1080f).coerceIn(0.75f, 1.6f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        canvas.drawText(text, targetWidth / 2f, y, paint)
    }

    private fun drawTextBlock(
        canvas: Canvas,
        rect: RectF,
        element: RenderElement.Text,
        targetWidth: Int
    ) {
        element.fillColor?.let { fill ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill
                alpha = 52
                style = Paint.Style.FILL
                canvas.drawRoundRect(rect, 8f, 8f, this)
            }
        }

        val scaledTextSize = (element.fontSize * (targetWidth / 960f)).coerceIn(12f, 78f)
        val padding = (scaledTextSize * 0.32f).coerceAtLeast(5f)
        drawParagraphsWithStaticLayout(
            canvas = canvas,
            rect = RectF(rect.left + padding, rect.top + padding, rect.right - padding, rect.bottom - padding),
            paragraphs = element.paragraphs.ifEmpty {
                listOf(
                    TextParagraphData(
                        runs = listOf(
                            TextRunData(
                                text = element.text,
                                fontSize = element.fontSize,
                                fontFamily = element.fontFamily,
                                isBold = element.isBold,
                                color = element.textColor
                            )
                        ),
                        align = element.align
                    )
                )
            },
            defaultFontSize = element.fontSize,
            defaultColor = element.textColor,
            targetWidth = targetWidth
        )
    }

    private fun drawShape(canvas: Canvas, rect: RectF, element: RenderElement.Shape) {
        if (element.fillColor != Color.TRANSPARENT) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = element.fillColor
                style = Paint.Style.FILL
            }
            when (element.preset?.lowercase(Locale.ROOT)) {
                "ellipse" -> canvas.drawOval(rect, fillPaint)
                "roundrect" -> canvas.drawRoundRect(rect, rect.width() * 0.08f, rect.height() * 0.08f, fillPaint)
                else -> canvas.drawRect(rect, fillPaint)
            }
        }
        element.strokeColor?.let { stroke ->
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                style = Paint.Style.STROKE
                strokeWidth = ((element.strokeWidthEmu ?: 12_000L) / DEFAULT_SLIDE_WIDTH_EMU.toFloat() * rect.width())
                    .coerceIn(1f, 10f)
            }
            when (element.preset?.lowercase(Locale.ROOT)) {
                "ellipse" -> canvas.drawOval(rect, strokePaint)
                "roundrect" -> canvas.drawRoundRect(rect, rect.width() * 0.08f, rect.height() * 0.08f, strokePaint)
                else -> canvas.drawRect(rect, strokePaint)
            }
        }
    }

    private fun drawConnector(canvas: Canvas, rect: RectF, element: RenderElement.Connector, targetWidth: Int) {
        val connectorStrokeWidth = ((element.strokeWidthEmu / DEFAULT_SLIDE_WIDTH_EMU.toFloat()) * targetWidth)
            .coerceIn(2f, 18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = connectorStrokeWidth
        }
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
        if (element.hasStartArrow) drawArrowHead(canvas, rect.right, rect.bottom, rect.left, rect.top, element.color, connectorStrokeWidth)
        if (element.hasEndArrow) drawArrowHead(canvas, rect.left, rect.top, rect.right, rect.bottom, element.color, connectorStrokeWidth)
    }

    private fun drawArrowHead(
        canvas: Canvas,
        fromX: Float,
        fromY: Float,
        tipX: Float,
        tipY: Float,
        color: Int,
        strokeWidth: Float
    ) {
        val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
        val length = (strokeWidth * 5f).coerceIn(12f, 36f)
        val spread = 0.55
        val p1x = tipX - (length * cos(angle - spread)).toFloat()
        val p1y = tipY - (length * sin(angle - spread)).toFloat()
        val p2x = tipX - (length * cos(angle + spread)).toFloat()
        val p2y = tipY - (length * sin(angle + spread)).toFloat()
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(p1x, p1y)
            lineTo(p2x, p2y)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
    }

    private fun drawTable(canvas: Canvas, rect: RectF, element: RenderElement.Table, targetWidth: Int) {
        val rows = element.rows
        if (rows.isEmpty()) return
        val columnCount = rows.maxOfOrNull { it.cells.size }?.coerceAtLeast(1) ?: 1
        val totalColumnWidth = element.columnWidths.takeIf { it.size >= columnCount }?.sum()?.takeIf { it > 0L }
        val fallbackColumnWidth = rect.width() / columnCount
        val totalRowHeight = rows.sumOf { it.height.coerceAtLeast(0L) }.takeIf { it > 0L }
        var y = rect.top
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 238, 238)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        rows.forEachIndexed { rowIndex, row ->
            val rowHeight = if (totalRowHeight != null) {
                rect.height() * (row.height.coerceAtLeast(1L).toFloat() / totalRowHeight.toFloat())
            } else {
                rect.height() / rows.size
            }
            var x = rect.left
            for (columnIndex in 0 until columnCount) {
                val columnWidth = if (totalColumnWidth != null) {
                    rect.width() * ((element.columnWidths.getOrNull(columnIndex) ?: 0L).coerceAtLeast(1L).toFloat() / totalColumnWidth.toFloat())
                } else {
                    fallbackColumnWidth
                }
                val cellRect = RectF(x, y, x + columnWidth, y + rowHeight)
                val cell = row.cells.getOrNull(columnIndex)
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = cell?.fillColor ?: if ((rowIndex + columnIndex) % 2 == 0) Color.rgb(226, 231, 244) else Color.rgb(242, 244, 250)
                    style = Paint.Style.FILL
                    canvas.drawRect(cellRect, this)
                }
                canvas.drawRect(cellRect, borderPaint)
                cell?.let { tableCell ->
                    // Each table cell is mapped from EMU cell bounds to pixels, then StaticLayout wraps text inside padding.
                    drawParagraphsWithStaticLayout(
                        canvas = canvas,
                        rect = RectF(cellRect.left + 8f, cellRect.top + 6f, cellRect.right - 8f, cellRect.bottom - 6f),
                        paragraphs = tableCell.paragraphs.ifEmpty {
                            listOf(
                                TextParagraphData(
                                    runs = listOf(TextRunData(text = normalizeText(tableCell.text.toString()))),
                                    align = tableCell.textAlign
                                )
                            )
                        },
                        defaultFontSize = tableCell.fontSize ?: 14f,
                        defaultColor = tableCell.textColor ?: Color.rgb(20, 20, 20),
                        targetWidth = targetWidth
                    )
                }
                x += columnWidth
            }
            y += rowHeight
        }
    }

    private fun drawSimplifiedPlaceholder(canvas: Canvas, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 248, 224)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, 10f, 10f, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(116, 92, 35)
            textSize = (rect.width() / 18f).coerceIn(12f, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("Simplified layout", rect.left + 10f, rect.centerY(), textPaint)
    }

    private fun drawParagraphsWithStaticLayout(
        canvas: Canvas,
        rect: RectF,
        paragraphs: List<TextParagraphData>,
        defaultFontSize: Float,
        defaultColor: Int,
        targetWidth: Int
    ) {
        val textWidth = rect.width().roundToInt().coerceAtLeast(1)
        val builder = SpannableStringBuilder()
        var resolvedAlignment = TextAlignMode.START
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraphIndex == 0) resolvedAlignment = paragraph.align
            paragraph.runs.forEach { run ->
                val start = builder.length
                builder.append(run.text)
                val end = builder.length
                if (end > start) {
                    val style = when {
                        run.isBold && run.isItalic -> Typeface.BOLD_ITALIC
                        run.isBold -> Typeface.BOLD
                        run.isItalic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    builder.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    run.fontFamily?.takeIf { it.isNotBlank() }?.let { family ->
                        builder.setSpan(TypefaceSpan(family), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    builder.setSpan(ForegroundColorSpan(run.color ?: defaultColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val px = ((run.fontSize ?: defaultFontSize) * (targetWidth / 960f)).coerceIn(10f, 80f).roundToInt()
                    builder.setSpan(AbsoluteSizeSpan(px), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (paragraphIndex < paragraphs.lastIndex) builder.append('\n')
        }
        if (builder.length == 0) return
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = defaultColor
            textSize = (defaultFontSize * (targetWidth / 960f)).coerceIn(10f, 80f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val alignment = when (resolvedAlignment) {
            TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.END -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlignMode.START -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, textWidth)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left, rect.top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImagePayload(canvas: Canvas, rect: RectF, image: ImagePayload) {
        val bitmap = decodePictureBytes(image.bytes, rect.width().roundToInt(), rect.height().roundToInt()) ?: return
        try {
            canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodePictureBytes(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val boundsStream = ByteArrayInputStream(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeStream(boundsStream, null, bounds)
        } finally {
            boundsStream.close()
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while ((bounds.outWidth / sample) > targetWidth.coerceAtLeast(1) * 2 ||
            (bounds.outHeight / sample) > targetHeight.coerceAtLeast(1) * 2
        ) {
            sample *= 2
        }

        val decodeStream = ByteArrayInputStream(bytes)
        return try {
            BitmapFactory.decodeStream(
                decodeStream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } finally {
            decodeStream.close()
        }
    }

    private fun scaleEmuRect(
        rect: EmuRect,
        sourceWidthEmu: Long,
        sourceHeightEmu: Long,
        targetWidth: Int,
        targetHeight: Int
    ): RectF {
        val safeSourceWidth = sourceWidthEmu.coerceAtLeast(1L).toFloat()
        val safeSourceHeight = sourceHeightEmu.coerceAtLeast(1L).toFloat()
        return RectF(
            targetWidth * (rect.x.toFloat() / safeSourceWidth),
            targetHeight * (rect.y.toFloat() / safeSourceHeight),
            targetWidth * ((rect.x + rect.width).toFloat() / safeSourceWidth),
            targetHeight * ((rect.y + rect.height).toFloat() / safeSourceHeight)
        )
    }

    private fun parsePresentationSize(xmlBytes: ByteArray?): SlideSize {
        if (xmlBytes == null) return SlideSize()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localName(parser.name) == "sldSz") {
                return SlideSize(
                    widthEmu = parser.attr("cx")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_SLIDE_WIDTH_EMU,
                    heightEmu = parser.attr("cy")?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_SLIDE_HEIGHT_EMU
                )
            }
            event = parser.next()
        }
        return SlideSize()
    }

    private fun countPptxSlides(uri: Uri): Int {
        val input = openInputStream(uri) ?: return 0
        val slideEntryPattern = Regex("""ppt/slides/slide\d+\.xml""")
        var count = 0
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (count < MAX_SLIDES) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.orEmpty()
                    if (!entry.isDirectory && slideEntryPattern.matches(entryName)) {
                        count += 1
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            input.close()
        }
        return count
    }

    private fun readPartOrNull(
        packageHandle: OPCPackage,
        partName: String,
        maxBytes: Int
    ): ByteArray? {
        return runCatching {
            val part = packageHandle.getPart(PackagingURIHelper.createPartName(partName))
            readPartBytes(part, maxBytes)
        }.getOrNull()
    }

    private fun readPartBytes(part: PackagePart, maxBytes: Int): ByteArray {
        return part.inputStream.use { input ->
            input.readBoundedBytes(maxBytes)
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { paragraph ->
            var current = ""
            paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                val next = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(next) <= maxWidth) {
                    current = next
                } else {
                    if (current.isNotBlank()) lines += current
                    current = word
                }
            }
            if (current.isNotBlank()) lines += current
        }
        return lines
    }

    private fun readBitmapFromDisk(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val file = cacheFile(sourceKey, position, targetWidth, targetHeight)
        if (!file.exists() || file.length() <= 0L) return null

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun writeBitmapToDisk(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int, bitmap: Bitmap) {
        cacheFile(sourceKey, position, targetWidth, targetHeight).outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        }
    }

    private fun sourceCacheKey(uriString: String, displayName: String, mimeType: String): String {
        return (uriString + "|" + displayName + "|" + mimeType).hashCode().toUInt().toString(16)
    }

    private fun cacheKey(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int): String {
        return "$sourceKey:$position:${targetWidth}x$targetHeight"
    }

    private fun cacheFile(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int): File {
        return File(renderDir, "${sourceKey}_slide_${position}_${targetWidth}x$targetHeight.jpg")
    }

    private fun openInputStream(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)

    private fun isLegacyPpt(displayName: String, mimeType: String): Boolean {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return extension == "ppt" || mimeType.equals("application/vnd.ms-powerpoint", ignoreCase = true)
    }

    private fun bitmapCacheKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemoryKb / 10).coerceIn(16 * 1024, 96 * 1024)
    }

    private companion object {
        const val TARGET_WIDTH = 1920
        const val TARGET_HEIGHT = 1080
        const val PRELOAD_RADIUS = 2
        const val MAX_SLIDE_XML_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val MAX_SLIDES = 300
        const val MAX_IMAGES_PER_LEGACY_SLIDE = 3
    }
}

private data class SlideSize(
    val widthEmu: Long = DEFAULT_SLIDE_WIDTH_EMU,
    val heightEmu: Long = DEFAULT_SLIDE_HEIGHT_EMU
)

private data class SlideExtraction(
    val slideWidthEmu: Long,
    val slideHeightEmu: Long,
    val backgroundColor: Int?,
    val elements: List<RenderElement>,
    val unsupported: Boolean
) {
    companion object {
        fun unsupported(): SlideExtraction {
            return SlideExtraction(
                slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU,
                slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU,
                backgroundColor = Color.WHITE,
                elements = emptyList(),
                unsupported = true
            )
        }
    }
}

private data class ImagePayload(
    val bytes: ByteArray,
    val contentType: String?
)

private data class EmuRect(
    val x: Long = 0L,
    val y: Long = 0L,
    val width: Long = 0L,
    val height: Long = 0L
) {
    fun withFallback(): EmuRect {
        return copy(
            width = width.takeIf { it > 0L } ?: (DEFAULT_SLIDE_WIDTH_EMU - DEFAULT_SLIDE_WIDTH_EMU / 8),
            height = height.takeIf { it > 0L } ?: (DEFAULT_SLIDE_HEIGHT_EMU / 4)
        )
    }
}

private sealed interface RenderElement {
    val rect: EmuRect

    data class Text(
        override val rect: EmuRect,
        val text: String,
        val paragraphs: List<TextParagraphData> = emptyList(),
        val fontSize: Float = 20f,
        val fontFamily: String? = "sans-serif",
        val isBold: Boolean = false,
        val textColor: Int = Color.rgb(20, 20, 20),
        val fillColor: Int? = null,
        val align: TextAlignMode = TextAlignMode.START
    ) : RenderElement

    data class Image(
        override val rect: EmuRect,
        val image: ImagePayload
    ) : RenderElement

    data class Shape(
        override val rect: EmuRect,
        val fillColor: Int,
        val preset: String?,
        val strokeColor: Int? = null,
        val strokeWidthEmu: Long? = null
    ) : RenderElement

    data class Table(
        override val rect: EmuRect,
        val rows: List<MutableTableRow>,
        val columnWidths: List<Long>
    ) : RenderElement

    data class Connector(
        override val rect: EmuRect,
        val color: Int,
        val strokeWidthEmu: Long,
        val hasStartArrow: Boolean,
        val hasEndArrow: Boolean
    ) : RenderElement

    data class Placeholder(
        override val rect: EmuRect
    ) : RenderElement
}

private enum class MutableRenderKind {
    SHAPE,
    PICTURE,
    CONNECTOR
}

private enum class ColorTarget {
    BACKGROUND,
    SHAPE_FILL,
    TEXT,
    STROKE,
    CELL_FILL
}

private enum class TransformTarget {
    NONE,
    SHAPE,
    GRAPHIC,
    GROUP
}

private enum class TextAlignMode {
    START,
    CENTER,
    END
}

private data class MutableRenderShape(
    val kind: MutableRenderKind,
    var x: Long = 0L,
    var y: Long = 0L,
    var width: Long = 0L,
    var height: Long = 0L,
    val text: StringBuilder = StringBuilder(),
    val paragraphs: MutableList<TextParagraphData> = mutableListOf(),
    var imageRelationshipId: String? = null,
    var placeholderType: String? = null,
    var fontSize: Float? = null,
    var fontFamily: String? = null,
    var isBold: Boolean = false,
    var textAlign: TextAlignMode = TextAlignMode.START,
    var textColor: Int? = null,
    var fillColor: Int? = null,
    var strokeColor: Int? = null,
    var strokeWidthEmu: Long? = null,
    var shapePreset: String? = null,
    var hasStartArrow: Boolean = false,
    var hasEndArrow: Boolean = false
)

private data class MutableGraphicFrame(
    var x: Long = 0L,
    var y: Long = 0L,
    var width: Long = 0L,
    var height: Long = 0L
) {
    fun toRect(): EmuRect = EmuRect(x, y, width, height)
}

private data class MutableGroupTransform(
    var x: Long = 0L,
    var y: Long = 0L,
    var width: Long = DEFAULT_SLIDE_WIDTH_EMU,
    var height: Long = DEFAULT_SLIDE_HEIGHT_EMU,
    var childX: Long = 0L,
    var childY: Long = 0L,
    var childWidth: Long = DEFAULT_SLIDE_WIDTH_EMU,
    var childHeight: Long = DEFAULT_SLIDE_HEIGHT_EMU
)

private data class MutableTable(
    val columnWidths: MutableList<Long> = mutableListOf(),
    val rows: MutableList<MutableTableRow> = mutableListOf()
)

private data class MutableTableRow(
    val height: Long,
    val cells: MutableList<MutableTableCell> = mutableListOf()
)

private data class MutableTableCell(
    val text: StringBuilder = StringBuilder(),
    val paragraphs: MutableList<TextParagraphData> = mutableListOf(),
    var fillColor: Int? = null,
    var textColor: Int? = Color.rgb(20, 20, 20),
    var fontSize: Float? = 14f,
    var fontFamily: String? = "sans-serif",
    var textAlign: TextAlignMode = TextAlignMode.START
)

private data class TextParagraphData(
    val runs: List<TextRunData>,
    val align: TextAlignMode = TextAlignMode.START
)

private data class TextRunData(
    val text: String,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val color: Int? = null
)

private data class MutableTextRunStyle(
    var fontSize: Float? = null,
    var fontFamily: String? = null,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var color: Int? = null
) {
    fun toTextRun(text: String): TextRunData {
        return TextRunData(
            text = text,
            fontSize = fontSize,
            fontFamily = fontFamily,
            isBold = isBold,
            isItalic = isItalic,
            color = color
        )
    }
}

private fun parseTextAlign(rawAlignment: String?, placeholderType: String?): TextAlignMode {
    return when (rawAlignment?.lowercase(Locale.ROOT)) {
        "ctr", "dist", "just" -> TextAlignMode.CENTER
        "r" -> TextAlignMode.END
        else -> when (placeholderType?.lowercase(Locale.ROOT)) {
            "ctrtitle", "subtitle" -> TextAlignMode.CENTER
            else -> TextAlignMode.START
        }
    }
}

private fun defaultFontSize(placeholderType: String?, heightEmu: Long): Float {
    return when (placeholderType?.lowercase(Locale.ROOT)) {
        "title", "ctrtitle" -> 30f
        "subtitle" -> 22f
        else -> if (heightEmu >= DEFAULT_SLIDE_HEIGHT_EMU / 3) 20f else 16f
    }
}

private fun isTitlePlaceholder(placeholderType: String?): Boolean {
    return when (placeholderType?.lowercase(Locale.ROOT)) {
        "title", "ctrtitle" -> true
        else -> false
    }
}

private fun transformEmuRect(rect: EmuRect, groupStack: List<MutableGroupTransform>): EmuRect {
    var x = rect.x.toFloat()
    var y = rect.y.toFloat()
    var width = rect.width.toFloat()
    var height = rect.height.toFloat()

    // Grouped shapes use their own child coordinate space. Convert child EMU coordinates
    // into the parent slide EMU space before the final EMU->pixel scaling happens.
    groupStack.asReversed().forEach { group ->
        val scaleX = group.width.toFloat() / group.childWidth.coerceAtLeast(1L).toFloat()
        val scaleY = group.height.toFloat() / group.childHeight.coerceAtLeast(1L).toFloat()
        x = group.x + ((x - group.childX) * scaleX)
        y = group.y + ((y - group.childY) * scaleY)
        width *= scaleX
        height *= scaleY
    }

    return EmuRect(
        x = x.toLong().coerceAtLeast(0L),
        y = y.toLong().coerceAtLeast(0L),
        width = width.toLong().coerceAtLeast(1L),
        height = height.toLong().coerceAtLeast(1L)
    )
}

private fun appendLineBreak(buffer: StringBuilder?) {
    if (buffer != null && buffer.isNotEmpty() && buffer.last() != '\n') {
        buffer.append('\n')
    }
}

private fun normalizeText(value: String): String {
    return value
        .replace("\r\n", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
}

private fun String?.toAndroidColorOrNull(): Int? {
    val raw = this?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6 && raw.length != 8) return null
    return runCatching { Color.parseColor("#$raw") }.getOrNull()
}

private fun String?.toThemeColorOrNull(): Int? {
    return when (this?.trim()?.lowercase(Locale.ROOT)) {
        "accent1" -> Color.rgb(68, 114, 196)
        "accent2" -> Color.rgb(237, 125, 49)
        "accent3" -> Color.rgb(165, 165, 165)
        "accent4" -> Color.rgb(255, 192, 0)
        "accent5" -> Color.rgb(91, 155, 213)
        "accent6" -> Color.rgb(112, 173, 71)
        "tx1", "dk1", "black" -> Color.rgb(18, 18, 18)
        "tx2", "dk2" -> Color.rgb(64, 64, 64)
        "bg1", "lt1", "white" -> Color.WHITE
        "bg2", "lt2" -> Color.rgb(242, 242, 242)
        "blue" -> Color.rgb(68, 114, 196)
        "red" -> Color.rgb(192, 0, 0)
        "green" -> Color.rgb(0, 128, 0)
        "yellow" -> Color.rgb(255, 192, 0)
        else -> null
    }
}

private fun localName(rawName: String?): String = rawName?.substringAfter(':').orEmpty()

private fun XmlPullParser.attr(attributeLocalName: String): String? {
    for (index in 0 until attributeCount) {
        if (localName(getAttributeName(index)) == attributeLocalName) {
            return getAttributeValue(index)
        }
    }
    return null
}

private fun InputStream.readBoundedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
