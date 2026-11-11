package simpledisplays

import simpledisplays.api.TextDraw

/** Shared slot identity bridges the anchor's frame task and each entity's owning task. */
internal class PreviewElement(@Volatile var node: TextDraw)

internal data class PreviewChanges(val added: List<PreviewElement>)

/** Validate a whole frame before changing any desired preview elements. */
internal fun reconcilePreview(slots: MutableMap<String, PreviewElement>, frame: List<TextDraw>): PreviewChanges {
    require(frame.map { it.key }.toSet().size == frame.size) { "Duplicate draw key" }
    val keys = frame.mapTo(mutableSetOf()) { it.key }
    val added = mutableListOf<PreviewElement>()
    for (node in frame) {
        val slot = slots[node.key]
        if (slot == null) {
            val created = PreviewElement(node)
            slots[node.key] = created
            added += created
        } else slot.node = node
    }
    slots.keys.toList().filter { it !in keys }.forEach { slots.remove(it) }
    return PreviewChanges(added)
}

internal fun previewDepth(zIndex: Int, width: Double, height: Double, unit: Double): Double =
    zIndex / 63.0 * minOf(width, height) * unit * 0.01
