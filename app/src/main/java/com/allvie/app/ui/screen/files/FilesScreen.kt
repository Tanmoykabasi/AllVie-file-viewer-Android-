package com.allvie.app.ui.screen.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.allvie.app.data.preferences.UserPreferencesRepository
import com.allvie.app.data.repository.FileRepository
import com.allvie.app.data.repository.HistoryRepository
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import com.allvie.app.domain.model.LayoutMode
import com.allvie.app.ui.components.CompactSearchBar
import com.allvie.app.ui.components.DeleteFileDialog
import com.allvie.app.ui.components.EmptyState
import com.allvie.app.ui.components.FileCollection
import com.allvie.app.ui.components.RenameFileDialog
import com.allvie.app.util.shareFile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FilesUiState(
    val hasStorageAccess: Boolean = false,
    val searchQuery: String = "",
    val selectedFilter: FileCategory = FileCategory.ALL,
    val layoutMode: LayoutMode = LayoutMode.GRID,
    val files: List<FileItem> = emptyList(),
    val totalSupportedFiles: Int = 0,
    val isRefreshing: Boolean = false,
    val message: String? = null
)

private data class FilesUiInputs(
    val hasStorageAccess: Boolean,
    val searchQuery: String,
    val selectedFilter: FileCategory,
    val layoutMode: LayoutMode
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val historyRepository: HistoryRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val hasStorageAccess = MutableStateFlow(false)
    private val rawFiles = MutableStateFlow<List<FileItem>>(emptyList())
    private val searchQuery = MutableStateFlow("")
    private val selectedFilter = MutableStateFlow(FileCategory.ALL)
    private val layoutMode = MutableStateFlow(LayoutMode.GRID)
    private val isRefreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)


    private val uiInputs = combine(
        hasStorageAccess,
        searchQuery,
        selectedFilter,
        layoutMode
    ) { storageAccess, query, filter, currentLayout ->
        FilesUiInputs(
            hasStorageAccess = storageAccess,
            searchQuery = query,
            selectedFilter = filter,
            layoutMode = currentLayout
        )
    }

    val uiState = combine(
        uiInputs,
        rawFiles,
        isRefreshing,
        message,
        historyRepository.observeBookmarkedUris()
    ) { inputs, files, refreshing, snackbar, bookmarks ->
        val filtered = files
            .asSequence()
            .filter { inputs.selectedFilter == FileCategory.ALL || it.category == inputs.selectedFilter }
            .filter {
                inputs.searchQuery.isBlank() ||
                    it.displayName.contains(inputs.searchQuery, ignoreCase = true) ||
                    it.pathLabel.contains(inputs.searchQuery, ignoreCase = true)
            }
            .map { file -> file.copy(isBookmarked = file.uriString in bookmarks) }
            .toList()

        FilesUiState(
            hasStorageAccess = inputs.hasStorageAccess,
            searchQuery = inputs.searchQuery,
            selectedFilter = inputs.selectedFilter,
            layoutMode = inputs.layoutMode,
            files = if (inputs.hasStorageAccess) filtered else emptyList(),
            totalSupportedFiles = if (inputs.hasStorageAccess) files.size else 0,
            isRefreshing = refreshing,
            message = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FilesUiState()
    )

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow
                .map { it.layoutMode }
                .distinctUntilChanged()
                .collect { selectedLayout ->
                    layoutMode.value = selectedLayout
                }
        }
    }


    fun setStorageAccess(granted: Boolean) {
        val changed = hasStorageAccess.value != granted
        hasStorageAccess.value = granted

        if (!granted) {
            rawFiles.value = emptyList()
            return
        }

        if (changed) {
            refresh()
        }
    }

    fun onSearchChange(value: String) {
        searchQuery.value = value
    }

    fun onFilterChange(filter: FileCategory) {
        selectedFilter.value = filter
    }

    fun refresh() {
        if (!hasStorageAccess.value || isRefreshing.value) {
            if (!hasStorageAccess.value) {
                message.value = "Storage permission is required to scan files."
            }
            return
        }

        viewModelScope.launch {
            scanDevice()
        }
    }

    fun setLayoutMode(mode: LayoutMode) {
        viewModelScope.launch {
            preferencesRepository.setLayoutMode(mode)
        }
    }

    fun onFileOpened(file: FileItem) {
        viewModelScope.launch {
            historyRepository.addRecent(file)
        }
    }

    fun toggleBookmark(file: FileItem) {
        viewModelScope.launch {
            val added = historyRepository.toggleBookmark(file)
            message.value = if (added) {
                "Added ${file.displayName} to bookmarks."
            } else {
                "Removed ${file.displayName} from bookmarks."
            }
        }
    }

    fun renameFile(file: FileItem, newName: String) {
        viewModelScope.launch {
            fileRepository.renameFile(file, newName)
                .onSuccess { updated ->
                    historyRepository.replaceFile(file.uriString, updated)
                    message.value = "Renamed to ${updated.displayName}."
                    refresh()
                }
                .onFailure { throwable ->
                    message.value = throwable.message ?: "Unable to rename file."
                }
        }
    }

    fun deleteFile(file: FileItem) {
        viewModelScope.launch {
            fileRepository.deleteFile(file.uriString)
                .onSuccess {
                    historyRepository.removeFile(file.uriString)
                    rawFiles.value = rawFiles.value.filterNot { it.uriString == file.uriString }
                    message.value = "Deleted ${file.displayName}."
                }
                .onFailure { throwable ->
                    message.value = throwable.message ?: "Unable to delete file."
                }
        }
    }

    fun onShareFailed(file: FileItem) {
        message.value = "No app available to share ${file.displayName}."
    }

    fun clearMessage() {
        message.value = null
    }

    private suspend fun scanDevice() {
        isRefreshing.value = true
        runCatching {
            fileRepository.scanFiles()
        }.onSuccess { files ->
            rawFiles.value = files
        }.onFailure { throwable ->
            message.value = throwable.message ?: "Unable to scan device files."
            rawFiles.value = emptyList()
        }
        isRefreshing.value = false
    }
}

