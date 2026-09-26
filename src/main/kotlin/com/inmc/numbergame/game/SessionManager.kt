package com.inmc.numbergame.game

import com.inmc.numbergame.Ng
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live games, one per player.
 *
 * Sessions survive a relog and a restart. A minigame that silently loses your progress because
 * the server rebooted is worse than one that never started, and since the answer lives in the
 * session there is nothing to re-roll - the same puzzle simply comes back.
 *
 * One session per player, not one per game: dialogs are modal, and letting somebody park three
 * half-finished baseball games to cherry-pick the best result would make the leaderboard a lie.
 */
class SessionManager(private val ng: Ng) :
    YamlFileStore(ng.io, listOf("data", "sessions.yml"), what = "세션") {

    private val sessions = ConcurrentHashMap<UUID, Session>()


    val size: Int get() = sessions.size

    fun of(playerId: UUID): Session? = sessions[playerId]

    fun has(playerId: UUID): Boolean = sessions.containsKey(playerId)

    fun put(session: Session) {
        sessions[session.playerId] = session
        markDirty()
    }

    fun remove(playerId: UUID): Session? {
        val removed = sessions.remove(playerId)
        if (removed != null) markDirty()
        return removed
    }

    fun all(): List<Session> = sessions.values.toList()

    /** Sessions past their deadline, ready for the engine's timeout handling. */
    fun expired(now: Long): List<Session> = sessions.values.filter { it.isExpired(now) }

    /** Sessions nobody has touched for [com.inmc.numbergame.config.PluginConfig.sessionIdleSeconds]. */
    fun idle(now: Long): List<Session> {
        val limit = ng.config.sessionIdleSeconds
        if (limit <= 0L) return emptyList()
        val cutoff = now - limit * 1000L
        return sessions.values.filter { it.lastActionAt <= cutoff }
    }

    // --- persistence -----------------------------------------------------------

    override fun read(config: YamlConfiguration) {
        sessions.clear()
        var dropped = 0

        config.getConfigurationSection("sessions")?.let { section ->
            for (raw in section.getKeys(false)) {
                val playerId = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
                val entry = section.getConfigurationSection(raw) ?: continue
                val gameId = entry.getString("game") ?: continue
                val def = ng.games.get(gameId)
                if (def == null) {
                    dropped++
                    continue
                }
                val stateSection = entry.getConfigurationSection("state")
                val state = stateSection?.let { def.engine.loadState(it) }
                if (state == null) {
                    dropped++
                    continue
                }
                val session = Session(
                    playerId = playerId,
                    gameId = def.id,
                    state = state,
                    attempts = entry.getInt("attempts", 0),
                    history = entry.getStringList("history").toMutableList(),
                    score = entry.getLong("score", 0L),
                    feePaid = entry.getBoolean("fee-paid", false),
                )
                // Clocks are stored as durations, not instants: the wall-clock time a game
                // started is meaningless after a restart, but how far into it the player
                // was - and how much time they had left - is exactly what has to survive.
                session.restoreClock(
                    elapsedMillis = entry.getLong("elapsed-millis", 0L),
                    remainingMillis = if (entry.contains("remaining-millis")) {
                        entry.getLong("remaining-millis")
                    } else {
                        null
                    },
                )
                // Engines that were mid-reveal get a chance to put the player back somewhere
                // fair - nobody can be expected to remember digits they saw before a reboot.
                def.engine.onRestore(def, session)
                sessions[playerId] = session
            }
        }

        if (dropped > 0) {
            ng.logger.info("복원할 수 없는 진행 중 게임 " + dropped + "건을 정리했습니다")
        }
        if (sessions.isNotEmpty()) {
            ng.logger.info("진행 중이던 게임 " + sessions.size + "건을 복원했습니다")
        }
    }




    override fun write(config: YamlConfiguration) {
        val now = System.currentTimeMillis()
        for ((playerId, session) in sessions) {
            val path = "sessions." + playerId
            config.set("$path.game", session.gameId)
            config.set("$path.elapsed-millis", session.elapsedMillis(now))
            // Absent means untimed. Present and negative means it had already run out.
            session.remainingMillis(now)?.let { config.set("$path.remaining-millis", it) }
            config.set("$path.attempts", session.attempts)
            config.set("$path.history", session.history.toList())
            config.set("$path.score", session.score)
            config.set("$path.fee-paid", session.feePaid)
            session.state.save(config.createSection("$path.state"))
        }
    }
}
