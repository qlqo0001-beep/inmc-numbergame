package com.inmc.numbergame

import kr.inmc.core.rank.Outcome
import kr.inmc.core.rank.Better
import kr.inmc.core.rank.RankBoard
import kr.inmc.core.rank.RankMode
import kr.inmc.core.reward.RankBracket
import kr.inmc.core.reward.RecordTier
import kr.inmc.core.reward.RewardTable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Leaderboard ordering and the reward brackets that read from it. */
class RankingTest {

    private fun outcome(record: Long, tiebreak: Long = 0L, score: Long = 0L) =
        Outcome(record = record, tiebreak = tiebreak, score = score, summary = emptyList())

    // Fixed ids so the tie test can rebuild the same board and compare orderings.
    private val leader = UUID.randomUUID()
    private val tiedA = UUID.randomUUID()
    private val tiedB = UUID.randomUUID()

    // --- ordering --------------------------------------------------------------

    @Test
    fun `fewest attempts wins when lower is better`() {
        val board = RankBoard()
        val quick = UUID.randomUUID()
        val slow = UUID.randomUUID()

        board.record(quick, "Quick", outcome(record = 3), cleared = true, better = Better.LOWER)
        board.record(slow, "Slow", outcome(record = 7), cleared = true, better = Better.LOWER)

        val order = board.sorted(RankMode.BEST_RECORD, Better.LOWER)
        assertEquals(listOf("Quick", "Slow"), order.map { it.name })
        assertEquals(1, board.rankOf(quick, RankMode.BEST_RECORD, Better.LOWER))
    }

    @Test
    fun `most solved wins when higher is better`() {
        val board = RankBoard()
        val many = UUID.randomUUID()
        val few = UUID.randomUUID()

        board.record(few, "Few", outcome(record = 4), cleared = true, better = Better.HIGHER)
        board.record(many, "Many", outcome(record = 19), cleared = true, better = Better.HIGHER)

        assertEquals(listOf("Many", "Few"), board.sorted(RankMode.BEST_RECORD, Better.HIGHER).map { it.name })
    }

    @Test
    fun `a tie on the record is broken by time taken`() {
        val board = RankBoard()
        val fast = UUID.randomUUID()
        val slow = UUID.randomUUID()

        board.record(slow, "Slow", outcome(record = 4, tiebreak = 90_000), cleared = true, better = Better.LOWER)
        board.record(fast, "Fast", outcome(record = 4, tiebreak = 12_000), cleared = true, better = Better.LOWER)

        assertEquals(listOf("Fast", "Slow"), board.sorted(RankMode.BEST_RECORD, Better.LOWER).map { it.name })
    }

    @Test
    fun `only a better attempt replaces the stored record`() {
        val board = RankBoard()
        val id = UUID.randomUUID()

        board.record(id, "P", outcome(record = 5, tiebreak = 1000), cleared = true, better = Better.LOWER)
        board.record(id, "P", outcome(record = 9, tiebreak = 10), cleared = true, better = Better.LOWER)
        assertEquals(5, board.entryOf(id)?.best, "더 나쁜 기록이 최고 기록을 덮어썼습니다")

        board.record(id, "P", outcome(record = 2, tiebreak = 9999), cleared = true, better = Better.LOWER)
        assertEquals(2, board.entryOf(id)?.best)
        assertEquals(3, board.entryOf(id)?.plays)
        assertEquals(3, board.entryOf(id)?.clears)
    }

    @Test
    fun `a failed attempt counts as a play but never as a record`() {
        val board = RankBoard()
        val id = UUID.randomUUID()

        board.record(id, "P", outcome(record = 1), cleared = false, better = Better.LOWER)

        assertEquals(1, board.entryOf(id)?.plays)
        assertEquals(0, board.entryOf(id)?.clears)
        assertTrue(board.sorted(RankMode.BEST_RECORD, Better.LOWER).isEmpty(), "기록 없는 사람이 순위에 올랐습니다")
        assertNull(board.rankOf(id, RankMode.BEST_RECORD, Better.LOWER))
    }

    @Test
    fun `cumulative score adds up across plays and ranks losers too`() {
        val board = RankBoard()
        val grinder = UUID.randomUUID()
        val ace = UUID.randomUUID()

        repeat(5) { board.record(grinder, "Grinder", outcome(record = 9, score = 30), cleared = true, better = Better.HIGHER) }
        board.record(ace, "Ace", outcome(record = 1, score = 100), cleared = true, better = Better.HIGHER)

        val order = board.sorted(RankMode.CUMULATIVE_SCORE, Better.HIGHER)
        assertEquals(listOf("Grinder", "Ace"), order.map { it.name })
        assertEquals(150, board.entryOf(grinder)?.score)
    }

    @Test
    fun `a losing session still places on a cumulative board`() {
        val board = RankBoard()
        val id = UUID.randomUUID()
        // Betting games record a negative score on a losing run; that has to be visible, not
        // dropped, or the leaderboard would only ever show winners.
        board.record(id, "Gambler", outcome(record = -500, score = -500), cleared = false, better = Better.HIGHER)

        assertEquals(-500, board.entryOf(id)?.score)
        assertEquals(1, board.rankOf(id, RankMode.CUMULATIVE_SCORE, Better.HIGHER))
    }

    // --- rankOf agrees with the list it is shown beside ------------------------

