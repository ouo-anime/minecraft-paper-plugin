import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.java.JavaPlugin
import org.java_websocket.server.WebSocketServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.event.player.AsyncPlayerChatEvent

class MainPlugin : JavaPlugin(), Listener {
    private lateinit var wsServer: WebSocketServer
    private val playerMiningStats = ConcurrentHashMap<String, Int>()

    private val playerAttackHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val playerMiningLocations = ConcurrentHashMap<String, MutableList<org.bukkit.Location>>()
    private val playerLastPosition = ConcurrentHashMap<String, org.bukkit.Location>()
    private val maxAttacksPerSecond = 6
    private val maxMiningDistance = 5.0
    private val maxVerticalSpeed = 1.0

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        startWebSocketServer()
        server.scheduler.runTaskTimer(this, Runnable {
            val players = server.onlinePlayers
            val playerList = players.joinToString(", ") { it.name }
            val avgPing = if (players.isNotEmpty()) players.sumOf { it.ping } / players.size else 0
            wsServer.broadcast("STATUS:${players.size}|$playerList|$avgPing")
        }, 0L, 200L)
        playerAttackHistory.clear()
        playerMiningLocations.clear()
        playerLastPosition.clear()
    }

    private fun startWebSocketServer() {
        wsServer = object : WebSocketServer(InetSocketAddress(8080)) {
            override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                logger.info("WebSocket client connected")
            }

            override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                logger.info("WebSocket client disconnected")
            }

            override fun onMessage(conn: WebSocket?, message: String?) {
                if (message?.startsWith("INVENTORY:") == true) {
                    val playerName = message.removePrefix("INVENTORY:")
                    val player = server.getPlayer(playerName)
                    if (player != null) {
                        val inventory = player.inventory.contents
                            .filterNotNull()
                            .joinToString(", ") { it.type.name }
                        conn?.send("INVENTORY:$playerName|$inventory")
                    } else {
                        conn?.send("INVENTORY:$playerName|Player not found")
                    }
                }
                if (message?.startsWith("CHAT:") == true) {
                    val parts = message.removePrefix("CHAT:").split("|")
                    val sender = parts[0]
                    val text = parts[1]
                    server.broadcastMessage("[$sender]: $text")
                }
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                logger.severe("WebSocket error: ${ex?.message}")
            }

            override fun onStart() {
                logger.info("WebSocket server started on port 8080")
            }
        }
        wsServer.start()
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        if (player.isFlying || player.isGliding || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION) ||
            player.gameMode == org.bukkit.GameMode.CREATIVE || player.gameMode == org.bukkit.GameMode.SPECTATOR ||
            player.hasPermission("anticheat.bypass")) return

        val from = event.from
        val to = event.to
        val lastPos = playerLastPosition[player.name]
        playerLastPosition[player.name] = to

        if (lastPos != null && to.y > from.y) {
            val verticalSpeed = to.y - lastPos.y
            if (verticalSpeed > maxVerticalSpeed && !player.isOnGround && to.block.getRelative(0, -1, 0).type.isAir) {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val coords = "${to.x}, ${to.y}, ${to.z}"
                wsServer.broadcast("ANTICHEAT:${player.name}|Fly|$coords|$timestamp")
                server.broadcastMessage("[Anticheat] ${player.name} is using Fly at $coords!")
            }
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (event.damager !is org.bukkit.entity.Player || event.entity !is org.bukkit.entity.Player) return
        val player = event.damager as org.bukkit.entity.Player
        if (player.hasPermission("anticheat.bypass")) return

        val now = System.currentTimeMillis()
        val attacks = playerAttackHistory.computeIfAbsent(player.name) { mutableListOf() }
        attacks.add(now)
        attacks.removeIf { it < now - 1000 }

        if (attacks.size > maxAttacksPerSecond) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val coords = "${player.location.x}, ${player.location.y}, ${player.location.z}"
            wsServer.broadcast("ANTICHEAT:${player.name}|KillAura|$coords|$timestamp")
            server.broadcastMessage("[Anticheat] ${player.name} is using KillAura at $coords!")
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.hasPermission("anticheat.bypass")) return
        if (event.block.type == org.bukkit.Material.DIAMOND_ORE) {
            val locations = playerMiningLocations.computeIfAbsent(player.name) { mutableListOf() }
            locations.add(event.block.location)
            if (locations.size > 3) {
                val lastThree = locations.takeLast(3)
                val isSuspicious = lastThree.windowed(2).all { (a, b) ->
                    a.distance(b) < maxMiningDistance && a.y == b.y
                }
                if (isSuspicious) {
                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val coords = "${event.block.x}, ${event.block.y}, ${event.block.z}"
                    wsServer.broadcast("ANTICHEAT:${player.name}|X-Ray|$coords|$timestamp")
                    server.broadcastMessage("[Anticheat] ${player.name} is using X-Ray at $coords!")
                }
                if (locations.size > 10) locations.removeAt(0)
            }
        }
    }

    @EventHandler
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val message = event.message
        wsServer.broadcast("CHAT:${player.name}|$message")
    }
}