package com.inmc.numbergame.util

import kr.inmc.core.util.Numbers
import kr.inmc.core.util.TokenBag
import org.bukkit.entity.Player

/**
 * Placeholder bag for a single message render.
 *
 * Tokens are spelled in Korean because that is what an admin editing `messages.yml` will
 * reach for; English aliases resolve to the same values so either spelling works.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES



    fun player(name: String): Ph = put(PLAYER, name)

    fun player(player: Player): Ph = put(PLAYER, player.name)

    fun game(displayName: String): Ph = put(GAME, displayName)

    fun attempts(value: Int): Ph = put(ATTEMPTS, value.toString())

    fun score(value: Long): Ph = put(SCORE, String.format("%,d", value))

    fun rank(value: Int): Ph = put(RANK, value.toString())

    fun record(text: String): Ph = put(RECORD, text)

    fun money(amount: Double): Ph = put(MONEY, Numbers.money(amount))

    fun item(name: String): Ph = put(ITEM, name)

    fun time(text: String): Ph = put(TIME, text)

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun amount(value: Int): Ph = put(AMOUNT, value.toString())

    fun season(value: Int): Ph = put(SEASON, value.toString())

    fun reason(text: String): Ph = put(REASON, text)

    fun copy(): Ph = copyValuesInto(Ph())



    companion object {
        const val PLAYER = "player"
        const val GAME = "game"
        const val ATTEMPTS = "attempts"
        const val SCORE = "score"
        const val RANK = "rank"
        const val RECORD = "record"
        const val MONEY = "money"
        const val ITEM = "item"
        const val TIME = "time"
        const val COUNT = "count"
        const val AMOUNT = "amount"
        const val SEASON = "season"
        const val REASON = "reason"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어네임}", "{플레이어}", "{player}"),
            GAME to listOf("{게임이름}", "{게임}", "{game}"),
            ATTEMPTS to listOf("{시도}", "{시도횟수}", "{attempts}"),
            SCORE to listOf("{점수}", "{score}"),
            RANK to listOf("{순위}", "{rank}"),
            RECORD to listOf("{기록}", "{record}"),
            MONEY to listOf("{금액}", "{돈}", "{money}"),
            ITEM to listOf("{아이템}", "{item}"),
            TIME to listOf("{시간}", "{time}"),
            COUNT to listOf("{개수}", "{count}"),
            AMOUNT to listOf("{수량}", "{amount}"),
            SEASON to listOf("{시즌}", "{season}"),
            REASON to listOf("{사유}", "{reason}"),
        )

        fun of(): Ph = Ph()
    }
}
