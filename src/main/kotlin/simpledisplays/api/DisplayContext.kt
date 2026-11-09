package simpledisplays.api

/**
 * data and viewerData accept string-keyed maps, lists, null, booleans, strings and finite numbers.
 * They are detached at invocation; callers must not mutate them while draw is copying them.
 * No Player or other live host object is exposed to scripts. viewerId is an opaque caller identity.
 * The optional resolver executes synchronously on the draw caller's thread, so the caller must
 * satisfy the external provider's threading contract. No PlaceholderAPI dependency is implied.
 */
data class DisplayContext(
    val data: Map<String, Any?> = emptyMap(),
    val viewerId: String? = null,
    val viewerData: Map<String, Any?> = emptyMap(),
    val elapsedTicks: Long = 0,
    val placeholderResolver: PlaceholderResolver? = null,
)

fun interface PlaceholderResolver {
    fun resolve(token: String): String
}
