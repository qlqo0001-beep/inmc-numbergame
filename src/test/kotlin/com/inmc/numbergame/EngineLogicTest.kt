package com.inmc.numbergame

import com.inmc.numbergame.game.engine.BaseballEngine
import com.inmc.numbergame.game.engine.BlackjackEngine
import com.inmc.numbergame.game.engine.LottoEngine
import com.inmc.numbergame.game.engine.NimEngine
import com.inmc.numbergame.game.engine.SequenceEngine
import com.inmc.numbergame.game.engine.SpeedMathEngine
import com.inmc.numbergame.game.engine.UniqueBidEngine
import com.inmc.numbergame.game.engine.UpDownEngine
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engines are deliberately free of Bukkit, so their rules can be checked here without a
 * server. These are the parts where a subtle mistake is invisible in play but wrong every time -
 * ball counting with repeated digits, ace handling, misère Nim's winning positions.
 */
class EngineLogicTest {

    // --- number baseball -------------------------------------------------------

    @Test
    fun `baseball scores strikes and balls`() {
        assertEquals(BaseballEngine.Verdict(3, 0), BaseballEngine.judge("123", "123"))
        assertEquals(BaseballEngine.Verdict(0, 3), BaseballEngine.judge("123", "231"))
        assertEquals(BaseballEngine.Verdict(1, 2), BaseballEngine.judge("123", "132"))
        assertEquals(BaseballEngine.Verdict(0, 0), BaseballEngine.judge("123", "456"))
    }

    @Test
    fun `baseball counts repeated digits by multiplicity`() {
        // The classic bug: guessing "11" against "12" must be 1S 0B, not 1S 1B. The answer holds
        // only one 1, so the second 1 in the guess has nothing left to match against.
        assertEquals(BaseballEngine.Verdict(1, 0), BaseballEngine.judge("12", "11"))
        assertEquals(BaseballEngine.Verdict(1, 0), BaseballEngine.judge("21", "11"))

        // ...and the mirror case, where the answer is the one holding the duplicate.
        assertEquals(BaseballEngine.Verdict(1, 0), BaseballEngine.judge("11", "12"))

        assertEquals(BaseballEngine.Verdict(2, 0), BaseballEngine.judge("11", "11"))
        assertEquals(BaseballEngine.Verdict(1, 2), BaseballEngine.judge("112", "121"))
    }

    @Test
    fun `baseball rejects bad guesses`() {
        assertNotNull(BaseballEngine.validate("12", 3, false, false))
        assertNotNull(BaseballEngine.validate("12a", 3, false, false))
        assertNotNull(BaseballEngine.validate("112", 3, allowDuplicate = false, allowLeadingZero = false))
        assertNotNull(BaseballEngine.validate("012", 3, allowDuplicate = false, allowLeadingZero = false))
        assertNull(BaseballEngine.validate("123", 3, allowDuplicate = false, allowLeadingZero = false))
        assertNull(BaseballEngine.validate("112", 3, allowDuplicate = true, allowLeadingZero = false))
        assertNull(BaseballEngine.validate("012", 3, allowDuplicate = false, allowLeadingZero = true))
    }

    @Test
    fun `baseball answers honour the digit rules`() {
        val rng = Random(20260826)
        repeat(500) {
            val distinct = BaseballEngine.roll(4, allowDuplicate = false, allowLeadingZero = false, rng = rng)
            assertEquals(4, distinct.length)
            assertEquals(4, distinct.toSet().size, "숫자가 중복됐습니다: $distinct")
            assertFalse(distinct.startsWith("0"), "맨 앞이 0입니다: $distinct")

            val repeats = BaseballEngine.roll(3, allowDuplicate = true, allowLeadingZero = false, rng = rng)
            assertEquals(3, repeats.length)
            assertFalse(repeats.startsWith("0"))
        }
    }

    // --- up and down -----------------------------------------------------------

