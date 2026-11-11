package simpledisplays

import kotlin.test.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.junit.jupiter.api.Test
import simpledisplays.api.DisplayContext
import simpledisplays.api.TextDraw

class StableDrawIdentityTest {
    @Test fun `original paths survive scripts moving static anonymous primitives`() {
        XmlDisplayService().use { service ->
            val template = service.compile("""<screen width="100" height="50">
                <div id="original"><span>Original</span></div><div id="target"/>
                <script><![CDATA[
                    function render(ctx) {
                        // Move an original container carrying its anonymous drawable.
                        const target=document.getElementById('target');
                        if(ctx.data.move) { target.appendChild(document.getElementById('original')); }
                    }
                ]]></script></screen>""")
            assertEquals("path:0/0/0", template.draw(DisplayContext()).single().key)
            assertEquals("path:0/0/0", template.draw(DisplayContext(mapOf("move" to true))).single().key)
            val anonymous = service.compile("""<screen width="100" height="50"><div><span>A</span><span>B</span></div>
                <script><![CDATA[document.documentElement.appendChild(document.createElement('div'));]]></script></screen>""")
            assertEquals(listOf("path:0/0/0", "path:0/0/1"), anonymous.draw(DisplayContext()).map { it.key })
            assertEquals(anonymous.draw(DisplayContext()).map { it.key }, anonymous.draw(DisplayContext()).map { it.key })
        }
    }

    @Test fun `generated primitives require explicit bounded ids and cannot alias static paths`() {
        XmlDisplayService().use { service ->
            for (tag in listOf("span", "rect", "icon")) {
                val xml = """<screen width="100" height="50"><script><![CDATA[
                    const e=document.createElement('$tag');document.documentElement.appendChild(e);
                ]]></script></screen>"""
                assertFails(tag) { service.compile(xml).draw(DisplayContext()) }
            }
            for (id in listOf("", " ", "x".repeat(1022))) {
                assertFails { service.compile("<screen width='1' height='1'><span id='$id'/></screen>") }
            }
            val template=service.compile("""<screen width="100" height="50"><span>A</span><script><![CDATA[
                const s=document.createElement('span');s.id='path:0/0';s.textContent='B';document.documentElement.appendChild(s);
            ]]></script></screen>""")
            assertEquals(listOf("path:0/0", "id:path:0/0"), template.draw(DisplayContext()).map { it.key })
            assertFails { TextDraw(" ", 0.0, 0.0, 1.0, Component.empty(), false) }
            assertFails { TextDraw("x".repeat(1025), 0.0, 0.0, 1.0, Component.empty(), false) }
        }
    }

    @Test fun `layers inherit absolute values and empty blink preserves key depth and count`() {
        XmlDisplayService().use { service ->
            val template = service.compile("""<screen width="100" height="50">
                <div style="z-index:20"><span id="ring">O</span>
                    <div style="z-index:21"><span id="dot">.</span><span id="label" style="z-index:30">Name</span></div>
                    <span id="back" style="z-index:0">Back</span>
                </div>
                <script><![CDATA[
                    function render(ctx) {document.getElementById('dot').textContent=ctx.elapsedTicks%20<10?'.':'';}
                ]]></script></screen>""")
            val on=template.draw(DisplayContext(elapsedTicks=0))
            val off=template.draw(DisplayContext(elapsedTicks=10))
            assertEquals(listOf(20,21,30,0), on.map { it.zIndex })
            assertEquals(on.map { it.key }, off.map { it.key })
            assertEquals(on.map { it.zIndex }, off.map { it.zIndex })
            assertEquals("", (off.single { it.key == "id:dot" }.text as TextComponent).content())
            assertEquals(on.filter { it.key != "id:dot" }, off.filter { it.key != "id:dot" })
            val slots=mutableMapOf<String, PreviewElement>()
            assertEquals(4, reconcilePreview(slots,on).added.size)
            val before=slots.toMap()
            assertTrue(reconcilePreview(slots,off.reversed()).added.isEmpty())
            before.forEach { (key,slot) -> assertSame(slot,slots[key]) }
            val retained=off.take(2)
            assertTrue(reconcilePreview(slots,retained).added.isEmpty())
            assertEquals(retained.map { it.key }.toSet(),slots.keys)
            assertEquals(0.21/63.0, previewDepth(21,100.0,50.0,0.02), 1e-12)
            val old=slots.toMap()
            assertFails { reconcilePreview(slots,listOf(on.first(),on.first())) }
            assertEquals(old,slots)
            reconcilePreview(slots,emptyList())
            assertTrue(slots.isEmpty())
        }
    }

    @Test fun `invalid static and script layers reject entire output`() {
        XmlDisplayService().use { service ->
            for (layer in listOf("-1","64","1.5","NaN","Infinity","2147483648","")) {
                assertFails(layer) { service.compile("<screen width='1' height='1'><span style='z-index:$layer'/></screen>") }
                val template=service.compile("""<screen width="1" height="1"><span id="s"/><script><![CDATA[
                    document.getElementById('s').setAttribute('style','z-index:$layer');
                ]]></script></screen>""")
                assertFails(layer) { template.draw(DisplayContext()) }
            }
            assertFails { TextDraw("x",0.0,0.0,1.0,Component.empty(),false,zIndex=64) }
            val template=service.compile("""<screen width="1" height="1"><div style="z-index:10" id="list"/><script><![CDATA[
                function render(ctx) {const s=document.createElement('span');s.id='same';s.textContent=ctx.viewerId;
                    s.setAttribute('style','z-index:'+ctx.viewerData.layer);document.getElementById('list').appendChild(s);}
            ]]></script></screen>""")
            val a=template.draw(DisplayContext(viewerId="a",viewerData=mapOf("layer" to 21)))
            val b=template.draw(DisplayContext(viewerId="b",viewerData=mapOf("layer" to 30)))
            assertEquals("id:same", a.single().key)
            assertEquals(a.single().key,b.single().key)
            assertEquals(21,a.single().zIndex);assertEquals(30,b.single().zIndex)
            assertEquals(a,template.draw(DisplayContext(viewerId="a",viewerData=mapOf("layer" to 21))))
        }
    }
}
