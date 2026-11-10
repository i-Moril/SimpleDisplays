package simpledisplays

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import org.w3c.dom.Element
import java.util.IdentityHashMap

/** Invocation-local visual DOM. Only explicit proxies cross the guest boundary. */
internal class DisplayDocument(val root: Element) {
    private val allocated = IdentityHashMap<Element, ElementProxy>()
    private var allocatedNodes = 0
    private var allocatedChars = 0L
    var failure: Throwable? = null
        private set

    init {
        fun visit(element: Element, depth: Int) {
            require(depth <= 32) { "Document is too deeply nested" }
            register(element)
            for (i in 0 until element.childNodes.length) (element.childNodes.item(i) as? Element)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
    }

    private fun register(element: Element): ElementProxy {
        require(++allocatedNodes <= 2048) { "Too many document nodes" }
        for (i in 0 until element.attributes.length) charge(element.attributes.item(i).nodeValue)
        for (i in 0 until element.childNodes.length) {
            val child = element.childNodes.item(i)
            if (child !is Element) charge(child.nodeValue.orEmpty())
        }
        return ElementProxy(element).also { allocated[element] = it }
    }
    private fun charge(text: String) {
        allocatedChars += text.length
        require(allocatedChars <= 131072) { "Document allocation text limit exceeded" }
    }
    private fun <T> guarded(block: () -> T): T = try { block() } catch (error: Throwable) {
        failure = error
        throw error
    }
    private fun callback(block: (Array<out Value>) -> Any?): ProxyExecutable = ProxyExecutable { args -> guarded { block(args) } }
    private fun string(args: Array<out Value>, index: Int): String {
        require(args.size > index && args[index].isString) { "Expected a string argument" }
        return args[index].asString()
    }
    private fun count(args: Array<out Value>, n: Int) { require(args.size == n) { "Expected $n arguments" } }
    private fun node(value: Value): Element {
        require(value.isProxyObject) { "Expected an element from this document" }
        val proxy = value.asProxyObject<ProxyObject>()
        require(proxy is ElementProxy && allocated[proxy.element] === proxy) { "Foreign document element" }
        return proxy.element
    }
    private fun checkChild(parent: Element, child: Element) {
        require(parent.tagName == "screen" || parent.tagName == "div") { "Only screen and div can contain visual children" }
        require(child !== root) { "Cannot move document root" }
        fun subtreeDepth(element: Element): Int {
            var depth = 1
            for (i in 0 until element.childNodes.length) (element.childNodes.item(i) as? Element)?.let {
                depth = maxOf(depth, 1 + subtreeDepth(it))
            }
            return depth
        }
        var parentDepth = 0
        var cursor: org.w3c.dom.Node? = parent
        while (cursor is Element) { parentDepth++; cursor = cursor.parentNode }
        require(parentDepth + subtreeDepth(child) <= 33) { "Document is too deeply nested" }
        var ancestor: org.w3c.dom.Node? = parent
        while (ancestor != null) {
            require(ancestor !== child) { "Document cycle" }
            ancestor = ancestor.parentNode
        }
    }
    private fun allowed(element: Element): Set<String> = when (element.tagName) {
        "screen" -> setOf("id", "width", "height")
        "div" -> setOf("id", "style")
        "span" -> setOf("id", "style", "font", "bind")
        "icon" -> setOf("id", "style", "src")
        "rect" -> setOf("id", "style")
        else -> error("Unsupported visual element <${element.tagName}>")
    }
    private fun setAttribute(element: Element, key: String, value: String) {
        require(key in allowed(element)) { "Unsupported attribute $key" }
        require(element !== root || key == "id") { "Root canvas dimensions are fixed" }
        charge(value)
        element.setAttribute(key, value)
        validate()
    }

    val proxy: ProxyObject = object : ProxyObject {
        private val members = mapOf(
            "documentElement" to allocated.getValue(root),
            "getElementById" to callback { args ->
                count(args, 1)
                val id = string(args, 0)
                fun find(element: Element): Element? {
                    if (id.isNotEmpty() && element.getAttribute("id") == id) return element
                    for (i in 0 until element.childNodes.length) {
                        val child = element.childNodes.item(i) as? Element ?: continue
                        find(child)?.let { return it }
                    }
                    return null
                }
                find(root)?.let { allocated.getValue(it) }
            },
            "createElement" to callback { args ->
                count(args, 1)
                val tag = string(args, 0)
                require(tag in setOf("div", "span", "icon", "rect")) { "Unsupported visual element $tag" }
                register(root.ownerDocument.createElement(tag))
            },
        )
        override fun getMember(key: String): Any? = members[key]
        override fun getMemberKeys(): Any = members.keys.toTypedArray()
        override fun hasMember(key: String) = key in members
        override fun putMember(key: String, value: Value) = guarded<Unit> { error("Read-only document API") }
        override fun removeMember(key: String): Boolean = guarded { error("Cannot delete document members") }
    }

    private inner class ElementProxy(val element: Element) : ProxyObject {
        private val methods = mapOf(
            "getAttribute" to callback { args -> count(args, 1); val key = string(args, 0); if (element.hasAttribute(key)) element.getAttribute(key) else null },
            "setAttribute" to callback { args -> count(args, 2); setAttribute(element, string(args, 0), string(args, 1)); null },
            "appendChild" to callback { args -> count(args, 1); val child = node(args[0]); checkChild(element, child); element.appendChild(child); validate(); allocated.getValue(child) },
            "remove" to callback { args -> count(args, 0); require(element !== root) { "Cannot remove document root" }; element.parentNode?.removeChild(element); null },
            "replaceChildren" to callback { args ->
                require(element.tagName in setOf("screen", "div")) { "Only containers accept visual children" }
                val children = args.map { node(it) }
                require(children.distinct().size == children.size) { "Duplicate child" }
                children.forEach { checkChild(element, it) }
                while (element.hasChildNodes()) element.removeChild(element.firstChild)
                children.forEach { element.appendChild(it) }
                validate()
                null
            },
        )
        override fun getMember(key: String): Any? = when (key) {
            "id" -> element.getAttribute("id")
            "tagName" -> element.tagName
            "textContent" -> element.textContent
            else -> methods[key]
        }
        override fun getMemberKeys(): Any = (methods.keys + setOf("id", "tagName", "textContent")).toTypedArray()
        override fun hasMember(key: String) = key in methods || key in setOf("id", "tagName", "textContent")
        override fun removeMember(key: String): Boolean = guarded { error("Cannot delete element members") }
        override fun putMember(key: String, value: Value) = guarded<Unit> {
            require(value.isString) { "$key must be a string" }
            when (key) {
                "id" -> setAttribute(element, "id", value.asString())
                "textContent" -> {
                    require(element.tagName == "span" || element.tagName == "div") { "Element does not accept textContent" }
                    val text = value.asString()
                    require(element.tagName == "span" || text.isBlank()) { "Text belongs in a span" }
                    charge(text)
                    element.textContent = text
                }
                else -> error("Read-only or unknown element property $key")
            }
        }
    }

    fun validate() {
        val ids = mutableSetOf<String>()
        var outputText = 0L
        fun visit(element: Element, depth: Int) {
            require(depth <= 32) { "Document is too deeply nested" }
            val attrs = allowed(element)
            for (i in 0 until element.attributes.length) require(element.attributes.item(i).nodeName in attrs) { "Unsupported element attribute" }
            val id = element.getAttribute("id")
            require(id.isEmpty() || ids.add(id)) { "Duplicate element ID: $id" }
            if (element.tagName == "span") {
                outputText += element.textContent.length
                require(outputText <= 65536) { "Too much output text" }
                val font = element.getAttribute("font")
                require(font.isEmpty() || (font.length <= 256 && font.matches(Regex("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+")))) { "Invalid font key" }
            }
            for (i in 0 until element.childNodes.length) {
                val child = element.childNodes.item(i)
                if (child is Element) {
                    require(element.tagName in setOf("screen", "div", "span")) { "Only containers and spans can contain visual children" }
                    visit(child, depth + 1)
                } else if (child.nodeType == org.w3c.dom.Node.TEXT_NODE || child.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    require(element.tagName == "span" || child.nodeValue.isBlank()) { "Text belongs in a span" }
                }
            }
        }
        visit(root, 0)
    }
}