    @Test
    fun `rankOf matches the sorted position for every entry`() {
        // rankOf counts entries ahead instead of sorting, because scoreboards resolve it several
        // times a second. That optimisation is only safe if it still agrees with the list.
        val board = RankBoard()
        val rng = kotlin.random.Random(31337)
        val ids = List(80) { UUID.randomUUID() }

        ids.forEachIndexed { index, id ->
            board.record(
                id, "P$index",
                outcome(record = rng.nextLong(1, 40), tiebreak = rng.nextLong(1, 500_000)),
                cleared = true, better = Better.LOWER,
            )
        }

        val order = board.sorted(RankMode.BEST_RECORD, Better.LOWER)
        order.forEachIndexed { index, entry ->
            assertEquals(
                index + 1,
                board.rankOf(entry.id, RankMode.BEST_RECORD, Better.LOWER),
                "${entry.name} 의 순위가 목록 위치와 다릅니다",
            )
        }
    }

    @Test
    fun `rankOf agrees with the list on a cumulative board too`() {
        val board = RankBoard()
        val rng = kotlin.random.Random(4242)
        repeat(60) { index ->
            board.record(
                UUID.randomUUID(), "P$index",
                outcome(record = 0, score = rng.nextLong(-500, 500)),
                cleared = true, better = Better.HIGHER,
            )
        }

        val order = board.sorted(RankMode.CUMULATIVE_SCORE, Better.HIGHER)
        order.forEachIndexed { index, entry ->
            assertEquals(
                index + 1,
                board.rankOf(entry.id, RankMode.CUMULATIVE_SCORE, Better.HIGHER),
            )
        }
    }

    @Test
    fun `an exact tie is ordered stably rather than at random`() {
        // Season rewards are paid out by list position, so two players who match on every real
        // measure still need one settled order - and the same one on every redraw.
        fun build(): RankBoard {
            val board = RankBoard()
            board.record(leader, "Leader", outcome(record = 2, tiebreak = 100), cleared = true, better = Better.LOWER)
            board.record(tiedA, "TiedA", outcome(record = 5, tiebreak = 500), cleared = true, better = Better.LOWER)
            board.record(tiedB, "TiedB", outcome(record = 5, tiebreak = 500), cleared = true, better = Better.LOWER)
            board.entryOf(tiedA)?.lastPlayAt = 1_000
            board.entryOf(tiedB)?.lastPlayAt = 1_000
            return board
        }

        val first = build().sorted(RankMode.BEST_RECORD, Better.LOWER).map { it.name }
        repeat(20) {
            assertEquals(
                first, build().sorted(RankMode.BEST_RECORD, Better.LOWER).map { it.name },
                "완전 동점자의 순서가 매번 달라집니다",
            )
        }
        assertEquals("Leader", first.first())

        val board = build()
        board.sorted(RankMode.BEST_RECORD, Better.LOWER).forEachIndexed { index, entry ->
            assertEquals(index + 1, board.rankOf(entry.id, RankMode.BEST_RECORD, Better.LOWER))
        }
    }

    // --- reward brackets -------------------------------------------------------

    @Test
    fun `rank brackets cover their range and nothing else`() {
        val table = RewardTable()
        table.rankBrackets.add(RankBracket(1, 1))
        table.rankBrackets.add(RankBracket(2, 3))
        table.rankBrackets.add(RankBracket(4, 10))

        assertEquals("1위", table.bracketFor(1)?.describe())
        assertEquals("2~3위", table.bracketFor(3)?.describe())
        assertEquals("4~10위", table.bracketFor(10)?.describe())
        assertNull(table.bracketFor(11), "구간 밖인데 보상이 잡혔습니다")
    }

    @Test
    fun `a bracket cannot be inverted`() {
        val bracket = RankBracket(5, 10)
        bracket.from = 20
        assertTrue(bracket.to >= bracket.from, "시작이 끝을 넘어섰습니다")
    }

    @Test
    fun `every qualifying record tier pays out`() {
        val table = RewardTable()
        table.recordTiers.add(RecordTier(3))
        table.recordTiers.add(RecordTier(5))
        table.recordTiers.add(RecordTier(10))

        // Two attempts qualifies for all three "within N" tiers, best first.
        val matched = table.matchingTiers(record = 2, better = Better.LOWER)
        assertEquals(listOf(3L, 5L, 10L), matched.map { it.threshold })

        assertEquals(listOf(10L), table.matchingTiers(record = 8, better = Better.LOWER).map { it.threshold })
        assertTrue(table.matchingTiers(record = 20, better = Better.LOWER).isEmpty())
    }

    @Test
    fun `record tiers flip direction for higher-is-better games`() {
        val table = RewardTable()
        table.recordTiers.add(RecordTier(10))
        table.recordTiers.add(RecordTier(20))

        // "20점 이상" is the harder tier, so it comes first.
        val matched = table.matchingTiers(record = 25, better = Better.HIGHER)
        assertEquals(listOf(20L, 10L), matched.map { it.threshold })
        assertTrue(table.matchingTiers(record = 5, better = Better.HIGHER).isEmpty())
    }

    @Test
    fun `tier descriptions read the right way round`() {
        assertEquals("3회 이내", RecordTier(3).describe(Better.LOWER, "회"))
        assertEquals("20점 이상", RecordTier(20).describe(Better.HIGHER, "점"))
    }
}
