package com.allvie.app.data.repository

import com.allvie.app.data.local.BookmarkDao
import com.allvie.app.data.local.BookmarkEntity
import com.allvie.app.data.local.RecentDao
import com.allvie.app.data.local.RecentEntity
import com.allvie.app.domain.model.FileCategory
import com.allvie.app.domain.model.FileItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class HistoryRepository @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val recentDao: RecentDao
) {

    fun observeBookmarks(): Flow<List<FileItem>> {
        return bookmarkDao.observeAll().map { entities ->
            entities.map { entity -> entity.toFileItem().copy(isBookmarked = true) }
        }
    }

    fun observeRecents(): Flow<List<FileItem>> {
        return recentDao.observeAll().map { entities ->
            entities.map { entity -> entity.toFileItem() }
        }
    }

    fun observeBookmarkedUris(): Flow<Set<String>> {
        return bookmarkDao.observeAll().map { entities -> entities.mapTo(mutableSetOf()) { it.uri } }
    }

    suspend fun toggleBookmark(file: FileItem): Boolean {
        val existing = bookmarkDao.findByUri(file.uriString)
        return if (existing == null) {
            bookmarkDao.insert(file.toBookmarkEntity())
            true
        } else {
            bookmarkDao.deleteByUri(file.uriString)
            false
        }
    }

    suspend fun addRecent(file: FileItem) {
        recentDao.insert(file.toRecentEntity())
    }

    suspend fun removeFile(uriString: String) {
        bookmarkDao.deleteByUri(uriString)
        recentDao.deleteByUri(uriString)
    }

    suspend fun replaceFile(oldUri: String, file: FileItem) {
        val bookmarkedAt = bookmarkDao.findTimestamp(oldUri) ?: bookmarkDao.findTimestamp(file.uriString)
        val recentAt = recentDao.findTimestamp(oldUri) ?: recentDao.findTimestamp(file.uriString)

        if (oldUri != file.uriString) {
            bookmarkDao.deleteByUri(oldUri)
            recentDao.deleteByUri(oldUri)
        }

        bookmarkedAt?.let { timestamp ->
            bookmarkDao.insert(file.toBookmarkEntity(timestamp))
        }
        recentAt?.let { timestamp ->
            recentDao.insert(file.toRecentEntity(timestamp))
        }
    }
}

private fun BookmarkEntity.toFileItem(): FileItem {
    return FileItem(
        uriString = uri,
        displayName = displayName,
        mimeType = mimeType,
        category = FileCategory.fromPersistedValue(category),
        size = size,
        lastModified = lastModified,
        pathLabel = pathLabel,
        isBookmarked = true
    )
}

private fun RecentEntity.toFileItem(): FileItem {
    return FileItem(
        uriString = uri,
        displayName = displayName,
        mimeType = mimeType,
        category = FileCategory.fromPersistedValue(category),
        size = size,
        lastModified = lastModified,
        pathLabel = pathLabel
    )
}

private fun FileItem.toBookmarkEntity(bookmarkedAt: Long = System.currentTimeMillis()): BookmarkEntity {
    return BookmarkEntity(
        uri = uriString,
        displayName = displayName,
        mimeType = mimeType,
        category = category.name,
        size = size,
        lastModified = lastModified,
        pathLabel = pathLabel,
        bookmarkedAt = bookmarkedAt
    )
}

private fun FileItem.toRecentEntity(lastOpenedAt: Long = System.currentTimeMillis()): RecentEntity {
    return RecentEntity(
        uri = uriString,
        displayName = displayName,
        mimeType = mimeType,
        category = category.name,
        size = size,
        lastModified = lastModified,
        pathLabel = pathLabel,
        lastOpenedAt = lastOpenedAt
    )
}
