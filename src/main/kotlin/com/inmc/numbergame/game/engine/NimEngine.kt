package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.ChoiceButton
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.ChoiceSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * The 31 game (a misère Nim variant).
 *
 * Players take turns adding 1..[MAX_ADD] to a running total, and whoever is forced to say the
 * target number loses. It is the most dialog-native game in the set - three buttons and a number,
 * no typing at all.
 *
 * The interesting design knob is the AI. Perfect play here is a solved, one-line strategy, so an
 * AI that always plays it is literally unbeatable when it moves first. [AI_LEVEL] therefore
 * controls how often it bothers, which is the only way this stays a game rather than a coin flip
 * on who got the first move.
 */
object NimEngine : GameEngine {

    val TARGET = IntSetting(
        key = "target",
        label = "목표 숫자",
        help = "이 숫자를 정확히 말하는 쪽이 집니다.",
        default = 31, min = 5, max = 200, slider = false,
    )

    val MAX_ADD = IntSetting(
        key = "max-add",
        label = "한 번에 더할 수 있는 최대",
        help = "1 부터 이 숫자까지 더할 수 있습니다.",
        default = 3, min = 2, max = 5,
    )

    val ROUNDS = IntSetting(
        key = "rounds",
        label = "판 수",
        help = "이만큼 겨루고, 이긴 판 수가 기록이 됩니다.",
        default = 3, min = 1, max = 15, suffix = "판",
    )

    val AI_LEVEL = ChoiceSetting(
        key = "ai-level",
        label = "AI 난이도",
        help = "완벽은 절대 실수하지 않습니다. 선공을 잡히면 이길 수 없으니 보통을 권장합니다.",
        default = "NORMAL",
        options = listOf(
            "EASY" to "쉬움 (자주 실수)",
            "NORMAL" to "보통 (가끔 실수)",
            "PERFECT" to "완벽 (실수 없음)",
        ),
    )

    val FIRST_MOVE = ChoiceSetting(
        key = "first-move",
        label = "선공",
        help = "31 게임은 선공이 유리합니다. 무작위를 권장합니다.",
        default = "RANDOM",
        options = listOf(
            "PLAYER" to "플레이어",
            "AI" to "서버",
            "RANDOM" to "무작위",
        ),
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다.",
        default = 300L, min = 0L, max = 1800L,
    )

    val SCORE_PER_WIN = IntSetting(
        key = "score-per-win",
        label = "승리당 점수",
        help = "한 판 이길 때마다 더해지는 점수입니다.",
        default = 50, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        TARGET, MAX_ADD, ROUNDS, AI_LEVEL, FIRST_MOVE, TIME_LIMIT, SCORE_PER_WIN,
    )

