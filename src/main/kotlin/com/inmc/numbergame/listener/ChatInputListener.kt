package com.inmc.numbergame.listener

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.InputMode
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Feeds chat lines into a game running in [InputMode.FAST].
 *
 * Only ever intercepts a player who has a live session on a game configured for fast input, so
 * ordinary chat is untouched for everyone else. The line is consumed - cancelled, never shown -
 * because a speed round would otherwise spam the whole server with bare numbers.
 *
 * `AsyncChatEvent` fires off the main thread, so the answer is handed back to the main thread
 * before it reaches any game logic.
 */
class ChatInputListener(private val ng: Ng) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!ng.ready) return
        val player = event.player
        val session = ng.sessions.of(player.uniqueId) ?: return
        val def = ng.games.get(session.gameId) ?: return
        if (ng.gameService.inputModeOf(def) != InputMode.FAST) return

        val message = plain.serialize(event.message()).trim()
        if (message.isEmpty()) return

        // Consume it: this was an answer, not something to broadcast.
        event.isCancelled = true

        Bukkit.getScheduler().runTask(ng.plugin, Runnable {
            if (!player.isOnline) return@Runnable
            // Re-checked on the main thread - the session may have ended while this hopped over.
            if (ng.sessions.of(player.uniqueId) !== session) return@Runnable
            if (message.equals(QUIT, ignoreCase = true) || message == "포기") {
                ng.gameService.giveUp(player)
            } else {
                ng.gameService.submitText(player, message)
            }
        })
    }

    companion object {
        /**
         * The key a fast-mode answer arrives under.
         *
         * Engines read their own declared input key, so the submission is keyed to whatever the
         * engine's single field is called - resolved by [com.inmc.numbergame.game.GameService].
         */
        const val FAST_KEY = "answer"

        const val QUIT = "quit"
    }
}
