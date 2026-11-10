# SimpleDisplays

SimpleDisplays compiles XML into renderer-independent `TextDraw` lists. XML defines the canvas and static layout; inline JavaScript can update a fresh document for each viewer before layout. The Bukkit plugin publishes `DisplayService` through the services registry and includes its own XML demo renderer. The existing demo remains a static XML example; callers of scripted templates own frame refresh and changing visual-node counts.

## An XML template with JavaScript

```xml
<screen width="256" height="128">
    <span id="destination" style="left:128;top:0;font-size:20;color:#FFFFFF">Destination</span>
    <div id="stops" style="top:28"/>
    <script><![CDATA[
        function render(ctx) {
            document.getElementById("destination").textContent = ctx.data.destination;
            const list = document.getElementById("stops");
            list.replaceChildren();
            ctx.data.futureStops.slice(0, 4).forEach((stop, i) => {
                const row = document.createElement("span");
                row.setAttribute("style", "left:128;top:" + (i * 18) + ";font-size:16");
                row.textContent = stop.name;
                list.appendChild(row);
            });
        }
    ]]></script>
</screen>
```

Use direct child inline `<script>` elements. CDATA lets JS contain `<` and `&` without XML escaping. No external scripts are fetched. Scripts execute in document order with `document` and `ctx` available as globals. Top-level document edits are supported. After all inline scripts execute, a defined `render(ctx)` function is invoked once; it must return `undefined` or `null`. Async functions, generators and non-void render results are unsupported.

Compilation validates scripts without executing data-dependent top-level code. Each scripted draw starts from the original XML and fresh JS globals: modifications never carry over to later frames or other viewers. Persistent state belongs to the host and must be supplied as input. Once scripts finish, the document is validated and passed through the XML layout engine. Only a successful complete invocation returns a frame; the host chooses whether to retain an older frame on error.

This is a small DOM-style interface over SimpleDisplays XML, not a browser environment. There is no separate JavaScript template format, `compileJavaScript`, or exposed drawing-command API.

## Host API

Declare `depend: [SimpleDisplays]` (or a soft dependency with an availability check) and compile against `simpledisplays-api`. Imports below are in `simpledisplays.api`:

```kotlin
val service = requireNotNull(server.servicesManager.load(DisplayService::class.java))
val template = service.compile(xml, icons = emptyMap())
val frame = template.draw(DisplayContext(
    data = mapOf("destination" to "Central",
                 "futureStops" to listOf(mapOf("name" to "Park"))),
    viewerId = player.uniqueId.toString(),
    viewerData = mapOf("language" to "en"),
    elapsedTicks = 20L,
))
// Send frame through your renderer; draw() creates no entities.
// Keep the compiled template for later frames; close when its owner unloads.
template.close()
```

`DisplayTemplate` and `DisplayService` are closeable. The service is shared and owned by SimpleDisplays; consumers close their templates, not the registry service. SimpleDisplays closes its service on disable. The existing `template.draw(Map<String, String>, elapsedTicks)` entry point remains available, including `<span bind="key">fallback</span>` bindings. With `DisplayContext`, string-valued entries in `data` provide those bindings. Bindings are applied during layout after scripts; to replace a bound span's text directly, clear its `bind` attribute first.

### Context and placeholders

`ctx` contains `data`, `viewerId`, `viewerData`, `elapsedTicks`, `width`, `height`, and `placeholder(token)`. Width and height are fixed by the XML screen. Data accepts string-keyed maps, lists, null, booleans, strings and finite primitive numbers. It is copied into detached read-only structures; do not mutate inputs while copying. Lists support ordinary JS operations such as `slice`, `map` and `forEach`. Numbers use JS number precision, so large identifiers should be strings. Live Java/Bukkit objects are not accepted.

The host may supply `placeholderResolver = PlaceholderResolver { token -> ... }`; scripts can then call `ctx.placeholder("%player_name%")`. There is no implicit resolver or Bukkit PlaceholderAPI adapter in this slice. Calling without a resolver fails. Resolver calls execute synchronously on the `draw` caller's thread; the host must obey the external provider's thread/region requirements. A callback must terminate promptly because JS cancellation cannot forcibly interrupt arbitrary blocked host code. Train-specific data can go directly into `ctx.data` without PAPI.

### Document interface

- `document.getElementById(id)`: find an attached element; absent IDs return null.
- `document.createElement(tag)`: create a detached `div`, `span`, `icon` or `rect`.
- `document.documentElement`: the screen root.
- Element `id`, `tagName`, `textContent`; `getAttribute(name)`, `setAttribute(name, value)`.
- Element `appendChild(node)`, `remove()`, `replaceChildren(...nodes)`.

