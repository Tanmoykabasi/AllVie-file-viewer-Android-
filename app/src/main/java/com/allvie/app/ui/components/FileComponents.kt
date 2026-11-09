package com.allvie.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import com.allvie.app.domain.model.LayoutMode
import com.allvie.app.ui.theme.allVieOutlineColor
import com.allvie.app.ui.theme.allViePanelColor
import com.allvie.app.util.formatFileSize
import com.allvie.app.util.formatTimestamp
import java.util.Locale

@Composable
fun GlassPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = allViePanelColor(alphaLight = 0.74f, alphaDark = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun SelectionActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAllToggle: () -> Unit,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = allViePanelColor(alphaLight = 0.88f, alphaDark = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel selection",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onSelectAllToggle,
                enabled = totalCount > 0
            ) {
                Icon(
                    imageVector = Icons.Rounded.SelectAll,
                    contentDescription = if (selectedCount == totalCount && totalCount > 0) {
                        "Deselect all"
                    } else {
                        "Select all"
                    },
                    tint = if (selectedCount == totalCount && totalCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = onShareSelected,
                enabled = selectedCount > 0
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share selected files",
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                )
            }
            IconButton(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete selected files",
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    }
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = allViePanelColor(alphaLight = 0.74f, alphaDark = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.16f, alphaDark = 0.28f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
fun FileCollection(
    files: List<FileItem>,
    layoutMode: LayoutMode,
    emptyTitle: String,
    emptyBody: String,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selectedUris: Set<String> = emptySet(),
    onToggleSelect: ((FileItem) -> Unit)? = null,
    onOpen: (FileItem) -> Unit,
    onToggleBookmark: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit
) {
    if (files.isEmpty()) {
        EmptyState(
            title = emptyTitle,
            body = emptyBody,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    when (layoutMode) {
        LayoutMode.LIST -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 2.dp)
            ) {
                items(files, key = { it.uriString }) { file ->
                    ListFileCard(
                        file = file,
                        selectionMode = selectionMode,
                        isSelected = file.uriString in selectedUris,
                        onToggleSelect = onToggleSelect,
                        onOpen = onOpen,
                        onToggleBookmark = onToggleBookmark,
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete
                    )
                }
            }
        }

        LayoutMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItems(files, key = { it.uriString }) { file ->
                    GridFileCard(
                        file = file,
                        selectionMode = selectionMode,
                        isSelected = file.uriString in selectedUris,
                        onToggleSelect = onToggleSelect,
                        onOpen = onOpen,
                        onToggleBookmark = onToggleBookmark,
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListFileCard(
    file: FileItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: ((FileItem) -> Unit)?,
    onOpen: (FileItem) -> Unit,
    onToggleBookmark: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(
                onClick = {
                    if (selectionMode && onToggleSelect != null) {
                        onToggleSelect(file)
                    } else {
                        onOpen(file)
                    }
                },
                onLongClick = {
                    onToggleSelect?.invoke(file)
                }
            ),
        shape = RoundedCornerShape(22.dp),
        color = allViePanelColor(alphaLight = 0.78f, alphaDark = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (isSelected) 6.dp else 3.dp,
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.14f, alphaDark = 0.24f))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileCategoryBadge(file)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.pathLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(context, file.size)} | ${formatTimestamp(file.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectionMode) {
                IconButton(onClick = { onToggleSelect?.invoke(file) }) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                FileActionMenu(
                    file = file,
                    onSelect = onToggleSelect?.let { toggle -> { toggle(file) } },
                    onToggleBookmark = onToggleBookmark,
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileCard(
    file: FileItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: ((FileItem) -> Unit)?,
    onOpen: (FileItem) -> Unit,
    onToggleBookmark: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(
                onClick = {
                    if (selectionMode && onToggleSelect != null) {
                        onToggleSelect(file)
                    } else {
                        onOpen(file)
                    }
                },
                onLongClick = {
                    onToggleSelect?.invoke(file)
                }
            ),
        shape = RoundedCornerShape(24.dp),
        color = allViePanelColor(alphaLight = 0.78f, alphaDark = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (isSelected) 6.dp else 3.dp,
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, allVieOutlineColor(alphaLight = 0.14f, alphaDark = 0.24f))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileCategoryBadge(file)
                Spacer(modifier = Modifier.weight(1f))
                if (selectionMode) {
                    IconButton(onClick = { onToggleSelect?.invoke(file) }) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    FileActionMenu(
                        file = file,
                        onSelect = onToggleSelect?.let { toggle -> { toggle(file) } },
                        onToggleBookmark = onToggleBookmark,
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.pathLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatFileSize(context, file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTimestamp(file.lastModified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileCategoryBadge(file: FileItem) {
    val badgeType = remember(file.displayName, file.mimeType, file.category) {
        resolveFileBadgeType(file)
    }
    val iconVector = remember(badgeType) {
        fileTypeCardIcon(badgeType)
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = iconVector,
            contentDescription = badgeType,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun FileActionMenu(
    file: FileItem,
    onSelect: (() -> Unit)? = null,
    onToggleBookmark: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onRename: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onSelect != null) {
                DropdownMenuItem(
                    text = { Text("Select") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(if (file.isBookmarked) "Remove bookmark" else "Add bookmark") },
                leadingIcon = {
                    Icon(
                        imageVector = if (file.isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null
                    )
                },
                onClick = {
                    expanded = false
                    onToggleBookmark(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(imageVector = Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onShare(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(imageVector = Icons.Rounded.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRename(file)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(imageVector = Icons.Rounded.DeleteOutline, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete(file)
                }
            )
        }
    }
}

private fun resolveFileBadgeType(file: FileItem): String {
    val ext = file.displayName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    val mime = file.mimeType.lowercase(Locale.ROOT)
    return when {
        ext == "pdf" || mime == "application/pdf" || file.category == FileCategory.PDF -> "PDF"
        ext == "doc" || ext == "docx" || "wordprocessingml" in mime || "msword" in mime -> "DOC"
        ext == "xls" || ext == "xlsx" || "spreadsheetml" in mime || "ms-excel" in mime -> "XLS"
        ext == "ppt" || ext == "pptx" || "presentationml" in mime || "ms-powerpoint" in mime -> "PPT"
        ext == "csv" || "csv" in mime -> "CSV"
        ext == "xml" || "xml" in mime -> "XML"
        ext == "txt" || ext == "md" || ext == "log" || ext == "json" || file.category == FileCategory.TEXT -> "TXT"
        file.category == FileCategory.OFFICE -> "DOC"
        else -> "TXT"
    }
}

private const val CARD_BASE_PATH =
    "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z" +
        "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z"

private fun fileTypeCardIcon(badgeType: String): ImageVector {
    val glyphsPath = when (badgeType) {
        "PDF" ->
            "M7.5 7H10c.83 0 1.5.67 1.5 1.5v1c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7z" +
                "M9 8.5h1v1H9v-1z" +
                "M12.5 7H15c.83 0 1.5.67 1.5 1.5v3c0 .83-.67 1.5-1.5 1.5h-2.5V7z" +
                "M14 8.5h1v3h-1v-3z" +
                "M17.5 7h3v1.5H19v1h1.5V11H19v2h-1.5V7z"

        "DOC" ->
            "M7.5 7H10c.72 0 1.3.58 1.3 1.3v3.4c0 .72-.58 1.3-1.3 1.3H7.5V7z" +
                "M9 8.5h.9v3H9v-3z" +
                "M13.4 7h1.4c.72 0 1.3.58 1.3 1.3v3.4c0 .72-.58 1.3-1.3 1.3h-1.4c-.72 0-1.3-.58-1.3-1.3V8.3c0-.72.58-1.3 1.3-1.3z" +
                "M13.6 8.5h1v3h-1v-3z" +
                "M20.5 7h-2.3c-.72 0-1.3.58-1.3 1.3v3.4c0 .72.58 1.3 1.3 1.3h2.3v-1.5h-2.1v-3h2.1V7z"

        "XLS" ->
            "M7.5 7h1.5l.7 1.7L10.4 7h1.5l-1.45 3 1.45 3h-1.5l-.7-1.7-.7 1.7H7.5l1.45-3L7.5 7z" +
                "M12.7 7h1.5v4.5h1.9V13h-3.4V7z" +
                "M20.5 7h-2.7c-.55 0-1 .45-1 1v1.6c0 .55.45 1 1 1h1.3v1.2h-2.3V13h2.7c.55 0 1-.45 1-1v-1.6c0-.55-.45-1-1-1h-1.3V8.2h2.3V7z"

        "PPT" ->
            "M7.5 7h2.4c.72 0 1.3.58 1.3 1.3v1c0 .72-.58 1.3-1.3 1.3H9v2.4H7.5V7z" +
                "M9 8.4h.8v1H9v-1z" +
                "M12.1 7h2.4c.72 0 1.3.58 1.3 1.3v1c0 .72-.58 1.3-1.3 1.3h-.9v2.4h-1.5V7z" +
                "M13.6 8.4h.8v1h-.8v-1z" +
                "M16.6 7h3.9v1.5H19.3V13h-1.5V8.5h-1.2V7z"

        "CSV" ->
            "M11 7H8.8c-.72 0-1.3.58-1.3 1.3v3.4c0 .72.58 1.3 1.3 1.3H11v-1.5H9v-3h2V7z" +
                "M15.5 7h-2.7c-.55 0-1 .45-1 1v1.6c0 .55.45 1 1 1h1.3v1.2h-2.3V13h2.7c.55 0 1-.45 1-1v-1.6c0-.55-.45-1-1-1h-1.3V8.2h2.3V7z" +
                "M16.3 7h1.5l.6 3.8L19 7h1.5l-1.35 6h-1.5L16.3 7z"

        "XML" ->
            "M7.3 7h1.4l.6 1.6L9.9 7h1.4l-1.3 3 1.3 3H9.9l-.6-1.6L8.7 13H7.3l1.3-3-1.3-3z" +
                "M11.9 7h1.4l.9 2.2L15.1 7h1.4v6h-1.3V9.6l-.9 2h-.2l-.9-2V13h-1.3V7z" +
                "M17.2 7h1.5v4.5h1.8V13h-3.3V7z"

        else ->
            "M7.5 7h3.6v1.5h-1.05V13h-1.5V8.5H7.5V7z" +
                "M11.8 7h1.5l.7 1.7L14.7 7h1.5l-1.45 3 1.45 3h-1.5l-.7-1.7-.7 1.7h-1.5l1.45-3-1.45-3z" +
                "M16.9 7h3.6v1.5h-1.05V13h-1.5V8.5H16.9V7z"
    }

    return ImageVector.Builder(
        name = "FileTypeCard_$badgeType",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(CARD_BASE_PATH + glyphsPath),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black)
    ).build()
}

@Composable
fun RenameFileDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename file") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("File name") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteFileDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete file") },
        text = { Text("Delete $fileName from device storage? This cannot be undone.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteMultipleFilesDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count files") },
        text = {
            Text(
                if (count == 1) {
                    "Delete 1 selected file from device storage? This cannot be undone."
                } else {
                    "Delete $count selected files from device storage? This cannot be undone."
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

