package com.allvie.app.ui.screen.viewer

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.allvie.app.data.repository.FileRepository
import com.allvie.app.data.repository.DocxRenderer
import com.allvie.app.data.repository.PoiSlideBitmapRenderer
import com.allvie.app.data.repository.PresentationSlideData
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import com.allvie.app.ui.components.EmptyState
import com.allvie.app.ui.theme.allVieOutlineColor
import com.allvie.app.ui.theme.allViePanelColor
import com.allvie.app.util.shareFile
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OfficeKind {
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    UNKNOWN
}

private enum class PresentationMode {
    LIST,
    VIEW,
    PLAY
}

private const val PRESENTATION_RENDER_WIDTH = 1920
private const val PRESENTATION_RENDER_HEIGHT = 1080
private const val DOCX_RENDER_WIDTH = 1440
private const val DOCX_RENDER_HEIGHT = 1864
private const val HEADER_SCROLL_THRESHOLD_PX = 8
private val VIEWER_HEADER_OVERLAY_TOP_PADDING = 92.dp
private const val LAZY_LIST_SCROLL_INDEX_WEIGHT = 100_000
private const val PDF_SCROLL_POSITION_WEIGHT = 10_000

data class ViewerUiState(
    val file: FileItem,
    val textContent: String? = null,
    val tableRows: List<List<String>> = emptyList(),
    val slides: List<PresentationSlideData> = emptyList(),
    val documentPageCount: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
    private val docxRenderer: DocxRenderer,
    private val slideBitmapRenderer: PoiSlideBitmapRenderer
) : ViewModel() {

    private val rawName = Uri.decode(savedStateHandle.get<String>("name").orEmpty())
    private val rawMimeType = Uri.decode(savedStateHandle.get<String>("mime").orEmpty())
    private val file = FileItem(
        uriString = Uri.decode(savedStateHandle.get<String>("uri").orEmpty()),
        displayName = rawName.ifBlank { "Untitled" },
        mimeType = rawMimeType,
        category = FileCategory.resolve(savedStateHandle.get<String>("category"), rawMimeType, rawName),
        size = 0,
        lastModified = 0,
        pathLabel = ""
    )

    private val officeKind = detectOfficeKind(file.displayName, file.mimeType)
    private var preloadJob: Job? = null
    private var documentPreloadJob: Job? = null

    private val _uiState = MutableStateFlow(
        ViewerUiState(
            file = file,
            isLoading = file.uriString.isNotBlank() &&
                (file.category == FileCategory.TEXT || file.category == FileCategory.OFFICE),
            error = if (file.uriString.isBlank()) "The selected file is no longer available." else null
        )
    )
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    init {
        if (file.uriString.isNotBlank()) {
            when (file.category) {
                FileCategory.TEXT -> loadTextContent()
                FileCategory.OFFICE -> loadOfficeContent()
                else -> Unit
            }
        }
    }

    private fun loadTextContent() {
        viewModelScope.launch {
            runCatching {
                fileRepository.loadTextContent(file.uriString)
            }.onSuccess { text ->
                _uiState.value = _uiState.value.copy(
                    textContent = text,
                    tableRows = emptyList(),
                    slides = emptyList(),
                    documentPageCount = 0,
                    isLoading = false,
                    error = null
                )
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    textContent = null,
                    tableRows = emptyList(),
                    slides = emptyList(),
                    documentPageCount = 0,
                    isLoading = false,
                    error = throwable.message ?: "Unable to load this text file."
                )
            }
        }
    }

    private fun loadOfficeContent() {
        viewModelScope.launch {
            if (officeKind == OfficeKind.PRESENTATION) {
                loadPresentationContent()
                return@launch
            }

            if (officeKind == OfficeKind.DOCUMENT) {
                loadDocumentContent()
                return@launch
            }

            runCatching {
                fileRepository.loadOfficeContent(
                    uriString = file.uriString,
                    displayName = file.displayName,
                    mimeType = file.mimeType
                )
            }.onSuccess { preview ->
                when (officeKind) {
                    OfficeKind.SPREADSHEET -> {
                        val rows = parseSpreadsheetRows(preview)
                        _uiState.value = _uiState.value.copy(
                            textContent = if (rows.isEmpty()) preview else null,
                            tableRows = rows,
                            slides = emptyList(),
                            documentPageCount = 0,
                            isLoading = false,
                            error = null
                        )
                    }

                    OfficeKind.DOCUMENT,
                    OfficeKind.UNKNOWN,
                    OfficeKind.PRESENTATION -> {
                        _uiState.value = _uiState.value.copy(
                            textContent = preview,
                            tableRows = emptyList(),
                            slides = emptyList(),
                            documentPageCount = 0,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    textContent = null,
                    tableRows = emptyList(),
                    slides = emptyList(),
                    documentPageCount = 0,
                    isLoading = false,
                    error = throwable.message ?: "Unable to preview this Office file."
                )
            }
        }
    }

    private suspend fun loadDocumentContent() {
        runCatching {
            docxRenderer.readPageCount(
                uriString = file.uriString,
                displayName = file.displayName,
                mimeType = file.mimeType,
                targetWidth = DOCX_RENDER_WIDTH,
                targetHeight = DOCX_RENDER_HEIGHT
            )
        }.onSuccess { pageCount ->
            _uiState.value = _uiState.value.copy(
                textContent = null,
                tableRows = emptyList(),
                slides = emptyList(),
                documentPageCount = pageCount,
                isLoading = false,
                error = if (pageCount > 0) {
                    null
                } else {
                    "Legacy .doc format: Open with external app for best compatibility."
                }
            )
        }.onFailure { throwable ->
            _uiState.value = _uiState.value.copy(
                textContent = null,
                tableRows = emptyList(),
                slides = emptyList(),
                documentPageCount = 0,
                isLoading = false,
                error = throwable.message ?: "Unable to render this document."
            )
        }
    }

    private suspend fun loadPresentationContent() {
        runCatching {
            val slideCount = slideBitmapRenderer.readSlideCount(
                uriString = file.uriString,
                displayName = file.displayName,
                mimeType = file.mimeType
            )
            buildPresentationPlaceholders(slideCount)
        }.onSuccess { slides ->
            val normalizedSlides = normalizePresentationSlides(slides)
            if (normalizedSlides.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    textContent = null,
                    tableRows = emptyList(),
                    slides = normalizedSlides,
                    documentPageCount = 0,
                    isLoading = false,
                    error = null
                )
            } else {
                runCatching {
                    fileRepository.loadOfficeContent(
                        uriString = file.uriString,
                        displayName = file.displayName,
                        mimeType = file.mimeType
                    )
                }.onSuccess { preview ->
                    val fallbackSlides = normalizePresentationSlides(fallbackTextToSlides(preview))
                    _uiState.value = _uiState.value.copy(
                        textContent = if (fallbackSlides.isEmpty()) preview.ifBlank { null } else null,
                        tableRows = emptyList(),
                        slides = fallbackSlides,
                        documentPageCount = 0,
                        isLoading = false,
                        error = if (fallbackSlides.isEmpty() && preview.isBlank()) {
                            "Unable to read slides from this presentation."
                        } else {
                            null
                        }
                    )
                }.onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        textContent = null,
                        tableRows = emptyList(),
                        slides = emptyList(),
                        documentPageCount = 0,
                        isLoading = false,
                        error = throwable.message ?: "Unable to preview this presentation."
                    )
                }
            }
        }.onFailure { throwable ->
            _uiState.value = _uiState.value.copy(
                textContent = null,
                tableRows = emptyList(),
                slides = emptyList(),
                documentPageCount = 0,
                isLoading = false,
                error = throwable.message ?: "Unable to preview this presentation."
            )
        }
    }

    suspend fun renderDocumentPageBitmap(
        position: Int,
        targetWidth: Int,
        targetHeight: Int,
        darkMode: Boolean
    ): Bitmap? {
        return docxRenderer.renderPageBitmap(
            uriString = file.uriString,
            displayName = file.displayName,
            mimeType = file.mimeType,
            position = position,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            darkMode = darkMode
        )
    }

    fun preloadDocumentPages(
        centerPosition: Int,
        pageCount: Int,
        targetWidth: Int = DOCX_RENDER_WIDTH,
        targetHeight: Int = DOCX_RENDER_HEIGHT,
        darkMode: Boolean
    ) {
        if (pageCount <= 0) return
        documentPreloadJob?.cancel()
        documentPreloadJob = viewModelScope.launch {
            docxRenderer.preloadAround(
                uriString = file.uriString,
                displayName = file.displayName,
                mimeType = file.mimeType,
                centerPosition = centerPosition.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                darkMode = darkMode
            )
        }
    }

    suspend fun renderPresentationSlideBitmap(
        position: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return slideBitmapRenderer.renderSlideBitmap(
            uriString = file.uriString,
            displayName = file.displayName,
            mimeType = file.mimeType,
            position = position,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    fun preloadPresentationSlides(
        centerPosition: Int,
        slideCount: Int,
        targetWidth: Int = PRESENTATION_RENDER_WIDTH,
        targetHeight: Int = PRESENTATION_RENDER_HEIGHT
    ) {
        if (slideCount <= 0) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            slideBitmapRenderer.preloadAround(
                uriString = file.uriString,
                displayName = file.displayName,
                mimeType = file.mimeType,
                centerPosition = centerPosition.coerceIn(0, slideCount - 1),
                slideCount = slideCount,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        }
    }

    fun clearPresentationRenderCache() {
        preloadJob?.cancel()
        preloadJob = null
        slideBitmapRenderer.clear()
    }

    fun clearDocumentRenderCache() {
        documentPreloadJob?.cancel()
        documentPreloadJob = null
        docxRenderer.clear()
    }

    private fun buildPresentationPlaceholders(slideCount: Int): List<PresentationSlideData> {
        if (slideCount <= 0) return emptyList()
        return List(slideCount.coerceAtMost(300)) { index ->
            PresentationSlideData(index = index + 1, text = "Slide ${index + 1}")
        }
    }

    private fun normalizePresentationSlides(slides: List<PresentationSlideData>): List<PresentationSlideData> {
        if (slides.isEmpty()) return emptyList()

        return slides
            .filter { slide ->
                slide.text.isNotBlank() || slide.imageUris.isNotEmpty() || slide.elements.isNotEmpty()
            }
            .mapIndexed { index, slide ->
                slide.copy(
                    index = index + 1,
                    widthEmu = slide.widthEmu.coerceAtLeast(1L),
                    heightEmu = slide.heightEmu.coerceAtLeast(1L)
                )
            }
    }

    private fun parseSpreadsheetRows(content: String): List<List<String>> {
        if (content.isBlank()) return emptyList()

        return content.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line ->
                when {
                    line.startsWith("Sheet ", ignoreCase = true) -> listOf(line)
                    '\t' in line -> line.split('\t').map { it.trim() }
                    else -> listOf(line)
                }
            }
            .take(240)
            .toList()
    }
}

