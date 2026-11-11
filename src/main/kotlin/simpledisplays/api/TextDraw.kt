package simpledisplays.api

import net.kyori.adventure.text.Component

/** Text positioned by its horizontal center and top, measured from the canvas's top-left. */
data class TextDraw(
    /** Stable logical primitive identity within one frame, independent of its list position. */
    val key: String,
    val x: Double,
    val y: Double,
    val size: Double,
    val text: Component,
    val animated: Boolean,
    /** Multipliers applied to the native text transform after the nominal size. */
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    /** Absolute layer; renderers use fixed spacing rather than frame size/order. */
    val zIndex: Int = 0,
) {
    init {
        require(key.isNotBlank() && key.length <= 1024) { "Invalid draw key" }
        require(zIndex in 0..63) { "z-index must be an integer in 0..63" }
    }

    companion object {
        const val UNITS_PER_BLOCK = 128.0
    }
}
