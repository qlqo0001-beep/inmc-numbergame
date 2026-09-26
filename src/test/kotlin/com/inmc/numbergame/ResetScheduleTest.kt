package com.inmc.numbergame

import kr.inmc.core.rank.ResetPolicy
import kr.inmc.core.rank.ResetSchedule
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The season clock.
 *
 * The property everything else depends on is that [ResetSchedule.nextAfter] returns an instant
 * **strictly after** the one it was given. That is what makes a server outage safe: the ticker
 * settles once, asks for the next time from now, and a fortnight of downtime produces one reset
 * rather than fourteen.
 */
class ResetScheduleTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun render(millis: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone).toString()

    private fun schedule(policy: ResetPolicy, block: ResetSchedule.() -> Unit = {}): ResetSchedule =
        ResetSchedule(policy = policy, zoneId = zone.id).apply(block)

    @Test
    fun `daily reset lands on the next configured hour`() {
        val daily = schedule(ResetPolicy.DAILY) { hour = 4; minute = 0 }

        assertEquals("2026-08-26T04:00", render(daily.nextAfter(at("2026-08-26T03:59"))))
        // Exactly on the hour still moves to tomorrow - the boundary belongs to the reset that
        // just fired, otherwise the ticker would settle the same period twice.
        assertEquals("2026-08-27T04:00", render(daily.nextAfter(at("2026-08-26T04:00"))))
        assertEquals("2026-08-27T04:00", render(daily.nextAfter(at("2026-08-26T12:00"))))
    }

    @Test
    fun `weekly reset lands on the configured weekday`() {
        val weekly = schedule(ResetPolicy.WEEKLY) {
            weekday = DayOfWeek.MONDAY
            hour = 4
        }
        // 2026-08-26 is a Wednesday.
        val next = weekly.nextAfter(at("2026-08-26T12:00"))
        assertEquals("2026-08-31T04:00", render(next))
        assertEquals(DayOfWeek.MONDAY, LocalDateTime.parse(render(next)).dayOfWeek)
    }

    @Test
    fun `monthly reset clamps to the last day of a short month`() {
        val monthly = schedule(ResetPolicy.MONTHLY) { dayOfMonth = 31; hour = 4 }
        // February has no 31st, so the reset lands on the last day rather than being skipped.
        assertEquals("2026-02-28T04:00", render(monthly.nextAfter(at("2026-02-01T00:00"))))
    }

    @Test
    fun `interval reset counts from the moment it is asked`() {
        val interval = schedule(ResetPolicy.INTERVAL) { intervalSeconds = 3600L }
        val from = at("2026-08-26T12:00")
        assertEquals("2026-08-26T13:00", render(interval.nextAfter(from)))
    }

    @Test
    fun `manual and none never schedule anything`() {
        assertEquals(0L, schedule(ResetPolicy.MANUAL).nextAfter(at("2026-08-26T12:00")))
        assertEquals(0L, schedule(ResetPolicy.NONE).nextAfter(at("2026-08-26T12:00")))
    }

    @Test
    fun `a long outage settles exactly once`() {
        val weekly = schedule(ResetPolicy.WEEKLY) { weekday = DayOfWeek.MONDAY; hour = 4 }

        // The server went down having scheduled this, then came back a fortnight later.
        var nextReset = weekly.nextAfter(at("2026-08-03T12:00"))
        val bootAt = at("2026-08-26T12:00")

        var settlements = 0
        // The ticker's actual loop: fire at most once per pass, then recompute from now.
        while (nextReset in 1..bootAt) {
            settlements++
            nextReset = weekly.nextAfter(bootAt)
            if (settlements > 5) break
        }

        assertEquals(1, settlements, "밀린 초기화가 여러 번 실행됐습니다")
        assertTrue(nextReset > bootAt, "다음 초기화가 과거로 잡혔습니다")
        assertEquals("2026-08-31T04:00", render(nextReset))
    }

    @Test
    fun `every automatic policy always moves forward`() {
        val from = at("2026-08-26T04:00")
        for (policy in ResetPolicy.entries.filter { it.automatic }) {
            val next = schedule(policy) { hour = 4; minute = 0 }.nextAfter(from)
            assertTrue(next > from, "$policy 가 과거 또는 현재를 반환했습니다")
        }
    }

    @Test
    fun `weekday names round-trip through config text`() {
        assertEquals(DayOfWeek.MONDAY, ResetSchedule.parseWeekday("MONDAY"))
        assertEquals(DayOfWeek.SATURDAY, ResetSchedule.parseWeekday("토요일"))
        assertEquals(DayOfWeek.MONDAY, ResetSchedule.parseWeekday("나쁜값"))
    }
}
