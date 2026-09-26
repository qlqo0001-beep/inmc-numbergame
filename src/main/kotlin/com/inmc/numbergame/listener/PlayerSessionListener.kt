package com.inmc.numbergame.listener

import com.inmc.numbergame.Ng
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Join and quit housekeeping.
 *
 * A session is deliberately **not** ended on quit: the puzzle is already rolled, the entry fee
 * is already spent, and dropping it would punish someone whose connection died. It waits, and
 * the idle sweep in the ticker collects it if they never come back.
 */
class PlayerSessionListener(private val ng: Ng) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // 이름 기억은 core 의 ProfileListener 가 한다 — 세 플러그인이 각자 하던 일이다.

        // Delayed so the notice lands after the server's own join messages rather than under
        // them, and so a player still loading terrain is not shouted at mid-download.
        Bukkit.getScheduler().runTaskLater(ng.plugin, Runnable {
            if (!player.isOnline || !ng.ready) return@Runnable
            ng.mailbox.notifyOnJoin(player)
        }, JOIN_NOTICE_DELAY_TICKS)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        ng.dlg.clearStack(playerId)
        ng.gameService.forget(playerId)
        // The session itself stays put so it can be resumed on the next login.
        ng.sessions.markDirty()
    }

    private companion object {
        const val JOIN_NOTICE_DELAY_TICKS = 40L
    }
}
