package simpledisplays.api

interface DisplayTemplate {
    val width: Double
    val height: Double

    /** Returns the screen at the given elapsed tick, in document order. */
    fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw>
}
