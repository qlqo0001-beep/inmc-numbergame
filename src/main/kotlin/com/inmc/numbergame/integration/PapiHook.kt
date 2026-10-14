package com.inmc.numbergame.integration

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.integration.Vanish
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

    /**
     * PAPI 목록용 — [onRequest] 가 답하는 이름 전부. 고정 셋([global]) + 지금 있는 게임마다 [perGame] 과 순위 1~[TOP_LISTED].
     * 목록과 답하는 쪽이 **같은 표**를 읽어서 이름이 어긋날 수 없다. `top_value_N` 은 `top_score_N` 의 별칭이라 뺐다.
     */
    override fun getPlaceholders(): List<String> {
        val keys = global.keys.toMutableList()
        for (id in ng.games.ids()) {
            for (suffix in perGame.keys) keys += id + "_" + suffix
            for (n in 1..TOP_LISTED) keys += listOf(id + "_top_name_" + n, id + "_top_score_" + n)
        }
        return keys.map { "%" + identifier + "_" + it + "%" }
    }

    /** 게임과 상관없는 것. 이름은 대소문자를 가리지 않는다. */
    private val global: Map<String, (Player?) -> String> = linkedMapOf(
        "game_count" to { _ -> ng.games.enabled().size.toString() },
        "mailbox_count" to { online -> (online?.let { ng.mailbox.countOf(it.uniqueId) } ?: 0).toString() },
        // 투명인 사람의 판은 그를 볼 수 있는 사람에게만 센다(2026-10-10) — 전광판·홀로그램(요청자 없음)에서는 뺀다.
        "playing" to { online ->
            ng.sessions.all().count { session ->
                Bukkit.getPlayer(session.playerId)?.let { Vanish.canSee(online, it) } ?: true
            }.toString()
        },
    )

    /**
     * `<game>_<suffix>`. 순서가 곧 검사 순서다 — `my_rank_alltime` 이 `my_rank` 보다 먼저.
     */
    private val perGame: Map<String, (GameDefinition, Player?) -> String> = linkedMapOf(
        "my_rank_alltime" to { def, online ->
            online?.let { ng.ranks.rankOf(def, it.uniqueId, allTime = true)?.toString() } ?: "-"
        },
        "my_rank" to { def, online -> online?.let { ng.ranks.rankOf(def, it.uniqueId)?.toString() } ?: "-" },
        "my_best" to { def, online ->
            val entry = online?.let { ng.ranks.boardsOf(def.id).season.entryOf(it.uniqueId) }
            if (entry == null || !entry.hasRecord) "-" else entry.best.toString()
        },
        "my_score" to { def, online ->
            (online?.let { ng.ranks.boardsOf(def.id).season.entryOf(it.uniqueId)?.score } ?: 0L).toString()
        },
        "plays_left" to { def, online -> online?.let { ng.plays.remaining(it.uniqueId, def) }?.toString() ?: "-" },
        "season" to { def, _ -> ng.ranks.boardsOf(def.id).seasonNumber.toString() },
        "next_reset" to { def, _ ->
            val next = ng.ranks.nextResetAt(def)
            if (next <= 0L) "-"
            else Durations.formatShort(((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
        },
    )

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: player?.uniqueId?.let { Bukkit.getPlayer(it) }

        global.entries.firstOrNull { params.equals(it.key, true) }?.let { return it.value(online) }

        // Everything else is "<game>_<what>". Game ids may themselves contain `_`, so the
        // known suffix is stripped from the end rather than splitting on the first underscore.
        TOP_NAME.find(params)?.let { match ->
            return topOf(params.dropLast(match.value.length), match.groupValues[1], name = true)
        }
        TOP_VALUE.find(params)?.let { match ->
            return topOf(params.dropLast(match.value.length), match.groupValues[1], name = false)
        }

        for ((suffix, resolve) in perGame) {
            if (!params.endsWith("_" + suffix, ignoreCase = true)) continue
            val gameId = params.dropLast(suffix.length + 1)
            val def = ng.games.get(gameId) ?: continue
            return resolve(def, online)
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

    private companion object {
        /** PAPI 목록에 싣는 순위 칸 수. 답은 그 밖의 순위도 한다. */
        const val TOP_LISTED = 10
        val TOP_NAME = Regex("_top_name_(\\d+)$")
        val TOP_VALUE = Regex("_top_(?:score|value)_(\\d+)$")
    }
}
