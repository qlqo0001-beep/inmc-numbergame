package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.ChoiceSetting
import com.inmc.numbergame.game.settings.DoubleSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.util.Numbers
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Odd/even, high/low and dice, under one roof.
 *
 * They are the same game with different odds, so they are one engine with one bet list rather
 * than three near-identical ones. Picking the bet *is* picking the mode - a single dropdown
 * instead of a mode selector plus a dependent second selector, which a dialog cannot express
 * anyway since its options are fixed when the screen is built.
 *
 * Money never moves in here: the engine records what it wants moved on [Session.pendingMoney]
 * and the caller settles it. That keeps the whole payout table testable without a server.
 */
object BettingEngine : GameEngine {

    val BET_TYPES = ChoiceSetting(
        key = "bet-types",
        label = "제공할 베팅",
        help = "플레이어가 고를 수 있는 베팅 종류입니다.",
        default = "ALL",
        options = listOf(
            "ODD_EVEN" to "홀짝만",
            "HIGH_LOW" to "하이로우만",
            "DICE" to "주사위만",
            "ALL" to "전부",
        ),
    )

    val MIN_BET = DoubleSetting(
        key = "min-bet",
        label = "최소 베팅액",
        help = "한 판에 걸 수 있는 최소 금액입니다.",
        default = 100.0, min = 1.0, max = 1_000_000_000.0, suffix = "원",
    )

    val MAX_BET = DoubleSetting(
        key = "max-bet",
        label = "최대 베팅액",
        help = "한 판에 걸 수 있는 최대 금액입니다. 서버 경제를 지키는 가장 중요한 값입니다.",
        default = 10_000.0, min = 1.0, max = 1_000_000_000.0, suffix = "원",
    )

    val PAYOUT_EVEN = DoubleSetting(
        key = "payout-even",
        label = "홀짝·하이로우 배당",
        help = "2.0 이면 손익이 정확히 0이 됩니다. 1.95 면 서버가 2.5% 를 가져갑니다.",
        default = 1.95, min = 1.0, max = 10.0, suffix = "배",
    )

    val PAYOUT_DICE = DoubleSetting(
        key = "payout-dice",
        label = "주사위 배당",
        help = "6.0 이면 손익이 0입니다. 기본 5.0 은 서버가 약 16% 를 가져갑니다.",
        default = 5.0, min = 1.0, max = 50.0, suffix = "배",
    )

    val STREAK_BONUS = DoubleSetting(
        key = "streak-bonus",
        label = "연승 보너스",
        help = "연속으로 이길 때마다 배당에 더해지는 값입니다. 0 이면 보너스가 없습니다.",
        default = 0.05, min = 0.0, max = 1.0, suffix = "배",
    )

    val STREAK_BONUS_CAP = DoubleSetting(
        key = "streak-bonus-cap",
        label = "연승 보너스 상한",
        help = "연승 보너스가 아무리 쌓여도 이 값을 넘지 않습니다.",
        default = 0.5, min = 0.0, max = 5.0, suffix = "배",
    )

    val ROUNDS = IntSetting(
        key = "rounds",
        label = "판 수",
        help = "한 번 시작하면 이만큼 베팅하고 끝납니다.",
        default = 5, min = 1, max = 50, suffix = "판",
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다.",
        default = 600L, min = 0L, max = 3600L,
    )

    override val schema: List<Setting<*>> = listOf(
        BET_TYPES, MIN_BET, MAX_BET, PAYOUT_EVEN, PAYOUT_DICE,
        STREAK_BONUS, STREAK_BONUS_CAP, ROUNDS, TIME_LIMIT,
    )

