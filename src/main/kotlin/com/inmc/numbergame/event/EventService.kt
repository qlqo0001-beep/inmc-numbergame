package com.inmc.numbergame.event

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameType
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.engine.LottoEngine
import com.inmc.numbergame.game.engine.UniqueBidEngine
import kr.inmc.core.store.YamlFileStore
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import com.inmc.numbergame.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Rounds for the server-wide games: lotto and the lowest-unique-bid auction.
 *
 * Both work the same way once you squint - everybody commits a number, a deadline passes, and
 * the entrants are put in order - so one service runs both and only the ordering step differs.
 * Prizes then go through the same rank-bracket path as a season settlement, which is why the
 * admin never has to learn a second reward editor.
 */
class EventService(private val ng: Ng) :
    YamlFileStore(ng.io, listOf("data", "events.yml"), what = "이벤트") {

    private val rounds = ConcurrentHashMap<String, EventRound>()

    private val rng: Random = SecureRandom().asKotlinRandom()


    // --- rounds ----------------------------------------------------------------

    /**
     * The live round for a game, opening one if there is none or the last was drawn.
     *
     * Opening a round starts its draw clock, so this must only be reached by something that
     * genuinely begins participation. Merely *looking* at a lotto should not set its first
     * deadline running - use [peek] for that.
     */
    fun roundOf(def: GameDefinition): EventRound {
        val existing = rounds[def.id.lowercase()]
        if (existing != null && existing.drawsAt > 0L) return existing
        return openRound(def, existing?.number?.plus(1) ?: 1)
    }

    /** The live round if one is already open. Never creates, so it is safe for display code. */
    fun peek(def: GameDefinition): EventRound? = rounds[def.id.lowercase()]

    fun currentOrNull(def: GameDefinition): EventRound? = peek(def)

    /**
     * Opens the first round for every enabled event game.
     *
     * Called once on enable so the draw schedule belongs to the server rather than to whoever
     * happened to open the screen first.
     */
    fun openMissingRounds() {
        for (def in ng.games.enabled()) {
            if (def.type.kind != GameType.Kind.EVENT) continue
            if (peek(def) == null) roundOf(def)
        }
    }

    private fun openRound(def: GameDefinition, number: Int): EventRound {
        val now = System.currentTimeMillis()
        val round = EventRound(def.id.lowercase(), number, now, now + intervalSeconds(def) * 1000L)
        rounds[def.id.lowercase()] = round
        markDirty()
        return round
    }

    private fun intervalSeconds(def: GameDefinition): Long = when (def.type) {
        GameType.LOTTO -> def.settings[LottoEngine.DRAW_INTERVAL]
        GameType.UNIQUE_BID -> def.settings[UniqueBidEngine.DRAW_INTERVAL]
        else -> 86400L
    }

    private fun entryPrice(def: GameDefinition): Double = when (def.type) {
        GameType.LOTTO -> def.settings[LottoEngine.TICKET_PRICE]
        GameType.UNIQUE_BID -> def.settings[UniqueBidEngine.ENTRY_PRICE]
        else -> 0.0
    }

    // --- entering --------------------------------------------------------------

    sealed interface EnterResult {
        data class Ok(val picks: List<Int>, val replaced: Boolean) : EnterResult
        data class Rejected(val reason: String) : EnterResult
    }

    /**
     * Records one player's numbers for the live round.
     *
     * Re-entering replaces the previous pick rather than adding a second one, and the fee is
     * only charged the first time - otherwise changing your mind would cost money, and people
     * would rightly call that a scam.
     */
    fun enter(player: Player, stale: GameDefinition, raw: String?): EnterResult {
        // Same reason as GameService.live: the dialog that produced this click may predate a
        // reload, and parsing a ticket against a stale pick-count would reject valid entries.
        val def = ng.games.get(stale.id) ?: return EnterResult.Rejected("게임을 찾을 수 없습니다.")
        val round = roundOf(def)
        val parsed = parse(def, raw)
        parsed.exceptionOrNull()?.let { return EnterResult.Rejected(it.message ?: "입력을 확인해주세요.") }
        val picks = parsed.getOrNull() ?: return EnterResult.Rejected("입력을 확인해주세요.")

        val existing = round.entryOf(player.uniqueId)
        if (existing == null) {
            ng.plays.check(player, def)?.let { denial ->
                return EnterResult.Rejected(ng.plays.describeDenial(denial))
            }

            // A ticket price with no economy to charge it against is treated as free, which is
            // what both of these games say in their own settings help. Refusing instead would
            // make a lotto shipped with a default price permanently unplayable on any server
            // without Vault - the game would sit in the list and turn everyone away.
            val price = if (ng.economy.isEnabled) entryPrice(def) else 0.0
            if (price > 0.0) {
                if (!ng.economy.has(player, price)) {
                    return EnterResult.Rejected(Numbers.money(price) + "원이 필요합니다.")
                }
                if (!ng.economy.withdraw(player, price)) {
                    return EnterResult.Rejected("참가비를 지불하지 못했습니다.")
                }
            }

            // The 참가 조건 fee is a second, separate price that the admin dialog offers for
            // every game. check() above already refuses anyone who cannot pay it, so it must
            // actually be taken - gating on a fee and then not collecting it is the worst of
            // both worlds. If it fails here the ticket price is handed straight back.
            if (!ng.plays.charge(player, def)) {
                if (price > 0.0) ng.economy.deposit(player, price)
                return EnterResult.Rejected("참가비를 지불하지 못했습니다.")
            }

            round.entries[player.uniqueId] = EventRound.Entry(player.uniqueId, player.name, picks)
            ng.plays.recordStart(player.uniqueId, def)
        } else {
            existing.picks = picks
            existing.name = player.name
        }

        markDirty()
        return EnterResult.Ok(picks, existing != null)
    }

    private fun parse(def: GameDefinition, raw: String?): Result<List<Int>> = when (def.type) {
        GameType.LOTTO -> LottoEngine.parseTicket(
            raw, def.settings[LottoEngine.POOL_SIZE], def.settings[LottoEngine.PICK_COUNT],
        )

        GameType.UNIQUE_BID ->
            UniqueBidEngine.parseBid(raw, def.settings[UniqueBidEngine.RANGE_MAX]).map { listOf(it) }

        else -> Result.failure(IllegalStateException("이벤트 게임이 아닙니다."))
    }

    /** A machine-picked ticket, for the 자동 선택 button. */
    fun autoPick(def: GameDefinition): String = when (def.type) {
        GameType.LOTTO -> LottoEngine.format(
            LottoEngine.draw(
                def.settings[LottoEngine.POOL_SIZE], def.settings[LottoEngine.PICK_COUNT], rng,
            )
        )

        GameType.UNIQUE_BID -> rng.nextInt(1, def.settings[UniqueBidEngine.RANGE_MAX] + 1).toString()

        else -> ""
    }

    // --- drawing ---------------------------------------------------------------

    data class DrawResult(val number: Int, val entrants: Int, val winners: Int, val voided: Boolean)

    /** Ticker hook: draws every event round whose deadline has passed. */
    fun tick(now: Long) {
        for (def in ng.games.enabled()) {
            if (def.type.kind != GameType.Kind.EVENT) continue
            val round = rounds[def.id.lowercase()] ?: continue
            if (!round.isDue(now)) continue
            draw(def, announce = true)
        }
    }

    /** Settles the live round and opens the next one. */
    fun draw(def: GameDefinition, announce: Boolean): DrawResult {
        val round = roundOf(def)
        val entrants = round.size

        val placings = when (def.type) {
            GameType.LOTTO -> drawLotto(def, round)
            GameType.UNIQUE_BID -> drawUniqueBid(def, round)
            else -> emptyList()
        }
        round.placings = placings

        val voided = def.type == GameType.UNIQUE_BID &&
            entrants < def.settings[UniqueBidEngine.MIN_PLAYERS]

        if (voided) {
            refundAll(def, round)
        } else {
            payOut(def, round, placings)
        }

        if (announce) broadcast(def, round, placings, voided)

        ng.logger.info(
            "이벤트 정산: " + def.id + " " + round.number + "회차 (참가 " + entrants +
                "명, 당첨 " + placings.size + "명" + (if (voided) ", 무효" else "") + ")"
        )

        val result = DrawResult(round.number, entrants, placings.size, voided)
        openRound(def, round.number + 1).also { next ->
            // Keep the settled round visible on the result screen until the next draw.
            next.result = round.result
            next.placings = placings
        }
        markDirty()
        return result
    }

    private fun drawLotto(def: GameDefinition, round: EventRound): List<EventRound.Placing> {
        val pool = def.settings[LottoEngine.POOL_SIZE]
        val count = def.settings[LottoEngine.PICK_COUNT]
        val minMatch = def.settings[LottoEngine.MIN_MATCH]

        val winning = LottoEngine.draw(pool, count, rng)
        round.result = winning

        return round.entries.values.mapNotNull { entry ->
            val matched = LottoEngine.matches(entry.picks, winning)
            val rank = LottoEngine.rankOf(matched, count, minMatch) ?: return@mapNotNull null
            EventRound.Placing(
                playerId = entry.playerId,
                name = entry.name,
                rank = rank,
                detail = matched.toString() + "개 일치 (" + LottoEngine.format(entry.picks) + ")",
            )
        }.sortedBy { it.rank }
    }

    private fun drawUniqueBid(def: GameDefinition, round: EventRound): List<EventRound.Placing> {
        val picks = round.entries.mapValues { it.value.picks.firstOrNull() ?: 0 }
        val order = UniqueBidEngine.rank(picks)
        round.result = listOfNotNull(UniqueBidEngine.winningNumber(picks))

        return order.mapIndexedNotNull { index, playerId ->
            val entry = round.entries[playerId] ?: return@mapIndexedNotNull null
            EventRound.Placing(
                playerId = playerId,
                name = entry.name,
                rank = index + 1,
                detail = (entry.picks.firstOrNull() ?: 0).toString() + " 선택",
            )
        }
    }

    /**
     * Hands out prizes and folds the result into the leaderboard.
     *
     * Rank brackets are the prize table, so an admin who has already set up 1위/2~3위 rewards for
     * a season gets a working lotto prize table with no extra work.
     */
    private fun payOut(
        def: GameDefinition,
        round: EventRound,
        placings: List<EventRound.Placing>,
    ) {
        for (placing in placings) {
            val score = scoreFor(def, round, placing)
            val outcome = Outcome(
                record = placing.rank.toLong(),
                tiebreak = 0L,
                score = score,
                summary = emptyList(),
            )
            // Only the online path can touch a Player; everyone else still gets ranked and mailed.
            val online = Bukkit.getPlayer(placing.playerId)
            if (online != null) {
                ng.ranks.record(online, def, outcome, cleared = true)
            } else {
                ng.ranks.recordOffline(placing.playerId, placing.name, def, outcome, cleared = true)
            }

            val bracket = def.rewards.bracketFor(placing.rank) ?: continue
            if (bracket.bundle.isEmpty()) continue
            val source = def.displayName + " · " + round.number + "회차 · " + placing.rank + "위"
            val payout = ng.rewards.resolve(bracket.bundle, rng)
            if (payout.isEmpty()) continue

            if (online != null) {
                ng.rewards.give(online, payout, def.displayName, source)
                ng.messages.send(
                    online, "event-prize",
                    Ph.of().game(def.displayName).rank(placing.rank).count(round.number),
                )
            } else {
                ng.rewards.mail(placing.playerId, payout, source)
            }
        }
    }

    private fun scoreFor(def: GameDefinition, round: EventRound, placing: EventRound.Placing): Long =
        when (def.type) {
            GameType.LOTTO -> {
                val count = def.settings[LottoEngine.PICK_COUNT]
                val matched = count - placing.rank + 1
                def.settings[LottoEngine.SCORE_PER_MATCH].toLong() * matched
            }

            GameType.UNIQUE_BID ->
                UniqueBidEngine.scoreFor(placing.rank, def.settings[UniqueBidEngine.SCORE_FOR_WIN])

            else -> 0L
        }

    /**
     * A round that failed to reach its minimum turnout gives everyone their money back.
     *
     * The 참가 조건 fee is returned too, and forced past `refund-on-fail`: nobody lost here, the
     * round simply did not happen.
     */
    private fun refundAll(def: GameDefinition, round: EventRound) {
        val price = entryPrice(def)
        for (entry in round.entries.values) {
            val online = Bukkit.getPlayer(entry.playerId)
            if (online != null) {
                if (price > 0.0 && ng.economy.isEnabled) ng.economy.deposit(online, price)
                ng.plays.refund(online, def, force = true)
                ng.messages.send(online, "event-voided", Ph.of().game(def.displayName))
            } else if (price > 0.0) {
                // Offline: only the money can be posted back. An item fee cannot be rebuilt into
                // a mailbox entry from here without the player, so it is left alone.
                val payout = kr.inmc.core.reward.RewardService.Payout(money = price)
                ng.rewards.mail(entry.playerId, payout, def.displayName + " · 참가비 환불")
            }
        }
    }

    private fun broadcast(
        def: GameDefinition,
        round: EventRound,
        placings: List<EventRound.Placing>,
        voided: Boolean,
    ) {
        if (voided) {
            Bukkit.getServer().sendMessage(
                ng.messages.component("event-voided-broadcast", Ph.of().game(def.displayName))
            )
            return
        }
        val winner = placings.firstOrNull()
        val ph = Ph.of()
            .game(def.displayName)
            .count(round.number)
            .player(winner?.name ?: "없음")
            .record(if (round.result.isEmpty()) "-" else LottoEngine.format(round.result))
        Bukkit.getServer().sendMessage(ng.messages.component("event-drawn", ph))
    }

    // --- description helpers ---------------------------------------------------

    fun describe(def: GameDefinition, viewer: UUID?): List<String> {
        // Display only - never opens a round, or the draw clock would start the moment somebody
        // glanced at the screen.
        val round = peek(def) ?: return listOf(
            "<gray>다음 회차가 곧 시작됩니다.</gray>",
            "<dark_gray>참가하면 회차가 열립니다.</dark_gray>",
        )
        val lines = mutableListOf<String>()
        lines.add(
            "<gray>" + round.number + "회차 <dark_gray>|</dark_gray> 참가 <white>" +
                round.size + "명</white></gray>"
        )
        val left = round.secondsLeft(System.currentTimeMillis())
        if (left >= 0L) {
            lines.add("<gray>추첨까지: <yellow>" + Durations.formatShort(left) + "</yellow></gray>")
        }
        val price = entryPrice(def)
        lines.add(
            if (price > 0.0) "<gray>참가비: <gold>" + Numbers.money(price) + "원</gold></gray>"
            else "<gray>참가비: <white>무료</white></gray>"
        )
        val mine = viewer?.let { round.entryOf(it) }
        lines.add(
            if (mine == null) "<dark_gray>아직 참가하지 않았습니다.</dark_gray>"
            else "<gray>내 번호: <yellow>" + LottoEngine.format(mine.picks) + "</yellow></gray>"
        )
        if (round.result.isNotEmpty()) {
            lines.add("")
            lines.add("<gray>지난 회차 결과: <white>" + LottoEngine.format(round.result) + "</white></gray>")
            round.placings.take(3).forEach {
                lines.add("<gray>  " + it.rank + "위 <white>" + it.name + "</white> <dark_gray>" + it.detail + "</dark_gray></gray>")
            }
        }
        return lines
    }

    // --- persistence -----------------------------------------------------------

    override fun read(config: YamlConfiguration) {
        rounds.clear()
        config.getConfigurationSection("games")?.let { games ->
            for (gameId in games.getKeys(false)) {
                val section = games.getConfigurationSection(gameId) ?: continue
                val round = EventRound(
                    gameId = gameId.lowercase(),
                    number = section.getInt("number", 1),
                    opensAt = section.getLong("opens-at", System.currentTimeMillis()),
                    drawsAt = section.getLong("draws-at", 0L),
                )
                round.result = section.getIntegerList("result")
                section.getConfigurationSection("entries")?.let { entries ->
                    for (raw in entries.getKeys(false)) {
                        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
                        round.entries[id] = EventRound.Entry(
                            playerId = id,
                            name = entries.getString("$raw.name") ?: "?",
                            picks = entries.getIntegerList("$raw.picks"),
                            at = entries.getLong("$raw.at", 0L),
                        )
                    }
                }
                rounds[round.gameId] = round
            }
        }
    }




    override fun write(config: YamlConfiguration) {
        for ((gameId, round) in rounds) {
            val path = "games." + gameId
            config.set("$path.number", round.number)
            config.set("$path.opens-at", round.opensAt)
            config.set("$path.draws-at", round.drawsAt)
            config.set("$path.result", round.result)
            for (entry in round.entries.values) {
                val entryPath = "$path.entries." + entry.playerId
                config.set("$entryPath.name", entry.name)
                config.set("$entryPath.picks", entry.picks)
                config.set("$entryPath.at", entry.at)
            }
        }
    }

    /** Used by the admin dialog to show what a manual draw would do. */
    fun plainDescribe(def: GameDefinition): String =
        Text.plain(describe(def, null).joinToString(" "))
}
