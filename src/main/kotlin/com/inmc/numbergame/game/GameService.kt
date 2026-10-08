package com.inmc.numbergame.game

import com.inmc.numbergame.Ng
import com.inmc.numbergame.listener.ChatInputListener
import kr.inmc.core.rank.Outcome
import kr.inmc.core.reward.RewardTrigger
import com.inmc.numbergame.stats.PlayLog
import com.inmc.numbergame.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The play loop: start, submit, finish.
 *
 * Engines decide what happens inside a game; this decides everything around one - whether the
 * player is allowed to start, what the fee costs, which rewards a result earns, what the
 * leaderboard is told, and which screen comes next. Keeping that here is what lets ten engines
 * stay short and testable.
 */
class GameService(private val ng: Ng) {

    /**
     * Puzzles are rolled from a cryptographic source.
     *
     * Overkill for guessing games in isolation, but the alternative - a seeded `Random` whose
     * sequence a determined player could reconstruct from earlier answers - turns a leaderboard
     * with real rewards into something worth attacking.
     */
    private val rng: Random = SecureRandom().asKotlinRandom()

    private val lastSubmitAt = ConcurrentHashMap<UUID, Long>()

    // --- starting --------------------------------------------------------------

    /**
     * Re-reads a definition from the registry.
     *
     * Dialog buttons capture the [GameDefinition] object they were drawn with, but a reload
     * replaces every one of those objects. Without this, a button left open across
     * `/숫자게임 리로드` would roll a puzzle from the old settings while every later turn is
     * judged against the new ones - a three-digit answer in a game that now demands four-digit
     * guesses, which no input can ever satisfy.
     */
    private fun live(def: GameDefinition): GameDefinition? = ng.games.get(def.id)

    /** Entry point from the game list. Handles the "already playing" case. */
    fun open(player: Player, stale: GameDefinition) {
        if (!ng.ready) {
            ng.messages.send(player, "not-ready")
            return
        }
        val def = live(stale) ?: run {
            ng.messages.send(player, "unknown-game", Ph.of().game(stale.displayName))
            ng.screens.showMain(player)
            return
        }
        if (!ng.games.allowed(player, def)) {
            ng.messages.send(player, "no-permission")
            return
        }
        // Event games have no private session to resume - everybody shares one round.
        if (def.type.kind == GameType.Kind.EVENT) {
            ng.screens.showEventEntry(player, def, null)
            return
        }
        val existing = ng.sessions.of(player.uniqueId)
        if (existing != null) {
            ng.screens.showResume(player, existing)
            return
        }
        ng.screens.showBriefing(player, def)
    }

    /** Runs the gate, takes the fee and rolls a puzzle. Reports failures to the player itself. */
    fun start(player: Player, stale: GameDefinition) {
        if (!ng.ready) {
            ng.messages.send(player, "not-ready")
            return
        }
        val def = live(stale) ?: run {
            ng.messages.send(player, "unknown-game", Ph.of().game(stale.displayName))
            ng.screens.showMain(player)
            return
        }
        if (!def.enabled) {
            ng.messages.send(player, "game-disabled", Ph.of().game(def.displayName))
            return
        }
        // Checked here as well as in the list: a player could reach this by command, and the
        // list they clicked from may predate the permission being taken away.
        if (!ng.games.allowed(player, def)) {
            ng.messages.send(player, "no-permission")
            return
        }
        if (!ng.games.runnable(def)) {
            ng.messages.send(
                player, "game-unavailable",
                Ph.of().game(def.displayName).reason(unavailableReason(def)),
            )
            return
        }
        if (ng.sessions.has(player.uniqueId)) {
            ng.screens.showResume(player, ng.sessions.of(player.uniqueId)!!)
            return
        }

        ng.plays.check(player, def)?.let { denial ->
            ng.plays.explain(player, def, denial)
            return
        }
        // 판 안에서 거는 게임 — 한 판의 최소 판돈(참가비 포함)도 없으면 시작하지 않는다. 시작하면 오늘 횟수가 세진다(테섭 2026-10-02).
        val stake = def.engine.minimumStake(def)
        if (stake > 0.0 && ng.economy.isEnabled && ng.economy.balance(player, def.entry.currency) < stake + def.entry.feeMoney) {
            ng.messages.send(player, "entry-need-stake", Ph.of().game(def.displayName).money(stake))
            return
        }
        if (!ng.plays.charge(player, def)) {
            ng.plays.check(player, def)?.let { ng.plays.explain(player, def, it) }
            return
        }

        val now = System.currentTimeMillis()
        val limit = def.engine.timeLimitSeconds(def)
        val session = Session(
            playerId = player.uniqueId,
            gameId = def.id,
            state = def.engine.start(def, rng),
            startedAt = now,
            deadlineAt = if (limit > 0L) now + limit * 1000L else 0L,
            lastActionAt = now,
            feePaid = def.entry.hasAnyFee(),
        )
        ng.sessions.put(session)
        ng.plays.recordStart(player.uniqueId, def, now)

        ng.rewards.award(
            player, def.rewards.bundle(RewardTrigger.JOIN), def.displayName,
            def.displayName + " · 참가 보상", rng,
        )

        if (inputModeOf(def) == InputMode.FAST) {
            // The briefing dialog would otherwise sit on top of a game played on the action bar.
            // Closed once here rather than on every turn, so a player who deliberately opens
            // another screen mid-round is not fought over it.
            ng.dlg.close(player)
            ng.messages.send(player, "fast-input-start", Ph.of().game(def.displayName))
        }

        ng.screens.showPlay(player, session, null)
    }

