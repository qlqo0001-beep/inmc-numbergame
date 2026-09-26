package com.inmc.numbergame.event

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One cycle of a server-wide game: entries open, then a single draw settles everybody at once.
 *
 * Lotto and the unique-bid auction are not really "sessions" - nobody plays them alone and
 * nothing happens until the deadline - so they get their own shape rather than being forced
 * through the one-player session machinery.
 */
class EventRound(
    val gameId: String,
    var number: Int = 1,
    var opensAt: Long = System.currentTimeMillis(),
    var drawsAt: Long = 0L,
) {

    class Entry(
        val playerId: UUID,
        var name: String,
        /** The numbers this player committed to. One value for a bid, several for a lotto ticket. */
        var picks: List<Int>,
        val at: Long = System.currentTimeMillis(),
    )

    val entries: MutableMap<UUID, Entry> = ConcurrentHashMap()

    /** The numbers the draw produced; empty until it happens. */
    var result: List<Int> = emptyList()

    /** Standings from the last draw, best first. Kept so the result screen can show them. */
    var placings: List<Placing> = emptyList()

    class Placing(val playerId: UUID, val name: String, val rank: Int, val detail: String)

    val size: Int get() = entries.size

    fun entryOf(playerId: UUID): Entry? = entries[playerId]

    fun isDue(now: Long): Boolean = drawsAt in 1..now

    fun secondsLeft(now: Long): Long =
        if (drawsAt <= 0L) -1L else ((drawsAt - now) / 1000L).coerceAtLeast(0L)
}