    class State(
        var total: Int = 0,
        var round: Int = 1,
        var wins: Int = 0,
        var losses: Int = 0,
        /** True when the player has the move. */
        var playerTurn: Boolean = true,
    ) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("total", total)
            section.set("round", round)
            section.set("wins", wins)
            section.set("losses", losses)
            section.set("player-turn", playerTurn)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState {
        val state = State()
        state.playerTurn = decideFirst(def.settings[FIRST_MOVE], rng)
        if (!state.playerTurn) {
            // The AI opens; play its move now so the first screen already shows a total.
            val move = chooseMove(state.total, def.settings[TARGET], def.settings[MAX_ADD], def.settings[AI_LEVEL], rng)
            state.total += move
            state.playerTurn = true
        }
        return state
    }

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("total")) return null
        return State(
            total = section.getInt("total"),
            round = section.getInt("round", 1),
            wins = section.getInt("wins", 0),
            losses = section.getInt("losses", 0),
            playerTurn = section.getBoolean("player-turn", true),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val target = def.settings[TARGET]
        val maxAdd = def.settings[MAX_ADD]
        val total = state?.total ?: 0
        val body = mutableListOf<String>()

        body.add(
            "<gray>번갈아 <white>1~" + maxAdd + "</white> 을 더해 " +
                "<white>" + target + "</white> 을 말하는 쪽이 <red>집니다</red>.</gray>"
        )
        body.add("")
        body.add("<yellow><b>현재 " + total + "</b></yellow> <dark_gray>/ " + target + "</dark_gray>")
        body.add("")
        body.add(
            "<gray>" + (state?.round ?: 1) + "판째 <dark_gray>/ " + def.settings[ROUNDS] + "판</dark_gray>" +
                "  <dark_gray>|</dark_gray>  <green>" + (state?.wins ?: 0) + "승</green> " +
                "<red>" + (state?.losses ?: 0) + "패</red></gray>"
        )
        if (session.history.isNotEmpty()) {
            body.add("")
            session.history.takeLast(5).forEach { body.add(it) }
        }

        // Only offer moves that stay legal; the last turn of a round often has fewer than three.
        val room = (target - total).coerceAtLeast(1)
        val buttons = (1..minOf(maxAdd, room)).map { step ->
            ChoiceButton(
                id = step.toString(),
                label = "+" + step,
                tooltip = (total + step).toString() + " 이 됩니다.",
            )
        }

        return Screen(title = def.displayName, body = body, buttons = buttons)
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")
        val target = def.settings[TARGET]
        val maxAdd = def.settings[MAX_ADD]

        val move = input.button?.toIntOrNull() ?: input.single()?.toIntOrNull()
            ?: return StepResult.Rejected("1 부터 " + maxAdd + " 사이에서 골라주세요.")
        if (move < 1 || move > maxAdd) return StepResult.Rejected("1 부터 " + maxAdd + " 사이에서 골라주세요.")
        if (state.total + move > target) {
            return StepResult.Rejected(target.toString() + " 을 넘길 수는 없습니다.")
        }

        session.attempts++
        session.touch()
        state.total += move
        session.addHistory("<gray>나 <white>+" + move + "</white> <dark_gray>→ " + state.total + "</dark_gray></gray>")

        // Saying the target loses the round.
        if (state.total >= target) {
            state.losses++
            return endRound(def, session, state, wonRound = false, rng)
        }

        val reply = chooseMove(state.total, target, maxAdd, def.settings[AI_LEVEL], rng)
        state.total += reply
        session.addHistory(
            "<gray>서버 <white>+" + reply + "</white> <dark_gray>→ " + state.total + "</dark_gray></gray>"
        )

        if (state.total >= target) {
            state.wins++
            return endRound(def, session, state, wonRound = true, rng)
        }

        return StepResult.Continue(null)
    }

    /** Closes one round and either starts the next or ends the session. */
    private fun endRound(
        def: GameDefinition,
        session: Session,
        state: State,
        wonRound: Boolean,
        rng: Random,
    ): StepResult {
        if (wonRound) session.score += def.settings[SCORE_PER_WIN].toLong()
        session.addHistory(
            if (wonRound) "<green>── " + state.round + "판 승리 ──</green>"
            else "<red>── " + state.round + "판 패배 ──</red>"
        )

        val rounds = def.settings[ROUNDS]
        if (state.round >= rounds) {
            val outcome = Outcome(
                record = state.wins.toLong(),
                tiebreak = session.elapsedMillis(),
                score = session.score,
                summary = listOf(
                    if (state.wins > state.losses) "<green>승리!</green>"
                    else if (state.wins == state.losses) "<yellow>무승부</yellow>"
                    else "<red>패배</red>",
                    "<gray>전적: <green>" + state.wins + "승</green> <red>" + state.losses + "패</red></gray>",
                ),
            )
            // Any win at all is a result worth ranking; a whitewash is not.
            return if (state.wins > 0) StepResult.Cleared(outcome)
            else StepResult.Failed(outcome, "전패")
        }

        state.round++
        state.total = 0
        state.playerTurn = decideFirst(def.settings[FIRST_MOVE], rng)
        if (!state.playerTurn) {
            val opening = chooseMove(0, def.settings[TARGET], def.settings[MAX_ADD], def.settings[AI_LEVEL], rng)
            state.total = opening
            state.playerTurn = true
            session.addHistory(
                "<gray>서버 <white>+" + opening + "</white> <dark_gray>→ " + state.total + "</dark_gray></gray>"
            )
        }
        return StepResult.Continue(
            if (wonRound) "한 판 이겼습니다! 다음 판을 시작합니다." else "한 판 졌습니다. 다음 판을 시작합니다."
        )
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
        val wins = state?.wins ?: 0
        val outcome = Outcome(
            record = wins.toLong(),
            tiebreak = session.elapsedMillis(),
            score = session.score,
            summary = listOf(
                "<red>시간이 초과되었습니다.</red>",
                "<gray>전적: <green>" + wins + "승</green> <red>" + (state?.losses ?: 0) + "패</red></gray>",
            ),
        )
        return if (wins > 0) StepResult.Cleared(outcome) else StepResult.Failed(outcome, "시간 초과")
    }

    // --- pure logic, exercised directly by the tests ---------------------------

    fun decideFirst(setting: String, rng: Random): Boolean = when (setting.uppercase()) {
        "PLAYER" -> true
        "AI" -> false
        else -> rng.nextBoolean()
    }

    /**
     * The AI's move.
     *
     * Perfect play leaves the opponent on a total where `(target - 1 - total)` is a multiple of
     * `maxAdd + 1`: from there, whatever they add, the AI can restore the multiple, and the
     * opponent eventually has no choice but to say the target itself. When no such move exists
     * the position is already lost, and the AI simply plays safely.
     */
    fun perfectMove(total: Int, target: Int, maxAdd: Int): Int {
        val safe = target - 1
        for (step in 1..maxAdd) {
            val next = total + step
            if (next > safe) continue
            if ((safe - next) % (maxAdd + 1) == 0) return step
        }
        // Lost position: take the smallest legal step and hope for a mistake.
        return (1..maxAdd).firstOrNull { total + it <= safe } ?: 1
    }

    /** Applies [AI_LEVEL] on top of [perfectMove]. */
    fun chooseMove(total: Int, target: Int, maxAdd: Int, level: String, rng: Random): Int {
        val blunderChance = when (level.uppercase()) {
            "PERFECT" -> 0.0
            "EASY" -> 0.45
            else -> 0.18
        }
        if (blunderChance > 0.0 && rng.nextDouble() < blunderChance) {
            val room = (target - total).coerceAtLeast(1)
            return rng.nextInt(1, minOf(maxAdd, room) + 1)
        }
        return perfectMove(total, target, maxAdd)
    }
}
