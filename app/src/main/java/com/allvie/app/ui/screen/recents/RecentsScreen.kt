package com.allvie.app.ui.screen.recents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.allvie.app.data.preferences.UserPreferencesRepository
import com.allvie.app.data.repository.FileRepository
import com.allvie.app.data.repository.HistoryRepository
import com.allvie.app.domain.model.FileItem
import com.allvie.app.domain.model.LayoutMode
import com.allvie.app.ui.components.CompactSearchBar
import com.allvie.app.ui.components.DeleteFileDialog
import com.allvie.app.ui.components.DeleteMultipleFilesDialog
import com.allvie.app.ui.components.FileCollection
import com.allvie.app.ui.components.RenameFileDialog
import com.allvie.app.ui.components.SelectionActionBar
import com.allvie.app.util.shareFile
import com.allvie.app.util.shareFiles
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

data class RecentsUiState(
    val searchQuery: String = "",
    val layoutMode: LayoutMode = LayoutMode.GRID,
    val files: List<FileItem> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class RecentsViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val historyRepository: HistoryRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val layoutMode = MutableStateFlow(LayoutMode.GRID)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        searchQuery,
        layoutMode,
        message,
        historyRepository.observeRecents(),
        historyRepository.observeBookmarkedUris()
    ) { query, currentLayout, snackbar, files, bookmarks ->
        val visible = files
            .asSequence()
            .map { file -> file.copy(isBookmarked = file.uriString in bookmarks) }
            .filter {
                query.isBlank() ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.pathLabel.contains(query, ignoreCase = true)
            }
            .toList()

        RecentsUiState(
            searchQuery = query,
            layoutMode = currentLayout,
            files = visible,
            message = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecentsUiState()
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

    fun onSearchChange(value: String) {
        searchQuery.value = value
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
                    message.value = "Deleted ${file.displayName}."
                }
                .onFailure { throwable ->
                    message.value = throwable.message ?: "Unable to delete file."
                }
        }
    }

    fun deleteFiles(files: List<FileItem>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            var deletedCount = 0
            var failedCount = 0
            files.forEach { file ->
                fileRepository.deleteFile(file.uriString)
                    .onSuccess {
                        historyRepository.removeFile(file.uriString)
                        deletedCount++
                    }
                    .onFailure {
                        failedCount++
                    }
            }
            message.value = when {
                deletedCount > 0 && failedCount == 0 ->
                    "Deleted $deletedCount ${if (deletedCount == 1) "file" else "files"}."
                deletedCount > 0 ->
                    "Deleted $deletedCount files ($failedCount failed)."
                else ->
                    "Unable to delete selected files."
            }
        }
    }

    fun onShareFailed(file: FileItem) {
        message.value = "No app available to share ${file.displayName}."
    }

    fun onShareMultipleFailed() {
        message.value = "No app available to share selected files."
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun RecentsScreen(
    onOpenFile: (FileItem) -> Unit,
    viewModel: RecentsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.files) {
        val validUris = state.files.mapTo(mutableSetOf()) { it.uriString }
        selectedUris = selectedUris.intersect(validUris)
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

        CompactSearchBar(
            query = state.searchQuery,
            placeholder = "Search files",
            onQueryChange = viewModel::onSearchChange,
            trailingContent = {
                if (state.files.isNotEmpty() && !selectionMode) {
                    IconButton(
                        onClick = {
                            selectionMode = true
                            selectedUris = emptySet()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Checklist,
                            contentDescription = "Select multiple files",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        if (selectionMode) {
            SelectionActionBar(
                selectedCount = selectedUris.size,
                totalCount = state.files.size,
                onSelectAllToggle = {
                    selectedUris = if (selectedUris.size == state.files.size && state.files.isNotEmpty()) {
                        emptySet()
                    } else {
                        state.files.mapTo(mutableSetOf()) { it.uriString }
                    }
                },
                onShareSelected = {
                    val selectedFiles = state.files.filter { it.uriString in selectedUris }
                    if (selectedFiles.isNotEmpty()) {
                        if (!shareFiles(context, selectedFiles)) {
                            viewModel.onShareMultipleFailed()
                        } else {
                            selectionMode = false
                            selectedUris = emptySet()
                        }
                    }
                },
                onDeleteSelected = {
                    if (selectedUris.isNotEmpty()) {
                        showBatchDeleteDialog = true
                    }
                },
                onClearSelection = {
                    selectionMode = false
                    selectedUris = emptySet()
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            FileCollection(
                files = state.files,
                layoutMode = state.layoutMode,
                emptyTitle = "Nothing opened yet",
                emptyBody = "Open a supported file from the Files tab and it will appear here.",
                selectionMode = selectionMode,
                selectedUris = selectedUris,
                onToggleSelect = { file ->
                    selectionMode = true
                    selectedUris = if (file.uriString in selectedUris) {
                        selectedUris - file.uriString
                    } else {
                        selectedUris + file.uriString
                    }
                },
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

    if (showBatchDeleteDialog && selectedUris.isNotEmpty()) {
        val selectedFiles = state.files.filter { it.uriString in selectedUris }
        DeleteMultipleFilesDialog(
            count = selectedFiles.size,
            onDismiss = { showBatchDeleteDialog = false },
            onConfirm = {
                viewModel.deleteFiles(selectedFiles)
                showBatchDeleteDialog = false
                selectionMode = false
                selectedUris = emptySet()
            }
        )
    }
}

