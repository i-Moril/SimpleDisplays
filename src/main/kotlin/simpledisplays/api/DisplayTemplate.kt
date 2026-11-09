package simpledisplays.api

interface DisplayTemplate : AutoCloseable {
    val width: Double
    val height: Double

    /** Returns the screen at the given elapsed tick, in document order. */
    fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw>
    /** Evaluates an isolated document for this viewer. String data entries also feed XML bind. */
    fun draw(context: DisplayContext): List<TextDraw> = draw(
        context.data.filterValues { it is String }.mapValues { it.value as String }, context.elapsedTicks)

    override fun close() {}
}
