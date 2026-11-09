package simpledisplays

import net.kyori.adventure.text.Component
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import simpledisplays.api.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Restricted execution, not an independent memory sandbox. Host callbacks must terminate promptly. */
internal class JavaScriptRuntime(
    private val deadlineMillis: Long = 250,
    private val initializationDeadlineMillis: Long = 3000,
) : AutoCloseable {
    private val engine = Engine.newBuilder().build()
    private val timer = Executors.newScheduledThreadPool(4) { job ->
        Thread(job, "simpledisplays-js-deadline").apply { isDaemon = true }
    }
    private val slots = Semaphore(4)
    private val templates = ConcurrentHashMap.newKeySet<Template>()
    private var closed = false

    fun compile(
        code: String, root: org.w3c.dom.Element, width: Double, height: Double,
        drawDocument: (org.w3c.dom.Element, Map<String, String>, Long) -> List<TextDraw>,
    ): DisplayTemplate {
        require(code.length <= 131072) { "Script exceeds 131072 characters" }
        val template = Template(Source.newBuilder("js", code, "display.js").buildLiteral(), root, width, height, drawDocument)
        synchronized(this) { check(!closed) { "Display service is closed" }; templates.add(template) }
        try {
            // Parsing validates syntax without evaluating top-level ctx/document-dependent code.
            template.execute(initializationDeadlineMillis) { it.parse(template.source); Unit }
            return template
        } catch (error: Throwable) { template.close(); throw error }
    }

    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            templates.toList()
        }
        try {
            pending.forEach { it.close() }
        } finally {
            templates.clear()
            timer.shutdownNow()
            engine.close(true)
        }
    }

    private inner class Template(
        val source: Source,
        private val originalRoot: org.w3c.dom.Element,
        override val width: Double,
        override val height: Double,
        private val drawDocument: (org.w3c.dom.Element, Map<String, String>, Long) -> List<TextDraw>,
    ) : DisplayTemplate {
        private var disposed = false
        private val active = ConcurrentHashMap.newKeySet<Context>()

        fun <T> execute(budgetMillis: Long = deadlineMillis, action: (Context) -> T): T {
            check(slots.tryAcquire()) { "Too many concurrent script draws" }
            var context: Context? = null
            try {
                context = Context.newBuilder("js").engine(engine)
                    .allowHostAccess(HostAccess.NONE).allowHostClassLookup { false }
                    .allowIO(IOAccess.NONE).allowNativeAccess(false).allowCreateThread(false)
                    .allowCreateProcess(false).allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                    .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE).build()
                synchronized(this) {
                    check(!disposed) { "Template is closed" }
                    active.add(context)
                }
                val expired = AtomicBoolean(false)
                val cancel = timer.schedule({ expired.set(true); context.close(true) }, budgetMillis, TimeUnit.MILLISECONDS)
                try {
                    val result = action(context)
                    check(!expired.get()) { "Script exceeded execution deadline" }
                    return result
                } finally {
                    cancel.cancel(false)
                }
            } finally {
                context?.let { active.remove(it); it.close(true) }
                slots.release()
            }
        }

        override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> =
            draw(DisplayContext(data = values, elapsedTicks = elapsedTicks))

        override fun draw(context: DisplayContext): List<TextDraw> {
            val snapshot = Snapshot()
            val data = snapshot.copy(context.data)
            val viewerData = snapshot.copy(context.viewerData)
            val values = context.data.filterValues { it is String }.mapValues { it.value as String }
            require((context.viewerId?.length ?: 0) <= 1024) { "Viewer identity too long" }
            // w3c Document is not thread safe even for cloneNode. Copy only under this short lock.
            val root = synchronized(originalRoot) {
                val detached = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
                (detached.importNode(originalRoot, true) as org.w3c.dom.Element).also { detached.appendChild(it) }
            }
            val document = DisplayDocument(root)
            var callbackFailure: Throwable? = null
            val ctx = readOnly(mapOf(
                "data" to data, "viewerData" to viewerData, "viewerId" to context.viewerId,
                "elapsedTicks" to context.elapsedTicks.toDouble(), "width" to width, "height" to height,
                "placeholder" to ProxyExecutable { args ->
                    try {
                        require(args.size == 1 && args[0].isString) { "placeholder requires one string" }
                        val token = args[0].asString()
                        require(token.length <= 1024) { "Placeholder token too long" }
                        val resolved = checkNotNull(context.placeholderResolver) { "No placeholder resolver configured" }.resolve(token)
                        require(resolved.length <= 65536) { "Placeholder result too long" }
                        resolved
                    } catch (error: Throwable) { callbackFailure = error; throw error }
                },
            ), { callbackFailure = it })
            return execute { guest ->
                val bindings = guest.getBindings("js")
                bindings.putMember("ctx", ctx)
                bindings.putMember("document", document.proxy)
                guest.eval(source)
                val render = bindings.getMember("render")
                if (render != null && !render.isNull) {
                    require(render.canExecute()) { "render must be a function" }
                    val returned = render.execute(ctx)
                    require(returned.isNull) { "render must be synchronous and return undefined or null" }
                }
                document.failure?.let { throw it }
                callbackFailure?.let { throw it }
                snapshot.failure?.let { throw it }
                document.validate()
                drawDocument(root, values, context.elapsedTicks).also { output ->
                    var chars = 0L
                    for (node in output) {
                        require(node.x.isFinite() && node.y.isFinite() && node.size.isFinite() && node.size > 0) { "Invalid derived geometry" }
                        fun textLength(component: Component): Long =
                            (if (component is net.kyori.adventure.text.TextComponent) component.content().length.toLong() else 0L) + component.children().sumOf { textLength(it) }
                        chars += textLength(node.text)
                    }
                    require(chars <= 65536) { "Too much output text" }
                }
            }
        }

        override fun close() {
            val contexts = synchronized(this) { disposed = true; active.toList() }
            try {
                contexts.forEach { it.close(true) }
            } finally {
                templates.remove(this)
            }
        }
    }

    private fun readOnly(values: Map<String, Any?>, failed: (Throwable) -> Unit = {}): ProxyObject = object : ProxyObject {
        override fun getMember(key: String): Any? = values[key]
        override fun getMemberKeys(): Any = values.keys.toTypedArray()
        override fun hasMember(key: String) = values.containsKey(key)
        override fun removeMember(key: String): Boolean {
            val error = UnsupportedOperationException("Read-only input")
            failed(error)
            throw error
        }
        override fun putMember(key: String, value: Value) {
            val error = UnsupportedOperationException("Read-only input")
            failed(error)
            throw error
        }
    }
    private inner class Snapshot {
        var failure: Throwable? = null
        private var nodes = 0
        private var chars = 0L
        fun copy(value: Any?, depth: Int = 0): Any? {
            require(++nodes <= 4096 && depth <= 32) { "Input too large or deeply nested" }
            return when (value) {
                null, is Boolean -> value
                is String -> value.also { chars += it.length; require(chars <= 131072) { "Input text too large" } }
                is Number -> {
                    require(value is Byte || value is Short || value is Int || value is Long || value is Float || value is Double) { "Unsupported number" }
                    value.toDouble().also { require(it.isFinite()) { "Nonfinite input" } }
                }
                is Map<*, *> -> {
                    require(value.size <= 4096) { "Too many input entries" }
                    readOnly(value.entries.associate { (key, item) ->
                        require(key is String) { "Input keys must be strings" }
                        copy(key, depth + 1)
                        key to copy(item, depth + 1)
                    }, { failure = it })
                }
                is List<*> -> {
                    require(value.size <= 4096) { "Too many input items" }
                    val items = value.map { copy(it, depth + 1) }
                    object : ProxyArray {
                        override fun get(index: Long): Any? = items[index.toInt()]
                        override fun getSize() = items.size.toLong()
                        override fun remove(index: Long): Boolean {
                            val error = UnsupportedOperationException("Read-only input")
                            failure = error
                            throw error
                        }
                        override fun set(index: Long, value: Value) {
                            val error = UnsupportedOperationException("Read-only input")
                            failure = error
                            throw error
                        }
                    }
                }
                else -> throw IllegalArgumentException("Unsupported input value")
            }
        }
    }
}
