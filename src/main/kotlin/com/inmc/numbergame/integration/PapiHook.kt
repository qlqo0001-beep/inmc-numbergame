package com.inmc.numbergame.integration

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * PlaceholderAPI, both directions.
 *
 * Inbound: `%papi_...%` inside our configured messages resolves before MiniMessage parsing.
 * Outbound: an expansion exposing ranks and allowances so scoreboards can show them.
 *
 * PlaceholderAPI classes are compile-only, so nothing here may be touched unless the plugin is
 * actually installed - [setup] is the only guard.
 */
class PapiHook(private val ng: Ng) {

    private var expansion: NgExpansion? = null

    val isEnabled: Boolean get() = expansion != null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            ng.logger.info("PlaceholderAPI 미설치 - %papi_...% 는 그대로 출력됩니다")
            return
        }
        try {
            Text.papiResolver = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
            expansion = NgExpansion(ng).also { it.register() }
            ng.logger.info("PlaceholderAPI 연동 활성화 (%ng_...%)")
        } catch (t: Throwable) {
            ng.logger.warning("PlaceholderAPI 연동 실패: " + t.message)
            Text.papiResolver = null
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        Text.papiResolver = null
    }
}

/** `%ng_...%` placeholders. A separate class so it only loads when PlaceholderAPI exists. */
private class NgExpansion(private val ng: Ng) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "ng"

    override fun getAuthor(): String = "INMC"

    override fun getVersion(): String = ng.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: player?.uniqueId?.let { Bukkit.getPlayer(it) }

        if (params.equals("game_count", true)) return ng.games.enabled().size.toString()
        if (params.equals("mailbox_count", true)) {
            return (online?.let { ng.mailbox.countOf(it.uniqueId) } ?: 0).toString()
        }
        if (params.equals("playing", true)) return ng.sessions.size.toString()

        // Everything else is "<game>_<what>". Game ids may themselves contain `_`, so the
        // known suffix is stripped from the end rather than splitting on the first underscore.
        TOP_NAME.find(params)?.let { match ->
            return topOf(params.dropLast(match.value.length), match.groupValues[1], name = true)
        }
        TOP_VALUE.find(params)?.let { match ->
            return topOf(params.dropLast(match.value.length), match.groupValues[1], name = false)
        }

        for (suffix in SUFFIXES) {
            if (!params.endsWith("_" + suffix, ignoreCase = true)) continue
            val gameId = params.dropLast(suffix.length + 1)
            val def = ng.games.get(gameId) ?: continue
            return resolve(def, suffix, online)
        }
        return null
    }

    /** `%ng_<game>_top_name_1%` / `%ng_<game>_top_score_1%`. */
    private fun topOf(gameId: String, rawRank: String, name: Boolean): String? {
        val def = ng.games.get(gameId) ?: return null
        val rank = rawRank.toIntOrNull() ?: return null
        if (rank < 1) return null
        val entry = ng.ranks.top(def, rank).getOrNull(rank - 1) ?: return "-"
        return if (name) entry.name else ng.ranks.formatValue(def, entry)
    }

    private fun resolve(def: GameDefinition, suffix: String, online: Player?): String? =
        when (suffix) {
            "my_rank" -> online?.let { ng.ranks.rankOf(def, it.uniqueId)?.toString() } ?: "-"

            "my_rank_alltime" ->
                online?.let { ng.ranks.rankOf(def, it.uniqueId, allTime = true)?.toString() } ?: "-"

            "my_best" -> {
                val entry = online?.let { ng.ranks.boardsOf(def.id).season.entryOf(it.uniqueId) }
                if (entry == null || !entry.hasRecord) "-" else entry.best.toString()
            }

            "my_score" ->
                (online?.let { ng.ranks.boardsOf(def.id).season.entryOf(it.uniqueId)?.score } ?: 0L).toString()

            "plays_left" -> {
                val left = online?.let { ng.plays.remaining(it.uniqueId, def) }
                left?.toString() ?: "-"
            }

            "season" -> ng.ranks.boardsOf(def.id).seasonNumber.toString()

            "next_reset" -> {
                val next = ng.ranks.nextResetAt(def)
                if (next <= 0L) "-"
                else Durations.formatShort(((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
            }

            else -> null
        }

    private companion object {
        val SUFFIXES = listOf(
            "my_rank_alltime", "my_rank", "my_best", "my_score", "plays_left", "season", "next_reset",
        )
        val TOP_NAME = Regex("_top_name_(\\d+)$")
        val TOP_VALUE = Regex("_top_(?:score|value)_(\\d+)$")
    }
}