    // --- playing ---------------------------------------------------------------

    fun submit(player: Player, input: SubmitInput) {
        val session = ng.sessions.of(player.uniqueId) ?: run {
            ng.screens.showMain(player)
            return
        }
        val def = ng.games.get(session.gameId) ?: run {
            ng.sessions.remove(player.uniqueId)
            ng.messages.send(player, "unknown-game", Ph.of().game(session.gameId))
            ng.screens.showMain(player)
            return
        }

        val now = System.currentTimeMillis()
        if (session.isExpired(now)) {
            handle(player, def, session, def.engine.onTimeout(def, session))
            return
        }

        // Blunt macro spam without punishing a fast human: too soon is simply redrawn.
        val gap = ng.config.submitCooldownMillis
        if (gap > 0L) {
            val previous = lastSubmitAt[player.uniqueId] ?: 0L
            if (now - previous < gap) {
                ng.screens.showPlay(player, session, ng.messages.raw("session-too-fast"))
                return
            }
        }
        lastSubmitAt[player.uniqueId] = now

        // Betting engines decide what a player can stake, so they need the balance in hand.
        session.balance = if (ng.economy.isEnabled) ng.economy.balance(player) else 0.0

        handle(player, def, session, def.engine.submit(def, session, input, rng))
    }

    private fun handle(player: Player, def: GameDefinition, session: Session, result: StepResult) {
        // Anything but a rejection means the turn moved on, which retires any timer still
        // waiting on the screen the player was looking at.
        if (result !is StepResult.Rejected) session.advance()

        settleMoney(player, session)

        when (result) {
            is StepResult.Rejected -> ng.screens.showPlay(player, session, "<red>" + result.reason + "</red>")

            is StepResult.Continue -> {
                ng.sessions.markDirty()
                ng.screens.showPlay(player, session, result.note?.let { "<gray>" + it + "</gray>" })
            }

            is StepResult.Cleared -> finish(player, def, session, result.outcome, cleared = true, reason = null)

            is StepResult.Failed -> finish(player, def, session, result.outcome, cleared = false, reason = result.reason)
        }
    }

    /**
     * Moves whatever the engine staked or won.
     *
     * Called after every step - including a rejection, which normally has nothing pending - so
     * there is exactly one place money changes hands and no result type can quietly skip it.
     */
    private fun settleMoney(player: Player, session: Session) {
        val delta = session.pendingMoney
        if (delta == 0.0) return
        session.pendingMoney = 0.0

        if (!ng.economy.isEnabled) {
            ng.logger.warning("경제 플러그인이 없어 베팅 정산을 처리하지 못했습니다: " + delta)
            return
        }
        // 판 안에서 거는 돈은 참가비와 같은 화폐(2026-10-08).
        val currency = ng.games.get(session.gameId)?.entry?.currency.orEmpty()
        if (delta > 0.0) ng.economy.deposit(player, delta, currency) else ng.economy.withdraw(player, -delta, currency)
        session.balance = ng.economy.balance(player, currency)
    }

    /** Abandons the current game as a loss. Counts against the allowance - it was already spent. */
    fun giveUp(player: Player) {
        val session = ng.sessions.of(player.uniqueId) ?: return
        val def = ng.games.get(session.gameId)
        if (def == null) {
            ng.sessions.remove(player.uniqueId)
            ng.screens.showMain(player)
            return
        }
        finish(
            player, def, session,
            Outcome(
                record = 0L,
                tiebreak = session.elapsedMillis(),
                // Whatever the session banked still counts. Zeroing it here would let a losing
                // gambler quit to keep the loss off the 순수익 board, and would rob everyone
                // else of the points they had already earned before walking away.
                score = session.score,
                summary = listOf("<gray>게임을 포기했습니다.</gray>"),
                netMoney = session.netMoney,
            ),
            cleared = false,
            reason = "포기",
        )
    }