The interface supports only the existing XML vocabulary. Scripts cannot create scripts, alter canvas dimensions, attach foreign nodes, form cycles, or leave duplicate IDs. `textContent` assigns literal text, never XML/HTML. There is no `innerHTML`, CSS selector engine, browser events, network API or timer API. Normal JS arrays and local computations remain available.

Layout uses existing `style` declarations: `div` has `left`/`top`; `span` adds `font-size`/`color`; `icon` adds `width`/`height` instead. Spans may use `font` and `bind`; icons use `src` from the compile-time `Map<String, List<IconFrame>>`. A span's `left` is its horizontal center and `top` its top edge. Icon boxes use a top-left origin and fit a square glyph at their center. `rect` uses `left`, `top`, `width`, `height`, `color` (white by default); its origin is its actual ink's top-left and its width/height are positive logical lengths. Each rectangle emits one vanilla U+2588 glyph using explicit `minecraft:include/default`, independently stretched to the requested dimensions. It needs no custom rectangle font or texture. Vanilla 26.1.2 and 26.2 provide an opaque 8×8 bitmap with advance 9 and ascent 7; using the include directly avoids the default font's Force Unicode filtering. Resource packs that override this vanilla include/glyph can change that geometry.

`TextDraw.scaleX` and `scaleY` default to 1. Hosts must apply both to the native text transform: `base = size * logicalUnitInBlocks * 40 / 9`, then scale `(base * scaleX, base * scaleY, base)`. The entity origin remains `(x, canvasTop - y - size)` for horizontal-center placement. A vanilla single-line block's native ink bounds are X `[-0.0875, 0.1125]`, Y `[0.025, 0.225]` blocks. Rectangle layout compensates those ink bearings: `size = height * 9 / 8`, `scaleX = width / height`, `scaleY = 1`, `x = left + width * 7 / 16`, `y = top`. Ordinary spans retain the prior text line-box contract; their top is not a promise that every font's first opaque pixel is at that position. Disable text shadows and background when rendering solid rectangles.

There are 128 logical units per block in the existing renderer contract. Canvas dimensions do not automatically clip content. Icon animation uses caller-supplied elapsed ticks; the library starts no rendering timer.

## Execution and lifecycle

Script execution uses restricted in-process GraalJS. Java class lookup, general host access, IO, process/thread creation, environment, native access and other languages are disabled. This does not isolate JVM heap or create a separate process: templates must come from trusted administrators/packages.

Current fixed limits are:

- XML up to 262,144 characters; combined script source up to 131,072 characters.
- Document allocation up to 2,048 elements, depth 32, and 131,072 cumulative attribute/text characters, including detached allocations and replacements.
- Structured input up to 4,096 nodes, depth 32, and 131,072 key/string characters across data and viewer data; viewer ID up to 1,024 characters.
- Up to 1,024 output elements and 65,536 output text characters; font keys up to 256 characters.
- Up to 1,024 icon IDs and 4,096 frames; nonempty frame lists with positive tick durations.
- Placeholder tokens up to 1,024 characters and results up to 65,536 characters.
- Four simultaneous JS evaluations; excess requests fail immediately. Compile-time parsing has a 3-second budget; each draw has a 250-ms budget including top-level script execution and optional render.

JS executions have cancellable deadlines. Context construction, host input copying and arbitrary host callbacks are not a hard wall-clock guarantee. Reusing a compiled template avoids rebuilding its source, but fresh per-frame document/JS contexts still cost allocation and execution time; high-frequency performance acceptance is separate. JS-free XML retains the precompiled layout path.

The host owns frame scheduling, visibility, caching and entity/region dispatch. The runtime's deadline workers only cancel JS contexts; they do not touch Bukkit entities. Template/service close cancels active scripted invocations and releases owned resources. Paper/Folia callers must still capture data and apply resulting visuals on the appropriate owners. Actual Folia/client rendering acceptance must be verified separately from unit tests or Paper service loading.

## Building

Use Gradle with a Java 21 toolchain: `gradle test apiJar jar`. Artifacts are generated under `build/libs`. The API publication is `simpledisplays:simpledisplays-api:0.1.0-SNAPSHOT`; it exposes no implementation or Graal types. API consumers supply their platform's Kotlin and Adventure dependencies.

The plugin resolves Kotlin 2.3.0 and GraalJS 24.2.2 using the actual JS-language/Truffle-runtime JAR coordinates in `plugin.yml`. These libraries must be accessible through the server library loader or its cache. Runtime dependencies are not shaded into the plugin JAR.
