package com.allvie.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val mimeType: String,
    val category: String,
    val size: Long,
    val lastModified: Long,
    val pathLabel: String,
    val bookmarkedAt: Long
)

@Entity(tableName = "recent_files")
data class RecentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val mimeType: String,
    val category: String,
    val size: Long,
    val lastModified: Long,
    val pathLabel: String,
    val lastOpenedAt: Long
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY bookmarkedAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): BookmarkEntity?

    @Query("SELECT bookmarkedAt FROM bookmarks WHERE uri = :uri LIMIT 1")
    suspend fun findTimestamp(uri: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<RecentEntity>>

    @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): RecentEntity?

    @Query("SELECT lastOpenedAt FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun findTimestamp(uri: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentEntity)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
