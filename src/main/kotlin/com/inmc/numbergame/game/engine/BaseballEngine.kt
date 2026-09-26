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
import com.inmc.numbergame.game.settings.BoolSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.util.Durations
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Number baseball.
 *
 * The classic three-digit game, generalised: digit count, whether digits may repeat and
 * whether a leading zero is allowed are all settings, so `baseball3`, `baseball4` and a
 * six-digit variant with repeats are one engine driven by different numbers.
 *
 * The answer exists only inside [State], which never leaves the server.
 */
object BaseballEngine : GameEngine {

    val DIGITS = IntSetting(
        key = "digits",
        label = "자릿수",
        help = "맞혀야 하는 숫자의 자리 수입니다. 3이면 고전 숫자야구입니다.",
        default = 3, min = 2, max = 8, suffix = "자리",
    )

    val ALLOW_DUPLICATE = BoolSetting(
        key = "allow-duplicate",
        label = "숫자 중복 허용",
        help = "켜면 112 처럼 같은 숫자가 두 번 나올 수 있습니다. 난이도가 올라갑니다.",
        default = false,
    )

    val ALLOW_LEADING_ZERO = BoolSetting(
        key = "allow-leading-zero",
        label = "맨 앞 0 허용",
        help = "켜면 012 같은 답이 나올 수 있습니다.",
        default = false,
    )

    val MAX_ATTEMPTS = IntSetting(
        key = "max-attempts",
        label = "최대 시도 횟수",
        help = "이 횟수 안에 못 맞히면 실패합니다.",
        default = 10, min = 1, max = 100, suffix = "회",
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다. 예: 5m",
        default = 300L, min = 0L, max = 3600L,
    )

    val SCORE_BASE = IntSetting(
        key = "score-base",
        label = "클리어 기본 점수",
        help = "누적 점수 랭킹에서 클리어 한 번에 주는 기본 점수입니다.",
        default = 100, min = 0, max = 100000, slider = false, suffix = "점",
    )

    val SCORE_PER_SPARE = IntSetting(
        key = "score-per-spare-attempt",
        label = "남은 시도당 점수",
        help = "빨리 맞힐수록 점수가 올라갑니다. 남은 시도 1회당 이만큼 더합니다.",
        default = 20, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        DIGITS, ALLOW_DUPLICATE, ALLOW_LEADING_ZERO, MAX_ATTEMPTS, TIME_LIMIT,
        SCORE_BASE, SCORE_PER_SPARE,
    )

    class State(val answer: String) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("answer", answer)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState = State(
        roll(
            digits = def.settings[DIGITS],
            allowDuplicate = def.settings[ALLOW_DUPLICATE],
            allowLeadingZero = def.settings[ALLOW_LEADING_ZERO],
            rng = rng,
        )
    )

    override fun loadState(section: ConfigurationSection): GameState? =
        section.getString("answer")?.takeIf { it.isNotBlank() }?.let { State(it) }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val digits = def.settings[DIGITS]
        val max = def.settings[MAX_ATTEMPTS]
        val body = mutableListOf<String>()

        val kind = if (def.settings[ALLOW_DUPLICATE]) "겹쳐도 되는" else "서로 다른"
        body.add("<gray>" + kind + " <white>" + digits + "자리</white> 숫자를 맞히세요.</gray>")
        body.add("<dark_gray>자리·숫자 모두 일치 = S  /  숫자만 일치 = B</dark_gray>")
        body.add("")

        if (session.history.isEmpty()) {
            body.add("<dark_gray>아직 시도한 기록이 없습니다.</dark_gray>")
        } else {
            session.history.forEach { body.add(it) }
        }

        body.add("")
        body.add(
            "<gray>남은 시도: <yellow>" + (max - session.attempts) +
                "회</yellow> <dark_gray>/ " + max + "회</dark_gray></gray>"
        )
        val remaining = session.remainingSeconds()
        if (remaining >= 0L) {
            body.add("<gray>남은 시간: <yellow>" + Durations.formatShort(remaining) + "</yellow></gray>")
        }

        return Screen(
            title = def.displayName,
            body = body,
            inputs = listOf(
                InputSpec.Text(
                    key = "guess",
                    label = digits.toString() + "자리 숫자",
                    maxLength = digits,
                )
            ),
        )
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")
        val digits = def.settings[DIGITS]
        val guess = input.text("guess").orEmpty()

        validate(guess, digits, def.settings[ALLOW_DUPLICATE], def.settings[ALLOW_LEADING_ZERO])
            ?.let { return StepResult.Rejected(it) }

        val verdict = judge(state.answer, guess)
        session.attempts++
        session.touch()
        session.addHistory(formatLine(session.attempts, guess, verdict, digits))

        val max = def.settings[MAX_ATTEMPTS]
        val elapsed = session.elapsedMillis()

        if (verdict.strikes == digits) {
            val spare = (max - session.attempts).coerceAtLeast(0)
            val score = def.settings[SCORE_BASE].toLong() + def.settings[SCORE_PER_SPARE].toLong() * spare
            return StepResult.Cleared(
                Outcome(
                    record = session.attempts.toLong(),
                    tiebreak = elapsed,
                    score = score,
                    summary = listOf(
                        "<green>정답! <white>" + state.answer + "</white></green>",
                        "<gray>시도 <yellow>" + session.attempts + "회</yellow> · 소요 <yellow>" +
                            Durations.formatShort(elapsed / 1000L) + "</yellow></gray>",
                    ),
                )
            )
        }

