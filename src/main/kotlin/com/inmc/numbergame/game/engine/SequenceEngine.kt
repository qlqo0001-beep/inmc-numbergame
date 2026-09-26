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
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.util.Durations
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Find the rule, give the next term.
 *
 * Questions are generated from a small set of rules rather than a question bank, so the game
 * never runs out and nobody can memorise the answers. Every rule is one an ordinary player can
 * actually spot from five terms - "add 3", "double", "add the previous two" - because a sequence
 * puzzle whose rule is only findable by a mathematician is indistinguishable from a random number.
 */
object SequenceEngine : GameEngine {

    val DIFFICULTY = ChoiceSetting(
        key = "difficulty",
        label = "난이도",
        help = "어려울수록 규칙의 종류가 늘어납니다.",
        default = "NORMAL",
        options = listOf(
            "EASY" to "쉬움 (등차)",
            "NORMAL" to "보통 (등차·등비·제곱)",
            "HARD" to "어려움 (피보나치·교대 등 전부)",
        ),
    )

    val TERMS = IntSetting(
        key = "terms",
        label = "보여줄 항 수",
        help = "많을수록 규칙을 찾기 쉬워집니다.",
        default = 5, min = 3, max = 8, suffix = "개",
    )

    val ROUNDS = IntSetting(
        key = "rounds",
        label = "문제 수",
        help = "이만큼 맞히면 클리어입니다.",
        default = 5, min = 1, max = 30, suffix = "문제",
    )

    val MISTAKES = IntSetting(
        key = "mistakes-allowed",
        label = "허용 오답 수",
        help = "이 횟수를 넘겨 틀리면 끝납니다. 0 이면 오답 제한이 없습니다.",
        default = 2, min = 0, max = 20, suffix = "회",
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다.",
        default = 300L, min = 0L, max = 1800L,
    )

    val SCORE_PER_CORRECT = IntSetting(
        key = "score-per-correct",
        label = "정답당 점수",
        help = "누적 점수 랭킹에 더해지는 점수입니다.",
        default = 30, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        DIFFICULTY, TERMS, ROUNDS, MISTAKES, TIME_LIMIT, SCORE_PER_CORRECT,
    )

