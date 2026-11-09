package com.allvie.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import android.text.style.UnderlineSpan
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.hslf.usermodel.HSLFAutoShape
import org.apache.poi.hslf.usermodel.HSLFPictureShape
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTable
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackagePart
import org.apache.poi.openxml4j.opc.PackagingURIHelper
import org.apache.poi.openxml4j.opc.TargetMode
import org.xmlpull.v1.XmlPullParser

class PoiSlideBitmapRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val renderDir: File = File(context.cacheDir, "presentation_render_cache_v4").apply { mkdirs() }

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
            return@withContext countLegacyPptSlides(uri)
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
        val extraction = extractLegacyPptWithPoi(uri, position)
        return renderExtractionToBitmap(extraction, position, targetWidth, targetHeight)
    }

    private fun extractPptxSlideWithPoi(uri: Uri, position: Int): SlideExtraction {
        val input = openInputStream(uri) ?: return SlideExtraction.unsupported()
        var packageHandle: OPCPackage? = null

        return try {
            val openedPackage = OPCPackage.open(input)
            packageHandle = openedPackage

            val presentationBytes = readPartOrNull(openedPackage, "/ppt/presentation.xml", MAX_SLIDE_XML_BYTES)
            val size = parsePresentationSize(presentationBytes)
            val themeColors = parseThemeColors(openedPackage)

            val slidePart = resolveSlidePart(openedPackage, presentationBytes, position)
                ?: return SlideExtraction.unsupported()

            val layoutPart = findRelatedPartByTypeSuffix(slidePart, "/slideLayout")
            val masterPart = layoutPart?.let { findRelatedPartByTypeSuffix(it, "/slideMaster") }
                ?: findRelatedPartByTypeSuffix(slidePart, "/slideMaster")

            val masterPlaceholders = mutableMapOf<String, PlaceholderSpec>()
            val masterDecorElements = mutableListOf<RenderElement>()
            var inheritedBackground: SlideBackground? = null

            if (masterPart != null) {
                val masterXml = runCatching { readPartBytes(masterPart, MAX_SLIDE_XML_BYTES) }.getOrNull()
                if (masterXml != null) {
                    val masterImages = extractPptxImages(masterPart)
                    val masterParsed = parsePptxSlideXml(
                        xmlBytes = masterXml,
                        slidePart = masterPart,
                        imagesByRelationship = masterImages,
                        slideSize = size,
                        themeColors = themeColors,
                        inheritedPlaceholders = emptyMap(),
                        isMasterOrLayout = true
                    )
                    masterPlaceholders.putAll(masterParsed.placeholders)
                    masterDecorElements.addAll(masterParsed.elements)
                    if (masterParsed.background != null) {
                        inheritedBackground = masterParsed.background
                    }
                }
            }

            val layoutPlaceholders = HashMap(masterPlaceholders)
            val layoutDecorElements = mutableListOf<RenderElement>()
            var showMasterShapesOnLayout = true

            if (layoutPart != null) {
                val layoutXml = runCatching { readPartBytes(layoutPart, MAX_SLIDE_XML_BYTES) }.getOrNull()
                if (layoutXml != null) {
                    val layoutImages = extractPptxImages(layoutPart)
                    val layoutParsed = parsePptxSlideXml(
                        xmlBytes = layoutXml,
                        slidePart = layoutPart,
                        imagesByRelationship = layoutImages,
                        slideSize = size,
                        themeColors = themeColors,
                        inheritedPlaceholders = masterPlaceholders,
                        isMasterOrLayout = true
                    )
                    layoutParsed.placeholders.forEach { (key, spec) ->
                        val prev = layoutPlaceholders[key]
                        layoutPlaceholders[key] = if (prev != null) spec.mergeWithFallback(prev) else spec
                    }
                    layoutDecorElements.addAll(layoutParsed.elements)
                    showMasterShapesOnLayout = layoutParsed.showMasterSp
                    if (layoutParsed.background != null) {
                        inheritedBackground = layoutParsed.background
                    }
                }
            }

            val slideXml = readPartBytes(slidePart, MAX_SLIDE_XML_BYTES)
            val imagesByRelationship = extractPptxImages(slidePart)
            val slideParsed = parsePptxSlideXml(
                xmlBytes = slideXml,
                slidePart = slidePart,
                imagesByRelationship = imagesByRelationship,
                slideSize = size,
                themeColors = themeColors,
                inheritedPlaceholders = layoutPlaceholders,
                isMasterOrLayout = false
            )

            val combinedElements = mutableListOf<RenderElement>()
            if (slideParsed.showMasterSp) {
                if (showMasterShapesOnLayout) {
                    combinedElements.addAll(masterDecorElements)
                }
                combinedElements.addAll(layoutDecorElements)
            }
            combinedElements.addAll(slideParsed.elements)

            SlideExtraction(
                slideWidthEmu = size.widthEmu,
                slideHeightEmu = size.heightEmu,
                background = slideParsed.background ?: inheritedBackground ?: SlideBackground.Solid(Color.WHITE),
                elements = combinedElements,
                unsupported = slideParsed.unsupported && combinedElements.isEmpty()
            )
        } catch (_: Throwable) {
            SlideExtraction.unsupported()
        } finally {
            runCatching { packageHandle?.close() }
            runCatching { input.close() }
        }
    }

    private fun resolveSlidePart(
        openedPackage: OPCPackage,
        presentationBytes: ByteArray?,
        position: Int
    ): PackagePart? {
        val presentationPart = runCatching {
            openedPackage.getPart(PackagingURIHelper.createPartName("/ppt/presentation.xml"))
        }.getOrNull()

        if (presentationPart != null && presentationBytes != null) {
            val orderedRelIds = parseOrderedSlideRelIds(presentationBytes)
            if (position in orderedRelIds.indices) {
                val relId = orderedRelIds[position]
                val rel = runCatching { presentationPart.getRelationship(relId) }.getOrNull()
                if (rel != null) {
                    val related = runCatching { presentationPart.getRelatedPart(rel) }.getOrNull()
                    if (related != null) return related
                }
            }
        }

        val direct = runCatching {
            openedPackage.getPart(PackagingURIHelper.createPartName("/ppt/slides/slide${position + 1}.xml"))
        }.getOrNull()
        if (direct != null) return direct

        val allSlideParts = runCatching {
            openedPackage.parts
                .filter { part ->
                    val name = part.partName.name
                    name.startsWith("/ppt/slides/slide") && name.endsWith(".xml")
                }
                .sortedBy { part ->
                    part.partName.name
                        .substringAfterLast("slide")
                        .removeSuffix(".xml")
                        .toIntOrNull() ?: Int.MAX_VALUE
                }
        }.getOrDefault(emptyList())

        return allSlideParts.getOrNull(position)
    }

    private fun parseOrderedSlideRelIds(presentationBytes: ByteArray): List<String> {
        val relIds = mutableListOf<String>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(presentationBytes), null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && localName(parser.name) == "sldId") {
                    val rId = parser.attr("id")
                    if (!rId.isNullOrBlank()) {
                        relIds.add(rId)
                    }
                }
                event = parser.next()
            }
        }
        return relIds
    }

    private fun findRelatedPartByTypeSuffix(sourcePart: PackagePart, suffix: String): PackagePart? {
        val relationships = runCatching { sourcePart.relationships }.getOrNull() ?: return null
        for (rel in relationships) {
            if (rel.targetMode != TargetMode.INTERNAL) continue
            if (rel.relationshipType.orEmpty().endsWith(suffix, ignoreCase = true)) {
                val part = runCatching { sourcePart.getRelatedPart(rel) }.getOrNull()
                if (part != null) return part
            }
        }
        return null
    }

    private fun parseThemeColors(openedPackage: OPCPackage): Map<String, Int> {
        val map = mutableMapOf(
            "dk1" to Color.rgb(18, 18, 18),
            "lt1" to Color.WHITE,
            "dk2" to Color.rgb(55, 65, 81),
            "lt2" to Color.rgb(241, 245, 249),
            "accent1" to Color.rgb(68, 114, 196),
            "accent2" to Color.rgb(237, 125, 49),
            "accent3" to Color.rgb(165, 165, 165),
            "accent4" to Color.rgb(255, 192, 0),
            "accent5" to Color.rgb(91, 155, 213),
            "accent6" to Color.rgb(112, 173, 71),
            "hlink" to Color.rgb(5, 99, 193),
            "folhlink" to Color.rgb(149, 79, 114)
        )

        val themeBytes = readPartOrNull(openedPackage, "/ppt/theme/theme1.xml", MAX_SLIDE_XML_BYTES)
            ?: runCatching {
                openedPackage.parts.firstOrNull {
                    it.partName.name.startsWith("/ppt/theme/theme") && it.partName.name.endsWith(".xml")
                }?.let { readPartBytes(it, MAX_SLIDE_XML_BYTES) }
            }.getOrNull()

        if (themeBytes != null) {
            runCatching {
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(themeBytes), null)
                var insideClrScheme = false
                var currentSchemeSlot: String? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    val tag = localName(parser.name)
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when {
                                tag == "clrScheme" -> insideClrScheme = true
                                insideClrScheme && tag in map.keys -> currentSchemeSlot = tag
                                insideClrScheme && currentSchemeSlot != null && tag == "srgbClr" -> {
                                    parser.attr("val")?.toAndroidColorOrNull()?.let { c ->
                                        map[currentSchemeSlot!!] = c
                                    }
                                }
                                insideClrScheme && currentSchemeSlot != null && tag == "sysClr" -> {
                                    val c = parser.attr("lastClr")?.toAndroidColorOrNull()
                                        ?: parser.attr("val").toSysColorOrNull()
                                    if (c != null) {
                                        map[currentSchemeSlot!!] = c
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tag == "clrScheme") insideClrScheme = false
                            else if (tag == currentSchemeSlot) currentSchemeSlot = null
                        }
                    }
                    event = parser.next()
                }
            }
        }

        map["tx1"] = map["dk1"] ?: Color.rgb(18, 18, 18)
        map["tx2"] = map["dk2"] ?: Color.rgb(55, 65, 81)
        map["bg1"] = map["lt1"] ?: Color.WHITE
        map["bg2"] = map["lt2"] ?: Color.rgb(241, 245, 249)
        map["phclr"] = map["accent1"] ?: Color.rgb(68, 114, 196)
        return map
    }

    private fun countLegacyPptSlides(uri: Uri): Int {
        val input = openInputStream(uri) ?: return 0
        var slideShow: HSLFSlideShow? = null
        return try {
            val opened = HSLFSlideShow(input)
            slideShow = opened
            opened.slides.size.coerceAtLeast(1)
        } catch (_: Throwable) {
            1
        } finally {
            runCatching { slideShow?.close() }
            runCatching { input.close() }
        }
    }

    private fun extractLegacyPptWithPoi(uri: Uri, position: Int): SlideExtraction {
        val input = openInputStream(uri) ?: return SlideExtraction.unsupported()
        var slideShow: HSLFSlideShow? = null

        return try {
            val openedSlideShow = HSLFSlideShow(input)
            slideShow = openedSlideShow
            val slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU
            val slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU

            val slides = runCatching { openedSlideShow.slides }.getOrDefault(emptyList())
            val slide = slides.getOrNull(position) ?: slides.firstOrNull()
            val elements = mutableListOf<RenderElement>()

            if (slide != null) {
                val marginX = slideWidthEmu / 14
                val usableWidth = slideWidthEmu - (marginX * 2)
                var currentY = slideHeightEmu / 12

                for (shape in slide.shapes) {
                    when (shape) {
                        is HSLFPictureShape -> {
                            val bytes = runCatching { shape.pictureData?.data }.getOrNull()
                            if (bytes != null && bytes.isNotEmpty()) {
                                val boxH = slideHeightEmu / 3
                                val rect = EmuRect(
                                    x = marginX,
                                    y = currentY.coerceAtMost(slideHeightEmu - boxH),
                                    width = usableWidth,
                                    height = boxH
                                )
                                elements += RenderElement.Image(
                                    rect = rect,
                                    image = ImagePayload(bytes, shape.pictureData?.contentType)
                                )
                                currentY += boxH + (slideHeightEmu / 30)
                            }
                        }
                        is HSLFTable -> {
                            val rows = mutableListOf<MutableTableRow>()
                            val numRows = runCatching { shape.numberOfRows }.getOrDefault(0)
                            val numCols = runCatching { shape.numberOfColumns }.getOrDefault(0)
                            val boxH = (slideHeightEmu / 2).coerceAtLeast(1L)
                            val rect = EmuRect(
                                x = marginX,
                                y = currentY.coerceAtMost(slideHeightEmu - boxH),
                                width = usableWidth,
                                height = boxH
                            )
                            for (r in 0 until numRows) {
                                val rowCells = mutableListOf<MutableTableCell>()
                                for (c in 0 until numCols) {
                                    val cell = runCatching { shape.getCell(r, c) }.getOrNull()
                                    val cellText = cell?.text.orEmpty().trim()
                                    rowCells += MutableTableCell(
                                        text = StringBuilder(cellText),
                                        paragraphs = if (cellText.isNotEmpty()) {
                                            mutableListOf(
                                                TextParagraphData(
                                                    runs = listOf(TextRunData(text = cellText, fontSize = 14f))
                                                )
                                            )
                                        } else {
                                            mutableListOf()
                                        }
                                    )
                                }
                                rows += MutableTableRow(height = rect.height / numRows.coerceAtLeast(1), cells = rowCells)
                            }
                            if (rows.isNotEmpty()) {
                                elements += RenderElement.Table(
                                    rect = rect,
                                    rows = rows,
                                    columnWidths = List(numCols.coerceAtLeast(1)) { rect.width / numCols.coerceAtLeast(1) }
                                )
                                currentY += boxH + (slideHeightEmu / 30)
                            }
                        }
                        is HSLFTextShape -> {
                            val rawText = runCatching { shape.text }.getOrNull().orEmpty().trim()
                            if (rawText.isNotBlank()) {
                                val isFirst = elements.isEmpty()
                                val boxH = if (isFirst) slideHeightEmu / 5 else slideHeightEmu / 4
                                val rect = EmuRect(
                                    x = marginX,
                                    y = currentY.coerceAtMost(slideHeightEmu - boxH),
                                    width = usableWidth,
                                    height = boxH
                                )
                                elements += RenderElement.ShapeWithText(
                                    rect = rect,
                                    preset = if (shape is HSLFAutoShape) "rect" else null,
                                    text = rawText,
                                    paragraphs = rawText.lines().map { line ->
                                        TextParagraphData(
                                            runs = listOf(
                                                TextRunData(
                                                    text = line,
                                                    fontSize = if (isFirst) 26f else 18f,
                                                    isBold = isFirst
                                                )
                                            )
                                        )
                                    }
                                )
                                currentY += boxH + (slideHeightEmu / 36)
                            }
                        }
                    }
                }
            }

            if (elements.isEmpty()) {
                val pictures = runCatching {
                    openedSlideShow.pictureData
                        .take(MAX_IMAGES_PER_LEGACY_SLIDE)
                        .mapNotNull { picture ->
                            val bytes = runCatching { picture.data }.getOrNull()
                            if (bytes == null || bytes.isEmpty()) null else ImagePayload(bytes, picture.contentType)
                        }
                }.getOrDefault(emptyList())

                pictures.forEachIndexed { index, payload ->
                    elements += RenderElement.Image(
                        rect = EmuRect(
                            x = slideWidthEmu / 10,
                            y = (slideHeightEmu / 6) + (index * slideHeightEmu / 4),
                            width = slideWidthEmu - (slideWidthEmu / 5),
                            height = slideHeightEmu / 5
                        ),
                        image = payload
                    )
                }
            }

            SlideExtraction(
                slideWidthEmu = slideWidthEmu,
                slideHeightEmu = slideHeightEmu,
                background = SlideBackground.Solid(Color.WHITE),
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
        slidePart: PackagePart,
        imagesByRelationship: Map<String, ImagePayload>,
        slideSize: SlideSize,
        themeColors: Map<String, Int>,
        inheritedPlaceholders: Map<String, PlaceholderSpec>,
        isMasterOrLayout: Boolean
    ): ParsedSlideXml {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        val elements = mutableListOf<RenderElement>()
        val discoveredPlaceholders = mutableMapOf<String, PlaceholderSpec>()
        val groupStack = mutableListOf<MutableGroupTransform>()
        var currentShape: MutableRenderShape? = null
        var currentGraphic: MutableGraphicFrame? = null
        var currentTable: MutableTable? = null
        var currentRow: MutableTableRow? = null
        var currentCell: MutableTableCell? = null
        var currentParagraphRuns = mutableListOf<TextRunData>()
        var currentParagraphAlign: TextAlignMode? = null
        var currentParagraphLevel = 0
        var currentParagraphBullet: String? = null
        var currentParagraphSuppressBullet = false
        var currentParagraphAutoNumType: String? = null
        var currentParagraphAutoNumIndex = 0
        var currentRunStyle = MutableTextRunStyle()
        val currentRunText = StringBuilder()

        var captureText = false
        var insideTransform = false
        var insideTextStyle = false
        var insideDefRPr = false
        var insideBackground = false
        var insideLine = false
        var insideCellBorder = false
        var insideGradientFill = false
        var insideCustGeom = false
        var currentCustomPath: MutableCustomPath? = null
        var currentPathCommand: String? = null
        val currentPathPoints = mutableListOf<Pair<Float, Float>>()

        var transformTarget = TransformTarget.NONE
        var colorTarget: ColorTarget? = null
        var activeColorBuilder: PendingColorBuilder? = null

        var background: SlideBackground? = null
        val bgGradientStops = mutableListOf<Int>()
        var bgGradientAngle = 45f
        var bgImageRelId: String? = null

        var showMasterSp = true
        var unsupported = false
        var fallbackBodyPlaceholderCounter = 0

        fun commitPendingColor(color: Int) {
            if (insideGradientFill) {
                when (colorTarget) {
                    ColorTarget.BACKGROUND -> bgGradientStops.add(color)
                    ColorTarget.SHAPE_FILL -> currentShape?.gradientColors?.add(color)
                    ColorTarget.CELL_FILL -> {
                        if (currentCell?.fillColor == null) currentCell?.fillColor = color
                    }
                    else -> Unit
                }
                return
            }

            when (colorTarget) {
                ColorTarget.BACKGROUND -> background = SlideBackground.Solid(color)
                ColorTarget.TEXT -> {
                    currentRunStyle.color = color
                    if (insideDefRPr) {
                        currentShape?.defaultTextColor = color
                        currentCell?.textColor = color
                    }
                }
                ColorTarget.BULLET -> Unit
                ColorTarget.SHAPE_FILL -> currentShape?.fillColor = color
                ColorTarget.STROKE -> currentShape?.strokeColor = color
                ColorTarget.CELL_BORDER -> {
                    currentCell?.borderColor = color
                }
                ColorTarget.CELL_FILL -> currentCell?.fillColor = color
                null -> Unit
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "sld", "sldLayout" -> {
                            val showAttr = parser.attr("showMasterSp")
                            if (showAttr == "0" || showAttr.equals("false", ignoreCase = true)) {
                                showMasterSp = false
                            }
                        }
                        "bg" -> {
                            insideBackground = true
                            bgGradientStops.clear()
                            bgImageRelId = null
                        }
                        "grpSp" -> groupStack += MutableGroupTransform()
                        "sp" -> {
                            currentShape = MutableRenderShape(kind = MutableRenderKind.SHAPE)
                            currentParagraphAutoNumIndex = 0
                        }
                        "pic" -> currentShape = MutableRenderShape(kind = MutableRenderKind.PICTURE)
                        "cxnSp" -> currentShape = MutableRenderShape(kind = MutableRenderKind.CONNECTOR)
                        "graphicFrame" -> currentGraphic = MutableGraphicFrame()
                        "oleObj", "control", "videoFile", "audioFile" -> unsupported = true
                        "chart" -> {
                            if (currentGraphic != null) {
                                currentGraphic?.chartRelId = parser.attr("id")
                            }
                        }
                        "relIds" -> {
                            if (currentGraphic != null) {
                                currentGraphic?.diagramDataRelId = parser.attr("dm")
                                currentGraphic?.diagramDrawingRelId = parser.attr("lo") ?: parser.attr("qs")
                            }
                        }
                        "xfrm" -> {
                            insideTransform = true
                            val flipH = parser.attr("flipH").let { it == "1" || it.equals("true", ignoreCase = true) }
                            val flipV = parser.attr("flipV").let { it == "1" || it.equals("true", ignoreCase = true) }
                            val rotDeg = (parser.attr("rot")?.toLongOrNull() ?: 0L) / 60_000f
                            transformTarget = when {
                                currentShape != null -> {
                                    currentShape?.flipH = flipH
                                    currentShape?.flipV = flipV
                                    currentShape?.rotationDeg = rotDeg
                                    TransformTarget.SHAPE
                                }
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
                                    currentShape?.hasExplicitPosition = true
                                }
                                TransformTarget.GRAPHIC -> {
                                    currentGraphic?.x = x
                                    currentGraphic?.y = y
                                }
                                TransformTarget.GROUP -> groupStack.lastOrNull()?.apply {
                                    if (!hasGroupPos) {
                                        this.x = x
                                        this.y = y
                                        this.hasGroupPos = true
                                    }
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
                                    if (width > 0L && height > 0L) {
                                        currentShape?.hasExplicitSize = true
                                    }
                                }
                                TransformTarget.GRAPHIC -> {
                                    currentGraphic?.width = width
                                    currentGraphic?.height = height
                                }
                                TransformTarget.GROUP -> groupStack.lastOrNull()?.apply {
                                    if (!hasGroupExt && width > 0L && height > 0L) {
                                        this.width = width
                                        this.height = height
                                        this.hasGroupExt = true
                                    }
                                }
                                TransformTarget.NONE -> Unit
                            }
                        }
                        "chOff" -> if (transformTarget == TransformTarget.GROUP) {
                            groupStack.lastOrNull()?.childX = parser.attr("x")?.toLongOrNull() ?: 0L
                            groupStack.lastOrNull()?.childY = parser.attr("y")?.toLongOrNull() ?: 0L
                        }
                        "chExt" -> if (transformTarget == TransformTarget.GROUP) {
                            groupStack.lastOrNull()?.childWidth = parser.attr("cx")?.toLongOrNull()?.takeIf { it > 0L } ?: slideSize.widthEmu
                            groupStack.lastOrNull()?.childHeight = parser.attr("cy")?.toLongOrNull()?.takeIf { it > 0L } ?: slideSize.heightEmu
                        }
                        "ph" -> {
                            currentShape?.isPlaceholder = true
                            currentShape?.placeholderType = parser.attr("type")
                            currentShape?.placeholderIdx = parser.attr("idx")
                        }
                        "prstGeom" -> {
                            currentShape?.shapePreset = parser.attr("prst")
                        }
                        "gd" -> {
                            if (currentShape != null && !insideCustGeom) {
                                val fmla = parser.attr("fmla").orEmpty()
                                if (fmla.startsWith("val ")) {
                                    val adjVal = fmla.removePrefix("val ").trim().toFloatOrNull()
                                    if (adjVal != null) {
                                        currentShape?.adjustValue = adjVal / 100_000f
                                    }
                                }
                            }
                        }
                        "custGeom" -> {
                            insideCustGeom = true
                        }
                        "path" -> if (insideCustGeom && currentShape != null) {
                            val w = parser.attr("w")?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
                            val h = parser.attr("h")?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
                            currentCustomPath = MutableCustomPath(width = w, height = h)
                        }
                        "moveTo", "lnTo", "cubicBezTo", "quadBezTo" -> if (currentCustomPath != null) {
                            currentPathCommand = name
                            currentPathPoints.clear()
                        }
                        "pt" -> if (currentCustomPath != null && currentPathCommand != null) {
                            val px = parser.attr("x")?.toFloatOrNull() ?: 0f
                            val py = parser.attr("y")?.toFloatOrNull() ?: 0f
                            currentPathPoints.add(px to py)
                        }
                        "close" -> if (currentCustomPath != null) {
                            currentCustomPath?.commands?.add(CustomPathCmd.Close)
                        }
                        "bodyPr" -> {
                            val anchor = parseVerticalAnchor(parser.attr("anchor"))
                            currentShape?.verticalAnchor = anchor
                        }
                        "ln" -> {
                            insideLine = true
                            val w = parser.attr("w")?.toLongOrNull()
                            if (w != null) currentShape?.strokeWidthEmu = w
                            colorTarget = ColorTarget.STROKE
                        }
                        "lnL", "lnR", "lnT", "lnB" -> {
                            if (currentCell != null) {
                                insideCellBorder = true
                                colorTarget = ColorTarget.CELL_BORDER
                            }
                        }
                        "prstDash" -> if (insideLine) {
                            val dashVal = parser.attr("val").orEmpty().lowercase(Locale.ROOT)
                            currentShape?.isDashedStroke = dashVal.isNotEmpty() && dashVal != "solid"
                        }
                        "noFill" -> {
                            when {
                                insideLine -> {
                                    currentShape?.hasNoStroke = true
                                    currentShape?.strokeColor = null
                                }
                                insideCellBorder -> Unit
                                currentCell != null && !insideTextStyle -> {
                                    currentCell?.fillColor = Color.TRANSPARENT
                                }
                                currentShape != null && !insideTextStyle -> {
                                    currentShape?.hasNoFill = true
                                    currentShape?.fillColor = null
                                }
                            }
                        }
                        "headEnd" -> currentShape?.hasStartArrow = parser.attr("type").orEmpty().let { it.isNotEmpty() && it != "none" }
                        "tailEnd" -> currentShape?.hasEndArrow = parser.attr("type").orEmpty().let { it.isNotEmpty() && it != "none" }
                        "tbl" -> if (currentGraphic != null) currentTable = MutableTable()
                        "gridCol" -> currentTable?.columnWidths?.add(parser.attr("w")?.toLongOrNull() ?: 0L)
                        "tr" -> currentRow = MutableTableRow(height = parser.attr("h")?.toLongOrNull() ?: 0L)
                        "tc" -> {
                            val gridSpan = parser.attr("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val rowSpan = parser.attr("rowSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val hMerge = parser.attr("hMerge").let { it == "1" || it.equals("true", ignoreCase = true) }
                            val vMerge = parser.attr("vMerge").let { it == "1" || it.equals("true", ignoreCase = true) }
                            currentCell = MutableTableCell(
                                gridSpan = gridSpan,
                                rowSpan = rowSpan,
                                isMergedContinuation = hMerge || vMerge
                            )
                        }
                        "tcPr" -> if (currentCell != null) {
                            currentCell?.verticalAnchor = parseVerticalAnchor(parser.attr("anchor"))
                            colorTarget = ColorTarget.CELL_FILL
                        }
                        "buClr" -> colorTarget = ColorTarget.BULLET
                        "buNone" -> currentParagraphSuppressBullet = true
                        "buChar" -> {
                            val ch = parser.attr("char")
                            if (!ch.isNullOrBlank()) currentParagraphBullet = ch
                        }
                        "buAutoNum" -> {
                            currentParagraphAutoNumType = parser.attr("type") ?: "arabicPeriod"
                        }
                        "solidFill" -> {
                            insideGradientFill = false
                            colorTarget = when {
                                insideBackground -> ColorTarget.BACKGROUND
                                colorTarget == ColorTarget.BULLET -> ColorTarget.BULLET
                                insideTextStyle -> ColorTarget.TEXT
                                insideLine -> ColorTarget.STROKE
                                insideCellBorder -> ColorTarget.CELL_BORDER
                                currentCell != null -> ColorTarget.CELL_FILL
                                currentShape != null -> ColorTarget.SHAPE_FILL
                                else -> null
                            }
                        }
                        "gradFill" -> {
                            insideGradientFill = true
                            colorTarget = when {
                                insideBackground -> {
                                    bgGradientStops.clear()
                                    ColorTarget.BACKGROUND
                                }
                                currentCell != null && !insideCellBorder && !insideTextStyle -> ColorTarget.CELL_FILL
                                currentShape != null && !insideLine && !insideTextStyle -> {
                                    currentShape?.gradientColors?.clear()
                                    ColorTarget.SHAPE_FILL
                                }
                                else -> null
                            }
                        }
                        "lin" -> if (insideGradientFill) {
                            val ang = (parser.attr("ang")?.toLongOrNull() ?: 0L) / 60_000f
                            if (insideBackground) bgGradientAngle = ang
                            else currentShape?.gradientAngle = ang
                        }
                        "srgbClr" -> {
                            val baseColor = parser.attr("val")?.toAndroidColorOrNull()
                            if (baseColor != null) {
                                activeColorBuilder = PendingColorBuilder(baseColor)
                            }
                        }
                        "schemeClr", "prstClr" -> {
                            val key = parser.attr("val")?.trim()?.lowercase(Locale.ROOT)
                            val baseColor = themeColors[key] ?: key.toThemeColorOrNull()
                            if (baseColor != null) {
                                activeColorBuilder = PendingColorBuilder(baseColor)
                            }
                        }
                        "sysClr" -> {
                            val baseColor = parser.attr("lastClr")?.toAndroidColorOrNull()
                                ?: parser.attr("val").toSysColorOrNull()
                            if (baseColor != null) {
                                activeColorBuilder = PendingColorBuilder(baseColor)
                            }
                        }
                        "lumMod" -> activeColorBuilder?.lumMod = (parser.attr("val")?.toFloatOrNull() ?: 100_000f) / 100_000f
                        "lumOff" -> activeColorBuilder?.lumOff = (parser.attr("val")?.toFloatOrNull() ?: 0f) / 100_000f
                        "tint" -> activeColorBuilder?.tint = (parser.attr("val")?.toFloatOrNull() ?: 100_000f) / 100_000f
                        "shade" -> activeColorBuilder?.shade = (parser.attr("val")?.toFloatOrNull() ?: 100_000f) / 100_000f
                        "alpha" -> activeColorBuilder?.alpha = (parser.attr("val")?.toFloatOrNull() ?: 100_000f) / 100_000f
                        "p" -> {
                            currentParagraphRuns = mutableListOf()
                            currentParagraphAlign = null
                            currentParagraphLevel = 0
                            currentParagraphBullet = null
                            currentParagraphSuppressBullet = false
                            currentParagraphAutoNumType = null
                        }
                        "pPr" -> {
                            val algnAttr = parser.attr("algn")
                            if (algnAttr != null) {
                                val parsedAlign = parseTextAlign(algnAttr, currentShape?.placeholderType)
                                currentParagraphAlign = parsedAlign
                                if (currentShape?.textAlign == null) currentShape?.textAlign = parsedAlign
                                currentCell?.textAlign = parsedAlign
                            }
                            currentParagraphLevel = parser.attr("lvl")?.toIntOrNull()?.coerceIn(0, 6) ?: 0
                        }
                        "r", "fld" -> {
                            currentRunText.clear()
                            currentRunStyle = MutableTextRunStyle(
                                fontSize = currentShape?.defaultFontSize ?: currentCell?.fontSize,
                                fontFamily = currentShape?.defaultFontFamily ?: currentCell?.fontFamily,
                                isBold = currentShape?.defaultBold ?: false,
                                isItalic = false,
                                isUnderline = false,
                                color = currentShape?.defaultTextColor ?: currentCell?.textColor
                            )
                        }
                        "rPr", "defRPr", "endParaRPr" -> {
                            insideTextStyle = true
                            insideDefRPr = (name == "defRPr")
                            val runSize = parser.attr("sz")?.toFloatOrNull()?.div(100f)
                            if (runSize != null) {
                                currentRunStyle.fontSize = runSize
                                if (insideDefRPr || currentShape?.defaultFontSize == null) {
                                    currentShape?.defaultFontSize = currentShape?.defaultFontSize ?: runSize
                                }
                                if (insideDefRPr || currentCell?.fontSize == null) {
                                    currentCell?.fontSize = runSize
                                }
                            }
                            val bold = parser.attr("b")
                            if (bold == "1" || bold.equals("true", ignoreCase = true)) {
                                currentRunStyle.isBold = true
                                if (insideDefRPr) currentShape?.defaultBold = true
                            } else if (bold == "0" || bold.equals("false", ignoreCase = true)) {
                                currentRunStyle.isBold = false
                            }
                            val italic = parser.attr("i")
                            if (italic == "1" || italic.equals("true", ignoreCase = true)) {
                                currentRunStyle.isItalic = true
                            }
                            val underline = parser.attr("u")
                            if (!underline.isNullOrBlank() && underline != "none") {
                                currentRunStyle.isUnderline = true
                            }
                        }
                        "latin", "ea", "cs" -> if (insideTextStyle) {
                            val typeface = parser.attr("typeface")
                            if (!typeface.isNullOrBlank() && !typeface.startsWith("+")) {
                                currentRunStyle.fontFamily = typeface
                                if (insideDefRPr || currentShape?.defaultFontFamily == null) {
                                    currentShape?.defaultFontFamily = typeface
                                }
                                currentCell?.fontFamily = typeface
                            }
                        }
                        "blip" -> {
                            val relId = parser.attr("embed") ?: parser.attr("link")
                            if (insideBackground) {
                                bgImageRelId = relId
                            } else if (currentShape != null) {
                                currentShape?.imageRelationshipId = relId
                            }
                        }
                        "srcRect" -> if (currentShape != null) {
                            val l = (parser.attr("l")?.toFloatOrNull() ?: 0f) / 100_000f
                            val t = (parser.attr("t")?.toFloatOrNull() ?: 0f) / 100_000f
                            val r = (parser.attr("r")?.toFloatOrNull() ?: 0f) / 100_000f
                            val b = (parser.attr("b")?.toFloatOrNull() ?: 0f) / 100_000f
                            if (l > 0f || t > 0f || r > 0f || b > 0f) {
                                currentShape?.cropRect = CropFractions(l, t, r, b)
                            }
                        }
                        "t" -> if (currentShape != null || currentCell != null) captureText = true
                        "br" -> if (currentShape != null || currentCell != null) {
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
                        "srgbClr", "schemeClr", "prstClr", "sysClr" -> {
                            activeColorBuilder?.build()?.let { commitPendingColor(it) }
                            activeColorBuilder = null
                        }
                        "bg" -> {
                            val bgImg = bgImageRelId?.let { imagesByRelationship[it] }
                            if (bgImg != null) {
                                background = SlideBackground.Image(bgImg)
                            } else if (bgGradientStops.size >= 2) {
                                background = SlideBackground.Gradient(bgGradientStops.toList(), bgGradientAngle)
                            }
                            insideBackground = false
                        }
                        "solidFill", "buClr" -> colorTarget = null
                        "gradFill" -> {
                            insideGradientFill = false
                            colorTarget = null
                        }
                        "custGeom" -> insideCustGeom = false
                        "path" -> if (insideCustGeom) {
                            currentCustomPath?.let { currentShape?.customPaths?.add(it) }
                            currentCustomPath = null
                        }
                        "moveTo" -> if (currentCustomPath != null && currentPathPoints.isNotEmpty()) {
                            val (px, py) = currentPathPoints.first()
                            currentCustomPath?.commands?.add(CustomPathCmd.MoveTo(px, py))
                            currentPathCommand = null
                        }
                        "lnTo" -> if (currentCustomPath != null && currentPathPoints.isNotEmpty()) {
                            val (px, py) = currentPathPoints.first()
                            currentCustomPath?.commands?.add(CustomPathCmd.LineTo(px, py))
                            currentPathCommand = null
                        }
                        "quadBezTo" -> if (currentCustomPath != null && currentPathPoints.size >= 2) {
                            val (x1, y1) = currentPathPoints[0]
                            val (x2, y2) = currentPathPoints[1]
                            currentCustomPath?.commands?.add(CustomPathCmd.QuadTo(x1, y1, x2, y2))
                            currentPathCommand = null
                        }
                        "cubicBezTo" -> if (currentCustomPath != null && currentPathPoints.size >= 3) {
                            val (x1, y1) = currentPathPoints[0]
                            val (x2, y2) = currentPathPoints[1]
                            val (x3, y3) = currentPathPoints[2]
                            currentCustomPath?.commands?.add(CustomPathCmd.CubicTo(x1, y1, x2, y2, x3, y3))
                            currentPathCommand = null
                        }
                        "t" -> captureText = false
                        "r", "fld" -> {
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
                            val hasNonBlankText = currentParagraphRuns.any { it.text.isNotBlank() }
                            if (hasNonBlankText) {
                                val resolvedBulletPrefix = when {
                                    currentParagraphSuppressBullet -> null
                                    currentParagraphAutoNumType != null -> {
                                        currentParagraphAutoNumIndex += 1
                                        "$currentParagraphAutoNumIndex. "
                                    }
                                    currentParagraphBullet != null -> "${currentParagraphBullet} "
                                    else -> null
                                }
                                val indentPrefix = if (currentParagraphLevel > 0) {
                                    "    ".repeat(currentParagraphLevel)
                                } else {
                                    ""
                                }
                                val prefix = indentPrefix + (resolvedBulletPrefix ?: "")
                                val finalRuns = if (prefix.isNotEmpty()) {
                                    val firstStyle = currentParagraphRuns.first()
                                    listOf(firstStyle.copy(text = prefix)) + currentParagraphRuns
                                } else {
                                    currentParagraphRuns.toList()
                                }
                                val align = currentParagraphAlign
                                    ?: currentShape?.textAlign
                                    ?: currentCell?.textAlign
                                    ?: parseTextAlign(null, currentShape?.placeholderType)
                                val paragraph = TextParagraphData(finalRuns, align)
                                currentShape?.paragraphs?.add(paragraph)
                                currentCell?.paragraphs?.add(paragraph)
                            }
                            if (currentShape != null || currentCell != null) {
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
                        "lnL", "lnR", "lnT", "lnB" -> {
                            insideCellBorder = false
                            colorTarget = null
                        }
                        "tcPr" -> {
                            colorTarget = null
                        }
                        "rPr", "defRPr", "endParaRPr" -> {
                            insideTextStyle = false
                            insideDefRPr = false
                        }
                        "sp", "pic", "cxnSp" -> {
                            val shape = currentShape
                            if (shape != null) {
                                if (isMasterOrLayout && shape.isPlaceholder) {
                                    val spec = shape.toPlaceholderSpec()
                                    spec.keys().forEach { k -> discoveredPlaceholders[k] = spec }
                                } else {
                                    val inherited = shape.resolveInheritedPlaceholder(inheritedPlaceholders)
                                    if (shape.isPlaceholder && !shape.hasExplicitSize && inherited == null) {
                                        val pType = shape.placeholderType?.lowercase(Locale.ROOT).orEmpty()
                                        if (pType == "body" || pType == "obj" || pType.isEmpty()) {
                                            fallbackBodyPlaceholderCounter += 1
                                        }
                                    }
                                    shape.toRenderElement(
                                        imagesByRelationship = imagesByRelationship,
                                        groupStack = groupStack,
                                        slideSize = slideSize,
                                        inherited = inherited,
                                        bodyIndex = fallbackBodyPlaceholderCounter
                                    )?.let(elements::add)
                                }
                            }
                            currentShape = null
                            captureText = false
                            insideTransform = false
                            insideTextStyle = false
                            insideDefRPr = false
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
                            if (graphic != null) {
                                val frameRect = transformEmuRect(graphic.toRect().withFallback(slideSize), groupStack)
                                when {
                                    table != null && table.rows.isNotEmpty() -> {
                                        elements += RenderElement.Table(
                                            rect = frameRect,
                                            rows = table.rows.toList(),
                                            columnWidths = table.columnWidths.toList()
                                        )
                                    }
                                    !graphic.chartRelId.isNullOrBlank() -> {
                                        val chartElem = parseChartElement(slidePart, graphic.chartRelId!!, frameRect, themeColors)
                                        if (chartElem != null) {
                                            elements += chartElem
                                        }
                                    }
                                    !graphic.diagramDrawingRelId.isNullOrBlank() || !graphic.diagramDataRelId.isNullOrBlank() -> {
                                        val diagElements = parseDiagramElements(
                                            slidePart = slidePart,
                                            drawingRelId = graphic.diagramDrawingRelId,
                                            dataRelId = graphic.diagramDataRelId,
                                            frameRect = frameRect,
                                            slideSize = slideSize,
                                            themeColors = themeColors
                                        )
                                        elements.addAll(diagElements)
                                    }
                                }
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

        return ParsedSlideXml(
            background = background,
            elements = elements,
            placeholders = discoveredPlaceholders,
            showMasterSp = showMasterSp,
            unsupported = unsupported && elements.isEmpty()
        )
    }

    private fun parseChartElement(
        slidePart: PackagePart,
        chartRelId: String,
        rect: EmuRect,
        themeColors: Map<String, Int>
    ): RenderElement.Chart? {
        val rel = runCatching { slidePart.getRelationship(chartRelId) }.getOrNull() ?: return null
        val chartPart = runCatching { slidePart.getRelatedPart(rel) }.getOrNull() ?: return null
        val xmlBytes = runCatching { readPartBytes(chartPart, MAX_SLIDE_XML_BYTES) }.getOrNull() ?: return null

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), null)

        var chartTitle = ""
        var chartType = ChartKind.BAR
        val seriesList = mutableListOf<ChartSeriesData>()
        var currentSeriesName = ""
        val currentCategories = mutableListOf<String>()
        val currentValues = mutableListOf<Float>()

        var insideTitle = false
        var insideSer = false
        var insideSerTx = false
        var insideCat = false
        var insideVal = false
        var captureV = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = localName(parser.name)
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "title" -> if (!insideSer) insideTitle = true
                        "pieChart", "pie3DChart", "doughnutChart" -> chartType = ChartKind.PIE
                        "lineChart", "line3DChart", "areaChart" -> chartType = ChartKind.LINE
                        "barChart", "bar3DChart" -> chartType = ChartKind.BAR
                        "ser" -> {
                            insideSer = true
                            currentSeriesName = ""
                            currentCategories.clear()
                            currentValues.clear()
                        }
                        "tx" -> if (insideSer) insideSerTx = true
                        "cat" -> if (insideSer) insideCat = true
                        "val", "yVal" -> if (insideSer) insideVal = true
                        "v", "t" -> captureV = true
                    }
                }
                XmlPullParser.TEXT -> if (captureV) {
                    val txt = parser.text.orEmpty().trim()
                    if (txt.isNotEmpty()) {
                        when {
                            insideTitle -> chartTitle = if (chartTitle.isEmpty()) txt else "$chartTitle $txt"
                            insideSerTx -> currentSeriesName = txt
                            insideCat -> currentCategories.add(txt)
                            insideVal -> txt.toFloatOrNull()?.let { currentValues.add(it) }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (name) {
                        "title" -> insideTitle = false
                        "tx" -> insideSerTx = false
                        "cat" -> insideCat = false
                        "val", "yVal" -> insideVal = false
                        "v", "t" -> captureV = false
                        "ser" -> {
                            if (currentValues.isNotEmpty()) {
                                val palette = listOf(
                                    themeColors["accent1"] ?: Color.rgb(68, 114, 196),
                                    themeColors["accent2"] ?: Color.rgb(237, 125, 49),
                                    themeColors["accent3"] ?: Color.rgb(165, 165, 165),
                                    themeColors["accent4"] ?: Color.rgb(255, 192, 0),
                                    themeColors["accent5"] ?: Color.rgb(91, 155, 213),
                                    themeColors["accent6"] ?: Color.rgb(112, 173, 71)
                                )
                                seriesList += ChartSeriesData(
                                    name = currentSeriesName.ifBlank { "Series ${seriesList.size + 1}" },
                                    categories = currentCategories.toList(),
                                    values = currentValues.toList(),
                                    color = palette[seriesList.size % palette.size]
                                )
                            }
                            insideSer = false
                        }
                    }
                }
            }
            event = parser.next()
        }

        if (seriesList.isEmpty() && chartTitle.isBlank()) return null
        return RenderElement.Chart(
            rect = rect,
            title = chartTitle,
            kind = chartType,
            series = seriesList
        )
    }

    private fun parseDiagramElements(
        slidePart: PackagePart,
        drawingRelId: String?,
        dataRelId: String?,
        frameRect: EmuRect,
        slideSize: SlideSize,
        themeColors: Map<String, Int>
    ): List<RenderElement> {
        if (!drawingRelId.isNullOrBlank()) {
            val rel = runCatching { slidePart.getRelationship(drawingRelId) }.getOrNull()
            val drawingPart = rel?.let { runCatching { slidePart.getRelatedPart(it) }.getOrNull() }
            val bytes = drawingPart?.let { runCatching { readPartBytes(it, MAX_SLIDE_XML_BYTES) }.getOrNull() }
            if (bytes != null && drawingPart != null) {
                val parsed = parsePptxSlideXml(
                    xmlBytes = bytes,
                    slidePart = drawingPart,
                    imagesByRelationship = extractPptxImages(drawingPart),
                    slideSize = slideSize,
                    themeColors = themeColors,
                    inheritedPlaceholders = emptyMap(),
                    isMasterOrLayout = false
                )
                if (parsed.elements.isNotEmpty()) {
                    return parsed.elements.map { elem ->
                        offsetElementRect(elem, frameRect.x, frameRect.y)
                    }
                }
            }
        }

        if (!dataRelId.isNullOrBlank()) {
            val rel = runCatching { slidePart.getRelationship(dataRelId) }.getOrNull()
            val dataPart = rel?.let { runCatching { slidePart.getRelatedPart(it) }.getOrNull() }
            val bytes = dataPart?.let { runCatching { readPartBytes(it, MAX_SLIDE_XML_BYTES) }.getOrNull() }
            if (bytes != null) {
                val nodes = mutableListOf<String>()
                runCatching {
                    val parser = Xml.newPullParser()
                    parser.setInput(ByteArrayInputStream(bytes), null)
                    var currentNode = StringBuilder()
                    var inT = false
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        val tag = localName(parser.name)
                        when (ev) {
                            XmlPullParser.START_TAG -> {
                                if (tag == "pt") currentNode = StringBuilder()
                                else if (tag == "t") inT = true
                            }
                            XmlPullParser.TEXT -> if (inT) {
                                currentNode.append(parser.text.orEmpty())
                            }
                            XmlPullParser.END_TAG -> {
                                if (tag == "t") inT = false
                                else if (tag == "pt") {
                                    val clean = currentNode.toString().trim()
                                    if (clean.isNotEmpty()) nodes.add(clean)
                                }
                            }
                        }
                        ev = parser.next()
                    }
                }
                if (nodes.isNotEmpty()) {
                    val cols = nodes.size.coerceIn(1, 4)
                    val rows = ((nodes.size + cols - 1) / cols).coerceAtLeast(1)
                    val gapX = frameRect.width / 30
                    val gapY = frameRect.height / 20
                    val itemW = ((frameRect.width - gapX * (cols - 1)) / cols).coerceAtLeast(1L)
                    val itemH = ((frameRect.height - gapY * (rows - 1)) / rows).coerceAtLeast(1L)
                    val accent = themeColors["accent1"] ?: Color.rgb(68, 114, 196)
                    return nodes.mapIndexed { idx, label ->
                        val c = idx % cols
                        val r = idx / cols
                        val nodeRect = EmuRect(
                            x = frameRect.x + c * (itemW + gapX),
                            y = frameRect.y + r * (itemH + gapY),
                            width = itemW,
                            height = itemH
                        )
                        RenderElement.ShapeWithText(
                            rect = nodeRect,
                            fillColor = accent,
                            preset = "roundRect",
                            text = label,
                            paragraphs = listOf(
                                TextParagraphData(
                                    runs = listOf(
                                        TextRunData(
                                            text = label,
                                            fontSize = 16f,
                                            isBold = true,
                                            color = Color.WHITE
                                        )
                                    ),
                                    align = TextAlignMode.CENTER
                                )
                            ),
                            verticalAnchor = VerticalAnchor.CENTER
                        )
                    }
                }
            }
        }

        return emptyList()
    }

    private fun offsetElementRect(element: RenderElement, dx: Long, dy: Long): RenderElement {
        if (dx == 0L && dy == 0L) return element
        val shifted = element.rect.copy(x = (element.rect.x + dx).coerceAtLeast(0L), y = (element.rect.y + dy).coerceAtLeast(0L))
        return when (element) {
            is RenderElement.ShapeWithText -> element.copy(rect = shifted)
            is RenderElement.Image -> element.copy(rect = shifted)
            is RenderElement.Connector -> element.copy(rect = shifted)
            is RenderElement.Table -> element.copy(rect = shifted)
            is RenderElement.Chart -> element.copy(rect = shifted)
        }
    }

    private fun MutableRenderShape.toPlaceholderSpec(): PlaceholderSpec {
        return PlaceholderSpec(
            type = placeholderType?.lowercase(Locale.ROOT),
            idx = placeholderIdx,
            rect = if (hasExplicitSize) EmuRect(x, y, width, height) else null,
            fontSize = defaultFontSize,
            textColor = defaultTextColor,
            textAlign = textAlign,
            verticalAnchor = verticalAnchor
        )
    }

    private fun MutableRenderShape.resolveInheritedPlaceholder(
        inheritedPlaceholders: Map<String, PlaceholderSpec>
    ): PlaceholderSpec? {
        if (!isPlaceholder) return null
        val idxKey = placeholderIdx?.let { "idx:$it" }
        val typeKey = placeholderType?.lowercase(Locale.ROOT)?.let { "type:$it" }
        val byIdx = idxKey?.let { inheritedPlaceholders[it] }
        val byType = typeKey?.let { inheritedPlaceholders[it] }
        return when {
            byIdx != null && byType != null -> byIdx.mergeWithFallback(byType)
            byIdx != null -> byIdx
            byType != null -> byType
            placeholderType == null && placeholderIdx != null -> inheritedPlaceholders["type:body"]
            else -> null
        }
    }

    private fun MutableRenderShape.toRenderElement(
        imagesByRelationship: Map<String, ImagePayload>,
        groupStack: List<MutableGroupTransform>,
        slideSize: SlideSize,
        inherited: PlaceholderSpec?,
        bodyIndex: Int
    ): RenderElement? {
        val rawRect = when {
            hasExplicitSize -> EmuRect(
                x = x.coerceAtLeast(0L),
                y = y.coerceAtLeast(0L),
                width = width,
                height = height
            )
            inherited?.rect != null -> inherited.rect
            isPlaceholder -> defaultPlaceholderRect(placeholderType, placeholderIdx, slideSize, bodyIndex)
            else -> EmuRect(
                x = x.coerceAtLeast(0L),
                y = y.coerceAtLeast(0L),
                width = width,
                height = height
            ).withFallback(slideSize)
        }

        val rect = transformEmuRect(rawRect, groupStack)

        val presetLower = shapePreset?.lowercase(Locale.ROOT).orEmpty()
        val isLineShape = kind == MutableRenderKind.CONNECTOR ||
            presetLower == "line" ||
            presetLower.startsWith("straightconnector") ||
            presetLower.startsWith("bentconnector") ||
            presetLower.startsWith("curvedconnector")

        if (isLineShape && normalizeText(text.toString()).isBlank() && imageRelationshipId == null) {
            if (hasNoStroke && strokeColor == null) return null
            return RenderElement.Connector(
                rect = rect,
                color = strokeColor ?: fillColor ?: Color.rgb(80, 80, 80),
                strokeWidthEmu = (strokeWidthEmu ?: 18_000L).coerceAtLeast(9_525L),
                hasStartArrow = hasStartArrow,
                hasEndArrow = hasEndArrow,
                isDashed = isDashedStroke,
                flipH = flipH,
                flipV = flipV
            )
        }

        if (kind == MutableRenderKind.PICTURE) {
            val payload = imageRelationshipId?.let(imagesByRelationship::get) ?: return null
            return RenderElement.Image(
                rect = rect,
                image = payload,
                crop = cropRect,
                strokeColor = if (hasNoStroke) null else strokeColor,
                strokeWidthEmu = strokeWidthEmu
            )
        }

        val shapeImagePayload = imageRelationshipId?.let(imagesByRelationship::get)
        val cleanText = normalizeText(text.toString())
        val effectiveFill = if (hasNoFill) null else fillColor
        val effectiveGradient = if (hasNoFill || gradientColors.size < 2) emptyList() else gradientColors.toList()
        val effectiveStroke = if (hasNoStroke) null else strokeColor

        if (cleanText.isBlank() &&
            effectiveFill == null &&
            effectiveGradient.isEmpty() &&
            effectiveStroke == null &&
            shapeImagePayload == null
        ) {
            return null
        }

        val resolvedPlaceholderType = placeholderType ?: inherited?.type
        val resolvedFontSize = defaultFontSize
            ?: inherited?.fontSize
            ?: defaultFontSize(resolvedPlaceholderType, rect.height)
        val resolvedTextColor = defaultTextColor
            ?: inherited?.textColor
            ?: defaultContrastTextColor(effectiveFill ?: effectiveGradient.firstOrNull())
        val resolvedAlign = textAlign
            ?: inherited?.textAlign
            ?: parseTextAlign(null, resolvedPlaceholderType)
        val resolvedVerticalAnchor = verticalAnchor
            ?: inherited?.verticalAnchor
            ?: defaultVerticalAnchor(resolvedPlaceholderType, shapePreset)

        return RenderElement.ShapeWithText(
            rect = rect,
            fillColor = effectiveFill,
            gradientColors = effectiveGradient,
            gradientAngle = gradientAngle,
            backgroundImage = shapeImagePayload,
            preset = shapePreset,
            adjustValue = adjustValue,
            customPaths = customPaths.toList(),
            strokeColor = effectiveStroke,
            strokeWidthEmu = strokeWidthEmu,
            isDashedStroke = isDashedStroke,
            text = cleanText,
            paragraphs = paragraphs.toList(),
            fontSize = resolvedFontSize,
            fontFamily = defaultFontFamily,
            isBold = defaultBold || isTitlePlaceholder(resolvedPlaceholderType),
            textColor = resolvedTextColor,
            align = resolvedAlign,
            verticalAnchor = resolvedVerticalAnchor
        )
    }

    private fun renderExtractionToBitmap(
        extraction: SlideExtraction,
        position: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val slideAspect = (extraction.slideWidthEmu.toFloat() / extraction.slideHeightEmu.coerceAtLeast(1L).toFloat())
            .takeIf { it.isFinite() && it > 0.2f } ?: (16f / 9f)
        val outWidth = targetWidth
        val outHeight = (outWidth / slideAspect).roundToInt().coerceIn(240, targetHeight * 2)

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        return try {
            drawSlideBackground(canvas, extraction.background, outWidth, outHeight)
            if (extraction.unsupported && extraction.elements.isEmpty()) {
                drawUnsupportedContent(canvas, position, outWidth, outHeight)
                return bitmap
            }

            extraction.elements.forEach { element ->
                val rect = scaleEmuRect(
                    rect = element.rect,
                    sourceWidthEmu = extraction.slideWidthEmu,
                    sourceHeightEmu = extraction.slideHeightEmu,
                    targetWidth = outWidth,
                    targetHeight = outHeight
                )
                if (rect.width() < 0.5f && rect.height() < 0.5f) return@forEach

                when (element) {
                    is RenderElement.Connector -> runCatching {
                        drawConnector(canvas, rect, element, outWidth)
                    }
                    is RenderElement.Image -> runCatching {
                        drawImageElement(canvas, rect, element, outWidth)
                    }
                    is RenderElement.ShapeWithText -> runCatching {
                        drawShapeWithText(canvas, rect, element, outWidth)
                    }
                    is RenderElement.Table -> runCatching {
                        drawTable(canvas, rect, element, outWidth)
                    }
                    is RenderElement.Chart -> runCatching {
                        drawChart(canvas, rect, element, outWidth)
                    }
                }
            }

            if (extraction.elements.isEmpty() && extraction.background is SlideBackground.Solid &&
                extraction.background.color == Color.WHITE
            ) {
                drawUnsupportedContent(canvas, position, outWidth, outHeight)
            }
            bitmap
        } catch (throwable: Throwable) {
            bitmap.recycle()
            throw throwable
        }
    }

    private fun drawSlideBackground(
        canvas: Canvas,
        background: SlideBackground?,
        width: Int,
        height: Int
    ) {
        val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        when (background) {
            is SlideBackground.Solid -> canvas.drawColor(background.color)
            is SlideBackground.Gradient -> {
                if (background.colors.size >= 2) {
                    val rad = Math.toRadians(background.angleDeg.toDouble())
                    val cx = width / 2f
                    val cy = height / 2f
                    val dx = (cos(rad) * cx).toFloat()
                    val dy = (sin(rad) * cy).toFloat()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            cx - dx,
                            cy - dy,
                            cx + dx,
                            cy + dy,
                            background.colors.toIntArray(),
                            null,
                            Shader.TileMode.CLAMP
                        )
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(fullRect, paint)
                } else {
                    canvas.drawColor(background.colors.firstOrNull() ?: Color.WHITE)
                }
            }
            is SlideBackground.Image -> {
                canvas.drawColor(Color.WHITE)
                drawImagePayload(canvas, fullRect, background.image, null)
            }
            null -> canvas.drawColor(Color.WHITE)
        }
    }

    private fun renderUnsupportedSlide(
        position: Int,
        targetWidth: Int,
        targetHeight: Int,
        detail: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
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
        detail: String = "Blank Slide"
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

    private fun drawShapeWithText(
        canvas: Canvas,
        rect: RectF,
        element: RenderElement.ShapeWithText,
        targetWidth: Int
    ) {
        val shapePath = buildShapePath(rect, element.preset, element.adjustValue, element.customPaths)

        if (element.backgroundImage != null) {
            canvas.save()
            canvas.clipPath(shapePath)
            drawImagePayload(canvas, rect, element.backgroundImage, null)
            canvas.restore()
        } else if (element.gradientColors.size >= 2) {
            val rad = Math.toRadians(element.gradientAngle.toDouble())
            val cx = rect.centerX()
            val cy = rect.centerY()
            val dx = (cos(rad) * (rect.width() / 2f)).toFloat()
            val dy = (sin(rad) * (rect.height() / 2f)).toFloat()
            val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    cx - dx,
                    cy - dy,
                    cx + dx,
                    cy + dy,
                    element.gradientColors.toIntArray(),
                    null,
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
            }
            canvas.drawPath(shapePath, gradPaint)
        } else if (element.fillColor != null && element.fillColor != Color.TRANSPARENT) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = element.fillColor
                style = Paint.Style.FILL
            }
            canvas.drawPath(shapePath, fillPaint)
        }

        element.strokeColor?.let { stroke ->
            val strokePx = (((element.strokeWidthEmu ?: 12_700L).toFloat() / DEFAULT_SLIDE_WIDTH_EMU.toFloat()) * targetWidth)
                .coerceIn(1.5f, 16f)
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                if (element.isDashedStroke) {
                    pathEffect = DashPathEffect(floatArrayOf(strokePx * 3f, strokePx * 2f), 0f)
                }
            }
            canvas.drawPath(shapePath, strokePaint)
        }

        if (element.text.isNotBlank() || element.paragraphs.isNotEmpty()) {
            val padX = (rect.width() * 0.03f).coerceIn(6f, 22f)
            val padY = (rect.height() * 0.04f).coerceIn(4f, 18f)
            val textRect = if (rect.width() > padX * 2.5f && rect.height() > padY * 2.5f) {
                RectF(rect.left + padX, rect.top + padY, rect.right - padX, rect.bottom - padY)
            } else {
                rect
            }

            drawParagraphsWithStaticLayout(
                canvas = canvas,
                rect = textRect,
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
                verticalAnchor = element.verticalAnchor,
                targetWidth = targetWidth
            )
        }
    }

    private fun buildShapePath(
        rect: RectF,
        preset: String?,
        adjustValue: Float?,
        customPaths: List<MutableCustomPath>
    ): Path {
        if (customPaths.isNotEmpty()) {
            val path = Path()
            for (custom in customPaths) {
                val sx = rect.width() / custom.width.coerceAtLeast(1f)
                val sy = rect.height() / custom.height.coerceAtLeast(1f)
                for (cmd in custom.commands) {
                    when (cmd) {
                        is CustomPathCmd.MoveTo -> path.moveTo(rect.left + cmd.x * sx, rect.top + cmd.y * sy)
                        is CustomPathCmd.LineTo -> path.lineTo(rect.left + cmd.x * sx, rect.top + cmd.y * sy)
                        is CustomPathCmd.QuadTo -> path.quadTo(
                            rect.left + cmd.x1 * sx,
                            rect.top + cmd.y1 * sy,
                            rect.left + cmd.x2 * sx,
                            rect.top + cmd.y2 * sy
                        )
                        is CustomPathCmd.CubicTo -> path.cubicTo(
                            rect.left + cmd.x1 * sx,
                            rect.top + cmd.y1 * sy,
                            rect.left + cmd.x2 * sx,
                            rect.top + cmd.y2 * sy,
                            rect.left + cmd.x3 * sx,
                            rect.top + cmd.y3 * sy
                        )
                        CustomPathCmd.Close -> path.close()
                    }
                }
            }
            return path
        }

        val path = Path()
        val p = preset?.lowercase(Locale.ROOT).orEmpty()
        when (p) {
            "ellipse", "oval" -> path.addOval(rect, Path.Direction.CW)
            "roundrect", "round1rect", "round2samerect", "sniproundrect" -> {
                val minSide = min(rect.width(), rect.height())
                val radius = (minSide * (adjustValue ?: 0.166f).coerceIn(0.02f, 0.5f))
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            "triangle", "isoscelestriangle" -> {
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "rttriangle" -> {
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "diamond" -> {
                path.moveTo(rect.centerX(), rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.centerX(), rect.bottom)
                path.lineTo(rect.left, rect.centerY())
                path.close()
            }
            "parallelogram" -> {
                val offset = rect.width() * 0.2f
                path.moveTo(rect.left + offset, rect.top)
                path.lineTo(rect.right, rect.top)
                path.lineTo(rect.right - offset, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "trapezoid" -> {
                val offset = rect.width() * 0.18f
                path.moveTo(rect.left + offset, rect.top)
                path.lineTo(rect.right - offset, rect.top)
                path.lineTo(rect.right, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                path.close()
            }
            "hexagon" -> {
                val dx = rect.width() * 0.22f
                path.moveTo(rect.left + dx, rect.top)
                path.lineTo(rect.right - dx, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - dx, rect.bottom)
                path.lineTo(rect.left + dx, rect.bottom)
                path.lineTo(rect.left, rect.centerY())
                path.close()
            }
            "chevron", "homeplate" -> {
                val tip = min(rect.width() * 0.25f, rect.height() * 0.45f)
                path.moveTo(rect.left, rect.top)
                path.lineTo(rect.right - tip, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - tip, rect.bottom)
                path.lineTo(rect.left, rect.bottom)
                if (p == "chevron") {
                    path.lineTo(rect.left + tip * 0.65f, rect.centerY())
                }
                path.close()
            }
            "rightarrow", "notchedrightarrow" -> {
                val headW = min(rect.width() * 0.35f, rect.height() * 0.8f)
                val shaftInset = rect.height() * 0.25f
                path.moveTo(rect.left, rect.top + shaftInset)
                path.lineTo(rect.right - headW, rect.top + shaftInset)
                path.lineTo(rect.right - headW, rect.top)
                path.lineTo(rect.right, rect.centerY())
                path.lineTo(rect.right - headW, rect.bottom)
                path.lineTo(rect.right - headW, rect.bottom - shaftInset)
                path.lineTo(rect.left, rect.bottom - shaftInset)
                path.close()
            }
            else -> path.addRect(rect, Path.Direction.CW)
        }
        return path
    }

    private fun drawConnector(canvas: Canvas, rect: RectF, element: RenderElement.Connector, targetWidth: Int) {
        val connectorStrokeWidth = ((element.strokeWidthEmu / DEFAULT_SLIDE_WIDTH_EMU.toFloat()) * targetWidth)
            .coerceIn(2f, 16f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = connectorStrokeWidth
            if (element.isDashed) {
                pathEffect = DashPathEffect(floatArrayOf(connectorStrokeWidth * 3f, connectorStrokeWidth * 2f), 0f)
            }
        }
        val startX = if (element.flipH) rect.right else rect.left
        val endX = if (element.flipH) rect.left else rect.right
        val startY = if (element.flipV) rect.bottom else rect.top
        val endY = if (element.flipV) rect.top else rect.bottom

        canvas.drawLine(startX, startY, endX, endY, paint)
        if (element.hasStartArrow) drawArrowHead(canvas, endX, endY, startX, startY, element.color, connectorStrokeWidth)
        if (element.hasEndArrow) drawArrowHead(canvas, startX, startY, endX, endY, element.color, connectorStrokeWidth)
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
        val length = (strokeWidth * 4.5f).coerceIn(10f, 32f)
        val spread = 0.52
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

        val colWidthsPx = FloatArray(columnCount) { colIdx ->
            if (totalColumnWidth != null) {
                rect.width() * ((element.columnWidths.getOrNull(colIdx) ?: 0L).coerceAtLeast(1L).toFloat() / totalColumnWidth.toFloat())
            } else {
                fallbackColumnWidth
            }
        }
        val rowHeightsPx = FloatArray(rows.size) { rowIdx ->
            if (totalRowHeight != null) {
                rect.height() * (rows[rowIdx].height.coerceAtLeast(1L).toFloat() / totalRowHeight.toFloat())
            } else {
                rect.height() / rows.size
            }
        }

        var y = rect.top
        rows.forEachIndexed { rowIndex, row ->
            val rowHeight = rowHeightsPx[rowIndex]
            var x = rect.left
            for (columnIndex in 0 until columnCount) {
                val columnWidth = colWidthsPx[columnIndex]
                val cell = row.cells.getOrNull(columnIndex)
                if (cell?.isMergedContinuation == true) {
                    x += columnWidth
                    continue
                }

                val spanCols = (cell?.gridSpan ?: 1).coerceAtMost(columnCount - columnIndex)
                val spanRows = (cell?.rowSpan ?: 1).coerceAtMost(rows.size - rowIndex)
                var mergedWidth = 0f
                for (c in 0 until spanCols) mergedWidth += colWidthsPx[columnIndex + c]
                var mergedHeight = 0f
                for (r in 0 until spanRows) mergedHeight += rowHeightsPx[rowIndex + r]

                val cellRect = RectF(x, y, x + mergedWidth, y + mergedHeight)
                val resolvedFill = when {
                    cell?.fillColor != null -> cell.fillColor
                    rowIndex == 0 -> Color.rgb(226, 232, 240)
                    rowIndex % 2 == 1 -> Color.rgb(248, 250, 252)
                    else -> Color.WHITE
                }
                if (resolvedFill != null && resolvedFill != Color.TRANSPARENT) {
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = resolvedFill
                        style = Paint.Style.FILL
                        canvas.drawRect(cellRect, this)
                    }
                }

                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = cell?.borderColor ?: Color.rgb(203, 213, 225)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                }
                canvas.drawRect(cellRect, borderPaint)

                cell?.let { tableCell ->
                    val defaultCellColor = tableCell.textColor
                        ?: defaultContrastTextColor(resolvedFill)
                    drawParagraphsWithStaticLayout(
                        canvas = canvas,
                        rect = RectF(cellRect.left + 8f, cellRect.top + 6f, cellRect.right - 8f, cellRect.bottom - 6f),
                        paragraphs = tableCell.paragraphs.ifEmpty {
                            val clean = normalizeText(tableCell.text.toString())
                            if (clean.isBlank()) {
                                emptyList()
                            } else {
                                listOf(
                                    TextParagraphData(
                                        runs = listOf(
                                            TextRunData(
                                                text = clean,
                                                fontSize = tableCell.fontSize ?: 13f,
                                                isBold = rowIndex == 0,
                                                color = defaultCellColor
                                            )
                                        ),
                                        align = tableCell.textAlign
                                    )
                                )
                            }
                        },
                        defaultFontSize = tableCell.fontSize ?: 13f,
                        defaultColor = defaultCellColor,
                        verticalAnchor = tableCell.verticalAnchor ?: VerticalAnchor.CENTER,
                        targetWidth = targetWidth
                    )
                }
                x += columnWidth
            }
            y += rowHeight
        }
    }

    private fun drawChart(canvas: Canvas, rect: RectF, chart: RenderElement.Chart, targetWidth: Int) {
        val scale = (targetWidth / 1280f).coerceIn(0.6f, 2f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(242, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
        }
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, cardPaint)
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, borderPaint)

        var topOffset = rect.top + 16f * scale
        if (chart.title.isNotBlank()) {
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = Color.rgb(30, 41, 59)
                textSize = 18f * scale
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(chart.title, rect.centerX(), topOffset + 16f * scale, titlePaint)
            topOffset += 32f * scale
        }

        val plotRect = RectF(
            rect.left + 44f * scale,
            topOffset + 8f * scale,
            rect.right - 20f * scale,
            rect.bottom - 38f * scale
        )
        if (plotRect.width() <= 20f || plotRect.height() <= 20f || chart.series.isEmpty()) return

        when (chart.kind) {
            ChartKind.PIE -> {
                val firstSeries = chart.series.first()
                val total = firstSeries.values.sum().takeIf { it > 0f } ?: return
                val radius = min(plotRect.width(), plotRect.height()) * 0.42f
                val oval = RectF(
                    plotRect.centerX() - radius,
                    plotRect.centerY() - radius,
                    plotRect.centerX() + radius,
                    plotRect.centerY() + radius
                )
                val sliceColors = listOf(
                    Color.rgb(68, 114, 196),
                    Color.rgb(237, 125, 49),
                    Color.rgb(112, 173, 71),
                    Color.rgb(255, 192, 0),
                    Color.rgb(91, 155, 213),
                    Color.rgb(165, 165, 165)
                )
                var startAngle = -90f
                firstSeries.values.forEachIndexed { idx, value ->
                    val sweep = (value / total) * 360f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = sliceColors[idx % sliceColors.size]
                        style = Paint.Style.FILL
                    }
                    canvas.drawArc(oval, startAngle, sweep, true, paint)
                    startAngle += sweep
                }
            }
            ChartKind.LINE, ChartKind.BAR -> {
                val maxVal = chart.series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(226, 232, 240)
                    strokeWidth = 1f * scale
                }
                for (step in 0..4) {
                    val gy = plotRect.bottom - (plotRect.height() * (step / 4f))
                    canvas.drawLine(plotRect.left, gy, plotRect.right, gy, gridPaint)
                }

                val categories = chart.series.first().categories
                val groupCount = chart.series.maxOf { it.values.size }.coerceAtLeast(1)
                val groupWidth = plotRect.width() / groupCount

                if (chart.kind == ChartKind.BAR) {
                    val barWidth = (groupWidth * 0.7f) / chart.series.size.coerceAtLeast(1)
                    chart.series.forEachIndexed { sIdx, series ->
                        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = series.color
                            style = Paint.Style.FILL
                        }
                        series.values.forEachIndexed { vIdx, value ->
                            val bh = (value.coerceAtLeast(0f) / maxVal) * plotRect.height()
                            val bx = plotRect.left + vIdx * groupWidth + (groupWidth * 0.15f) + sIdx * barWidth
                            canvas.drawRoundRect(
                                RectF(bx, plotRect.bottom - bh, bx + barWidth * 0.9f, plotRect.bottom),
                                4f * scale,
                                4f * scale,
                                barPaint
                            )
                        }
                    }
                } else {
                    chart.series.forEach { series ->
                        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = series.color
                            style = Paint.Style.STROKE
                            strokeWidth = 3f * scale
                        }
                        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = series.color
                            style = Paint.Style.FILL
                        }
                        val path = Path()
                        series.values.forEachIndexed { vIdx, value ->
                            val px = plotRect.left + (vIdx + 0.5f) * groupWidth
                            val py = plotRect.bottom - (value.coerceAtLeast(0f) / maxVal) * plotRect.height()
                            if (vIdx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            canvas.drawCircle(px, py, 4.5f * scale, dotPaint)
                        }
                        canvas.drawPath(path, linePaint)
                    }
                }

                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    color = Color.rgb(100, 116, 139)
                    textSize = 11f * scale
                    textAlign = Paint.Align.CENTER
                }
                for (i in 0 until groupCount) {
                    val cat = categories.getOrNull(i) ?: "${i + 1}"
                    val lx = plotRect.left + (i + 0.5f) * groupWidth
                    canvas.drawText(cat.take(12), lx, plotRect.bottom + 18f * scale, labelPaint)
                }
            }
        }
    }

    private fun drawParagraphsWithStaticLayout(
        canvas: Canvas,
        rect: RectF,
        paragraphs: List<TextParagraphData>,
        defaultFontSize: Float,
        defaultColor: Int,
        verticalAnchor: VerticalAnchor,
        targetWidth: Int
    ) {
        val textWidth = rect.width().roundToInt().coerceAtLeast(1)
        val availHeight = rect.height().coerceAtLeast(1f)
        if (paragraphs.isEmpty()) return

        var fontScale = 1.0f
        var builtLayout: StaticLayout? = null

        for (attempt in 0..4) {
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
                        if (run.isUnderline) {
                            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        run.fontFamily?.takeIf { it.isNotBlank() }?.let { family ->
                            builder.setSpan(TypefaceSpan(family), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        builder.setSpan(
                            ForegroundColorSpan(run.color ?: defaultColor),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        val px = (((run.fontSize ?: defaultFontSize) * (targetWidth / 960f) * fontScale))
                            .coerceIn(9f, 96f)
                            .roundToInt()
                        builder.setSpan(AbsoluteSizeSpan(px), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                if (paragraphIndex < paragraphs.lastIndex) builder.append('\n')
            }
            if (builder.isEmpty()) return

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = defaultColor
                textSize = (defaultFontSize * (targetWidth / 960f) * fontScale).coerceIn(9f, 96f)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val alignment = when (resolvedAlignment) {
                TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlignMode.END -> Layout.Alignment.ALIGN_OPPOSITE
                TextAlignMode.START -> Layout.Alignment.ALIGN_NORMAL
            }
            val candidate = StaticLayout.Builder.obtain(builder, 0, builder.length, textPaint, textWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.08f)
                .build()

            builtLayout = candidate
            if (candidate.height <= availHeight * 1.05f || fontScale <= 0.55f) {
                break
            }
            val ratio = (availHeight / candidate.height.toFloat()).coerceIn(0.65f, 0.88f)
            fontScale = (fontScale * ratio).coerceAtLeast(0.52f)
        }

        val layout = builtLayout ?: return
        val dy = when (verticalAnchor) {
            VerticalAnchor.CENTER -> ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)
            VerticalAnchor.BOTTOM -> (rect.height() - layout.height).coerceAtLeast(0f)
            VerticalAnchor.TOP -> 0f
        }

        canvas.save()
        canvas.clipRect(RectF(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 4f))
        canvas.translate(rect.left, rect.top + dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImageElement(
        canvas: Canvas,
        rect: RectF,
        element: RenderElement.Image,
        targetWidth: Int
    ) {
        drawImagePayload(canvas, rect, element.image, element.crop)
        element.strokeColor?.let { stroke ->
            val strokePx = (((element.strokeWidthEmu ?: 12_700L).toFloat() / DEFAULT_SLIDE_WIDTH_EMU.toFloat()) * targetWidth)
                .coerceIn(1.5f, 12f)
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke
                style = Paint.Style.STROKE
                strokeWidth = strokePx
            }
            canvas.drawRect(rect, strokePaint)
        }
    }

    private fun drawImagePayload(
        canvas: Canvas,
        rect: RectF,
        image: ImagePayload,
        crop: CropFractions?
    ) {
        val bitmap = decodePictureBytes(image.bytes, rect.width().roundToInt(), rect.height().roundToInt()) ?: return
        try {
            val srcRect = if (crop != null) {
                val left = (bitmap.width * crop.left.coerceIn(0f, 0.9f)).roundToInt()
                val top = (bitmap.height * crop.top.coerceIn(0f, 0.9f)).roundToInt()
                val right = (bitmap.width * (1f - crop.right.coerceIn(0f, 0.9f))).roundToInt().coerceAtLeast(left + 1)
                val bottom = (bitmap.height * (1f - crop.bottom.coerceIn(0f, 0.9f))).roundToInt().coerceAtLeast(top + 1)
                Rect(left, top, right, bottom)
            } else {
                null
            }
            canvas.drawBitmap(bitmap, srcRect, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
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
                    inPreferredConfig = Bitmap.Config.ARGB_8888
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

    private fun readBitmapFromDisk(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val file = cacheFile(sourceKey, position, targetWidth, targetHeight)
        if (!file.exists() || file.length() <= 0L) return null

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun writeBitmapToDisk(sourceKey: String, position: Int, targetWidth: Int, targetHeight: Int, bitmap: Bitmap) {
        cacheFile(sourceKey, position, targetWidth, targetHeight).outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
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

    private fun openInputStream(uri: Uri): InputStream? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            return runCatching { File(path).inputStream() }.getOrNull()
        }
        return context.contentResolver.openInputStream(uri)
    }

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
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_SLIDES = 300
        const val MAX_IMAGES_PER_LEGACY_SLIDE = 6
    }
}

private data class SlideSize(
    val widthEmu: Long = DEFAULT_SLIDE_WIDTH_EMU,
    val heightEmu: Long = DEFAULT_SLIDE_HEIGHT_EMU
)

private sealed interface SlideBackground {
    data class Solid(val color: Int) : SlideBackground
    data class Gradient(val colors: List<Int>, val angleDeg: Float = 45f) : SlideBackground
    data class Image(val image: ImagePayload) : SlideBackground
}

private data class ParsedSlideXml(
    val background: SlideBackground?,
    val elements: List<RenderElement>,
    val placeholders: Map<String, PlaceholderSpec>,
    val showMasterSp: Boolean,
    val unsupported: Boolean
)

private data class PlaceholderSpec(
    val type: String?,
    val idx: String?,
    val rect: EmuRect?,
    val fontSize: Float?,
    val textColor: Int?,
    val textAlign: TextAlignMode?,
    val verticalAnchor: VerticalAnchor?
) {
    fun keys(): List<String> {
        val list = mutableListOf<String>()
        if (!idx.isNullOrBlank()) list += "idx:$idx"
        if (!type.isNullOrBlank()) list += "type:${type.lowercase(Locale.ROOT)}"
        return list
    }

    fun mergeWithFallback(fallback: PlaceholderSpec): PlaceholderSpec {
        return PlaceholderSpec(
            type = type ?: fallback.type,
            idx = idx ?: fallback.idx,
            rect = rect ?: fallback.rect,
            fontSize = fontSize ?: fallback.fontSize,
            textColor = textColor ?: fallback.textColor,
            textAlign = textAlign ?: fallback.textAlign,
            verticalAnchor = verticalAnchor ?: fallback.verticalAnchor
        )
    }
}

private data class SlideExtraction(
    val slideWidthEmu: Long,
    val slideHeightEmu: Long,
    val background: SlideBackground?,
    val elements: List<RenderElement>,
    val unsupported: Boolean
) {
    companion object {
        fun unsupported(): SlideExtraction {
            return SlideExtraction(
                slideWidthEmu = DEFAULT_SLIDE_WIDTH_EMU,
                slideHeightEmu = DEFAULT_SLIDE_HEIGHT_EMU,
                background = SlideBackground.Solid(Color.WHITE),
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

private data class CropFractions(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
)

private data class EmuRect(
    val x: Long = 0L,
    val y: Long = 0L,
    val width: Long = 0L,
    val height: Long = 0L
) {
    fun withFallback(slideSize: SlideSize = SlideSize()): EmuRect {
        return copy(
            width = width.takeIf { it > 0L } ?: (slideSize.widthEmu - slideSize.widthEmu / 8),
            height = height.takeIf { it > 0L } ?: (slideSize.heightEmu / 4)
        )
    }
}

private sealed interface CustomPathCmd {
    data class MoveTo(val x: Float, val y: Float) : CustomPathCmd
    data class LineTo(val x: Float, val y: Float) : CustomPathCmd
    data class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : CustomPathCmd
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : CustomPathCmd
    data object Close : CustomPathCmd
}

private data class MutableCustomPath(
    val width: Float,
    val height: Float,
    val commands: MutableList<CustomPathCmd> = mutableListOf()
)

private enum class ChartKind {
    BAR,
    LINE,
    PIE
}

private data class ChartSeriesData(
    val name: String,
    val categories: List<String>,
    val values: List<Float>,
    val color: Int
)

private sealed interface RenderElement {
    val rect: EmuRect

    data class ShapeWithText(
        override val rect: EmuRect,
        val fillColor: Int? = null,
        val gradientColors: List<Int> = emptyList(),
        val gradientAngle: Float = 45f,
        val backgroundImage: ImagePayload? = null,
        val preset: String? = null,
        val adjustValue: Float? = null,
        val customPaths: List<MutableCustomPath> = emptyList(),
        val strokeColor: Int? = null,
        val strokeWidthEmu: Long? = null,
        val isDashedStroke: Boolean = false,
        val text: String = "",
        val paragraphs: List<TextParagraphData> = emptyList(),
        val fontSize: Float = 20f,
        val fontFamily: String? = "sans-serif",
        val isBold: Boolean = false,
        val textColor: Int = Color.rgb(20, 20, 20),
        val align: TextAlignMode = TextAlignMode.START,
        val verticalAnchor: VerticalAnchor = VerticalAnchor.TOP
    ) : RenderElement

    data class Image(
        override val rect: EmuRect,
        val image: ImagePayload,
        val crop: CropFractions? = null,
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
        val hasEndArrow: Boolean,
        val isDashed: Boolean = false,
        val flipH: Boolean = false,
        val flipV: Boolean = false
    ) : RenderElement

    data class Chart(
        override val rect: EmuRect,
        val title: String,
        val kind: ChartKind,
        val series: List<ChartSeriesData>
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
    BULLET,
    STROKE,
    CELL_BORDER,
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

private enum class VerticalAnchor {
    TOP,
    CENTER,
    BOTTOM
}

private class PendingColorBuilder(val baseColor: Int) {
    var lumMod: Float? = null
    var lumOff: Float? = null
    var tint: Float? = null
    var shade: Float? = null
    var alpha: Float? = null

    fun build(): Int {
        var r = Color.red(baseColor) / 255f
        var g = Color.green(baseColor) / 255f
        var b = Color.blue(baseColor) / 255f
        val a = ((alpha ?: 1f).coerceIn(0f, 1f) * 255f).roundToInt()

        if (lumMod != null || lumOff != null) {
            val hsl = FloatArray(3)
            rgbToHsl(r, g, b, hsl)
            val m = lumMod ?: 1f
            val o = lumOff ?: 0f
            hsl[2] = (hsl[2] * m + o).coerceIn(0f, 1f)
            val rgb = hslToRgb(hsl[0], hsl[1], hsl[2])
            r = rgb[0]
            g = rgb[1]
            b = rgb[2]
        }

        shade?.let { s ->
            val factor = s.coerceIn(0f, 1f)
            r *= factor
            g *= factor
            b *= factor
        }

        tint?.let { t ->
            val factor = t.coerceIn(0f, 1f)
            r = 1f - (1f - r) * factor
            g = 1f - (1f - g) * factor
            b = 1f - (1f - b) * factor
        }

        return Color.argb(
            a,
            (r * 255f).roundToInt().coerceIn(0, 255),
            (g * 255f).roundToInt().coerceIn(0, 255),
            (b * 255f).roundToInt().coerceIn(0, 255)
        )
    }
}

private fun rgbToHsl(r: Float, g: Float, b: Float, outHsl: FloatArray) {
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    var h = 0f
    var s = 0f
    val l = (maxC + minC) / 2f

    if (maxC != minC) {
        val d = maxC - minC
        s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
    }
    outHsl[0] = h
    outHsl[1] = s
    outHsl[2] = l
}

private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
    if (s == 0f) return floatArrayOf(l, l, l)
    fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        if (t < 1f / 6f) return p + (q - p) * 6f * t
        if (t < 1f / 2f) return q
        if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f
        return p
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    return floatArrayOf(
        hue2rgb(p, q, h + 1f / 3f),
        hue2rgb(p, q, h),
        hue2rgb(p, q, h - 1f / 3f)
    )
}

private data class MutableRenderShape(
    val kind: MutableRenderKind,
    var x: Long = 0L,
    var y: Long = 0L,
    var width: Long = 0L,
    var height: Long = 0L,
    var hasExplicitPosition: Boolean = false,
    var hasExplicitSize: Boolean = false,
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    var rotationDeg: Float = 0f,
    val text: StringBuilder = StringBuilder(),
    val paragraphs: MutableList<TextParagraphData> = mutableListOf(),
    var imageRelationshipId: String? = null,
    var cropRect: CropFractions? = null,
    var isPlaceholder: Boolean = false,
    var placeholderType: String? = null,
    var placeholderIdx: String? = null,
    var defaultFontSize: Float? = null,
    var defaultFontFamily: String? = null,
    var defaultBold: Boolean = false,
    var textAlign: TextAlignMode? = null,
    var verticalAnchor: VerticalAnchor? = null,
    var defaultTextColor: Int? = null,
    var fillColor: Int? = null,
    var hasNoFill: Boolean = false,
    val gradientColors: MutableList<Int> = mutableListOf(),
    var gradientAngle: Float = 45f,
    var strokeColor: Int? = null,
    var hasNoStroke: Boolean = false,
    var strokeWidthEmu: Long? = null,
    var isDashedStroke: Boolean = false,
    var shapePreset: String? = null,
    var adjustValue: Float? = null,
    val customPaths: MutableList<MutableCustomPath> = mutableListOf(),
    var hasStartArrow: Boolean = false,
    var hasEndArrow: Boolean = false
)

private data class MutableGraphicFrame(
    var x: Long = 0L,
    var y: Long = 0L,
    var width: Long = 0L,
    var height: Long = 0L,
    var chartRelId: String? = null,
    var diagramDataRelId: String? = null,
    var diagramDrawingRelId: String? = null
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
    var childHeight: Long = DEFAULT_SLIDE_HEIGHT_EMU,
    var hasGroupPos: Boolean = false,
    var hasGroupExt: Boolean = false
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
    var borderColor: Int? = null,
    var textColor: Int? = null,
    var fontSize: Float? = 13f,
    var fontFamily: String? = "sans-serif",
    var textAlign: TextAlignMode = TextAlignMode.START,
    var verticalAnchor: VerticalAnchor? = null,
    val gridSpan: Int = 1,
    val rowSpan: Int = 1,
    val isMergedContinuation: Boolean = false
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
    val isUnderline: Boolean = false,
    val color: Int? = null
)

private data class MutableTextRunStyle(
    var fontSize: Float? = null,
    var fontFamily: String? = null,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var isUnderline: Boolean = false,
    var color: Int? = null
) {
    fun toTextRun(text: String): TextRunData {
        return TextRunData(
            text = text,
            fontSize = fontSize,
            fontFamily = fontFamily,
            isBold = isBold,
            isItalic = isItalic,
            isUnderline = isUnderline,
            color = color
        )
    }
}

private fun defaultPlaceholderRect(
    placeholderType: String?,
    placeholderIdx: String?,
    slideSize: SlideSize,
    bodyIndex: Int
): EmuRect {
    val w = slideSize.widthEmu
    val h = slideSize.heightEmu
    return when (placeholderType?.lowercase(Locale.ROOT)) {
        "ctrtitle" -> EmuRect(
            x = (w * 0.08).toLong(),
            y = (h * 0.24).toLong(),
            width = (w * 0.84).toLong(),
            height = (h * 0.26).toLong()
        )
        "subtitle" -> EmuRect(
            x = (w * 0.12).toLong(),
            y = (h * 0.54).toLong(),
            width = (w * 0.76).toLong(),
            height = (h * 0.20).toLong()
        )
        "title" -> EmuRect(
            x = (w * 0.06).toLong(),
            y = (h * 0.06).toLong(),
            width = (w * 0.88).toLong(),
            height = (h * 0.16).toLong()
        )
        "dt" -> EmuRect(
            x = (w * 0.06).toLong(),
            y = (h * 0.91).toLong(),
            width = (w * 0.22).toLong(),
            height = (h * 0.06).toLong()
        )
        "ftr" -> EmuRect(
            x = (w * 0.34).toLong(),
            y = (h * 0.91).toLong(),
            width = (w * 0.32).toLong(),
            height = (h * 0.06).toLong()
        )
        "sldnum" -> EmuRect(
            x = (w * 0.72).toLong(),
            y = (h * 0.91).toLong(),
            width = (w * 0.22).toLong(),
            height = (h * 0.06).toLong()
        )
        else -> {
            if (placeholderIdx == "0" && bodyIndex <= 1) {
                EmuRect(
                    x = (w * 0.06).toLong(),
                    y = (h * 0.06).toLong(),
                    width = (w * 0.88).toLong(),
                    height = (h * 0.16).toLong()
                )
            } else {
                EmuRect(
                    x = (w * 0.06).toLong(),
                    y = (h * 0.25).toLong(),
                    width = (w * 0.88).toLong(),
                    height = (h * 0.64).toLong()
                )
            }
        }
    }
}

private fun parseTextAlign(rawAlignment: String?, placeholderType: String?): TextAlignMode {
    return when (rawAlignment?.lowercase(Locale.ROOT)) {
        "ctr", "dist", "just" -> TextAlignMode.CENTER
        "r" -> TextAlignMode.END
        "l" -> TextAlignMode.START
        else -> when (placeholderType?.lowercase(Locale.ROOT)) {
            "ctrtitle", "subtitle" -> TextAlignMode.CENTER
            "sldnum" -> TextAlignMode.END
            else -> TextAlignMode.START
        }
    }
}

private fun parseVerticalAnchor(rawAnchor: String?): VerticalAnchor? {
    return when (rawAnchor?.lowercase(Locale.ROOT)) {
        "ctr", "dist" -> VerticalAnchor.CENTER
        "b" -> VerticalAnchor.BOTTOM
        "t" -> VerticalAnchor.TOP
        else -> null
    }
}

private fun defaultVerticalAnchor(placeholderType: String?, shapePreset: String?): VerticalAnchor {
    return when (placeholderType?.lowercase(Locale.ROOT)) {
        "ctrtitle", "title" -> VerticalAnchor.CENTER
        else -> {
            val p = shapePreset?.lowercase(Locale.ROOT).orEmpty()
            if (p in setOf("ellipse", "oval", "roundrect", "diamond", "hexagon", "chevron", "homeplate", "rightarrow")) {
                VerticalAnchor.CENTER
            } else {
                VerticalAnchor.TOP
            }
        }
    }
}

private fun defaultContrastTextColor(backgroundFill: Int?): Int {
    if (backgroundFill == null || Color.alpha(backgroundFill) < 60) {
        return Color.rgb(20, 20, 20)
    }
    val r = Color.red(backgroundFill) / 255.0
    val g = Color.green(backgroundFill) / 255.0
    val b = Color.blue(backgroundFill) / 255.0
    val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return if (lum < 0.48) Color.WHITE else Color.rgb(20, 20, 20)
}

private fun defaultFontSize(placeholderType: String?, heightEmu: Long): Float {
    return when (placeholderType?.lowercase(Locale.ROOT)) {
        "title", "ctrtitle" -> 32f
        "subtitle" -> 22f
        "dt", "ftr", "sldnum" -> 12f
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

private fun String?.toSysColorOrNull(): Int? {
    return when (this?.trim()?.lowercase(Locale.ROOT)) {
        "window", "menu", "btnhighlight" -> Color.WHITE
        "windowtext", "menutext", "btntext", "captiontext" -> Color.rgb(18, 18, 18)
        else -> null
    }
}

private fun String?.toThemeColorOrNull(): Int? {
    return when (this?.trim()?.lowercase(Locale.ROOT)) {
        "accent1", "phclr" -> Color.rgb(68, 114, 196)
        "accent2" -> Color.rgb(237, 125, 49)
        "accent3" -> Color.rgb(165, 165, 165)
        "accent4" -> Color.rgb(255, 192, 0)
        "accent5" -> Color.rgb(91, 155, 213)
        "accent6" -> Color.rgb(112, 173, 71)
        "tx1", "dk1", "black" -> Color.rgb(18, 18, 18)
        "tx2", "dk2", "dkgray", "gray" -> Color.rgb(64, 64, 64)
        "bg1", "lt1", "white" -> Color.WHITE
        "bg2", "lt2", "ltgray", "silver" -> Color.rgb(242, 242, 242)
        "blue", "navy", "royalblue" -> Color.rgb(68, 114, 196)
        "red", "crimson", "darkred" -> Color.rgb(192, 0, 0)
        "green", "darkgreen", "forestgreen" -> Color.rgb(0, 128, 0)
        "yellow", "gold" -> Color.rgb(255, 192, 0)
        "orange", "darkorange" -> Color.rgb(237, 125, 49)
        "purple", "violet", "indigo" -> Color.rgb(112, 48, 160)
        "teal", "cyan", "aqua" -> Color.rgb(0, 134, 122)
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