        if (session.attempts >= max) {
            return StepResult.Failed(
                Outcome(
                    record = max.toLong(),
                    tiebreak = elapsed,
                    score = 0L,
                    summary = listOf(
                        "<red>시도 횟수를 모두 사용했습니다.</red>",
                        "<gray>정답은 <white>" + state.answer + "</white> 였습니다.</gray>",
                    ),
                ),
                reason = "시도 초과",
            )
        }

        return StepResult.Continue(hint(verdict))
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val answer = (session.state as? State)?.answer ?: "?"
        return StepResult.Failed(
            Outcome(
                record = def.settings[MAX_ATTEMPTS].toLong(),
                tiebreak = session.elapsedMillis(),
                score = 0L,
                summary = listOf(
                    "<red>시간이 초과되었습니다.</red>",
                    "<gray>정답은 <white>" + answer + "</white> 였습니다.</gray>",
                ),
            ),
            reason = "시간 초과",
        )
    }

    // --- pure logic, exercised directly by the tests ---------------------------

    data class Verdict(val strikes: Int, val balls: Int)

    /** Builds an answer honouring the duplicate and leading-zero settings. */
    fun roll(digits: Int, allowDuplicate: Boolean, allowLeadingZero: Boolean, rng: Random): String {
        val size = digits.coerceIn(2, 8)
        if (allowDuplicate) {
            val sb = StringBuilder(size)
            sb.append(if (allowLeadingZero) rng.nextInt(0, 10) else rng.nextInt(1, 10))
            repeat(size - 1) { sb.append(rng.nextInt(0, 10)) }
            return sb.toString()
        }
        // Distinct digits: take `size` of a shuffled 0..9. A leading zero is fixed by swapping
        // it with a later digit rather than reshuffling, which could loop indefinitely.
        val pool = (0..9).toMutableList()
        for (i in pool.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        val picked = pool.take(size).toMutableList()
        if (!allowLeadingZero && picked[0] == 0) {
            val swapWith = (1 until size).first { picked[it] != 0 }
            picked[0] = picked[swapWith]
            picked[swapWith] = 0
        }
        return picked.joinToString("")
    }

    /** Null when the guess is usable, otherwise the reason to show the player. */
    fun validate(guess: String, digits: Int, allowDuplicate: Boolean, allowLeadingZero: Boolean): String? {
        if (guess.length != digits) return digits.toString() + "자리 숫자를 입력해주세요."
        if (!guess.all { it.isDigit() }) return "숫자만 입력할 수 있습니다."
        if (!allowDuplicate && guess.toSet().size != digits) return "서로 다른 숫자를 입력해주세요."
        if (!allowLeadingZero && guess.first() == ZERO) return "맨 앞자리는 0 이 될 수 없습니다."
        return null
    }

    /**
     * Strikes (right digit, right place) and balls (right digit, wrong place).
     *
     * Counting shared digits by frequency rather than by "does the other string contain it"
     * is what keeps the duplicate-allowed variant correct: balls is the multiset intersection
     * size minus the strikes, so 11 against an answer of 12 scores 1S 0B, not 1S 1B.
     */
    fun judge(answer: String, guess: String): Verdict {
        var strikes = 0
        val answerCounts = IntArray(10)
        val guessCounts = IntArray(10)
        for (i in answer.indices) {
            if (i < guess.length && answer[i] == guess[i]) strikes++
            answerCounts[answer[i] - ZERO]++
        }
        for (c in guess) {
            if (c.isDigit()) guessCounts[c - ZERO]++
        }
        var shared = 0
        for (d in 0..9) shared += minOf(answerCounts[d], guessCounts[d])
        return Verdict(strikes, (shared - strikes).coerceAtLeast(0))
    }

    private const val ZERO = '0'

    private fun formatLine(attempt: Int, guess: String, verdict: Verdict, digits: Int): String {
        val text = when {
            verdict.strikes == digits -> "<green>정답!</green>"
            verdict.strikes == 0 && verdict.balls == 0 -> "<dark_gray>아웃</dark_gray>"
            else -> buildString {
                if (verdict.strikes > 0) append("<red>").append(verdict.strikes).append("S</red>")
                if (verdict.strikes > 0 && verdict.balls > 0) append(" ")
                if (verdict.balls > 0) append("<yellow>").append(verdict.balls).append("B</yellow>")
            }
        }
        return "<dark_gray>" + attempt + ".</dark_gray> <white>" + guess +
            "</white> <dark_gray>→</dark_gray> " + text
    }

    private fun hint(verdict: Verdict): String = when {
        verdict.strikes == 0 && verdict.balls == 0 -> "아웃 - 입력한 숫자는 하나도 쓰이지 않았습니다."
        verdict.strikes == 0 -> verdict.balls.toString() + "볼 - 숫자는 맞지만 자리가 전부 틀렸습니다."
        else -> verdict.strikes.toString() + "스트라이크 " + verdict.balls + "볼"
    }
}