    class State(
        var terms: List<Long>,
        var answer: Long,
        var rule: String,
        var solved: Int = 0,
        var wrong: Int = 0,
    ) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("terms", terms.map { it.toString() })
            section.set("answer", answer)
            section.set("rule", rule)
            section.set("solved", solved)
            section.set("wrong", wrong)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState {
        val puzzle = roll(def.settings[DIFFICULTY], def.settings[TERMS], rng)
        return State(puzzle.terms, puzzle.answer, puzzle.rule)
    }

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("answer")) return null
        val terms = section.getStringList("terms").mapNotNull { it.toLongOrNull() }
        if (terms.isEmpty()) return null
        return State(
            terms = terms,
            answer = section.getLong("answer"),
            rule = section.getString("rule").orEmpty(),
            solved = section.getInt("solved", 0),
            wrong = section.getInt("wrong", 0),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val rounds = def.settings[ROUNDS]
        val body = mutableListOf<String>()

        body.add("<gray>규칙을 찾아 다음에 올 숫자를 입력하세요.</gray>")
        body.add("")
        body.add(
            "<yellow><b>" + (state?.terms?.joinToString(", ") ?: "?") + ", ?</b></yellow>"
        )
        body.add("")
        body.add(
            "<gray>진행: <green>" + (state?.solved ?: 0) + "</green> / " + rounds + "문제</gray>"
        )
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
            session.history.takeLast(4).forEach { body.add(it) }
        }

        return Screen(
            title = def.displayName,
            body = body,
            inputs = listOf(InputSpec.Text(key = "answer", label = "다음 숫자", maxLength = 18)),
        )
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")
        val value = input.long("answer")
            ?: return StepResult.Rejected("숫자를 입력해주세요.")

        session.attempts++
        session.touch()
        val correct = value == state.answer

        session.addHistory(
            "<dark_gray>" + state.terms.joinToString(", ") + ", </dark_gray>" +
                (if (correct) "<green>" + value + " ✔</green>"
                else "<red>" + value + " ✘ <gray>(정답 " + state.answer + " · " + state.rule + ")</gray></red>"),
            limit = 8,
        )

        if (correct) {
            state.solved++
            session.score += def.settings[SCORE_PER_CORRECT].toLong()
            if (state.solved >= def.settings[ROUNDS]) {
                return StepResult.Cleared(
                    Outcome(
                        record = state.solved.toLong(),
                        tiebreak = session.elapsedMillis(),
                        score = session.score,
                        summary = listOf(
                            "<green>모든 문제를 맞혔습니다!</green>",
                            "<gray>맞힌 문제: <yellow>" + state.solved + "문제</yellow> · 오답 <red>" +
                                state.wrong + "회</red></gray>",
                        ),
                    )
                )
            }
        } else {
            state.wrong++
            val allowed = def.settings[MISTAKES]
            if (allowed > 0 && state.wrong >= allowed) {
                val outcome = Outcome(
                    record = state.solved.toLong(),
                    tiebreak = session.elapsedMillis(),
                    score = session.score,
                    summary = listOf(
                        "<red>오답 한도에 도달했습니다.</red>",
                        "<gray>맞힌 문제: <yellow>" + state.solved + "문제</yellow></gray>",
                    ),
                )
                return if (state.solved > 0) StepResult.Cleared(outcome)
                else StepResult.Failed(outcome, "오답 초과")
            }
        }

        val next = roll(def.settings[DIFFICULTY], def.settings[TERMS], rng)
        val note = if (correct) "정답!" else "오답 - " + state.rule + " 규칙이었습니다."
        state.terms = next.terms
        state.answer = next.answer
        state.rule = next.rule
        return StepResult.Continue(note)
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
        val solved = state?.solved ?: 0
        val outcome = Outcome(
            record = solved.toLong(),
            tiebreak = session.elapsedMillis(),
            score = session.score,
            summary = listOf(
                "<red>시간이 초과되었습니다.</red>",
                "<gray>맞힌 문제: <yellow>" + solved + "문제</yellow></gray>",
            ),
        )
        return if (solved > 0) StepResult.Cleared(outcome) else StepResult.Failed(outcome, "시간 초과")
    }

    // --- puzzle generation -----------------------------------------------------

    data class Puzzle(val terms: List<Long>, val answer: Long, val rule: String)

    /**
     * Builds a sequence and its next term.
     *
     * Growth is capped: a geometric run of eight terms can reach the billions, and a puzzle
     * whose answer does not fit on a line is a typing test rather than a reasoning one.
     */
    fun roll(difficulty: String, terms: Int, rng: Random): Puzzle {
        val count = terms.coerceIn(3, 8)
        val rules = when (difficulty.uppercase()) {
            "EASY" -> listOf(Rule.ARITHMETIC)
            "HARD" -> Rule.entries
            else -> listOf(Rule.ARITHMETIC, Rule.GEOMETRIC, Rule.SQUARE)
        }

        repeat(GENERATION_ATTEMPTS) {
            val puzzle = build(rules[rng.nextInt(rules.size)], count, rng)
            if (puzzle.answer in -MAX_TERM..MAX_TERM) return puzzle
        }
        // Fall back to the one rule that cannot overflow, so generation always terminates.
        return build(Rule.ARITHMETIC, count, rng)
    }

    enum class Rule { ARITHMETIC, GEOMETRIC, SQUARE, FIBONACCI, ALTERNATING }

    private fun build(rule: Rule, count: Int, rng: Random): Puzzle = when (rule) {
        Rule.ARITHMETIC -> {
            val start = rng.nextInt(1, 30).toLong()
            val step = rng.nextInt(2, 13).toLong() * (if (rng.nextBoolean()) 1 else -1)
            val all = (0..count).map { start + step * it }
            Puzzle(all.dropLast(1), all.last(), (if (step > 0) "+" else "") + step + "씩 더하기")
        }

        Rule.GEOMETRIC -> {
            val start = rng.nextInt(1, 6).toLong()
            val ratio = rng.nextInt(2, 4).toLong()
            val all = generateSequence(start) { it * ratio }.take(count + 1).toList()
            Puzzle(all.dropLast(1), all.last(), ratio.toString() + "배씩 곱하기")
        }

        Rule.SQUARE -> {
            val start = rng.nextInt(1, 8).toLong()
            val all = (0..count).map { val n = start + it; n * n }
            Puzzle(all.dropLast(1), all.last(), "연속한 수의 제곱")
        }

        Rule.FIBONACCI -> {
            val a = rng.nextInt(1, 6).toLong()
            val b = rng.nextInt(1, 9).toLong()
            val all = mutableListOf(a, b)
            while (all.size <= count) all.add(all[all.size - 1] + all[all.size - 2])
            Puzzle(all.dropLast(1), all.last(), "앞의 두 수를 더하기")
        }

        Rule.ALTERNATING -> {
            val start = rng.nextInt(1, 20).toLong()
            val up = rng.nextInt(3, 15).toLong()
            val down = rng.nextInt(1, up.toInt()).toLong()
            val all = mutableListOf(start)
            while (all.size <= count) {
                val previous = all.last()
                all.add(if (all.size % 2 == 1) previous + up else previous - down)
            }
            Puzzle(all.dropLast(1), all.last(), "+" + up + " 과 -" + down + " 을 번갈아")
        }
    }

    private const val MAX_TERM = 100_000_000L
    private const val GENERATION_ATTEMPTS = 12
}
