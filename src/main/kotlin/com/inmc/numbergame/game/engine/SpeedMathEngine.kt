package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import com.inmc.numbergame.game.InputMode
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.ChoiceSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.util.Durations
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Speed arithmetic: solve as many as possible before the clock runs out.
 *
 * Unlike the guessing games this one ends *by* the timer rather than being cut short by it -
 * running out of time is the normal, successful ending, and the score is whatever was banked.
 * A game that reported "실패" every single time it was played correctly would be nonsense.
 */
object SpeedMathEngine : GameEngine {

    val OPERATIONS = ChoiceSetting(
        key = "operations",
        label = "연산 종류",
        help = "출제할 연산입니다. 나눗셈은 항상 나누어떨어지게 출제됩니다.",
        default = "ADD_SUB",
        options = listOf(
            "ADD" to "덧셈만",
            "ADD_SUB" to "덧셈·뺄셈",
            "ADD_SUB_MUL" to "덧셈·뺄셈·곱셈",
            "ALL" to "사칙연산 전부",
        ),
    )

    val MAX_OPERAND = IntSetting(
        key = "max-operand",
        label = "숫자 크기 상한",
        help = "덧셈·뺄셈에 쓰이는 숫자의 최댓값입니다. 곱셈·나눗셈은 이보다 작게 출제됩니다.",
        default = 30, min = 5, max = 999, slider = false,
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "이 시간이 지나면 게임이 끝나고 맞힌 개수가 기록됩니다.",
        default = 60L, min = 10L, max = 600L,
    )

    val MISTAKES = IntSetting(
        key = "mistakes-allowed",
        label = "허용 오답 수",
        help = "이 횟수를 넘겨 틀리면 시간이 남아도 끝납니다. 0 이면 오답 제한이 없습니다.",
        default = 3, min = 0, max = 50, suffix = "회",
    )

    val SCORE_PER_CORRECT = IntSetting(
        key = "score-per-correct",
        label = "정답당 점수",
        help = "누적 점수 랭킹에 더해지는 점수입니다.",
        default = 10, min = 0, max = 10000, slider = false, suffix = "점",
    )

    /**
     * Defaults to fast input, alone among the ten games.
     *
     * A dialog cannot be updated in place, so every question would close and reopen the screen -
     * twenty-five flashes in a sixty-second round. The action bar redraws silently.
     */
    val INPUT_MODE = InputMode.setting(InputMode.FAST)

    override val schema: List<Setting<*>> = listOf(
        OPERATIONS, MAX_OPERAND, TIME_LIMIT, MISTAKES, SCORE_PER_CORRECT, INPUT_MODE,
    )

