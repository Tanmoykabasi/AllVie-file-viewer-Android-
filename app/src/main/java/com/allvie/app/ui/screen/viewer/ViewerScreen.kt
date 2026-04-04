package com.allvie.app.ui.screen.viewer

import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.allvie.app.data.repository.FileRepository
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import com.allvie.app.ui.components.EmptyState
import com.allvie.app.util.openFileWithSystem
import com.allvie.app.util.shareFile
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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

data class ViewerUiState(
    val file: FileItem,
    val textContent: String? = null,
    val tableRows: List<List<String>> = emptyList(),
    val slides: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository
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
                if (isXmlFile(file.displayName, file.mimeType)) {
                    fileRepository.loadXmlSpreadsheetRows(file.uriString)
                } else {
                    null
                }
            }.onSuccess { rows ->
                if (!rows.isNullOrEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        textContent = null,
                        tableRows = rows,
                        slides = emptyList(),
                        isLoading = false,
                        error = null
                    )
                    return@onSuccess
                }

                runCatching {
                    fileRepository.loadTextContent(file.uriString)
                }.onSuccess { text ->
                    _uiState.value = _uiState.value.copy(
                        textContent = text,
                        tableRows = emptyList(),
                        slides = emptyList(),
                        isLoading = false,
                        error = null
                    )
                }.onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        textContent = null,
                        tableRows = emptyList(),
                        slides = emptyList(),
                        isLoading = false,
                        error = throwable.message ?: "Unable to load this text file."
                    )
                }
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    textContent = null,
                    tableRows = emptyList(),
                    slides = emptyList(),
                    isLoading = false,
                    error = throwable.message ?: "Unable to load this text file."
                )
            }
        }
    }

    private fun loadOfficeContent() {
        viewModelScope.launch {
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
                            isLoading = false,
                            error = null
                        )
                    }

                    OfficeKind.PRESENTATION -> {
                        _uiState.value = _uiState.value.copy(
                            textContent = if (preview.isBlank()) null else preview,
                            tableRows = emptyList(),
                            slides = parsePresentationSlides(preview),
                            isLoading = false,
                            error = null
                        )
                    }

                    OfficeKind.DOCUMENT,
                    OfficeKind.UNKNOWN -> {
                        _uiState.value = _uiState.value.copy(
                            textContent = preview,
                            tableRows = emptyList(),
                            slides = emptyList(),
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
                    isLoading = false,
                    error = throwable.message ?: "Unable to preview this Office file."
                )
            }
        }
    }

    private fun isXmlFile(displayName: String, mimeType: String): Boolean {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (extension == "xml") return true

        val normalizedMime = mimeType.lowercase(Locale.ROOT)
        return normalizedMime == "application/xml" || normalizedMime == "text/xml"
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

    private fun parsePresentationSlides(content: String): List<String> {
        if (content.isBlank()) return emptyList()

        val normalized = content.replace("\r\n", "\n")
        val markerRegex = Regex("(?m)^Slide\\s+\\d+\\b.*$")
        val markers = markerRegex.findAll(normalized).toList()

        if (markers.isEmpty()) {
            return normalized
                .split("\n\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(60)
        }

        val slides = mutableListOf<String>()
        markers.forEachIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < markers.size) {
                markers[index + 1].range.first
            } else {
                normalized.length
            }
            val chunk = normalized.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                val body = chunk.replaceFirst(Regex("^Slide\\s+\\d+\\s*\\n?"), "").trim()
                slides.add(if (body.isNotBlank()) body else chunk)
            }
        }

        return slides.take(120)
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
    val canOpenExternally = state.file.category != FileCategory.OFFICE

    fun openExternally() {
        if (!openFileWithSystem(context, state.file)) {
            Toast.makeText(context, "No compatible app installed.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.file.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = state.file.category.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    if (!shareFile(context, state.file)) {
                        Toast.makeText(context, "No app available to share this file.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(imageVector = Icons.Rounded.Share, contentDescription = "Share")
                }
                if (canOpenExternally) {
                    IconButton(onClick = ::openExternally) {
                        Icon(imageVector = Icons.Rounded.OpenInNew, contentDescription = "Open externally")
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
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
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = if (canOpenExternally) "Open with app" else null,
                        onAction = if (canOpenExternally) ::openExternally else null
                    )
                }

                state.file.category == FileCategory.PDF -> {
                    PdfViewer(
                        uriString = state.file.uriString,
                        onOpenExternal = ::openExternally
                    )
                }

                state.file.category == FileCategory.TEXT -> {
                    if (state.tableRows.isNotEmpty()) {
                        SpreadsheetPreview(
                            title = "XML Spreadsheet Preview",
                            rows = state.tableRows
                        )
                    } else {
                        PlainTextPreview(
                            title = "Text Preview",
                            content = state.textContent.orEmpty()
                        )
                    }
                }

                state.file.category == FileCategory.OFFICE -> {
                    when (officeKind) {
                        OfficeKind.SPREADSHEET -> {
                            if (state.tableRows.isNotEmpty()) {
                                SpreadsheetPreview(
                                    title = "Spreadsheet Preview",
                                    rows = state.tableRows
                                )
                            } else {
                                PlainTextPreview(
                                    title = "Spreadsheet Preview",
                                    content = state.textContent.orEmpty()
                                )
                            }
                        }

                        OfficeKind.PRESENTATION -> {
                            PresentationPreview(
                                slides = state.slides,
                                fallbackText = state.textContent.orEmpty()
                            )
                        }

                        OfficeKind.DOCUMENT,
                        OfficeKind.UNKNOWN -> {
                            PlainTextPreview(
                                title = "Document Preview",
                                content = state.textContent.orEmpty()
                            )
                        }
                    }
                }

                else -> {
                    EmptyState(
                        title = "Preview not available",
                        body = "Use Open with app for this file type.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "Open with app",
                        onAction = ::openExternally
                    )
                }
            }
        }
    }
}

