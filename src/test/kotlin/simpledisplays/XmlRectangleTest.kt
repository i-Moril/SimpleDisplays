package simpledisplays

import kotlin.test.*
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.TextComponent
import org.junit.jupiter.api.Test
import simpledisplays.api.DisplayContext
import simpledisplays.api.TextDraw

class XmlRectangleTest {
    private fun assertInk(draw: TextDraw, left: Double, top: Double, width: Double, height: Double) {
        // Vanilla TextDisplay renderer: single-line centered bitmap block, 9px advance.
        val nativeScale = draw.size * 40.0 / 9.0
        val originY = -draw.y - draw.size
        assertEquals(left, draw.x - .0875 * nativeScale * draw.scaleX, 1e-9)
        assertEquals(left + width, draw.x + .1125 * nativeScale * draw.scaleX, 1e-9)
        assertEquals(-top, originY + .225 * nativeScale * draw.scaleY, 1e-9)
        assertEquals(-top - height, originY + .025 * nativeScale * draw.scaleY, 1e-9)
        assertEquals("\u2588", (draw.text as TextComponent).content())
        assertEquals(Key.key("minecraft:include/default"), draw.text.font())
    }

    @Test fun `one rectangle fills requested ink box under centered native renderer`() {
        XmlDisplayService().use { service ->
            val frame = service.compile("""<screen width="800" height="200"><div style="left:5;top:7"><rect style="left:195;top:3;width:600;height:180;color:#ffffff"/></div></screen>""").draw(emptyMap(), 0)
            assertEquals(1, frame.size)
            assertInk(frame.single(), 200.0, 10.0, 600.0, 180.0)
            assertFalse(frame.single().animated)
        }
    }

    @Test fun `DOM rectangle can change aspect ratio without changing its ink origin`() {
        XmlDisplayService().use { service ->
            val template = service.compile("""<screen width="256" height="128"><script><![CDATA[
                function render(ctx) {
                    const rect = document.createElement('rect');
                    rect.setAttribute('style', 'left:12;top:8;width:' + ctx.data.width + ';height:20;color:#ff0000');
                    document.documentElement.appendChild(rect);
                }
            ]]></script></screen>""")
            assertInk(template.draw(DisplayContext(data = mapOf("width" to 120))).single(), 12.0, 8.0, 120.0, 20.0)
            assertInk(template.draw(DisplayContext(data = mapOf("width" to 8))).single(), 12.0, 8.0, 8.0, 20.0)
        }
    }

    @Test fun `invalid rectangle geometry and nonvisual content fail whole frame`() {
        XmlDisplayService().use { service ->
            for (style in listOf("width:0", "height:-1", "width:NaN", "height:Infinity", "color:bad", "width:1e308;height:1e-308", "height:1.79e308", "font-size:10")) {
                assertFails(style) { service.compile("<screen width='256' height='128'><rect style='$style'/></screen>") }
            }
            for (body in listOf("<rect>text</rect>", "<rect><span/></rect>", "<rect font='minecraft:default'/>", "<rect bind='x'/>")) {
                assertFails(body) { service.compile("<screen width='256' height='128'>$body</screen>") }
            }
        }
    }

    @Test fun `ordinary spans keep nominal size and identity aspect transform`() {
        XmlDisplayService().use { service ->
            val draw = service.compile("<screen width='256' height='128'><span style='left:100;top:20;font-size:24'>Station</span></screen>").draw(emptyMap(), 0).single()
            assertEquals(100.0, draw.x)
            assertEquals(20.0, draw.y)
            assertEquals(24.0, draw.size)
            assertEquals(1.0, draw.scaleX)
            assertEquals(1.0, draw.scaleY)
        }
    }
}
