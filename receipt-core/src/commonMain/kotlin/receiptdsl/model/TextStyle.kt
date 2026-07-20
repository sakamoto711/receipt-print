package receiptdsl.model

public data class TextStyle(
    val widthScale: Int = 1,
    val heightScale: Int = 1,
    val emphasized: Boolean = false,
    val inverted: Boolean = false,
    val align: TextAlign = TextAlign.Left,
) {
    public companion object {
        public val Default: TextStyle = TextStyle()
    }
}

public enum class TextAlign {
    Left,
    Center,
    Right,
}
