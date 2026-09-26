package com.inmc.numbergame

import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameType
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.engine.BaseballEngine
import com.inmc.numbergame.game.engine.BettingEngine
import com.inmc.numbergame.game.engine.MemoryEngine
import com.inmc.numbergame.game.engine.NimEngine
import com.inmc.numbergame.game.engine.SpeedMathEngine
import com.inmc.numbergame.game.engine.UpDownEngine
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end playthroughs, driven straight against the engines.
 *
 * The unit tests above check individual rules; this checks that a whole game can actually be
 * played from first turn to result. It exists because the surrounding machinery - the session
 * clock, the score carried on the session, the money the caller settles - was changed after the
 * engines were written, and none of that is visible from a rules test.
 */
class GameFlowTest {

    private fun def(type: GameType, vararg settings: Pair<String, Any>): GameDefinition {
        val definition = GameDefinition.create(type.name.lowercase(), type)
        settings.forEach { (key, value) -> definition.settings.putRaw(key, value) }
        return definition
    }

    private fun session(definition: GameDefinition, rng: Random): Session {
        val limit = definition.engine.timeLimitSeconds(definition)
        val now = System.currentTimeMillis()
        return Session(
            playerId = UUID.randomUUID(),
            gameId = definition.id,
            state = definition.engine.start(definition, rng),
            startedAt = now,
            deadlineAt = if (limit > 0L) now + limit * 1000L else 0L,
        )
    }

    // --- every screen must be usable -------------------------------------------

    /**
     * A dialog with no button at all is rejected by the server, not merely ugly.
     *
     * `DialogType.multiAction` throws `actions cannot be empty`, and because screens are drawn
     * from a scheduled task the failure surfaces a tick later as a plugin exception with the
     * player left looking at nothing. The memory game's reveal screen did exactly this: it was
     * meant to advance on a timer, so it had no input, no buttons, and give-up disabled.
     */
    @Test
    fun `every game screen offers at least one action`() {
        val rng = Random(2026)
        for (type in GameType.entries) {
            val game = def(type)
            val play = session(game, rng)
            val screen = game.engine.screen(game, play)
            assertTrue(
                screen.inputs.isNotEmpty() || screen.buttons.isNotEmpty() || screen.canGiveUp,
                "$type 의 첫 화면에 누를 것이 하나도 없습니다 - 서버가 다이얼로그를 거부합니다",
            )
        }
    }

    @Test
    fun `the memory reveal screen is actionable in both phases`() {
        val rng = Random(1)
        val game = def(GameType.MEMORY)
        val play = session(game, rng)
        val state = play.state as MemoryEngine.State

        val reveal = game.engine.screen(game, play)
        assertTrue(reveal.buttons.isNotEmpty(), "노출 화면에 버튼이 없습니다")
        assertFalse(reveal.canGiveUp, "노출 화면에서 포기할 수 있으면 안 됩니다")

        // The button ends the reveal early, exactly as the timer would.
        game.engine.submit(game, play, SubmitInput(button = MemoryEngine.SKIP), rng)
        assertEquals(MemoryEngine.Phase.ANSWERING, state.phase, "버튼으로 입력 단계에 못 갔습니다")

        val answering = game.engine.screen(game, play)
        assertTrue(answering.inputs.isNotEmpty(), "입력 화면에 입력란이 없습니다")
    }

    // --- number baseball -------------------------------------------------------

    @Test
    fun `baseball can be played to a win`() {
        val rng = Random(1234)
        val game = def(GameType.BASEBALL)
        val play = session(game, rng)
        val answer = (play.state as BaseballEngine.State).answer

        // Two deliberate misses, then the answer.
        val wrong = generateWrongGuess(answer)
        repeat(2) {
            val step = game.engine.submit(game, play, SubmitInput.of("guess", wrong), rng)
            assertTrue(step is StepResult.Continue, "오답인데 게임이 끝났습니다: $step")
        }

        val result = game.engine.submit(game, play, SubmitInput.of("guess", answer), rng)
        assertTrue(result is StepResult.Cleared, "정답을 맞혔는데 클리어가 아닙니다: $result")
        assertEquals(3, result.outcome.record, "시도 횟수가 기록과 다릅니다")
        assertTrue(result.outcome.score > 0, "클리어 점수가 0입니다")
        assertEquals(3, play.attempts)
    }

    @Test
    fun `baseball ends when the attempts run out`() {
        val rng = Random(99)
        val game = def(GameType.BASEBALL, "max-attempts" to 3)
        val play = session(game, rng)
        val wrong = generateWrongGuess((play.state as BaseballEngine.State).answer)

        assertTrue(game.engine.submit(game, play, SubmitInput.of("guess", wrong), rng) is StepResult.Continue)
        assertTrue(game.engine.submit(game, play, SubmitInput.of("guess", wrong), rng) is StepResult.Continue)
        val last = game.engine.submit(game, play, SubmitInput.of("guess", wrong), rng)

        assertTrue(last is StepResult.Failed, "시도를 다 썼는데 끝나지 않았습니다: $last")
        assertEquals("시도 초과", last.reason)
    }

