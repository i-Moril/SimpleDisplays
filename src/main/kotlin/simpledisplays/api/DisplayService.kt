package simpledisplays.api

/** Bukkit service for compiling and evaluating screens independently of their renderer. */
interface DisplayService {
    fun compile(xml: String, icons: Map<String, List<IconFrame>> = emptyMap()): DisplayTemplate
}
