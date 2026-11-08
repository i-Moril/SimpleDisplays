package simpledisplays

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.w3c.dom.Element
import org.xml.sax.InputSource
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.IconFrame
import simpledisplays.api.TextDraw

internal class XmlDisplayService : DisplayService {
    private class Node(
        val x: Double,
        val y: Double,
        val size: Double,
        val animated: Boolean = false,
        val text: (Map<String, String>, Long) -> Component,
    ) {
        fun draw(values: Map<String, String>, ticks: Long) = TextDraw(x, y, size, text(values, ticks), animated)
    }

    override fun compile(xml: String, icons: Map<String, List<IconFrame>>): DisplayTemplate {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        require(root.tagName == "screen") { "Expected a <screen> root" }
        val width = length(root.getAttribute("width"), positive = true)
        val height = length(root.getAttribute("height"), positive = true)
        val nodes = buildList { readChildren(root, 0.0, 0.0, icons, this) }
        return object : DisplayTemplate {
            override val width = width
            override val height = height

            override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> =
                nodes.map { it.draw(values, elapsedTicks) }
        }
    }

    private fun readChildren(
        parent: Element,
        x: Double,
        y: Double,
        icons: Map<String, List<IconFrame>>,
        output: MutableList<Node>,
    ) {
        for (i in 0 until parent.childNodes.length) {
            val element = parent.childNodes.item(i) as? Element ?: continue
            val style = element.getAttribute("style").split(';').filter { it.isNotBlank() }.associate {
                val parts = it.split(':', limit = 2)
                require(parts.size == 2) { "Invalid style declaration: $it" }
                parts[0].trim() to parts[1].trim()
            }
            val supported = when (element.tagName) {
                "div" -> setOf("left", "top")
                "span" -> setOf("left", "top", "font-size", "color")
                "icon" -> setOf("left", "top", "width", "height")
                else -> error("Unsupported element <${element.tagName}>")
            }
            require(supported.containsAll(style.keys)) { "Unsupported style on <${element.tagName}>" }
            val left = x + length(style["left"] ?: "0")
            val top = y + length(style["top"] ?: "0")
            when (element.tagName) {
                "div" -> readChildren(element, left, top, icons, output)
                "span" -> {
                    val size = length(style["font-size"] ?: "16", positive = true)
                    val font = Key.key(element.getAttribute("font").ifBlank { "minecraft:default" })
                    val color = style["color"]?.let {
                        requireNotNull(TextColor.fromHexString(it)) { "Invalid text color: $it" }
                    }
                    val fallback = Component.text(element.textContent.trim()).font(font).color(color)
                    val binding = element.getAttribute("bind").trim()
                    output += Node(left, top, size) { values, _ ->
                        values[binding]?.takeIf { binding.isNotEmpty() }
                            ?.let { fallback.content(it) } ?: fallback
                    }
                }
                "icon" -> {
                    val source = element.getAttribute("src")
                    val frames = requireNotNull(icons[source]) { "Unknown icon: $source" }.toList()
                    require(frames.isNotEmpty()) { "Icon must contain at least one frame: $source" }
                    val cycleTicks = frames.sumOf { it.ticks.toLong() }
                    val width = length(style["width"] ?: "16", positive = true)
                    val height = length(style["height"] ?: "16", positive = true)
                    val size = minOf(width, height)
                    output += Node(left + width / 2, top + (height - size) / 2, size, frames.size > 1) { _, ticks ->
                        var offset = Math.floorMod(ticks, cycleTicks)
                        frames.first { frame ->
                            offset -= frame.ticks
                            offset < 0
                        }.text
                    }
                }
            }
        }
    }

    private fun length(value: String, positive: Boolean = false): Double {
        val number = value.toDoubleOrNull()
        require(number != null && number.isFinite() && (!positive || number > 0)) {
            "Invalid logical length: $value"
        }
        return number
    }
}
