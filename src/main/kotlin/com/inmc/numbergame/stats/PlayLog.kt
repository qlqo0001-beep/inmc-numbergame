package com.inmc.numbergame.stats

import com.inmc.numbergame.Ng
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.ArrayDeque

/**
 * A bounded log of recent finished games.
 *
 * Per-player totals are not duplicated here - the all-time [kr.inmc.core.rank.RankBoard]
 * already carries plays, clears and best records, and two places counting the same thing is two
 * places to disagree. This only answers "what happened lately", which the boards cannot.
 */
class PlayLog(private val ng: Ng) : YamlFileStore(ng.io, listOf("data", "stats.yml"), what = "플레이 기록") {

    data class Entry(
        val at: Long,
        val player: String,
        val gameId: String,
        val gameName: String,
        val cleared: Boolean,
        val detail: String,
    )

    private val log = ArrayDeque<Entry>()


    fun record(entry: Entry) {
        synchronized(log) {
            log.addFirst(entry)
            while (log.size > ng.config.logSize) log.removeLast()
        }
        markDirty()
    }

    fun recent(limit: Int = Int.MAX_VALUE): List<Entry> = synchronized(log) { log.take(limit) }

    fun recentOf(gameId: String, limit: Int): List<Entry> = synchronized(log) {
        log.filter { it.gameId.equals(gameId, ignoreCase = true) }.take(limit)
    }

    fun clear(gameId: String?) {
        synchronized(log) {
            if (gameId == null) log.clear()
            else log.removeIf { it.gameId.equals(gameId, ignoreCase = true) }
        }
        markDirty()
    }

    override fun read(config: YamlConfiguration) {
        synchronized(log) {
            log.clear()
            for (raw in config.getMapList("log")) {
                log.add(
                    Entry(
                        at = (raw["at"] as? Number)?.toLong() ?: 0L,
                        player = raw["player"] as? String ?: "?",
                        gameId = raw["game"] as? String ?: "?",
                        gameName = raw["game-name"] as? String ?: raw["game"] as? String ?: "?",
                        cleared = raw["cleared"] as? Boolean ?: false,
                        detail = raw["detail"] as? String ?: "",
                    )
                )
            }
        }
    }

    override fun write(config: YamlConfiguration) {
        config.set("log", recent().map { entry ->
            linkedMapOf(
                "at" to entry.at,
                "player" to entry.player,
                "game" to entry.gameId,
                "game-name" to entry.gameName,
                "cleared" to entry.cleared,
                "detail" to entry.detail,
            )
        })
    }
}
