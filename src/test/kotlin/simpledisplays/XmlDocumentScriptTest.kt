package simpledisplays

import kotlin.test.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.junit.jupiter.api.Test
import simpledisplays.api.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class XmlDocumentScriptTest {
    private fun text(draws: List<TextDraw>) = draws.map { (it.text as TextComponent).content() }
    private fun xml(script: String, body: String = "<span id='title'>Static</span>") =
        "<screen width='256' height='128'>$body<script><![CDATA[$script]]></script></screen>"

    @Test fun `legacy XML preserves layout binding nested text and icons`() {
        XmlDisplayService().use { service ->
            val template = service.compile("""<screen width="256" height="128"><div style="left: 10; top: 20"><span bind="name" style="left: 30; font-size: 24; color: #00ff00">A<span>B</span></span><icon src="logo" style="width: 20; height: 40"/></div></screen>""", mapOf("logo" to listOf(IconFrame(Component.text("X"), 2), IconFrame(Component.text("Y"), 3))))
            assertEquals(listOf("AB", "X"), text(template.draw(emptyMap(), 0)))
            val output = template.draw(mapOf("name" to "bound"), 2)
            assertEquals(listOf("bound", "Y"), text(output))
            assertEquals(40.0, output[0].x); assertEquals(20.0, output[0].y)
            assertEquals(20.0, output[1].x); assertEquals(30.0, output[1].y)
            assertTrue(output[1].animated)
            assertEquals(listOf("context", "X"), text(template.draw(DisplayContext(data = mapOf("name" to "context")))))
        }
    }
    @Test fun `compile parses without executing top level code and scripts run in order`() {
        XmlDisplayService().use { service ->
            val source = """<screen width="256" height="128"><span id="title"/><script>const label=ctx.data.name;</script><script>document.getElementById('title').textContent=label+'!';</script></screen>"""
            service.compile(source).use { template ->
                assertEquals(listOf("Alice!"), text(template.draw(DisplayContext(data = mapOf("name" to "Alice")))))
            }
            assertFails { service.compile(xml("function broken( {")) }
        }
    }
    @Test fun `render uses structured lists and each viewer and frame is isolated`() {
        XmlDisplayService().use { service ->
            val script = """let n=0; function render(ctx) {
              const title=document.getElementById('title'); title.textContent=ctx.viewerId+':'+(++n)+':'+ctx.viewerData.locale;
              const list=document.getElementById('list'); list.replaceChildren();
              ctx.data.stops.slice(0,2).forEach((stop,i)=>{const row=document.createElement('span');row.id='stop:'+stop.name;row.textContent=stop.name;row.setAttribute('style','top: '+(i*20));list.appendChild(row);});
            }"""
            service.compile(xml(script, "<span id='title'/><div id='list'/>")).use { template ->
                fun draw(id: String) = text(template.draw(DisplayContext(mapOf("stops" to listOf(mapOf("name" to "Central"),mapOf("name" to "Park"))),id,mapOf("locale" to "en"))))
                assertEquals(listOf("alice:1:en", "Central", "Park"), draw("alice"))
                assertEquals(listOf("bob:1:en", "Central", "Park"), draw("bob"))
                assertEquals(listOf("alice:1:en", "Central", "Park"), draw("alice"))
            }
        }
    }
    @Test fun `document supports IDs attributes moves removals and replacement`() {
        XmlDisplayService().use { service ->
            val script = """
                if(document.getElementById('missing')!==null) throw Error('missing');
                const old=document.getElementById('title'); old.remove();
                const span=document.createElement('span'); span.id='title'; span.textContent='New';
                span.setAttribute('style','left: 64; top: 12; font-size: 20');
                if(span.tagName!=='span'||span.getAttribute('id')!=='title'||span.getAttribute('missing')!==null) throw Error('attrs');
                document.documentElement.appendChild(span);
                const div=document.createElement('div');document.documentElement.appendChild(div);div.appendChild(span);
                div.replaceChildren(span);
            """
            val output = service.compile(xml(script)).draw(DisplayContext())
            assertEquals(listOf("New"), text(output)); assertEquals(64.0, output.single().x); assertEquals(12.0, output.single().y)
        }
    }
    @Test fun `context ticks placeholder and bind override work`() {
        XmlDisplayService().use { service ->
            val script = """function render(ctx) { const e=document.getElementById('title'); e.setAttribute('bind',''); e.textContent=ctx.placeholder('%name%')+':'+ctx.elapsedTicks+':'+ctx.width+'x'+ctx.height; }"""
            val template = service.compile(xml(script,"<span id='title' bind='name'>Static</span>"))
            assertEquals(listOf("Alice:20:256x128"), text(template.draw(DisplayContext(mapOf("name" to "Legacy"),elapsedTicks=20,placeholderResolver=PlaceholderResolver { "Alice" }))))
            assertFails { template.draw(DisplayContext()) }
        }
    }
    @Test fun `invalid document mutations cannot be hidden by catching errors`() {
        val invalid = listOf(
            "document.createElement('script')",
            "delete document.documentElement",
            "delete document.getElementById('title').textContent",
            "document.documentElement.setAttribute('width','1')",
            "document.documentElement.remove()",
            "document.documentElement.appendChild({})",
            "const d=document.createElement('div'); d.appendChild(d)",
            "const d=document.createElement('div');document.documentElement.appendChild(d); d.appendChild(document.documentElement)",
            "const d=document.createElement('span');d.id='title';document.documentElement.appendChild(d)",
            "document.getElementById('title').setAttribute('onclick','x')",
            "document.getElementById('title').tagName='div'",
            "document.getElementById('title').appendChild(document.createElement('span'))",
            "document.getElementById('title').textContent=1",
            "document.getElementById('title').setAttribute('font','minecraft:')",
        )
        XmlDisplayService().use { service ->
            for (statement in invalid) {
                val source = xml("try {$statement;} catch(e) {}")
                service.compile(source).use { template -> assertFails(statement) { template.draw(DisplayContext()) } }
            }
        }
    }
    @Test fun `invalid final layout and icons reject entire frame`() {
        XmlDisplayService().use { service ->
            for (style in listOf("left: NaN", "font-size: -1", "top: Infinity", "color: bad", "unknown: 1")) {
                val source = xml("document.getElementById('title').setAttribute('style','$style');")
                assertFails(style) { service.compile(source).draw(DisplayContext()) }
            }
            assertFails { service.compile(xml("const i=document.createElement('icon');i.setAttribute('src','missing');document.documentElement.appendChild(i);")).draw(DisplayContext()) }
            assertFails { service.compile(xml("document.getElementById('title').setAttribute('style','left: 1e308');", "<div style='left: 1e308'><span id='title'/></div>")).draw(DisplayContext()) }
        }
    }
    @Test fun `input snapshot is read only detached and does not expose Java objects`() {
        XmlDisplayService().use { service ->
            val source = xml("document.getElementById('title').textContent=ctx.data.name+ctx.placeholder('x');", "<span id='title'/><span bind='name'/>")
            val data = mutableMapOf<String,Any?>("name" to "before")
            val template = service.compile(source)
            val output = template.draw(DisplayContext(data, placeholderResolver=PlaceholderResolver { data["name"]="after"; "!" }))
            assertEquals(listOf("before!", "before"), text(output))
            for (script in listOf("try {ctx.data.name='changed'}catch(e){}", "try {ctx.data.items[0]='changed'}catch(e){}", "try {ctx.viewerId='changed'}catch(e){}", "try {delete ctx.data.name}catch(e){}")) {
                assertFails(script) { service.compile(xml(script)).draw(DisplayContext(mapOf("name" to "x","items" to listOf("a")))) }
            }
            // Graal rejects deleting array slots before invoking ProxyArray.remove; input stays unchanged.
            val deletion = service.compile(xml("try {delete ctx.data.items[0]}catch(e){};document.getElementById('title').textContent=ctx.data.items[0]"))
            assertEquals(listOf("a"), text(deletion.draw(DisplayContext(mapOf("items" to listOf("a"))))))
            assertFails { template.draw(DisplayContext(mapOf("live" to Any()))) }
            assertFails { template.draw(DisplayContext(mapOf("nan" to Double.NaN))) }
            assertFails { service.compile(xml("Java.type('java.lang.System').exit(0)")).draw(DisplayContext()) }
            assertFails { service.compile(xml("load('file:///tmp/no')")).draw(DisplayContext()) }
        }
    }
    @Test fun `async generator and nonvoid render results are rejected`() {
        XmlDisplayService().use { service ->
            for (script in listOf("async function render(ctx) {}", "function* render(ctx) {yield 1}","function render(ctx){return 1}","var render=1")) {
                assertFails { service.compile(xml(script)).draw(DisplayContext()) }
            }
        }
    }
    @Test fun `source and render loops time out and runtime remains usable`() {
        XmlDisplayService().use { service ->
            for (script in listOf("while(true){}", "function render(ctx){while(true){}}")) {
                service.compile(xml(script)).use { template -> assertFails { template.draw(DisplayContext()) } }
            }
            assertEquals(listOf("OK"), text(service.compile(xml("document.getElementById('title').textContent='OK'")).draw(DisplayContext())))
        }
    }
    @Test fun `document allocation output source and input limits are enforced`() {
        XmlDisplayService().use { service ->
            for(script in listOf(
                "for(let i=0;i<2048;i++) document.createElement('span')",
                "for(let i=0;i<3;i++) document.getElementById('title').textContent='x'.repeat(60000)",
                "document.getElementById('title').textContent='x'.repeat(65537)",
                "let p=document.createElement('div');for(let i=0;i<40;i++){const c=document.createElement('div');p.appendChild(c);p=c}",
            )) assertFails { service.compile(xml(script)).draw(DisplayContext()) }
            assertFails { service.compile(xml("/".repeat(131073))) }
            assertFails { service.compile(xml(";")).draw(DisplayContext(mapOf("data" to "x".repeat(131073)))) }
            assertFails { service.compile(xml(";", (0..1024).joinToString("") {"<span/>"})) }
        }
    }
    @Test fun `raw XML depth is rejected before recursive traversal`() {
        XmlDisplayService().use { service ->
            assertFails { service.compile("<screen width='1' height='1'>" + "<div>".repeat(10000) + "</div>".repeat(10000) + "</screen>") }
            assertFails { service.compile("<screen width='1' height='1'>" + "<div>".repeat(40) + "</div>".repeat(40) + "</screen>") }
        }
    }
    @Test fun `template and service close reject later scripted use`() {
        val service = XmlDisplayService()
        val template = service.compile(xml(";"))
        template.close(); template.close()
        assertFails { template.draw(DisplayContext()) }
        val second=service.compile(xml(";"))
        service.close();service.close()
        assertFails { second.draw(DisplayContext()) }
        assertFails { service.compile(xml(";")) }
        val static=XmlDisplayService();static.compile("<screen width='1' height='1'/>");static.close();assertFails {static.compile(xml(";"))}
    }
    @Test fun `closing active templates or service cancels invocation`() {
        for (closeService in listOf(false, true)) {
            XmlDisplayService().use { service ->
                val template = service.compile(xml("if(ctx.data.loop){ctx.placeholder('entered');while(true){}}"))
                template.draw(DisplayContext())
                val entered = java.util.concurrent.CountDownLatch(1)
                val pool = Executors.newSingleThreadExecutor()
                try {
                    val pending = pool.submit<List<TextDraw>> {
                        template.draw(DisplayContext(mapOf("loop" to true), placeholderResolver = PlaceholderResolver { entered.countDown(); "" }))
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "Invocation must enter host hook")
                    if (closeService) service.close() else template.close()
                    assertFailsWith<java.util.concurrent.ExecutionException> { pending.get(5, TimeUnit.SECONDS) }
                    assertTrue(pending.isDone)
                } finally { pool.shutdownNow() }
            }
        }
    }
    @Test fun `parallel viewers own separate documents`() {
        XmlDisplayService().use { service ->
            val template=service.compile(xml("function render(ctx){const s=document.createElement('span');s.id='title';s.textContent=ctx.viewerId;document.documentElement.replaceChildren(s)}"))
            // Warm the shared engine before asserting ordinary invocation budgets.
            template.draw(DisplayContext(viewerId="warm"))
            val pool=Executors.newFixedThreadPool(4)
            try {
                val jobs=(0..19).map { id -> pool.submit<List<String>> { text(template.draw(DisplayContext(viewerId="viewer$id"))) } }
                jobs.forEachIndexed { id, job -> assertEquals(listOf("viewer$id"),job.get(10,TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
        }
    }
}
