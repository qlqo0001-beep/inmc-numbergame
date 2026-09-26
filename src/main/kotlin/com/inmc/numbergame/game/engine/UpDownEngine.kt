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
 * Up and down.
 *
 * A hidden number in a configurable range; every guess is answered with "higher" or "lower".
 * Perfect binary search needs ceil(log2(span)) guesses, which is what [suggestedAttempts]
 * computes - the admin dialog shows it next to the attempt limit so nobody accidentally sets
 * an unwinnable game.
 */
object UpDownEngine : GameEngine {

    val RANGE_MIN = IntSetting(
        key = "range-min",
        label = "범위 최솟값",
        help = "맞힐 숫자가 될 수 있는 가장 작은 값입니다.",
        default = 1, min = 0, max = 1_000_000, slider = false,
    )

    val RANGE_MAX = IntSetting(
        key = "range-max",
        label = "범위 최댓값",
        help = "맞힐 숫자가 될 수 있는 가장 큰 값입니다. 1~100 이 기본입니다.",
        default = 100, min = 1, max = 1_000_000, slider = false,
    )

    val MAX_ATTEMPTS = IntSetting(
        key = "max-attempts",
        label = "최대 시도 횟수",
        help = "이 횟수 안에 못 맞히면 실패합니다. 1~100 범위라면 7회면 이론상 항상 맞힐 수 있습니다.",
        default = 7, min = 1, max = 100, suffix = "회",
    )

    val NARROW_RANGE = BoolSetting(
        key = "narrow-range",
        label = "남은 범위 표시",
        help = "켜면 지금까지의 힌트로 좁혀진 범위를 화면에 보여줍니다. 끄면 난이도가 올라갑니다.",
        default = true,
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다. 예: 3m",
        default = 180L, min = 0L, max = 3600L,
    )

    val SCORE_BASE = IntSetting(
        key = "score-base",
        label = "클리어 기본 점수",
        help = "누적 점수 랭킹에서 클리어 한 번에 주는 기본 점수입니다.",
        default = 80, min = 0, max = 100000, slider = false, suffix = "점",
    )

    val SCORE_PER_SPARE = IntSetting(
        key = "score-per-spare-attempt",
        label = "남은 시도당 점수",
        help = "남은 시도 1회당 이만큼 더합니다.",
        default = 25, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        RANGE_MIN, RANGE_MAX, MAX_ATTEMPTS, NARROW_RANGE, TIME_LIMIT, SCORE_BASE, SCORE_PER_SPARE,
    )

    /** [low]..[high] is the span still consistent with every hint given so far. */
    class State(val answer: Int, var low: Int, var high: Int) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("answer", answer)
            section.set("low", low)
            section.set("high", high)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState {
        val (low, high) = bounds(def)
        return State(rng.nextInt(low, high + 1), low, high)
    }

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("answer")) return null
        return State(
            answer = section.getInt("answer"),
            low = section.getInt("low"),
            high = section.getInt("high"),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    /** Normalised range; a misconfigured max below min is swapped rather than rejected. */
    fun bounds(def: GameDefinition): Pair<Int, Int> {
        val a = def.settings[RANGE_MIN]
        val b = def.settings[RANGE_MAX]
        return if (a <= b) a to b else b to a
    }

    /** Guesses a perfect binary search needs for a span of [size] values. */
    fun suggestedAttempts(size: Int): Int {
        var span = size.coerceAtLeast(1)
        var needed = 0
        while (span > 1) {
            span = (span + 1) / 2
            needed++
        }
        return needed.coerceAtLeast(1)
    }

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val (low, high) = bounds(def)
        val max = def.settings[MAX_ATTEMPTS]
        val body = mutableListOf<String>()

        body.add("<gray><white>" + low + "</white> 부터 <white>" + high + "</white> 사이의 숫자를 맞히세요.</gray>")
        if (def.settings[NARROW_RANGE] && state != null) {
            body.add("<gray>남은 범위: <yellow>" + state.low + " ~ " + state.high + "</yellow></gray>")
        }
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
            inputs = listOf(InputSpec.Text(key = "guess", label = "숫자", maxLength = high.toString().length)),
        )
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")
        val (low, high) = bounds(def)

        val guess = input.int("guess")
            ?: return StepResult.Rejected("숫자를 입력해주세요.")
        if (guess < low || guess > high) {
            return StepResult.Rejected(low.toString() + " ~ " + high + " 사이의 숫자를 입력해주세요.")
        }

        session.attempts++
        session.touch()

        val max = def.settings[MAX_ATTEMPTS]
        val elapsed = session.elapsedMillis()

        if (guess == state.answer) {
            session.addHistory(
                "<dark_gray>" + session.attempts + ".</dark_gray> <white>" + guess +
                    "</white> <dark_gray>→</dark_gray> <green>정답!</green>"
            )
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

        val up = guess < state.answer
        if (up) state.low = maxOf(state.low, guess + 1) else state.high = minOf(state.high, guess - 1)
        session.addHistory(
            "<dark_gray>" + session.attempts + ".</dark_gray> <white>" + guess +
                "</white> <dark_gray>→</dark_gray> " +
                (if (up) "<red>UP ▲</red>" else "<aqua>DOWN ▼</aqua>")
        )

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

        return StepResult.Continue(if (up) "더 큰 숫자입니다." else "더 작은 숫자입니다.")
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val answer = (session.state as? State)?.answer?.toString() ?: "?"
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
}