@Composable
private fun PlainTextPreview(
    title: String,
    content: String
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = content.ifBlank { "No readable content found in this file." },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PresentationPreview(
    slides: List<String>,
    fallbackText: String
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        if (slides.isEmpty()) {
            PlainTextPreview(
                title = "Presentation Preview",
                content = fallbackText
            )
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Presentation Preview",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            slides.forEachIndexed { index, slideText ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Slide ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = slideText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpreadsheetPreview(
    title: String,
    rows: List<List<String>>
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
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
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                                    }
                                ) {
                                    Text(
                                        text = cell.ifBlank { " " },
                                        modifier = Modifier
                                            .widthIn(min = 120.dp, max = 260.dp)
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfViewer(
    uriString: String,
    onOpenExternal: () -> Unit
) {
    var loadError by remember(uriString) { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PDFView(context, null).apply {
                    tag = uriString
                    setBackgroundColor(Color.WHITE)
                    runCatching {
                        fromUri(Uri.parse(uriString))
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .autoSpacing(true)
                            .pageFitPolicy(FitPolicy.WIDTH)
                            .spacing(8)
                            .onError { throwable ->
                                loadError = throwable.message ?: "Unable to render this PDF."
                            }
                            .onPageError { _, throwable ->
                                loadError = throwable.message ?: "Unable to render this PDF page."
                            }
                            .load()
                    }.onFailure { throwable ->
                        loadError = throwable.message ?: "Unable to open this PDF file."
                    }
                }
            },
            update = { pdfView ->
                if (pdfView.tag != uriString) {
                    pdfView.tag = uriString
                    loadError = null
                    runCatching {
                        pdfView.recycle()
                        pdfView.fromUri(Uri.parse(uriString))
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .autoSpacing(true)
                            .pageFitPolicy(FitPolicy.WIDTH)
                            .spacing(8)
                            .onError { throwable ->
                                loadError = throwable.message ?: "Unable to render this PDF."
                            }
                            .onPageError { _, throwable ->
                                loadError = throwable.message ?: "Unable to render this PDF page."
                            }
                            .load()
                    }.onFailure { throwable ->
                        loadError = throwable.message ?: "Unable to open this PDF file."
                    }
                }
            }
        )

        if (loadError != null) {
            EmptyState(
                title = "Unable to render PDF",
                body = loadError.orEmpty(),
                modifier = Modifier.fillMaxSize(),
                actionLabel = "Open with app",
                onAction = onOpenExternal
            )
        }
    }
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