    @Test
    fun `a rejected guess costs nothing`() {
        val rng = Random(7)
        val game = def(GameType.BASEBALL)
        val play = session(game, rng)

        val result = game.engine.submit(game, play, SubmitInput.of("guess", "12"), rng)
        assertTrue(result is StepResult.Rejected, "자릿수가 틀렸는데 통과했습니다")
        assertEquals(0, play.attempts, "거부된 입력이 시도 횟수를 잡아먹었습니다")
        assertTrue(play.history.isEmpty())
    }

    /** Any valid-shaped guess that is not the answer. */
    private fun generateWrongGuess(answer: String): String {
        val candidates = listOf("123", "456", "789", "132", "465")
        return candidates.first { it != answer && it.toSet().size == answer.length }
    }

    // --- up and down -----------------------------------------------------------

    @Test
    fun `updown can be won by binary search within its own advice`() {
        val rng = Random(555)
        val game = def(GameType.UPDOWN)
        val play = session(game, rng)
        val state = play.state as UpDownEngine.State

        var low = 1
        var high = 100
        var result: StepResult = StepResult.Continue()
        var guesses = 0

        while (result is StepResult.Continue && guesses < 10) {
            val guess = (low + high) / 2
            guesses++
            result = game.engine.submit(game, play, SubmitInput.of("guess", guess.toString()), rng)
            if (guess < state.answer) low = guess + 1 else high = guess - 1
        }

        assertTrue(result is StepResult.Cleared, "이분탐색으로 못 맞혔습니다: $result")
        assertTrue(
            guesses <= UpDownEngine.suggestedAttempts(100),
            "권장 횟수($guesses)를 넘겨서야 맞혔습니다",
        )
    }

    // --- speed math ------------------------------------------------------------

    @Test
    fun `speed math banks its score on the session as it goes`() {
        val rng = Random(31)
        val game = def(GameType.SPEED_MATH, "score-per-correct" to 10)
        val play = session(game, rng)

        repeat(4) {
            val state = play.state as SpeedMathEngine.State
            val step = game.engine.submit(game, play, SubmitInput.of("answer", state.answer.toString()), rng)
            assertTrue(step is StepResult.Continue, "정답인데 게임이 끝났습니다: $step")
        }

        assertEquals(40L, play.score, "세션에 점수가 쌓이지 않았습니다")

        // Running out of time is the normal ending, and it must keep what was banked.
        val timeout = game.engine.onTimeout(game, play)
        assertTrue(timeout is StepResult.Cleared, "4문제를 맞혔는데 실패 처리됐습니다")
        assertEquals(40L, timeout.outcome.score)
        assertEquals(4L, timeout.outcome.record)
    }

    @Test
    fun `speed math with no correct answers is a loss, not a clear`() {
        val rng = Random(32)
        val game = def(GameType.SPEED_MATH)
        val play = session(game, rng)

        val timeout = game.engine.onTimeout(game, play)
        assertTrue(timeout is StepResult.Failed, "한 문제도 못 풀었는데 클리어 처리됐습니다")
    }

    // --- memory ----------------------------------------------------------------

    @Test
    fun `memory reveals then accepts the answer`() {
        val rng = Random(808)
        val game = def(GameType.MEMORY, "start-length" to 3)
        val play = session(game, rng)
        val state = play.state as MemoryEngine.State

        // The reveal screen advances on a timer rather than on input.
        assertTrue(game.engine.screen(game, play).autoAdvanceMillis > 0, "노출 화면에 타이머가 없습니다")
        assertEquals(MemoryEngine.Phase.SHOWING, state.phase)

        val digits = state.sequence
        game.engine.submit(game, play, SubmitInput(button = "auto"), rng)
        assertEquals(MemoryEngine.Phase.ANSWERING, state.phase, "타이머가 입력 단계로 넘기지 못했습니다")
        assertEquals(0L, game.engine.screen(game, play).autoAdvanceMillis, "입력 화면에 타이머가 남았습니다")

        val step = game.engine.submit(game, play, SubmitInput.of("answer", digits), rng)
        assertTrue(step is StepResult.Continue, "정답인데 진행되지 않았습니다: $step")
        assertEquals(1, state.cleared)
        assertEquals(MemoryEngine.Phase.SHOWING, state.phase, "다음 단계가 노출로 돌아가지 않았습니다")
        assertEquals(4, state.sequence.length, "단계마다 한 자리씩 늘어나지 않았습니다")
    }