    class State(
        var left: Int,
        var right: Int,
        var operator: String,
        var answer: Int,
        var solved: Int = 0,
        var wrong: Int = 0,
    ) : GameState {
        fun question(): String = "$left $operator $right"

        override fun save(section: ConfigurationSection) {
            section.set("left", left)
            section.set("right", right)
            section.set("operator", operator)
            section.set("answer", answer)
            section.set("solved", solved)
            section.set("wrong", wrong)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState {
        val q = roll(def.settings[OPERATIONS], def.settings[MAX_OPERAND], rng)
        return State(q.left, q.right, q.operator, q.answer)
    }

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("answer")) return null
        return State(
            left = section.getInt("left"),
            right = section.getInt("right"),
            operator = section.getString("operator") ?: "+",
            answer = section.getInt("answer"),
            solved = section.getInt("solved", 0),
            wrong = section.getInt("wrong", 0),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val body = mutableListOf<String>()

        body.add("<gray>제한 시간 안에 최대한 많이 푸세요.</gray>")
        body.add("")
        body.add("<yellow><b>" + (state?.question() ?: "?") + " = ?</b></yellow>")
        body.add("")
        body.add("<gray>맞힌 개수: <green>" + (state?.solved ?: 0) + "개</green></gray>")

        val allowed = def.settings[MISTAKES]
        if (allowed > 0) {
            body.add("<gray>남은 오답: <red>" + (allowed - (state?.wrong ?: 0)) + "회</red></gray>")
        }
        val remaining = session.remainingSeconds()
        if (remaining >= 0L) {
            body.add("<gray>남은 시간: <yellow>" + Durations.formatShort(remaining) + "</yellow></gray>")
        }
        if (session.history.isNotEmpty()) {
            body.add("")
            session.history.takeLast(5).forEach { body.add(it) }
        }

        return Screen(
            title = def.displayName,
            body = body,
            inputs = listOf(InputSpec.Text(key = "answer", label = "정답", maxLength = 10)),
            // One line, because that is all the action bar gets: the sum, the running total and
            // the clock. Everything else in `body` is context the dialog has room for.
            actionBar = buildString {
                append("<yellow><b>").append(state?.question() ?: "?").append(" = ?</b></yellow>")
                append("   <dark_gray>|</dark_gray>   <green>").append(state?.solved ?: 0).append("</green>")
                if (remaining >= 0L) {
                    append("   <dark_gray>|</dark_gray>   <yellow>").append(remaining).append("초</yellow>")
                }
            },
        )
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")
        val value = input.int("answer")
            ?: return StepResult.Rejected("숫자를 입력해주세요.")

        session.touch()
        val correct = value == state.answer

        if (correct) {
            state.solved++
            session.score += def.settings[SCORE_PER_CORRECT].toLong()
            session.addHistory(
                "<dark_gray>" + state.question() + " = </dark_gray><green>" + state.answer + " ✔</green>",
                limit = 10,
            )
        } else {
            state.wrong++
            session.addHistory(
                "<dark_gray>" + state.question() + " = </dark_gray><red>" + value +
                    " ✘ <gray>(정답 " + state.answer + ")</gray></red>",
                limit = 10,
            )
        }
        session.attempts++

        val allowed = def.settings[MISTAKES]
        if (allowed > 0 && state.wrong >= allowed) {
            return finish(def, session, state, byTimeout = false)
        }

        // Read before the swap - once the next question is in place the old answer is gone.
        val note = if (correct) "정답!" else "오답 - 정답은 " + state.answer + " 였습니다."

        val next = roll(def.settings[OPERATIONS], def.settings[MAX_OPERAND], rng)
        state.left = next.left
        state.right = next.right
        state.operator = next.operator
        state.answer = next.answer

        return StepResult.Continue(note)
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
            ?: return StepResult.Failed(
                Outcome(0L, session.elapsedMillis(), 0L, listOf("<red>게임이 종료되었습니다.</red>")),
                "시간 초과",
            )
        return finish(def, session, state, byTimeout = true)
    }

    private fun finish(
        def: GameDefinition,
        session: Session,
        state: State,
        byTimeout: Boolean,
    ): StepResult {
        val outcome = Outcome(
            record = state.solved.toLong(),
            tiebreak = session.elapsedMillis(),
            score = session.score,
            summary = listOf(
                if (byTimeout) "<yellow>시간 종료!</yellow>" else "<red>오답 한도에 도달했습니다.</red>",
                "<gray>맞힌 문제: <green>" + state.solved + "개</green> · 틀린 문제: <red>" +
                    state.wrong + "개</red></gray>",
            ),
        )
        // Solving nothing is a loss; solving anything is a run worth putting on the board.
        return if (state.solved > 0) StepResult.Cleared(outcome)
        else StepResult.Failed(outcome, if (byTimeout) "시간 초과" else "오답 초과")
    }

    // --- question generation ---------------------------------------------------

    data class Question(val left: Int, val right: Int, val operator: String, val answer: Int)

    /**
     * Builds one solvable question.
     *
     * Subtraction is ordered so the answer is never negative and division is generated from its
     * own answer, so every question has a clean integer result - a speed round is no place to
     * ask somebody to type -7 or 3.333.
     */
    fun roll(operations: String, maxOperand: Int, rng: Random): Question {
        val cap = maxOperand.coerceAtLeast(5)
        val pool = when (operations.uppercase()) {
            "ADD" -> listOf("+")
            "ADD_SUB_MUL" -> listOf("+", "-", "×")
            "ALL" -> listOf("+", "-", "×", "÷")
            else -> listOf("+", "-")
        }

        return when (val op = pool[rng.nextInt(pool.size)]) {
            "+" -> {
                val a = rng.nextInt(1, cap + 1)
                val b = rng.nextInt(1, cap + 1)
                Question(a, b, op, a + b)
            }

            "-" -> {
                val a = rng.nextInt(1, cap + 1)
                val b = rng.nextInt(1, cap + 1)
                val high = maxOf(a, b)
                val low = minOf(a, b)
                Question(high, low, op, high - low)
            }

            "×" -> {
                val limit = mulLimit(cap)
                val a = rng.nextInt(2, limit + 1)
                val b = rng.nextInt(2, limit + 1)
                Question(a, b, op, a * b)
            }

            else -> {
                val limit = mulLimit(cap)
                val quotient = rng.nextInt(2, limit + 1)
                val divisor = rng.nextInt(2, limit + 1)
                Question(quotient * divisor, divisor, "÷", quotient)
            }
        }
    }

    /** Multiplication grows fast, so it uses a smaller pool than the additive cap. */
    private fun mulLimit(cap: Int): Int = (cap / 2).coerceIn(3, 20)
}