@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val officeKind = remember(state.file.displayName, state.file.mimeType) {
        detectOfficeKind(state.file.displayName, state.file.mimeType)
    }
    val isPresentationViewer = state.file.category == FileCategory.OFFICE && officeKind == OfficeKind.PRESENTATION
    var presentationModeIndex by rememberSaveable(state.file.uriString) {
        mutableIntStateOf(PresentationMode.LIST.ordinal)
    }
    val presentationMode = PresentationMode.entries.getOrElse(presentationModeIndex) { PresentationMode.LIST }
    var activeSlide by rememberSaveable(state.file.uriString) { mutableIntStateOf(0) }
    val isImmersivePresentation = isPresentationViewer && presentationMode != PresentationMode.LIST
    var headerVisible by rememberSaveable(state.file.uriString) { mutableStateOf(true) }
    val headerHeight = 64.dp
    val headerOffsetY by animateDpAsState(
        targetValue = if (headerVisible) 0.dp else -headerHeight,
        label = "viewerHeaderOffset"
    )

    fun handleViewerScroll(delta: Int) {
        when {
            delta > HEADER_SCROLL_THRESHOLD_PX -> headerVisible = false
            delta < -HEADER_SCROLL_THRESHOLD_PX -> headerVisible = true
        }
    }

    DisposableEffect(state.file.uriString) {
        onDispose {
            viewModel.clearPresentationRenderCache()
            viewModel.clearDocumentRenderCache()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds()
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.error != null -> {
                    EmptyState(
                        title = if (state.file.category == FileCategory.OFFICE) {
                            "Unable to open Office file"
                        } else {
                            "Unable to open file"
                        },
                        body = state.error.orEmpty(),
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.file.category == FileCategory.PDF -> {
                    PdfViewer(
                        file = state.file,
                        onScrollDelta = ::handleViewerScroll
                    )
                }

                state.file.category == FileCategory.TEXT -> {
                    PlainTextPreview(
                        title = "Text Preview",
                        content = state.textContent.orEmpty(),
                        onScrollDelta = ::handleViewerScroll
                    )
                }

                state.file.category == FileCategory.OFFICE -> {
                    when (officeKind) {
                        OfficeKind.SPREADSHEET -> {
                            if (state.tableRows.isNotEmpty()) {
                                SpreadsheetPreview(
                                    title = "Spreadsheet Preview",
                                    rows = state.tableRows,
                                    onScrollDelta = ::handleViewerScroll
                                )
                            } else {
                                PlainTextPreview(
                                    title = "Spreadsheet Preview",
                                    content = state.textContent.orEmpty(),
                                    onScrollDelta = ::handleViewerScroll
                                )
                            }
                        }

                        OfficeKind.PRESENTATION -> {
                            PresentationPreview(
                                slides = state.slides,
                                fallbackText = state.textContent.orEmpty(),
                                mode = presentationMode,
                                activeSlide = activeSlide,
                                renderSlideBitmap = viewModel::renderPresentationSlideBitmap,
                                preloadSlides = viewModel::preloadPresentationSlides,
                                onOpenSlide = { target ->
                                    if (state.slides.isNotEmpty()) {
                                        activeSlide = target.coerceIn(0, state.slides.lastIndex)
                                        presentationModeIndex = PresentationMode.VIEW.ordinal
                                    }
                                },
                                onSlideChange = { target ->
                                    if (state.slides.isNotEmpty()) {
                                        activeSlide = target.coerceIn(0, state.slides.lastIndex)
                                    }
                                },
                                onCloseViewer = { presentationModeIndex = PresentationMode.LIST.ordinal },
                                onScrollDelta = ::handleViewerScroll
                            )
                        }

                        OfficeKind.DOCUMENT -> {
                            DocxPreview(
                                pageCount = state.documentPageCount,
                                renderPageBitmap = viewModel::renderDocumentPageBitmap,
                                preloadPages = viewModel::preloadDocumentPages,
                                onScrollDelta = ::handleViewerScroll
                            )
                        }

                        OfficeKind.UNKNOWN -> {
                            PlainTextPreview(
                                title = "Office Preview",
                                content = state.textContent.orEmpty(),
                                onScrollDelta = ::handleViewerScroll
                            )
                        }
                    }
                }

                else -> {
                    EmptyState(
                        title = "Preview not available",
                        body = "This file type is not supported for preview.",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (!isImmersivePresentation) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(headerHeight)
                    .clipToBounds()
            ) {
                ViewerHeader(
                    file = state.file,
                    isPresentation = isPresentationViewer,
                    canPlay = isPresentationViewer && state.slides.isNotEmpty(),
                    shadowElevation = if (state.file.category == FileCategory.PDF) 0.dp else 6.dp,
                    modifier = Modifier.offset(y = headerOffsetY),
                    onBack = onBack,
                    onShare = {
                        if (!shareFile(context, state.file)) {
                            Toast.makeText(context, "No app available to share this file.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onPlay = {
                        if (state.slides.isNotEmpty()) {
                            activeSlide = activeSlide.coerceIn(0, state.slides.lastIndex)
                            presentationModeIndex = PresentationMode.PLAY.ordinal
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ViewerHeader(
    file: FileItem,
    isPresentation: Boolean,
    canPlay: Boolean,
    shadowElevation: Dp = 6.dp,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPlay: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = shadowElevation
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (canPlay) 0.64f else 0.74f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = file.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!isPresentation) {
                    Text(
                        text = file.category.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onShare) {
                Icon(imageVector = Icons.Rounded.Share, contentDescription = "Share")
            }
            if (canPlay) {
                IconButton(onClick = onPlay) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = "Play presentation")
                }
            }
        }
    }
}

@Composable
private fun PresentationSideBar(
    currentIndex: Int,
    totalSlides: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(46.dp),
        shape = RoundedCornerShape(18.dp),
        color = allViePanelColor(alphaLight = 0.88f, alphaDark = 0.96f),
        border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f)),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = currentIndex > 0
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Previous slide"
                )
            }
            Text(
                text = "${currentIndex + 1}/$totalSlides",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(84.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(99.dp)
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                val progress = if (totalSlides > 1) {
                    currentIndex.toFloat() / (totalSlides - 1).toFloat()
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height((84.dp * progress).coerceAtLeast(6.dp))
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(99.dp)
                        )
                )
            }
            IconButton(
                onClick = onNext,
                enabled = currentIndex < totalSlides - 1
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Next slide"
                )
            }
        }
    }
}