    @Test
    fun `updown suggests enough attempts for a binary search`() {
        assertEquals(7, UpDownEngine.suggestedAttempts(100))
        assertEquals(10, UpDownEngine.suggestedAttempts(1000))
        assertEquals(1, UpDownEngine.suggestedAttempts(2))
        assertEquals(1, UpDownEngine.suggestedAttempts(1))
    }

    // --- 31 game ---------------------------------------------------------------

    @Test
    fun `nim leaves the opponent on a losing multiple`() {
        // Safe totals are those where (target - 1 - total) divides evenly by (maxAdd + 1).
        assertEquals(2, NimEngine.perfectMove(0, target = 31, maxAdd = 3))
        assertEquals(1, NimEngine.perfectMove(1, target = 31, maxAdd = 3))
        assertEquals(3, NimEngine.perfectMove(3, target = 31, maxAdd = 3))
    }

    @Test
    fun `nim perfect play wins from the opening move`() {
        val rng = Random(7)
        repeat(200) {
            var total = 0
            var aiTurn = true
            var loser: String? = null
            while (loser == null) {
                val move = if (aiTurn) {
                    NimEngine.perfectMove(total, 31, 3)
                } else {
                    // The human plays at random - perfect play must beat every one of them.
                    rng.nextInt(1, minOf(3, 31 - total) + 1)
                }
                total += move
                if (total >= 31) loser = if (aiTurn) "ai" else "human"
                aiTurn = !aiTurn
            }
            assertEquals("human", loser, "완벽한 AI가 선공에서 졌습니다")
        }
    }

    // --- blackjack -------------------------------------------------------------

    @Test
    fun `blackjack counts aces high only while it fits`() {
        assertEquals(21, BlackjackEngine.score(listOf(1, 10)))
        assertEquals(12, BlackjackEngine.score(listOf(1, 1)))
        assertEquals(13, BlackjackEngine.score(listOf(1, 1, 1)))
        assertEquals(21, BlackjackEngine.score(listOf(1, 1, 9)))
        assertEquals(20, BlackjackEngine.score(listOf(13, 12)))
        assertEquals(22, BlackjackEngine.score(listOf(10, 5, 7)))
    }

    @Test
    fun `blackjack deck holds four of every rank`() {
        val deck = BlackjackEngine.freshDeck(Random(1))
        assertEquals(52, deck.size)
        for (rank in 1..13) assertEquals(4, deck.count { it == rank }, "$rank 이 4장이 아닙니다")
    }

    // --- speed math ------------------------------------------------------------

    @Test
    fun `speed math only generates clean answers`() {
        val rng = Random(99)
        repeat(2000) {
            val q = SpeedMathEngine.roll("ALL", 30, rng)
            val expected = when (q.operator) {
                "+" -> q.left + q.right
                "-" -> q.left - q.right
                "×" -> q.left * q.right
                else -> q.left / q.right
            }
            assertEquals(expected, q.answer, "정답이 어긋납니다: ${q.left} ${q.operator} ${q.right}")
            assertTrue(q.answer >= 0, "음수 정답이 나왔습니다: ${q.answer}")
            if (q.operator == "÷") {
                assertEquals(0, q.left % q.right, "나누어떨어지지 않습니다: ${q.left} ÷ ${q.right}")
            }
        }
    }

    // --- sequences -------------------------------------------------------------

    @Test
    fun `sequence puzzles stay within a readable range`() {
        val rng = Random(4242)
        repeat(1000) {
            val puzzle = SequenceEngine.roll("HARD", 5, rng)
            assertEquals(5, puzzle.terms.size)
            assertTrue(puzzle.rule.isNotBlank())
            assertTrue(
                puzzle.answer in -100_000_000L..100_000_000L,
                "답이 너무 큽니다: ${puzzle.answer}",
            )
        }
    }

