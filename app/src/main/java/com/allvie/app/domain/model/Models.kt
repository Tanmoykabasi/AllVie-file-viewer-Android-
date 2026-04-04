package com.allvie.app.domain.model

enum class FileCategory(
    val label: String,
    val supportsInlinePreview: Boolean
) {
    ALL("All", false),
    PDF("PDF", true),
    TEXT("Text", true),
    OFFICE("Office", true);

    companion object {
        private val textExtensions = setOf("txt", "xml")
        private val textMimeTypes = setOf("text/plain", "text/xml", "application/xml")
        private val officeExtensions = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")
        private val officeMimeTypes = setOf(
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )

        fun from(mimeType: String?, displayName: String): FileCategory? {
            val normalizedMime = mimeType.orEmpty().lowercase()
            val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return when {
                normalizedMime == "application/pdf" || extension == "pdf" -> PDF
                normalizedMime in officeMimeTypes || extension in officeExtensions -> OFFICE
                normalizedMime in textMimeTypes || extension in textExtensions -> TEXT
                else -> null
            }
        }

        fun fromStoredValue(value: String?): FileCategory? {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

        fun fromPersistedValue(value: String?): FileCategory {
            return fromStoredValue(value) ?: ALL
        }

        fun resolve(categoryValue: String?, mimeType: String?, displayName: String): FileCategory {
            return fromStoredValue(categoryValue) ?: from(mimeType, displayName) ?: ALL
        }
    }
}

data class FileItem(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val category: FileCategory,
    val size: Long,
    val lastModified: Long,
    val pathLabel: String,
    val isBookmarked: Boolean = false
)

enum class LayoutMode {
    GRID,
    LIST;

    companion object {
        fun fromStoredValue(value: String?): LayoutMode {
            return entries.firstOrNull { it.name == value } ?: GRID
        }
    }
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun fromStoredValue(value: String?): ThemeMode {
            return entries.firstOrNull { it.name == value } ?: SYSTEM
        }
    }
}

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val layoutMode: LayoutMode = LayoutMode.GRID
)