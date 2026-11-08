package simpledisplays.api

import net.kyori.adventure.text.Component

data class IconFrame(val text: Component, val ticks: Int) {
    init {
        require(ticks > 0) { "Icon frame duration must be positive" }
    }
}
