package com.inmc.numbergame

import com.inmc.numbergame.game.GameState
import com.inmc.numbergame.game.Session
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression cover for the session clock.
 *
 * Sessions are persisted as durations rather than instants. Storing the absolute deadline meant
 * that any timed game restored after the server had been off longer than its remaining time was
 * failed the instant it came back - the player lost their entry fee without ever taking a turn.
 */
class SessionClockTest {

    private object Dummy : GameState {
        override fun save(section: ConfigurationSection) = Unit
    }

    private fun session(startedAt: Long, deadlineAt: Long) = Session(
        playerId = UUID.randomUUID(),
        gameId = "baseball3",
        state = Dummy,
        startedAt = startedAt,
        deadlineAt = deadlineAt,
    )

    @Test
    fun `a restored session keeps the time it had left`() {
        val bootAt = 1_000_000_000L

        // Saved two minutes into a five-minute game, so three minutes were still owed.
        val restored = session(0, 0)
        restored.restoreClock(elapsedMillis = 120_000, remainingMillis = 180_000, now = bootAt)

        assertFalse(restored.isExpired(bootAt), "복원 직후 시간 초과로 판정됐습니다")
        assertEquals(180, restored.remainingSeconds(bootAt))
        assertEquals(120_000, restored.elapsedMillis(bootAt), "경과 시간이 보존되지 않았습니다")
    }

    @Test
    fun `downtime does not eat the remaining time`() {
        val savedAt = 1_000_000_000L
        val live = session(savedAt - 120_000, savedAt + 180_000)

        // What the file would hold, computed at save time...
        val elapsed = live.elapsedMillis(savedAt)
        val remaining = live.remainingMillis(savedAt)
        assertEquals(180_000, remaining)

        // ...and the server comes back an hour later.
        val bootAt = savedAt + 3_600_000L
        val restored = session(0, 0)
        restored.restoreClock(elapsed, remaining, now = bootAt)

        assertFalse(restored.isExpired(bootAt), "다운타임 뒤 복원한 세션이 즉시 만료됐습니다")
        assertEquals(180, restored.remainingSeconds(bootAt))
        // The hour the server spent switched off must not count against the player's record.
        assertEquals(120_000, restored.elapsedMillis(bootAt))
    }

    @Test
    fun `an untimed session stays untimed`() {
        val live = session(1_000_000_000L - 5_000, 0L)
        assertNull(live.remainingMillis(1_000_000_000L), "시간 제한이 없는데 남은 시간이 잡혔습니다")

        val restored = session(0, 0)
        restored.restoreClock(elapsedMillis = 5_000, remainingMillis = null, now = 2_000_000_000L)

        assertEquals(0L, restored.deadlineAt)
        assertFalse(restored.isExpired(2_000_000_000L))
        assertEquals(-1L, restored.remainingSeconds(2_000_000_000L))
    }

    @Test
    fun `a session that had already run out comes back expired`() {
        // Zero remaining must not be confused with "untimed", or an out-of-time game would be
        // restored as one that runs forever.
        val restored = session(0, 0)
        restored.restoreClock(elapsedMillis = 300_000, remainingMillis = -2_000, now = 5_000_000L)

        assertTrue(restored.isExpired(5_000_000L), "이미 끝난 세션이 무제한으로 복원됐습니다")
    }

    @Test
    fun `restoring clears the idle clock`() {
        // Otherwise a session saved before an hour of downtime is swept as abandoned the moment
        // the server is back, before the player has had any chance to touch it.
        val bootAt = 9_000_000L
        val restored = session(0, 0)
        restored.restoreClock(elapsedMillis = 1_000, remainingMillis = 60_000, now = bootAt)

        assertEquals(bootAt, restored.lastActionAt)
    }

    @Test
    fun `revision only moves when the turn does`() {
        val live = session(0, 0)
        assertEquals(0L, live.revision)
        live.advance()
        assertEquals(1L, live.revision)
    }
}
