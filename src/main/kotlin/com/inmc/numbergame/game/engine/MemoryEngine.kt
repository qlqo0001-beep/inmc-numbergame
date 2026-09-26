package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.ChoiceButton
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Number memory: a digit string is shown for a moment, then typed back from memory.
 *
 * Each level adds a digit. The reveal ends on a clock, not on the player's say-so: that is the
 * whole difficulty, and a screen they could sit on indefinitely would just be a copying exercise.
 * The button on that screen only ever *shortens* the look, so a fast player is not made to wait
 * out a timer they no longer need.
 */
object MemoryEngine : GameEngine {

    val START_LENGTH = IntSetting(
        key = "start-length",
        label = "시작 자릿수",
        help = "첫 단계에서 보여줄 숫자의 길이입니다.",
        default = 3, min = 2, max = 12, suffix = "자리",
    )

    val MAX_LENGTH = IntSetting(
        key = "max-length",
        label = "최대 자릿수",
        help = "여기까지 통과하면 클리어로 끝납니다.",
        default = 12, min = 3, max = 24, suffix = "자리",
    )

    val SHOW_TIME = DurationSetting(
        key = "show-time",
        label = "노출 시간",
        help = "숫자를 보여주는 시간입니다. 자릿수가 늘어도 이 시간은 그대로입니다.",
        default = 3L, min = 1L, max = 30L,
    )

    val ANSWER_TIME = DurationSetting(
        key = "answer-time",
        label = "단계별 입력 제한 시간",
        help = "0 이면 입력 시간 제한이 없습니다. 전체 제한 시간과는 별개입니다.",
        default = 15L, min = 0L, max = 300L,
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "전체 제한 시간",
        help = "0 이면 전체 제한이 없습니다.",
        default = 300L, min = 0L, max = 1800L,
    )

    val SCORE_PER_LEVEL = IntSetting(
        key = "score-per-level",
        label = "단계당 점수",
        help = "한 단계를 통과할 때마다 더해지는 점수입니다.",
        default = 15, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        START_LENGTH, MAX_LENGTH, SHOW_TIME, ANSWER_TIME, TIME_LIMIT, SCORE_PER_LEVEL,
    )

    enum class Phase { SHOWING, ANSWERING }

    class State(
        var sequence: String,
        var phase: Phase = Phase.SHOWING,
        var cleared: Int = 0,
    ) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("sequence", sequence)
            section.set("phase", phase.name)
            section.set("cleared", cleared)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState =
        State(roll(def.settings[START_LENGTH], rng))

