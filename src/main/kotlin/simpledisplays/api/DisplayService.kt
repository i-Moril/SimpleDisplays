package simpledisplays.api

/** Bukkit service for compiling and evaluating screens independently of their renderer. */
interface DisplayService : AutoCloseable {
    fun compile(xml: String, icons: Map<String, List<IconFrame>> = emptyMap()): DisplayTemplate
    override fun close() {}
}
