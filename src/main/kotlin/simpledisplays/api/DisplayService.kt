package simpledisplays.api

import net.kyori.adventure.text.Component

/** Bukkit service for compiling and evaluating screens independently of their renderer. */
interface DisplayService {
    fun compile(xml: String, icons: Map<String, List<IconFrame>> = emptyMap()): DisplayTemplate
}

data class IconFrame(val text: Component, val ticks: Int) {
    init {
        require(ticks > 0) { "Icon frame duration must be positive" }
    }
}

interface DisplayTemplate {
    val width: Double
    val height: Double

    /** Returns the screen at the given elapsed tick, in document order. */
    fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw>
}

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