    @Test
    fun `a restored memory game gets its reveal back with time to answer`() {
        val rng = Random(909)
        val game = def(GameType.MEMORY, "answer-time" to "10s", "time-limit" to "5m")
        val play = session(game, rng)
        val state = play.state as MemoryEngine.State

        // Get into the answering phase, which arms the short per-level clock...
        game.engine.submit(game, play, SubmitInput(button = "auto"), rng)
        assertEquals(MemoryEngine.Phase.ANSWERING, state.phase)
        val answerDeadline = play.deadlineAt

        // ...then simulate the restore: clock re-anchored, phase forced back to the reveal.
        // The instant is pinned so the assertions below read the same clock the restore used;
        // taking a fresh reading would lose a millisecond and round 270 seconds down to 269.
        val bootAt = 1_700_000_000_000L
        state.phase = MemoryEngine.Phase.SHOWING
        play.restoreClock(elapsedMillis = 30_000, remainingMillis = 4_000, now = bootAt)
        game.engine.onRestore(game, play)

        assertFalse(play.isExpired(bootAt), "복원된 기억 게임이 즉시 만료됐습니다")
        assertTrue(
            play.deadlineAt > answerDeadline || play.remainingSeconds(bootAt) > 10,
            "다시 보여주는데 짧은 입력 제한시간이 그대로 남았습니다",
        )
        // Whole-session budget minus the elapsed time, not the leftover answer window.
        assertEquals(270, play.remainingSeconds(bootAt), "전체 제한 시간이 복원되지 않았습니다")
    }

    // --- 31 game ---------------------------------------------------------------

    @Test
    fun `nim plays a full match and banks wins on the session`() {
        val rng = Random(4)
        val game = def(GameType.NIM, "rounds" to 1, "ai-level" to "EASY", "first-move" to "PLAYER")
        val play = session(game, rng)

        var result: StepResult = StepResult.Continue()
        var turns = 0
        while (result is StepResult.Continue && turns < 40) {
            turns++
            val state = play.state as NimEngine.State
            val target = game.settings[NimEngine.TARGET]
            val maxAdd = game.settings[NimEngine.MAX_ADD]
            // Play perfectly; against an EASY AI this should resolve one way or the other.
            val move = NimEngine.perfectMove(state.total, target, maxAdd)
            result = game.engine.submit(game, play, SubmitInput(button = move.toString()), rng)
        }

        assertTrue(turns < 40, "게임이 끝나지 않고 계속 돌았습니다")
        assertTrue(
            result is StepResult.Cleared || result is StepResult.Failed,
            "한 판인데 결과가 나오지 않았습니다: $result",
        )
    }

    @Test
    fun `nim refuses a move that would overshoot the target`() {
        val rng = Random(5)
        val game = def(GameType.NIM)
        val play = session(game, rng)
        (play.state as NimEngine.State).total = 30

        val result = game.engine.submit(game, play, SubmitInput(button = "3"), rng)
        assertTrue(result is StepResult.Rejected, "31을 넘기는 수가 통과했습니다: $result")
    }

    // --- betting ---------------------------------------------------------------

    @Test
    fun `a bet stages its money on the session for the caller to settle`() {
        val rng = Random(2468)
        val game = def(GameType.BETTING, "rounds" to 5, "min-bet" to 100.0, "max-bet" to 1000.0)
        val play = session(game, rng)
        play.balance = 10_000.0

        val step = game.engine.submit(
            game, play,
            SubmitInput(mapOf("pick" to "ODD", "amount" to "500")),
            rng,
        )

        assertTrue(step is StepResult.Continue, "5판 중 첫 판인데 끝났습니다: $step")
        assertTrue(play.pendingMoney != 0.0, "베팅했는데 정산할 금액이 없습니다")
        // Both must be set: abandoning the session reports from these, not from engine state.
        assertEquals(Math.round(play.netMoney), play.score, "세션 점수와 순수익이 어긋납니다")
        assertTrue(
            play.pendingMoney == 500.0 * (game.settings[BettingEngine.PAYOUT_EVEN] - 1.0) ||
                play.pendingMoney == -500.0,
            "정산액이 배당표와 맞지 않습니다: ${play.pendingMoney}",
        )
    }

    @Test
    fun `a bet beyond the balance is refused without touching the session`() {
        val rng = Random(13)
        val game = def(GameType.BETTING, "min-bet" to 100.0, "max-bet" to 100_000.0)
        val play = session(game, rng)
        play.balance = 200.0

        val step = game.engine.submit(
            game, play,
            SubmitInput(mapOf("pick" to "ODD", "amount" to "5000")),
            rng,
        )

        assertTrue(step is StepResult.Rejected, "소지금보다 큰 베팅이 통과했습니다")
        assertEquals(0.0, play.pendingMoney, "거부된 베팅이 돈을 움직였습니다")
        assertEquals(0, play.attempts)
    }

    // --- the give-up path ------------------------------------------------------

    @Test
    fun `an abandoned session still carries the score it earned`() {
        // GameService.giveUp builds its Outcome from these two fields. They are what stops a
        // losing gambler from quitting to keep the loss off the 순수익 board.
        val rng = Random(77)
        val game = def(GameType.SPEED_MATH, "score-per-correct" to 25)
        val play = session(game, rng)

        repeat(3) {
            val state = play.state as SpeedMathEngine.State
            game.engine.submit(game, play, SubmitInput.of("answer", state.answer.toString()), rng)
        }

        assertEquals(75L, play.score, "포기 시 넘겨줄 점수가 세션에 없습니다")
    }
}