@Composable
private fun PdfPageSideBar(
    currentPage: Int,
    totalPages: Int,
    isVisible: Boolean,
    onSeekPage: (Int) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible || totalPages <= 0) return

    var trackHeightPx by remember(totalPages) { mutableIntStateOf(1) }
    val thumbHeight = 34.dp
    val bubbleHeight = 34.dp
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeight.toPx() }
    val maxThumbOffsetPx = (trackHeightPx.toFloat() - thumbHeightPx).coerceAtLeast(0f)

    val pageFraction = if (totalPages > 1) {
        currentPage.coerceIn(0, totalPages - 1).toFloat() / (totalPages - 1).toFloat()
    } else {
        0f
    }
    val thumbOffsetPx = maxThumbOffsetPx * pageFraction
    val bubbleOffsetPx = (thumbOffsetPx + (thumbHeightPx / 2f) - (bubbleHeightPx / 2f))
        .coerceIn(0f, (trackHeightPx.toFloat() - bubbleHeightPx).coerceAtLeast(0f))
    val pageIndicatorTextColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color.White
    } else {
        Color.Black
    }

    fun seekByY(y: Float) {
        if (totalPages <= 1) return
        val normalized = if (trackHeightPx > 0) {
            y.coerceIn(0f, trackHeightPx.toFloat()) / trackHeightPx.toFloat()
        } else {
            0f
        }
        val target = ((totalPages - 1) * normalized).roundToInt().coerceIn(0, totalPages - 1)
        onSeekPage(target)
        onInteraction()
    }

    Box(
        modifier = modifier
            .width(62.dp)
            .fillMaxHeight(0.56f)
            .pointerInput(totalPages) {
                detectTapGestures { offset ->
                    seekByY(offset.y)
                }
            }
            .pointerInput(totalPages) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> seekByY(offset.y) },
                    onVerticalDrag = { change, _ -> seekByY(change.position.y) }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(99.dp)
                )
                .onSizeChanged { size ->
                    trackHeightPx = size.height.coerceAtLeast(1)
                }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(12.dp)
                .height(thumbHeight),
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 2.dp
        ) {}

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = with(density) { bubbleOffsetPx.toDp() })
                .height(bubbleHeight),
            shape = RoundedCornerShape(99.dp),
            color = allViePanelColor(alphaLight = 0.9f, alphaDark = 0.96f),
            border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f)),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
            Text(
                text = "${currentPage + 1}/$totalPages",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = pageIndicatorTextColor
            )
        }
    }
}

