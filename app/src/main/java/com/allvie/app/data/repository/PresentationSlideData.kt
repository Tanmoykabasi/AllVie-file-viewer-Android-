package com.allvie.app.data.repository

const val DEFAULT_SLIDE_WIDTH_EMU = 9_144_000L
const val DEFAULT_SLIDE_HEIGHT_EMU = 6_858_000L

data class PresentationSlideData(
    val index: Int,
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val widthEmu: Long = DEFAULT_SLIDE_WIDTH_EMU,
    val heightEmu: Long = DEFAULT_SLIDE_HEIGHT_EMU,
    val backgroundColorHex: String? = null,
    val elements: List<PresentationElementData> = emptyList(),
    val renderedImageUri: String? = null
)

data class PresentationElementData(
    val kind: PresentationElementKind,
    val xEmu: Long,
    val yEmu: Long,
    val widthEmu: Long,
    val heightEmu: Long,
    val text: String = "",
    val imageUri: String? = null,
    val fontSizeSp: Float? = null,
    val isBold: Boolean = false,
    val textAlign: PresentationTextAlign = PresentationTextAlign.START,
    val textColorHex: String? = null,
    val fillColorHex: String? = null
)

enum class PresentationElementKind {
    TEXT,
    IMAGE
}

enum class PresentationTextAlign {
    START,
    CENTER,
    END
}
