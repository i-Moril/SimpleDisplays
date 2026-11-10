package simpledisplays

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.util.Vector
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import simpledisplays.api.DisplayService
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.IconFrame
import simpledisplays.api.TextDraw
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SimpleDisplaysPlugin : JavaPlugin() {
    private class Placement {
        val entities = ConcurrentHashMap.newKeySet<TextDisplay>()
    }

    private val displays = ConcurrentHashMap<UUID, Placement>()
    private var displayService: DisplayService? = null
    private lateinit var demo: DisplayTemplate
    private var demoValues = emptyMap<String, String>()

    override fun onEnable() {
        val service: DisplayService = XmlDisplayService()
        displayService = service
        server.servicesManager.register(DisplayService::class.java, service, this, ServicePriority.Normal)
        saveDefaultConfig()
        try {
            val font = Key.key(config.getString("demo-icon.font") ?: "minecraft:default")
            val frames = config.getMapList("demo-icon.frames").mapIndexed { index, frame ->
                val glyph = frame["glyph"] as? String
                val ticks = frame["ticks"] as? Int
                require(!glyph.isNullOrBlank() && ticks != null && ticks > 0) {
                    "demo-icon.frames[$index] requires a glyph and positive integer ticks"
                }
                IconFrame(Component.text(glyph).font(font), ticks)
            }
            require(frames.isNotEmpty()) { "demo-icon.frames must contain at least one frame" }
            if (!dataFolder.resolve("demo.xml").exists()) saveResource("demo.xml", false)
            val iconId = requireNotNull(config.getString("demo-icon.id")) { "Missing demo-icon.id" }
            demoValues = config.getConfigurationSection("demo-values")?.getValues(false)
                ?.mapValues { (key, value) -> value as? String ?: error("demo-values.$key must be text") }
                .orEmpty()
            demo = service.compile(dataFolder.resolve("demo.xml").readText(), mapOf(iconId to frames))
        } catch (error: Exception) {
            logger.severe(error.message ?: "Invalid demo configuration")
            logger.warning("The demo command is unavailable.")
        }
    }

    override fun onDisable() {
        try { displayService?.close() } catch (error: Exception) {
            logger.log(java.util.logging.Level.SEVERE, "Failed to close display runtime", error)
        }
        displayService = null
        server.servicesManager.unregisterAll(this)
        val placements = displays.values.toList()
        displays.clear()
        // Folia can shut down from outside the owning regions. The demo entities
        // are nonpersistent, so they will be discarded when those regions unload.
        for (placement in placements) {
            for (entity in placement.entities) {
                if (server.isOwnedByCurrentRegion(entity)) entity.remove()
            }
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Run this command in game.")
            return true
        }

        when (args.firstOrNull()?.lowercase()) {
            "place" -> {
                if (args.size > 2) return false
                val scale = (args.getOrNull(1) ?: "1").toDoubleOrNull()
                if (scale == null || !scale.isFinite() || scale !in 0.01..100.0) {
                    sender.sendMessage("Scale must be between 0.01 and 100.")
                    return true
                }
                if (!::demo.isInitialized) {
                    sender.sendMessage("Demo configuration is unavailable.")
                    return true
                }
                placeDemo(sender, scale)
                sender.sendMessage("Demo display scheduled. Use /$label remove to remove it.")
            }
            "remove" -> {
                if (args.size != 1) return false
                val playback = displays.remove(sender.uniqueId)
                playback?.let(::removePlacement)
                sender.sendMessage(if (playback == null) "No demo display found." else "Demo display removal scheduled.")
            }
            else -> return false
        }
        return true
    }

    private fun placeDemo(player: Player, scale: Double) {
        val center = player.eyeLocation
        center.add(center.direction.multiply(2.0))
        center.yaw += 180f
        center.pitch = 0f
        val right = Vector(center.direction.z, 0.0, -center.direction.x)
        val unit = scale / TextDraw.UNITS_PER_BLOCK
        val owner = player.uniqueId
        val placement = Placement()
        displays.put(owner, placement)?.let(::removePlacement)
        for ((index, node) in demo.draw(demoValues, 0).withIndex()) {
            val location = center.clone()
                .add(right.clone().multiply((node.x - demo.width / 2) * unit))
                .add(0.0, (demo.height / 2 - node.y - node.size) * unit, 0.0)
            server.regionScheduler.execute(this, location) {
                if (displays[owner] !== placement) return@execute
                try {
                    val entity = spawnNode(location, node, unit)
                    placement.entities.add(entity)
                    if (displays[owner] !== placement) {
                        placement.entities.remove(entity)
                        entity.remove()
                    } else {
                        trackEntity(placement, entity, index, node)
                    }
                } catch (error: RuntimeException) {
                    logger.log(java.util.logging.Level.WARNING, "Could not place demo display", error)
                    if (displays.remove(owner, placement)) removePlacement(placement)
                }
            }
        }
    }

    private fun removePlacement(placement: Placement) {
        for (entity in placement.entities) {
            entity.scheduler.run(this, { entity.remove() }, null)
        }
    }

    private fun spawnNode(location: Location, node: TextDraw, unit: Double): TextDisplay =
        location.world.spawn(location, TextDisplay::class.java) { entity ->
            entity.isPersistent = false
            entity.billboard = Display.Billboard.FIXED
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.isDefaultBackground = false
            entity.backgroundColor = Color.fromARGB(0)
            entity.text(node.text)
            val transformation = entity.transformation
            val nativeScale = node.size * unit * 40.0 / 9.0
            require(node.scaleX.isFinite() && node.scaleX > 0 && node.scaleY.isFinite() && node.scaleY > 0) {
                "Invalid text transform"
            }
            val scaleX = (nativeScale * node.scaleX).toFloat()
            val scaleY = (nativeScale * node.scaleY).toFloat()
            require(scaleX.isFinite() && scaleX > 0 && scaleY.isFinite() && scaleY > 0) { "Invalid text transform" }
            transformation.scale.set(scaleX, scaleY, nativeScale.toFloat())
            entity.transformation = transformation
        }

    private fun trackEntity(placement: Placement, entity: TextDisplay, index: Int, node: TextDraw) {
        val interval = if (node.animated) 1L else 20L
        var elapsed = 0L
        var currentText = node.text
        entity.scheduler.runAtFixedRate(this, { task ->
            if (!entity.isValid) {
                placement.entities.remove(entity)
                task.cancel()
            } else if (node.animated) {
                val nextText = demo.draw(demoValues, ++elapsed)[index].text
                if (nextText != currentText) {
                    entity.text(nextText)
                    currentText = nextText
                }
            }
        }, { placement.entities.remove(entity) }, interval, interval)
    }
}