@Composable
private fun ScrollPositionSideBar(
    currentScroll: Int,
    maxScroll: Int,
    isVisible: Boolean,
    onSeekScroll: (Int) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible || maxScroll <= 0) return

    var trackHeightPx by remember(maxScroll) { mutableIntStateOf(1) }
    val thumbHeight = 34.dp
    val bubbleHeight = 34.dp
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeight.toPx() }
    val maxThumbOffsetPx = (trackHeightPx.toFloat() - thumbHeightPx).coerceAtLeast(0f)
    val scrollFraction = currentScroll.coerceIn(0, maxScroll).toFloat() / maxScroll.toFloat()
    val thumbOffsetPx = maxThumbOffsetPx * scrollFraction
    val bubbleOffsetPx = (thumbOffsetPx + (thumbHeightPx / 2f) - (bubbleHeightPx / 2f))
        .coerceIn(0f, (trackHeightPx.toFloat() - bubbleHeightPx).coerceAtLeast(0f))
    val labelTextColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color.White
    } else {
        Color.Black
    }
    val percent = (scrollFraction * 100f).roundToInt().coerceIn(0, 100)

    fun seekByY(y: Float) {
        val normalized = if (trackHeightPx > 0) {
            y.coerceIn(0f, trackHeightPx.toFloat()) / trackHeightPx.toFloat()
        } else {
            0f
        }
        val target = (maxScroll * normalized).roundToInt().coerceIn(0, maxScroll)
        onSeekScroll(target)
        onInteraction()
    }

    Box(
        modifier = modifier
            .width(62.dp)
            .fillMaxHeight(0.56f)
            .pointerInput(maxScroll) {
                detectTapGestures { offset ->
                    seekByY(offset.y)
                }
            }
            .pointerInput(maxScroll) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> seekByY(offset.y) },
                    onVerticalDrag = { change, _ -> seekByY(change.position.y) }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(99.dp)
                )
                .onSizeChanged { size ->
                    trackHeightPx = size.height.coerceAtLeast(1)
                }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(12.dp)
                .height(thumbHeight),
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 2.dp
        ) {}

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = with(density) { bubbleOffsetPx.toDp() })
                .height(bubbleHeight),
            shape = RoundedCornerShape(99.dp),
            color = allViePanelColor(alphaLight = 0.9f, alphaDark = 0.96f),
            border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f)),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
            Text(
                text = "$percent%",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = labelTextColor
            )
        }
    }
}