    @Test
    fun `arithmetic sequences continue their own step`() {
        val rng = Random(11)
        repeat(200) {
            val puzzle = SequenceEngine.roll("EASY", 5, rng)
            val step = puzzle.terms[1] - puzzle.terms[0]
            for (i in 1 until puzzle.terms.size) {
                assertEquals(step, puzzle.terms[i] - puzzle.terms[i - 1])
            }
            assertEquals(puzzle.terms.last() + step, puzzle.answer)
        }
    }

    // --- lotto -----------------------------------------------------------------

    @Test
    fun `lotto draws distinct numbers in range`() {
        val rng = Random(2026)
        repeat(500) {
            val drawn = LottoEngine.draw(45, 6, rng)
            assertEquals(6, drawn.size)
            assertEquals(6, drawn.toSet().size)
            assertTrue(drawn.all { it in 1..45 })
            assertEquals(drawn.sorted(), drawn, "정렬되어 있지 않습니다")
        }
    }

    @Test
    fun `lotto maps match counts onto placings`() {
        assertEquals(1, LottoEngine.rankOf(matched = 6, pickCount = 6, minMatch = 3))
        assertEquals(2, LottoEngine.rankOf(matched = 5, pickCount = 6, minMatch = 3))
        assertEquals(4, LottoEngine.rankOf(matched = 3, pickCount = 6, minMatch = 3))
        assertNull(LottoEngine.rankOf(matched = 2, pickCount = 6, minMatch = 3))
    }

    @Test
    fun `lotto ticket parsing catches the usual mistakes`() {
        assertTrue(LottoEngine.parseTicket("1, 2, 3, 4, 5, 6", 45, 6).isSuccess)
        assertTrue(LottoEngine.parseTicket("1 2 3 4 5 6", 45, 6).isSuccess)
        assertTrue(LottoEngine.parseTicket("1,2,3,4,5", 45, 6).isFailure, "개수 부족을 놓쳤습니다")
        assertTrue(LottoEngine.parseTicket("1,2,3,4,5,5", 45, 6).isFailure, "중복을 놓쳤습니다")
        assertTrue(LottoEngine.parseTicket("1,2,3,4,5,46", 45, 6).isFailure, "범위 초과를 놓쳤습니다")
        assertTrue(LottoEngine.parseTicket("a,2,3,4,5,6", 45, 6).isFailure, "숫자가 아닌 값을 놓쳤습니다")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), LottoEngine.parseTicket("6,5,4,3,2,1", 45, 6).getOrNull())
    }

    @Test
    fun `lotto counts matches against the draw`() {
        assertEquals(3, LottoEngine.matches(listOf(1, 2, 3, 40, 41, 42), listOf(1, 2, 3, 10, 11, 12)))
        assertEquals(0, LottoEngine.matches(listOf(1, 2), listOf(3, 4)))
    }

    // --- lowest unique bid -----------------------------------------------------

    @Test
    fun `unique bid drops every duplicated number`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val d = UUID.randomUUID()

        // 1 is picked twice so it is out entirely; 2 is the lowest number held alone.
        val entries = mapOf(a to 1, b to 1, c to 2, d to 5)
        assertEquals(listOf(c, d), UniqueBidEngine.rank(entries))
        assertEquals(2, UniqueBidEngine.winningNumber(entries))
    }

    @Test
    fun `unique bid can have no winner at all`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val entries = mapOf(a to 7, b to 7)
        assertTrue(UniqueBidEngine.rank(entries).isEmpty())
        assertNull(UniqueBidEngine.winningNumber(entries))
    }

    @Test
    fun `unique bid halves the score for each place down`() {
        assertEquals(100L, UniqueBidEngine.scoreFor(1, 100))
        assertEquals(50L, UniqueBidEngine.scoreFor(2, 100))
        assertEquals(25L, UniqueBidEngine.scoreFor(3, 100))
        assertEquals(0L, UniqueBidEngine.scoreFor(0, 100))
    }
}
