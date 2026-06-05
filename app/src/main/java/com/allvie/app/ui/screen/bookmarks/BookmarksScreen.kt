package com.allvie.app.ui.screen.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

data class BookmarksUiState(
    val searchQuery: String = "",
    val layoutMode: LayoutMode = LayoutMode.GRID,
    val files: List<FileItem> = emptyList(),
    val message: String? = null
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
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
        historyRepository.observeBookmarks()
    ) { query, currentLayout, snackbar, files ->
        val visible = files.filter {
            query.isBlank() ||
                it.displayName.contains(query, ignoreCase = true) ||
                it.pathLabel.contains(query, ignoreCase = true)
        }

        BookmarksUiState(
            searchQuery = query,
            layoutMode = currentLayout,
            files = visible,
            message = snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookmarksUiState()
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

    fun onShareFailed(file: FileItem) {
        message.value = "No app available to share ${file.displayName}."
    }

    fun clearMessage() {
        message.value = null
    }
}

@Composable
fun BookmarksScreen(
    onOpenFile: (FileItem) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    val context = LocalContext.current

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
            onQueryChange = viewModel::onSearchChange
        )

        Box(modifier = Modifier.weight(1f)) {
            FileCollection(
                files = state.files,
                layoutMode = state.layoutMode,
                emptyTitle = "No bookmarks yet",
                emptyBody = "Bookmark a file from the Files or Recents tabs to see it here.",
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
}