@Composable
private fun DocxPreview(
    pageCount: Int,
    renderPageBitmap: suspend (Int, Int, Int, Boolean) -> Bitmap?,
    preloadPages: (Int, Int, Int, Int, Boolean) -> Unit,
    onScrollDelta: (Int) -> Unit
) {
    if (pageCount <= 0) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            color = allViePanelColor(alphaLight = 0.72f, alphaDark = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            EmptyState(
                title = "Document preview unavailable",
                body = "Unable to render this document.",
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val isDarkDocument = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val listScrollPosition = (listState.firstVisibleItemIndex * LAZY_LIST_SCROLL_INDEX_WEIGHT) +
        listState.firstVisibleItemScrollOffset

    ReportScrollDelta(
        position = listScrollPosition,
        key = pageCount,
        onScrollDelta = onScrollDelta
    )

    LaunchedEffect(pageCount, listState.firstVisibleItemIndex, isDarkDocument) {
        preloadPages(
            listState.firstVisibleItemIndex.coerceIn(0, pageCount - 1),
            pageCount,
            DOCX_RENDER_WIDTH,
            DOCX_RENDER_HEIGHT,
            isDarkDocument
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = Color.Black,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                top = VIEWER_HEADER_OVERLAY_TOP_PADDING,
                end = 10.dp,
                bottom = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(count = pageCount, key = { index -> index }) { index ->
                DocxPageBitmapCard(
                    pageIndex = index,
                    pageCount = pageCount,
                    darkMode = isDarkDocument,
                    renderPageBitmap = renderPageBitmap,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DocxPageBitmapCard(
    pageIndex: Int,
    pageCount: Int,
    darkMode: Boolean,
    renderPageBitmap: suspend (Int, Int, Int, Boolean) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(pageIndex, darkMode) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(pageIndex, darkMode) { mutableStateOf(false) }

    LaunchedEffect(pageIndex, darkMode) {
        bitmap = null
        failed = false
        bitmap = renderPageBitmap(pageIndex, DOCX_RENDER_WIDTH, DOCX_RENDER_HEIGHT, darkMode)
        failed = bitmap == null
    }

    Surface(
        modifier = modifier.aspectRatio(DOCX_RENDER_WIDTH.toFloat() / DOCX_RENDER_HEIGHT.toFloat()),
        shape = RoundedCornerShape(6.dp),
        color = if (darkMode) Color.Black else Color.White,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val rendered = bitmap
            when {
                rendered != null && !rendered.isRecycled -> {
                    Image(
                        bitmap = rendered.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1} of $pageCount",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                failed -> {
                    Text(
                        text = "Unable to render page ${pageIndex + 1}",
                        color = if (darkMode) Color.White else Color.Black,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    CircularProgressIndicator()
                }
            }
        }

    }
}

@Composable
private fun ReportScrollDelta(
    position: Int,
    key: Any?,
    onScrollDelta: (Int) -> Unit
) {
    var lastPosition by remember(key) { mutableIntStateOf(position) }

    LaunchedEffect(position, key) {
        val delta = position - lastPosition
        if (delta != 0) {
            onScrollDelta(delta)
            lastPosition = position
        }
    }
}

@Composable
private fun PlainTextPreview(
    title: String,
    content: String,
    onScrollDelta: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollMax = max(scrollState.maxValue, 0)
    var controlsVisible by remember(content) { mutableStateOf(true) }
    var controlsToken by remember(content) { mutableIntStateOf(0) }

    fun showControls() {
        controlsVisible = true
        controlsToken += 1
    }

    LaunchedEffect(scrollState.value, scrollMax) {
        if (scrollMax > 0) {
            showControls()
        }
    }
    ReportScrollDelta(
        position = scrollState.value,
        key = content,
        onScrollDelta = onScrollDelta
    )

    LaunchedEffect(controlsVisible, controlsToken, scrollMax) {
        if (controlsVisible && scrollMax > 0) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = allViePanelColor(alphaLight = 0.72f, alphaDark = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        start = 20.dp,
                        top = VIEWER_HEADER_OVERLAY_TOP_PADDING + 20.dp,
                        end = 46.dp,
                        bottom = 20.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                SelectionContainer {
                    Text(
                        text = content.ifBlank { "No readable content found in this file." },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            ScrollPositionSideBar(
                currentScroll = scrollState.value,
                maxScroll = scrollMax,
                isVisible = controlsVisible,
                onSeekScroll = { target ->
                    scope.launch {
                        scrollState.scrollTo(target.coerceIn(0, scrollMax))
                    }
                },
                onInteraction = { showControls() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            )
        }
    }
}
@Composable
private fun PresentationPreview(
    slides: List<PresentationSlideData>,
    fallbackText: String,
    mode: PresentationMode,
    activeSlide: Int,
    renderSlideBitmap: suspend (Int, Int, Int) -> Bitmap?,
    preloadSlides: (Int, Int) -> Unit,
    onOpenSlide: (Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onCloseViewer: () -> Unit,
    onScrollDelta: (Int) -> Unit
) {
    val resolvedSlides = remember(slides, fallbackText) {
        val baseSlides = if (slides.isNotEmpty()) {
            slides
        } else {
            fallbackTextToSlides(fallbackText)
        }
        preparePresentationSlides(baseSlides)
    }

    LaunchedEffect(resolvedSlides.size, activeSlide, mode) {
        if (resolvedSlides.isNotEmpty() && mode != PresentationMode.LIST) {
            preloadSlides(activeSlide.coerceIn(0, resolvedSlides.lastIndex), resolvedSlides.size)
        }
    }

    if (resolvedSlides.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            color = allViePanelColor(alphaLight = 0.72f, alphaDark = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            EmptyState(
                title = "Presentation preview unavailable",
                body = "Unable to read slides from this presentation.",
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    when (mode) {
        PresentationMode.VIEW -> {
            PresentationSlideViewer(
                slideCount = resolvedSlides.size,
                currentSlide = activeSlide.coerceIn(0, resolvedSlides.lastIndex),
                renderSlideBitmap = renderSlideBitmap,
                preloadSlides = preloadSlides,
                onSlideChange = onSlideChange,
                onClose = onCloseViewer
            )
            return
        }

        PresentationMode.PLAY -> {
            PresentationPlayMode(
                slides = resolvedSlides,
                currentSlide = activeSlide.coerceIn(0, resolvedSlides.lastIndex),
                renderSlideBitmap = renderSlideBitmap,
                preloadSlides = preloadSlides,
                onSlideChange = onSlideChange,
                onExit = onCloseViewer
            )
            return
        }

        PresentationMode.LIST -> Unit
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = Color.Black,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        val listState = rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
        val listScrollPosition = (listState.firstVisibleItemIndex * LAZY_LIST_SCROLL_INDEX_WEIGHT) +
            listState.firstVisibleItemScrollOffset

        ReportScrollDelta(
            position = listScrollPosition,
            key = resolvedSlides.size,
            onScrollDelta = onScrollDelta
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                top = VIEWER_HEADER_OVERLAY_TOP_PADDING,
                end = 10.dp,
                bottom = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(items = resolvedSlides, key = { listIndex, _ -> listIndex }) { listIndex, _ ->
                PresentationSlideBitmapCard(
                    slideIndex = listIndex,
                    renderSlideBitmap = renderSlideBitmap,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenSlide(listIndex) }
                )
            }
        }
    }
}

@Composable
private fun PresentationSlideBitmapCard(
    slideIndex: Int,
    renderSlideBitmap: suspend (Int, Int, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var bitmap by remember(slideIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(slideIndex) { mutableStateOf(false) }

    LaunchedEffect(slideIndex) {
        bitmap = null
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            renderSlideBitmap(slideIndex, 1280, 720)
        }
        failed = bitmap == null
    }

    Surface(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = Color.White,
        tonalElevation = 1.dp,
        shadowElevation = 3.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val rendered = bitmap
            when {
                rendered != null && !rendered.isRecycled -> {
                    Image(
                        bitmap = rendered.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }

                failed -> {
                    Text(
                        text = "Unable to render slide ${slideIndex + 1}",
                        color = Color.Black,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresentationSlideViewer(
    slideCount: Int,
    currentSlide: Int,
    renderSlideBitmap: suspend (Int, Int, Int) -> Bitmap?,
    preloadSlides: (Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    val safeSlide = currentSlide.coerceIn(0, slideCount - 1)
    val configuration = LocalConfiguration.current
    val pagerState = rememberPagerState(
        initialPage = safeSlide,
        pageCount = { slideCount }
    )
    var indicatorVisible by rememberSaveable { mutableStateOf(true) }
    var indicatorToken by rememberSaveable { mutableIntStateOf(0) }
    var zoomResetToken by rememberSaveable { mutableIntStateOf(0) }

    fun showIndicator() {
        indicatorVisible = true
        indicatorToken += 1
    }

    LaunchedEffect(currentSlide, slideCount) {
        val target = currentSlide.coerceIn(0, slideCount - 1)
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    LaunchedEffect(pagerState.currentPage, configuration.orientation) {
        zoomResetToken += 1
        onSlideChange(pagerState.currentPage)
        preloadSlides(pagerState.currentPage, slideCount)
        showIndicator()
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            zoomResetToken += 1
        }
    }

    LaunchedEffect(indicatorVisible, indicatorToken) {
        if (indicatorVisible) {
            delay(3_000)
            indicatorVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.88f))
            .pointerInput(slideCount) {
                detectTapGestures { showIndicator() }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                ZoomablePresentationSlide(
                    slideIndex = page,
                    resetKey = zoomResetToken,
                    renderSlideBitmap = renderSlideBitmap,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                )
            }
        }

        if (indicatorVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(99.dp),
                color = allViePanelColor(alphaLight = 0.9f, alphaDark = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f)),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Text(
                    text = "Slide ${pagerState.currentPage + 1} / $slideCount",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close slide viewer",
                tint = Color.White
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePresentationSlide(
    slideIndex: Int,
    resetKey: Int,
    renderSlideBitmap: suspend (Int, Int, Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(slideIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(slideIndex) { mutableStateOf(false) }
    var zoom by rememberSaveable(slideIndex) { mutableStateOf(1f) }
    var panX by rememberSaveable(slideIndex) { mutableStateOf(0f) }
    var panY by rememberSaveable(slideIndex) { mutableStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(slideIndex) {
        bitmap = null
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            renderSlideBitmap(slideIndex, PRESENTATION_RENDER_WIDTH, PRESENTATION_RENDER_HEIGHT)
        }
        failed = bitmap == null
    }

    LaunchedEffect(slideIndex, resetKey) {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (!zoomChange.isFinite() || !panChange.x.isFinite() || !panChange.y.isFinite()) {
            return@rememberTransformableState
        }
        val targetZoom = (zoom * zoomChange).coerceIn(1f, 5f)
        if (targetZoom <= 1.01f) {
            zoom = 1f
            panX = 0f
            panY = 0f
            return@rememberTransformableState
        }

        val halfMaxPanX = (viewportSize.width * (targetZoom - 1f)) / 2f
        val halfMaxPanY = (viewportSize.height * (targetZoom - 1f)) / 2f
        zoom = targetZoom
        panX = (panX + panChange.x).coerceIn(-halfMaxPanX, halfMaxPanX)
        panY = (panY + panChange.y).coerceIn(-halfMaxPanY, halfMaxPanY)
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewportSize = size
                val halfMaxPanX = (size.width * (zoom - 1f)) / 2f
                val halfMaxPanY = (size.height * (zoom - 1f)) / 2f
                panX = panX.coerceIn(-halfMaxPanX, halfMaxPanX)
                panY = panY.coerceIn(-halfMaxPanY, halfMaxPanY)
            },
        contentAlignment = Alignment.Center
    ) {
        val containerAspect = if (constraints.maxHeight > 0) {
            constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
        } else {
            16f / 9f
        }
        val slideAspect = 16f / 9f
        val slideModifier = if (containerAspect > slideAspect) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(slideAspect)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(slideAspect)
        }

        val rendered = bitmap
        when {
            rendered != null && !rendered.isRecycled -> {
                Image(
                    bitmap = rendered.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = slideModifier
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                        }
                        .transformable(
                            state = transformState,
                            canPan = { panChange ->
                                zoom > 1.01f && abs(panChange.y) >= abs(panChange.x)
                            }
                        )
                )
            }

            failed -> {
                Text(
                    text = "Unable to render slide ${slideIndex + 1}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            else -> {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@Composable
private fun PresentationPlayMode(
    slides: List<PresentationSlideData>,
    currentSlide: Int,
    renderSlideBitmap: suspend (Int, Int, Int) -> Bitmap?,
    preloadSlides: (Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onExit: () -> Unit
) {
    val slideIndex = currentSlide.coerceIn(0, slides.lastIndex)
    val configuration = LocalConfiguration.current
    val leftTapInteraction = remember { MutableInteractionSource() }
    val rightTapInteraction = remember { MutableInteractionSource() }
    var renderedBitmap by remember(slideIndex) { mutableStateOf<Bitmap?>(null) }
    var renderFailed by remember(slideIndex) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsToken by rememberSaveable { mutableIntStateOf(0) }

    fun showControls() {
        controlsVisible = true
        controlsToken += 1
    }

    LaunchedEffect(slideIndex) {
        renderedBitmap = null
        renderFailed = false
        renderedBitmap = withContext(Dispatchers.IO) {
            renderSlideBitmap(slideIndex, PRESENTATION_RENDER_WIDTH, PRESENTATION_RENDER_HEIGHT)
        }
        renderFailed = renderedBitmap == null
    }

    LaunchedEffect(slideIndex, configuration.orientation) {
        preloadSlides(slideIndex, slides.size)
        showControls()
    }

    LaunchedEffect(controlsVisible, controlsToken) {
        if (controlsVisible) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val containerAspect = if (constraints.maxHeight > 0) {
                constraints.maxWidth.toFloat() / constraints.maxHeight.toFloat()
            } else {
                16f / 9f
            }
            val slideAspect = 16f / 9f
            val slideModifier = if (containerAspect > slideAspect) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(slideAspect)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(slideAspect)
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = renderedBitmap
                when {
                    bitmap != null && !bitmap.isRecycled -> {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = slideModifier
                        )
                    }

                    renderFailed -> {
                        Text(
                            text = "Unable to render slide ${slideIndex + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    else -> {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = leftTapInteraction,
                        indication = null
                    ) {
                        showControls()
                        if (slideIndex > 0) {
                            onSlideChange(slideIndex - 1)
                        }
                    }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = rightTapInteraction,
                        indication = null
                    ) {
                        showControls()
                        if (slideIndex < slides.lastIndex) {
                            onSlideChange(slideIndex + 1)
                        }
                    }
            )
        }

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = Color.Black.copy(alpha = 0.44f),
                    contentColor = Color.White
                ) {
                    Text(
                        text = "Slide ${slideIndex + 1} / ${slides.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Exit presentation",
                        tint = Color.White
                    )
                }
            }
        }

    }
}

private fun preparePresentationSlides(slides: List<PresentationSlideData>): List<PresentationSlideData> {
    if (slides.isEmpty()) return emptyList()

    return slides
        .filter { slide ->
            slide.text.isNotBlank() ||
                slide.imageUris.isNotEmpty() ||
                slide.elements.isNotEmpty() ||
                !slide.renderedImageUri.isNullOrBlank()
        }
        .mapIndexed { index, slide ->
            slide.copy(
                index = index + 1,
                widthEmu = slide.widthEmu.coerceAtLeast(1L),
                heightEmu = slide.heightEmu.coerceAtLeast(1L)
            )
        }
}

private fun fallbackTextToSlides(content: String): List<PresentationSlideData> {
    if (content.isBlank()) return emptyList()

    val normalized = content.replace("\r\n", "\n").trim()
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
@Composable
private fun SpreadsheetPreview(
    title: String,
    rows: List<List<String>>,
    onScrollDelta: (Int) -> Unit
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollMax = max(vertical.maxValue, 0)
    var controlsVisible by remember(rows) { mutableStateOf(true) }
    var controlsToken by remember(rows) { mutableIntStateOf(0) }

    fun showControls() {
        controlsVisible = true
        controlsToken += 1
    }

    LaunchedEffect(vertical.value, scrollMax) {
        if (scrollMax > 0) {
            showControls()
        }
    }
    ReportScrollDelta(
        position = vertical.value,
        key = rows,
        onScrollDelta = onScrollDelta
    )

    LaunchedEffect(controlsVisible, controlsToken, scrollMax) {
        if (controlsVisible && scrollMax > 0) {
            delay(3_000)
            controlsVisible = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = allViePanelColor(alphaLight = 0.72f, alphaDark = 0.94f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = VIEWER_HEADER_OVERLAY_TOP_PADDING)
                    .padding(12.dp)
                    .padding(end = 26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontal)
                        .verticalScroll(vertical),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rows.forEachIndexed { rowIndex, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (row.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ) {
                                    Text(
                                        text = " ",
                                        modifier = Modifier
                                            .widthIn(min = 120.dp)
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            } else {
                                row.forEach { cell ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (rowIndex == 0) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                                        },
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ) {
                                        Text(
                                            text = cell.ifBlank { " " },
                                            modifier = Modifier
                                                .widthIn(min = 120.dp, max = 260.dp)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ScrollPositionSideBar(
                currentScroll = vertical.value,
                maxScroll = scrollMax,
                isVisible = controlsVisible,
                onSeekScroll = { target ->
                    scope.launch {
                        vertical.scrollTo(target.coerceIn(0, scrollMax))
                    }
                },
                onInteraction = { showControls() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            )
        }
    }
}

@Composable
private fun PdfViewer(
    file: FileItem,
    onScrollDelta: (Int) -> Unit
) {
    val context = LocalContext.current
    val uriString = file.uriString
    val pdfUri = remember(uriString) {
        runCatching { Uri.parse(uriString) }.getOrNull()
    }
    val canLoadPdf = pdfUri != null && uriString.isNotBlank()
    var loadError by remember(uriString) {
        mutableStateOf(if (canLoadPdf) null else "This PDF file path is invalid.")
    }
    var isLoading by remember(uriString) { mutableStateOf(canLoadPdf) }
    var pageCount by rememberSaveable(uriString) { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(uriString) { mutableIntStateOf(0) }
    var pdfViewRef by remember(uriString) { mutableStateOf<PDFView?>(null) }
    var controlsVisible by rememberSaveable(uriString) { mutableStateOf(true) }
    var controlsToken by rememberSaveable(uriString) { mutableIntStateOf(0) }
    var lastPdfScrollPosition by rememberSaveable(uriString) { mutableIntStateOf(0) }
    var isDisposed by remember(uriString) { mutableStateOf(false) }
    var pdfPassword by rememberSaveable(uriString) { mutableStateOf("") }
    var passwordInput by rememberSaveable(uriString) { mutableStateOf("") }
    var showPasswordDialog by rememberSaveable(uriString) { mutableStateOf(false) }
    var passwordError by rememberSaveable(uriString) { mutableStateOf<String?>(null) }

    fun showControls() {
        controlsVisible = true
        controlsToken += 1
    }

    fun reportPdfScroll(page: Int, positionOffset: Float) {
        if (!positionOffset.isFinite()) return
        val safePage = page.coerceAtLeast(0)
        val safeOffset = positionOffset.coerceIn(0f, 1f)
        val nextPosition = ((safePage.toLong() * PDF_SCROLL_POSITION_WEIGHT) +
            (safeOffset * PDF_SCROLL_POSITION_WEIGHT).roundToInt())
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
        val delta = nextPosition - lastPdfScrollPosition
        if (delta != 0) {
            onScrollDelta(delta)
            lastPdfScrollPosition = nextPosition
        }
    }

    DisposableEffect(uriString) {
        isDisposed = false
        onDispose {
            isDisposed = true
            runCatching { pdfViewRef?.recycle() }
            pdfViewRef = null
        }
    }

    LaunchedEffect(controlsVisible, controlsToken, pageCount) {
        if (controlsVisible && pageCount > 0) {
            kotlinx.coroutines.delay(3_000)
            controlsVisible = false
        }
    }

    fun loadPdf(pdfView: PDFView, password: String = pdfPassword) {
        val uri = pdfUri ?: run {
            isLoading = false
            loadError = "This PDF file path is invalid."
            return
        }
        val targetPage = currentPage.coerceAtLeast(0)
        runCatching {
            isLoading = true
            loadError = null
            pageCount = 0
            currentPage = targetPage
            val configurator = pdfView.fromUri(uri)
                .defaultPage(targetPage)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .enableAnnotationRendering(true)
                .enableAntialiasing(true)
                .autoSpacing(false)
                .pageFitPolicy(FitPolicy.WIDTH)
                .spacing(2)
                .onLoad { total ->
                    if (isDisposed) return@onLoad
                    showPasswordDialog = false
                    passwordError = null
                    val safeTotal = total.coerceAtLeast(0)
                    isLoading = false
                    pageCount = safeTotal
                    currentPage = if (pageCount > 0) {
                        targetPage.coerceIn(0, pageCount - 1)
                    } else {
                        0
                    }
                    lastPdfScrollPosition = currentPage * PDF_SCROLL_POSITION_WEIGHT
                    showControls()
                }
                .onPageScroll { page, positionOffset ->
                    if (!isDisposed) {
                        reportPdfScroll(page, positionOffset)
                    }
                }
                .onPageChange { page, total ->
                    if (isDisposed) return@onPageChange
                    currentPage = page.coerceAtLeast(0)
                    pageCount = total.coerceAtLeast(0)
                    loadError = null
                    if (controlsVisible) {
                        controlsToken += 1
                    }
                }
                .onTap {
                    showControls()
                    false
                }
                .onError { throwable ->
                    if (isDisposed) return@onError
                    isLoading = false
                    pageCount = 0
                    if (isPdfPasswordError(throwable)) {
                        passwordError = if (password.isBlank()) {
                            "Password required."
                        } else {
                            "Incorrect password."
                        }
                        showPasswordDialog = true
                        loadError = "Password required or incorrect password."
                    } else {
                        loadError = throwable.message ?: "Unable to render this PDF."
                    }
                }
                .onPageError { _, throwable ->
                    if (isDisposed) return@onPageError
                    isLoading = false
                    if (isPdfPasswordError(throwable)) {
                        passwordError = if (password.isBlank()) {
                            "Password required."
                        } else {
                            "Incorrect password."
                        }
                        showPasswordDialog = true
                        loadError = "Password required or incorrect password."
                    } else {
                        loadError = throwable.message ?: "Unable to render this PDF page."
                    }
                }
            if (password.isNotBlank()) {
                configurator.password(password)
            }
            configurator.load()
        }.onFailure { throwable ->
            isLoading = false
            if (isPdfPasswordError(throwable)) {
                passwordError = if (password.isBlank()) {
                    "Password required."
                } else {
                    "Incorrect password."
                }
                showPasswordDialog = true
                loadError = "Password required or incorrect password."
            } else {
                loadError = throwable.message ?: "Unable to open this PDF file."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (canLoadPdf) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = VIEWER_HEADER_OVERLAY_TOP_PADDING),
                factory = { viewContext ->
                    PDFView(viewContext, null).apply {
                        pdfViewRef = this
                        tag = uriString
                        setBackgroundColor(AndroidColor.BLACK)
                        loadPdf(this)
                    }
                },
                update = { pdfView ->
                    pdfViewRef = pdfView
                    if (pdfView.tag != uriString) {
                        pdfView.tag = uriString
                        runCatching {
                            pdfView.recycle()
                        }
                        loadPdf(pdfView)
                    }
                }
            )
        }

        if (loadError != null) {
            EmptyState(
                title = "Unable to render PDF",
                body = loadError.orEmpty(),
                modifier = Modifier.fillMaxSize(),
                actionLabel = if (isPdfPasswordMessage(loadError)) "Enter password" else "Open with app",
                onAction = {
                    if (isPdfPasswordMessage(loadError)) {
                        showPasswordDialog = true
                    } else if (!openPdfExternally(context, file)) {
                        Toast.makeText(
                            context,
                            "No app available to open this PDF.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } else if (isLoading) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(20.dp),
                color = allViePanelColor(alphaLight = 0.9f, alphaDark = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f)),
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Rendering PDF...",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else if (pageCount > 0) {
            PdfPageSideBar(
                currentPage = currentPage.coerceIn(0, pageCount - 1),
                totalPages = pageCount,
                isVisible = controlsVisible,
                onSeekPage = { target ->
                    val safe = target.coerceIn(0, pageCount - 1)
                    if (safe != currentPage) {
                        currentPage = safe
                        runCatching { pdfViewRef?.jumpTo(safe, true) }
                    }
                },
                onInteraction = { showControls() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
            )
        }

        if (showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { Text("PDF password") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = passwordError ?: "Enter the password for this PDF.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = {
                                passwordInput = it
                                passwordError = null
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            label = { Text("Password") }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val entered = passwordInput
                            if (entered.isBlank()) {
                                passwordError = "Password cannot be empty."
                            } else {
                                pdfPassword = entered
                                showPasswordDialog = false
                                pdfViewRef?.let { pdfView ->
                                    runCatching { pdfView.recycle() }
                                    loadPdf(pdfView, entered)
                                }
                            }
                        }
                    ) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasswordDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun isPdfPasswordError(throwable: Throwable): Boolean {
    return isPdfPasswordMessage(throwable.message)
}

private fun isPdfPasswordMessage(message: String?): Boolean {
    val normalized = message.orEmpty()
    return normalized.contains("password", ignoreCase = true) ||
        normalized.contains("encrypted", ignoreCase = true)
}

private fun openPdfExternally(context: Context, file: FileItem): Boolean {
    val sourceUri = runCatching { Uri.parse(file.uriString) }.getOrNull() ?: return false
    val viewUri = when (sourceUri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> {
            val localFile = File(sourceUri.path.orEmpty())
            if (!localFile.isFile) return false
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    localFile
                )
            }.getOrNull() ?: return false
        }
        else -> sourceUri
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(viewUri, "application/pdf")
        clipData = ClipData.newUri(context.contentResolver, file.displayName, viewUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse { false }
}

private fun detectOfficeKind(displayName: String, mimeType: String): OfficeKind {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return when (extension) {
        "doc", "docx" -> OfficeKind.DOCUMENT
        "ppt", "pptx" -> OfficeKind.PRESENTATION
        "xls", "xlsx" -> OfficeKind.SPREADSHEET
        else -> {
            val normalizedMime = mimeType.lowercase(Locale.ROOT)
            when {
                "word" in normalizedMime -> OfficeKind.DOCUMENT
                "powerpoint" in normalizedMime || "presentation" in normalizedMime -> OfficeKind.PRESENTATION
                "excel" in normalizedMime || "spreadsheet" in normalizedMime -> OfficeKind.SPREADSHEET
                else -> OfficeKind.UNKNOWN
            }
        }
    }
}