@Composable
fun FilesScreen(
    hasStorageAccess: Boolean,
    onRequestStorageAccess: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(hasStorageAccess) {
        viewModel.setStorageAccess(hasStorageAccess)
    }

    DisposableEffect(lifecycleOwner, state.hasStorageAccess) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.hasStorageAccess) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { text ->
            snackbarHostState.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!state.hasStorageAccess) {
            EmptyState(
                title = "Storage access required",
                body = "Grant storage access once. AllVie will automatically scan your device for supported files every time you reopen the app.",
                modifier = Modifier.fillMaxWidth(),
                actionLabel = "Grant access",
                onAction = onRequestStorageAccess
            )
        } else {

            CompactSearchBar(
                query = state.searchQuery,
                placeholder = "Search files",
                onQueryChange = viewModel::onSearchChange,
                trailingContent = {
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Rescan files",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FileCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.selectedFilter == category,
                        onClick = { viewModel.onFilterChange(category) },
                        modifier = Modifier.height(34.dp),
                        label = {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.selectedFilter == category,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.files.size} items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        viewModel.setLayoutMode(
                            if (state.layoutMode == LayoutMode.GRID) LayoutMode.LIST else LayoutMode.GRID
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (state.layoutMode == LayoutMode.GRID) {
                            Icons.Rounded.ViewAgenda
                        } else {
                            Icons.Rounded.Dashboard
                        },
                        contentDescription = "Toggle layout",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                FileCollection(
                    files = state.files,
                    layoutMode = state.layoutMode,
                    emptyTitle = "No files found",
                    emptyBody = "Try another filter or wait for the next scan.",
                    onOpen = { file ->
                        viewModel.onFileOpened(file)
                        onOpenFile(file)
                    },
                    onToggleBookmark = viewModel::toggleBookmark,
                    onShare = { file ->
                        if (!shareFile(context, file)) {
                            viewModel.onShareFailed(file)
                        }
                    },
                    onRename = { file -> renameTarget = file },
                    onDelete = { file -> deleteTarget = file }
                )
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }

    renameTarget?.let { file ->
        RenameFileDialog(
            initialName = file.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { updatedName ->
                viewModel.renameFile(file, updatedName)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { file ->
        DeleteFileDialog(
            fileName = file.displayName,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteFile(file)
                deleteTarget = null
            }
        )
    }
}

