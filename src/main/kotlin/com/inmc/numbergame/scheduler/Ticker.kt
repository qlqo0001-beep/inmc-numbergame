package com.inmc.numbergame.scheduler

import com.inmc.numbergame.Ng
import kr.inmc.core.scheduler.TickerBase

/**
 * The plugin's one and only repeating task, running at 1 Hz.
 *
 * Everything periodic lives here rather than in its own scheduled job: session timeouts, the
 * season reset clock, and the debounced disk flushes. One task at one hertz is cheap enough to
 * be invisible in a profile, and it means there is exactly one place where a stuck stage can be
 * seen and isolated - each step is wrapped so one broken game cannot stop the rest.
 */
class Ticker(private val ng: Ng) : TickerBase(ng.plugin) {

    override val periodTicks = PERIOD_TICKS

    private var lastSaveAt = 0L
    private var lastPurgeAt = 0L

    override fun ready(): Boolean = ng.ready

    override fun tick(now: Long) {
        step("sessions") { ng.gameService.tick(now) }
        step("ranking") { ng.ranks.tick(now) }
        step("events") { ng.events.tick(now) }

        // Serialising is main-thread work - only the disk write is handed off - so it runs on
        // its own slower clock rather than every second. Nothing that decides a reward rides on
        // this cadence; shutdown flushes unconditionally.
        if (now - lastSaveAt >= ng.config.saveIntervalSeconds * 1000L) {
            lastSaveAt = now
            step("flush") { flushAll() }
        }

        if (now - lastPurgeAt >= PURGE_PERIOD_MS) {
            lastPurgeAt = now
            step("mailbox-purge") {
                val removed = ng.mailbox.purgeExpired(now)
                if (removed > 0) ng.logger.info("오래된 우편함 보상 " + removed + "건을 정리했습니다")
            }
        }
    }

    private fun flushAll() {
        ng.games.flushDirty()
        ng.sessions.flush()
        ng.events.flush()
        ng.ranks.flush()
        ng.plays.flush()
        ng.mailbox.flush()
        ng.log.flush()
    }

    companion object {
        const val PERIOD_TICKS = 20L

        /** Expired mailbox entries are swept hourly - nothing depends on it being prompt. */
        const val PURGE_PERIOD_MS = 3_600_000L
    }
}