    // --- finishing -------------------------------------------------------------

    private fun finish(
        player: Player,
        def: GameDefinition,
        session: Session,
        outcome: Outcome,
        cleared: Boolean,
        reason: String?,
    ) {
        ng.sessions.remove(player.uniqueId)
        lastSubmitAt.remove(player.uniqueId)

        ng.ranks.record(player, def, outcome, cleared)

        val earned = mutableListOf<String>()
        fun award(bundle: kr.inmc.core.reward.RewardBundle, source: String) {
            ng.rewards.award(player, bundle, def.displayName, source, rng)
                ?.let { earned.addAll(it.labels) }
        }

        if (cleared) {
            award(def.rewards.bundle(RewardTrigger.CLEAR), def.displayName + " · 클리어")

            // Every qualifying tier pays. "3회 이내" and "5회 이내" both firing on a 2-attempt
            // clear is the intent: tiers stack into a bonus rather than replacing one another.
            for (tier in def.rewards.matchingTiers(outcome.record, def.type.better)) {
                award(
                    tier.bundle,
                    def.displayName + " · " + tier.describe(def.type.better, def.type.recordUnit),
                )
            }

            if (ng.plays.recordClear(player.uniqueId, def)) {
                award(def.rewards.bundle(RewardTrigger.DAILY_FIRST_CLEAR), def.displayName + " · 일일 첫 클리어")
            }
        } else {
            award(def.rewards.bundle(RewardTrigger.FAIL), def.displayName + " · 실패 보상")
            if (session.feePaid) ng.plays.refund(player, def)
        }

        ng.log.record(
            PlayLog.Entry(
                at = System.currentTimeMillis(),
                player = player.name,
                gameId = def.id,
                gameName = def.displayName,
                cleared = cleared,
                detail = Text.plain(outcome.summary.firstOrNull().orEmpty()),
            )
        )

        ng.screens.showResult(player, def, outcome, cleared, earned, reason)
    }

    // --- ticker ----------------------------------------------------------------

    /** Times out expired sessions and sweeps abandoned ones. */
    fun tick(now: Long) {
        for (session in ng.sessions.expired(now)) {
            val player = org.bukkit.Bukkit.getPlayer(session.playerId)
            val def = ng.games.get(session.gameId)
            if (def == null) {
                ng.sessions.remove(session.playerId)
                continue
            }
            if (player == null) {
                // Offline when the clock ran out: drop it silently rather than paying a fail
                // reward to somebody who is not there to see why.
                ng.sessions.remove(session.playerId)
                continue
            }
            ng.messages.send(player, "session-timeout", Ph.of().game(def.displayName))
            handle(player, def, session, def.engine.onTimeout(def, session))
        }

        for (session in ng.sessions.idle(now)) {
            val def = ng.games.get(session.gameId)
            ng.sessions.remove(session.playerId)
            val player = org.bukkit.Bukkit.getPlayer(session.playerId) ?: continue
            ng.messages.send(
                player, "session-idle",
                Ph.of().game(def?.displayName ?: session.gameId),
            )
            ng.dlg.close(player)
        }
    }

    fun forget(playerId: UUID) {
        lastSubmitAt.remove(playerId)
    }

    /** How this game takes answers. Engines that do not offer the choice always get DIALOG. */
    fun inputModeOf(def: GameDefinition): InputMode {
        val setting = def.type.engine.schema
            .firstOrNull { it.key == INPUT_MODE_KEY } ?: return InputMode.DIALOG
        return InputMode.parse(setting.parse(def.settings.rawOrNull(setting.key)).toString())
    }

    /**
     * Submits a bare line of text, keyed to whatever the engine's own field is called.
     *
     * Fast input arrives from chat with no field name attached, so it is matched up here rather
     * than making the chat listener know that baseball calls its input `guess` and speed math
     * calls it `answer`.
     */
    fun submitText(player: Player, text: String) {
        val session = ng.sessions.of(player.uniqueId) ?: return
        val def = ng.games.get(session.gameId) ?: return
        val key = def.engine.screen(def, session).inputs.firstOrNull()?.key
            ?: ChatInputListener.FAST_KEY
        submit(player, SubmitInput.of(key, text))
    }

    private companion object {
        const val INPUT_MODE_KEY = "input-mode"
    }

    fun unavailableReason(def: GameDefinition): String =
        if (def.type.needsEconomy && !ng.economy.isEnabled) "경제 플러그인(Vault) 필요"
        else "설정을 확인해주세요"
}