    class State(
        var round: Int = 1,
        var streak: Int = 0,
        var net: Double = 0.0,
        var wins: Int = 0,
        var lastBet: Double = 0.0,
    ) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("round", round)
            section.set("streak", streak)
            section.set("net", net)
            section.set("wins", wins)
            section.set("last-bet", lastBet)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState = State()

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("round")) return null
        return State(
            round = section.getInt("round", 1),
            streak = section.getInt("streak", 0),
            net = section.getDouble("net", 0.0),
            wins = section.getInt("wins", 0),
            lastBet = section.getDouble("last-bet", 0.0),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val body = mutableListOf<String>()

        body.add(
            "<gray>" + (state?.round ?: 1) + "판째 <dark_gray>/ " + def.settings[ROUNDS] + "판</dark_gray></gray>"
        )
        body.add(
            "<gray>소지금: <gold>" + Numbers.money(session.balance) + "원</gold>" +
                "  <dark_gray>|</dark_gray>  손익: " + signed(state?.net ?: 0.0) + "</gray>"
        )
        val streak = state?.streak ?: 0
        if (streak > 1) {
            body.add(
                "<gray>연승: <yellow>" + streak + "연승</yellow> <dark_gray>(배당 +" +
                    Numbers.chance(streakBonus(def, streak)) + "배)</dark_gray></gray>"
            )
        }
        body.add("")
        body.add(
            "<gray>베팅 한도: <white>" + Numbers.money(def.settings[MIN_BET]) + " ~ " +
                Numbers.money(def.settings[MAX_BET]) + "원</white></gray>"
        )
        body.add(
            "<dark_gray>홀짝·하이로우 " + Numbers.chance(def.settings[PAYOUT_EVEN]) + "배 · 주사위 " +
                Numbers.chance(def.settings[PAYOUT_DICE]) + "배</dark_gray>"
        )
        if (session.history.isNotEmpty()) {
            body.add("")
            session.history.takeLast(5).forEach { body.add(it) }
        }

        return Screen(
            title = def.displayName,
            body = body,
            inputs = listOf(
                InputSpec.Choice(
                    key = "pick",
                    label = "무엇에 걸까요",
                    options = picksFor(def.settings[BET_TYPES]).map { it.id to it.display },
                ),
                InputSpec.Text(
                    key = "amount",
                    label = "베팅액",
                    maxLength = 12,
                    initial = defaultStake(def, state).toLong().toString(),
                ),
            ),
            submitLabel = "베팅",
        )
    }

    /** Pre-fills with the last stake, or the minimum on the first round. */
    private fun defaultStake(def: GameDefinition, state: State?): Double {
        val last = state?.lastBet ?: 0.0
        val candidate = if (last > 0.0) last else def.settings[MIN_BET]
        return candidate.coerceIn(def.settings[MIN_BET], def.settings[MAX_BET])
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")

        val pickId = input.text("pick") ?: return StepResult.Rejected("무엇에 걸지 선택해주세요.")
        val pick = picksFor(def.settings[BET_TYPES]).firstOrNull { it.id == pickId }
            ?: return StepResult.Rejected("고를 수 없는 베팅입니다.")

        val amount = input.double("amount")
            ?: return StepResult.Rejected("베팅액을 숫자로 입력해주세요.")
        val min = def.settings[MIN_BET]
        val max = def.settings[MAX_BET]
        if (amount < min || amount > max) {
            return StepResult.Rejected(
                Numbers.money(min) + " ~ " + Numbers.money(max) + "원 사이로 걸어주세요."
            )
        }
        if (amount > session.balance) {
            return StepResult.Rejected("소지금이 부족합니다. (보유 " + Numbers.money(session.balance) + "원)")
        }

        session.attempts++
        session.touch()
        state.lastBet = amount

        val roll = if (pick.dice) rng.nextInt(1, 7) else rng.nextInt(1, 101)
        val won = pick.wins(roll)

        val delta: Double
        if (won) {
            state.streak++
            state.wins++
            val multiplier = payout(def, pick, state.streak)
            // Staked and returned in one movement: the player only ever sees the difference.
            delta = amount * multiplier - amount
        } else {
            state.streak = 0
            delta = -amount
        }

        state.net += delta
        session.pendingMoney += delta
        // Mirrored onto the session so that abandoning mid-run reports the same numbers a
        // finished run would.
        session.score = Math.round(state.net)
        session.netMoney = state.net

        session.addHistory(
            "<dark_gray>" + state.round + ".</dark_gray> " + pick.display +
                " <dark_gray>→ " + pick.rollLabel(roll) + "</dark_gray> " +
                (if (won) "<green>승</green> " else "<red>패</red> ") + signed(delta),
            limit = 8,
        )

        val note = if (won) {
            pick.rollLabel(roll) + " - 적중! " + signed(delta)
        } else {
            pick.rollLabel(roll) + " - 아쉽습니다. " + signed(delta)
        }

        if (state.round >= def.settings[ROUNDS]) return finish(def, session, state)
        state.round++
        return StepResult.Continue(note)
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
            ?: return StepResult.Failed(
                Outcome(0L, session.elapsedMillis(), 0L, listOf("<red>게임이 종료되었습니다.</red>")),
                "시간 초과",
            )
        return finish(def, session, state)
    }

    private fun finish(def: GameDefinition, session: Session, state: State): StepResult {
        val outcome = Outcome(
            record = Math.round(state.net),
            tiebreak = session.elapsedMillis(),
            score = Math.round(state.net),
            summary = listOf(
                if (state.net > 0.0) "<green>이번 판은 이득입니다!</green>"
                else if (state.net == 0.0) "<yellow>본전입니다.</yellow>"
                else "<red>손해로 끝났습니다.</red>",
                "<gray>전적: <green>" + state.wins + "승</green> / " + (state.round) + "판</gray>",
                "<gray>손익: " + signed(state.net) + "</gray>",
            ),
            netMoney = state.net,
        )
        // Coming out ahead is the "cleared" condition; anything else is a losing session.
        return if (state.net > 0.0) StepResult.Cleared(outcome)
        else StepResult.Failed(outcome, if (state.net == 0.0) "본전" else "손실")
    }

    // --- pure logic, exercised directly by the tests ---------------------------

    /** One thing a player can bet on, and how it settles. */
    class Pick(
        val id: String,
        val display: String,
        val dice: Boolean,
        private val test: (Int) -> Boolean,
    ) {
        fun wins(roll: Int): Boolean = test(roll)

        fun rollLabel(roll: Int): String =
            if (dice) "주사위 " + roll else roll.toString()
    }

    fun picksFor(betTypes: String): List<Pick> {
        val all = betTypes.equals("ALL", ignoreCase = true)
        val picks = mutableListOf<Pick>()
        if (all || betTypes.equals("ODD_EVEN", ignoreCase = true)) {
            picks += Pick("ODD", "홀", false) { it % 2 == 1 }
            picks += Pick("EVEN", "짝", false) { it % 2 == 0 }
        }
        if (all || betTypes.equals("HIGH_LOW", ignoreCase = true)) {
            picks += Pick("LOW", "로우 (1~50)", false) { it <= 50 }
            picks += Pick("HIGH", "하이 (51~100)", false) { it >= 51 }
        }
        if (all || betTypes.equals("DICE", ignoreCase = true)) {
            for (face in 1..6) picks += Pick("DICE_$face", "주사위 $face", true) { it == face }
        }
        // A misconfigured value must still leave something playable rather than an empty list.
        if (picks.isEmpty()) {
            picks += Pick("ODD", "홀", false) { it % 2 == 1 }
            picks += Pick("EVEN", "짝", false) { it % 2 == 0 }
        }
        return picks
    }

    /** Total multiplier, base odds plus whatever the streak has earned. */
    fun payout(def: GameDefinition, pick: Pick, streak: Int): Double {
        val base = if (pick.dice) def.settings[PAYOUT_DICE] else def.settings[PAYOUT_EVEN]
        return base + streakBonus(def, streak)
    }

    fun streakBonus(def: GameDefinition, streak: Int): Double {
        val per = def.settings[STREAK_BONUS]
        if (per <= 0.0 || streak <= 1) return 0.0
        return (per * (streak - 1)).coerceAtMost(def.settings[STREAK_BONUS_CAP])
    }

    private fun signed(amount: Double): String =
        if (amount >= 0.0) "<green>+" + Numbers.money(amount) + "원</green>"
        else "<red>-" + Numbers.money(-amount) + "원</red>"
}
