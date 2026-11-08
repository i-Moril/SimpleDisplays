package simpledisplays.api

import net.kyori.adventure.text.Component

/** Text positioned by its horizontal center and top, measured from the canvas's top-left. */
data class TextDraw(
    val x: Double,
    val y: Double,
    val size: Double,
    val text: Component,
    val animated: Boolean,
) {
    companion object {
        const val UNITS_PER_BLOCK = 128.0
    }
}