    override fun loadState(section: ConfigurationSection): GameState? {
        val sequence = section.getString("sequence")?.takeIf { it.isNotBlank() } ?: return null
        return State(
            sequence = sequence,
            // A restored session always re-shows the digits: the player cannot be expected to
            // remember something they were looking at before the server went down.
            phase = Phase.SHOWING,
            cleared = section.getInt("cleared", 0),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    /**
     * A restored session is put back into the reveal, so the per-level answer clock has to go.
     *
     * [loadState] forces the phase back to SHOWING because nobody can be asked to recall digits
     * they last saw before a reboot. Leaving the short answering deadline in place would then
     * kill the game during the very reveal that was meant to make it fair again.
     */
    override fun onRestore(def: GameDefinition, session: Session) {
        session.deadlineAt = sessionDeadline(def, session)
    }

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State
        val level = (state?.cleared ?: 0) + 1
        val length = state?.sequence?.length ?: def.settings[START_LENGTH]

        if (state == null || state.phase == Phase.SHOWING) {
            return Screen(
                title = def.displayName,
                body = listOf(
                    "<gray><white>" + level + "단계</white> · <white>" + length + "자리</white></gray>",
                    "<gray>아래 숫자를 외우세요.</gray>",
                    "",
                    "<yellow><b>" + spaced(state?.sequence.orEmpty()) + "</b></yellow>",
                    "",
                    "<dark_gray>" + def.settings[SHOW_TIME] + "초 뒤 사라집니다.</dark_gray>",
                ),
                // A dialog must offer at least one action, and this screen would otherwise have
                // none - it is meant to advance on its own. Giving it an explicit button also
                // means somebody who has already memorised three digits is not made to sit and
                // wait for the rest of the reveal; the timer still fires for everyone else, and
                // whichever arrives first wins because the other is retired by the revision guard.
                buttons = listOf(ChoiceButton(SKIP, "지금 입력하기", "노출을 끝내고 바로 입력합니다.")),
                canGiveUp = false,
                autoAdvanceMillis = def.settings[SHOW_TIME] * 1000L,
            )
        }

        return Screen(
            title = def.displayName,
            body = listOf(
                "<gray><white>" + level + "단계</white> · <white>" + length + "자리</white></gray>",
                "<gray>방금 본 숫자를 그대로 입력하세요.</gray>",
                "",
                "<gray>통과한 단계: <green>" + state.cleared + "단계</green></gray>",
            ),
            inputs = listOf(InputSpec.Text(key = "answer", label = "숫자", maxLength = length)),
        )
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")

        if (state.phase == Phase.SHOWING) {
            state.phase = Phase.ANSWERING
            session.touch()
            applyAnswerDeadline(def, session)
            return StepResult.Continue(null)
        }

        val answer = input.text("answer").orEmpty()
        if (answer.isEmpty()) return StepResult.Rejected("숫자를 입력해주세요.")
        if (!answer.all { it.isDigit() }) return StepResult.Rejected("숫자만 입력할 수 있습니다.")

        session.attempts++
        session.touch()

        if (answer != state.sequence) {
            return StepResult.Failed(
                Outcome(
                    record = state.cleared.toLong(),
                    tiebreak = session.elapsedMillis(),
                    score = session.score,
                    summary = listOf(
                        "<red>틀렸습니다.</red>",
                        "<gray>정답: <white>" + spaced(state.sequence) + "</white></gray>",
                        "<gray>입력: <red>" + spaced(answer) + "</red></gray>",
                        "<gray>통과한 단계: <yellow>" + state.cleared + "단계</yellow></gray>",
                    ),
                ),
                reason = "오답",
            )
        }

        state.cleared++
        session.score += def.settings[SCORE_PER_LEVEL].toLong()

        val nextLength = def.settings[START_LENGTH] + state.cleared
        if (nextLength > def.settings[MAX_LENGTH]) {
            return StepResult.Cleared(
                Outcome(
                    record = state.cleared.toLong(),
                    tiebreak = session.elapsedMillis(),
                    score = session.score,
                    summary = listOf(
                        "<green>최고 단계까지 모두 통과했습니다!</green>",
                        "<gray>통과한 단계: <yellow>" + state.cleared + "단계</yellow></gray>",
                    ),
                )
            )
        }

        state.sequence = roll(nextLength, rng)
        state.phase = Phase.SHOWING
        // Back to the whole-session clock: the level window that just ended must not keep
        // shortening the deadline for every level after it.
        session.deadlineAt = sessionDeadline(def, session)
        return StepResult.Continue("통과! 다음은 " + nextLength + "자리입니다.")
    }

    /** The session-wide deadline, derived rather than remembered so it survives a restore. */
    private fun sessionDeadline(def: GameDefinition, session: Session): Long {
        val limit = def.settings[TIME_LIMIT]
        return if (limit <= 0L) 0L else session.startedAt + limit * 1000L
    }

    /**
     * Applies the per-level answering window without letting it outlive the session clock.
     *
     * Two deadlines share one field, so the earlier of the two always wins - a 15 second answer
     * window must not hand back time to somebody whose five-minute session is nearly up.
     */
    private fun applyAnswerDeadline(def: GameDefinition, session: Session) {
        val window = def.settings[ANSWER_TIME]
        val whole = sessionDeadline(def, session)
        if (window <= 0L) {
            session.deadlineAt = whole
            return
        }
        val candidate = System.currentTimeMillis() + window * 1000L
        session.deadlineAt = if (whole <= 0L) candidate else minOf(whole, candidate)
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
        val cleared = state?.cleared ?: 0
        val outcome = Outcome(
            record = cleared.toLong(),
            tiebreak = session.elapsedMillis(),
            score = session.score,
            summary = listOf(
                "<red>시간이 초과되었습니다.</red>",
                "<gray>정답: <white>" + spaced(state?.sequence.orEmpty()) + "</white></gray>",
                "<gray>통과한 단계: <yellow>" + cleared + "단계</yellow></gray>",
            ),
        )
        return if (cleared > 0) StepResult.Cleared(outcome) else StepResult.Failed(outcome, "시간 초과")
    }

    // --- pure logic ------------------------------------------------------------

    fun roll(length: Int, rng: Random): String {
        val size = length.coerceIn(1, 32)
        val sb = StringBuilder(size)
        repeat(size) { sb.append(rng.nextInt(0, 10)) }
        return sb.toString()
    }

    /** Digits are spaced out so a long run does not read as one unbroken number. */
    fun spaced(digits: String): String = digits.toCharArray().joinToString(" ")

    /** Button id for ending the reveal early; the timer uses the default "auto" id. */
    const val SKIP = "skip"
}
