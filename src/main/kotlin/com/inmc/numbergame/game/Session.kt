package com.inmc.numbergame.game

import java.util.UUID

/**
 * One player's game in progress.
 *
 * Everything mutable about a play lives here, including the answer (inside [state]). Engines
 * are stateless singletons, so a session is the only thing that has to be persisted, and the
 * only thing that has to be thrown away when a player gives up.
 */
class Session(
    val playerId: UUID,
    val gameId: String,
    var state: GameState,
    startedAt: Long = System.currentTimeMillis(),
    /** Epoch millis at which the game is lost on time; 0 means untimed. */
    var deadlineAt: Long = 0L,
    var attempts: Int = 0,
    /** Rendered turn history, newest last. */
    val history: MutableList<String> = mutableListOf(),
    /** Running score for games that accumulate within one session (speed math, memory). */
    var score: Long = 0L,
    var lastActionAt: Long = System.currentTimeMillis(),
    /** True once the entry fee has been taken, so a refund knows there is something to give back. */
    var feePaid: Boolean = false,
) {

    /**
     * When this game began.
     *
     * Not final because a session restored after a restart has to have its clock moved forward:
     * the wall-clock instant it started at is meaningless once the server has been off for an
     * hour, but the *elapsed* time it had accumulated is not. See [restoreClock].
     */
    var startedAt: Long = startedAt
        private set

    /**
     * Net economy movement so far, for betting games.
     *
     * Kept next to [score] so that abandoning a session reports the same numbers a finished one
     * would - otherwise a losing gambler could quit to keep the loss off the leaderboard.
     */
    var netMoney: Double = 0.0

    /**
     * The player's balance as of this turn, refreshed before the engine runs.
     *
     * Betting games need to know what a player can afford, and this keeps them from having to
     * reach into Bukkit and Vault themselves - which is what makes them unit-testable.
     * Meaningless (and zero) for games that never touch money.
     */
    var balance: Double = 0.0

    /**
     * Money the engine has decided to move but not yet moved, positive for a payout.
     *
     * One field settled by the caller after every step, rather than each result type carrying
     * its own amount: a bet that wins on the last round of a session must pay out exactly like
     * one that wins in the middle, and a single flush point is how that stays true.
     */
    var pendingMoney: Double = 0.0

    /**
     * Bumped every time the screen genuinely moves on.
     *
     * Timed screens schedule a task against the revision they were drawn for; when it fires and
     * the number has moved, the turn already ended some other way and the task stands down.
     * Rejected input does not count as movement, so a typo does not cancel a running reveal.
     */
    var revision: Long = 0
        private set

    fun advance() {
        revision++
    }

    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        (now - startedAt).coerceAtLeast(0L)

    /** Seconds left, or -1 when the game is untimed. */
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        if (deadlineAt <= 0L) -1L else ((deadlineAt - now) / 1000L).coerceAtLeast(0L)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        deadlineAt > 0L && now >= deadlineAt

    fun touch(now: Long = System.currentTimeMillis()) {
        lastActionAt = now
    }

    /**
     * Re-anchors a restored session onto the current clock.
     *
     * Both halves matter. Keeping the *elapsed* time means a 90-second-old game is still
     * 90 seconds old and its ranking tiebreak is honest. Keeping the *remaining* time means a
     * five-minute game restored after an hour of downtime still has its unused minutes, instead
     * of being instantly failed for a timer that ran out while nobody could play.
     *
     * A null [remainingMillis] means the game is untimed. Zero is *not* the same thing: a game
     * that was already out of time when it was saved must come back still out of time, so the
     * ticker can finish it properly rather than letting it run forever.
     */
    fun restoreClock(elapsedMillis: Long, remainingMillis: Long?, now: Long = System.currentTimeMillis()) {
        startedAt = now - elapsedMillis.coerceAtLeast(0L)
        deadlineAt = if (remainingMillis == null) 0L else now + remainingMillis
        lastActionAt = now
    }

    /** Milliseconds left on the clock, or null when the game is untimed. May be negative. */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long? =
        if (deadlineAt <= 0L) null else deadlineAt - now

    /** Keeps the rendered history bounded so a long game cannot overflow the dialog body. */
    fun addHistory(line: String, limit: Int = 40) {
        history.add(line)
        while (history.size > limit) history.removeAt(0)
    }
}
