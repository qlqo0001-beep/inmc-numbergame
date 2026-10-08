package com.inmc.numbergame.play

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.CorePlugin
import kr.inmc.core.store.Profile
import kr.inmc.core.store.YamlFileStore
import kr.inmc.core.util.Durations
import com.inmc.numbergame.util.Ph
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The gate in front of every game: daily allowance, cooldown and entry fee.
 *
 * A "day" starts at the game's own [EntryConfig.dailyResetHour] rather than at midnight, and is
 * measured against the server clock rather than each player's first play. Per-player windows
 * drift, are impossible to explain to a player who is one minute early, and cannot be reset
 * fairly server-wide - and a server-wide reset is exactly what the admin tooling offers.
 */
class PlayService(private val ng: Ng) :
    YamlFileStore(ng.io, listOf("data", "plays.yml"), what = "플레이 기록") {

    class Record {
        var plays: Long = 0
        var todayCount: Int = 0
        /** Which game-day [todayCount] belongs to; a change resets the counter lazily. */
        var todayIndex: Long = Long.MIN_VALUE
        var lastPlayAt: Long = 0
        /** Game-day of the most recent clear, for the 일일 첫 클리어 reward. */
        var lastClearIndex: Long = Long.MIN_VALUE
    }

    /** Why a player may not start right now. */
    sealed interface Denial {
        data class DailyLimit(val limit: Int) : Denial
        data class Cooldown(val secondsLeft: Long) : Denial
        data class NeedMoney(val amount: Double) : Denial
        data class NeedItem(val label: String, val amount: Int) : Denial
    }

    private val records = ConcurrentHashMap<String, Record>()

    private fun key(playerId: UUID, gameId: String): String = playerId.toString() + "|" + gameId.lowercase()

    private fun recordOf(playerId: UUID, gameId: String): Record =
        records.computeIfAbsent(key(playerId, gameId)) { Record() }

    // --- day arithmetic --------------------------------------------------------

    /** Index of the game-day [now] falls in, given a reset hour. */
    fun dayIndex(now: Long, resetHour: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return moment.minusHours(resetHour.toLong().coerceIn(0L, 23L)).toLocalDate().toEpochDay()
    }

    /** Rolls the daily counter over if the day changed since it was last touched. */
    private fun syncDay(record: Record, def: GameDefinition, now: Long) {
        val today = dayIndex(now, def.entry.dailyResetHour)
        if (record.todayIndex != today) {
            record.todayIndex = today
            record.todayCount = 0
        }
    }

    // --- reads -----------------------------------------------------------------

    /** Plays left today, or null when the game is unlimited. */
    fun remaining(playerId: UUID, def: GameDefinition, now: Long = System.currentTimeMillis()): Int? {
        if (def.entry.dailyLimit <= 0) return null
        val record = recordOf(playerId, def.id)
        synchronized(record) {
            syncDay(record, def, now)
            return (def.entry.dailyLimit - record.todayCount).coerceAtLeast(0)
        }
    }

    fun playsOf(playerId: UUID, def: GameDefinition): Long = recordOf(playerId, def.id).plays

    fun cooldownLeft(playerId: UUID, def: GameDefinition, now: Long = System.currentTimeMillis()): Long {
        if (def.entry.cooldownSeconds <= 0L) return 0L
        val last = recordOf(playerId, def.id).lastPlayAt
        if (last <= 0L) return 0L
        val elapsed = (now - last) / 1000L
        return (def.entry.cooldownSeconds - elapsed).coerceAtLeast(0L)
    }

    // --- gate ------------------------------------------------------------------

    /** Null when the player may start. Checks only - nothing is consumed here. */
    fun check(player: Player, def: GameDefinition, now: Long = System.currentTimeMillis()): Denial? {
        remaining(player.uniqueId, def, now)?.let {
            if (it <= 0) return Denial.DailyLimit(def.entry.dailyLimit)
        }

        val cooldown = cooldownLeft(player.uniqueId, def, now)
        if (cooldown > 0L) return Denial.Cooldown(cooldown)

        val entry = def.entry
        // No economy means no money fee, rather than no game. A server without Vault should
        // still be able to run everything except the betting types, which are hidden outright
        // by GameRegistry.runnable - and both the setting help and the README say a money fee
        // is ignored when Vault is absent, so refusing here would be the code contradicting
        // its own documentation.
        if (entry.feeMoney > 0.0 && ng.economy.isEnabled) {
            if (!ng.economy.has(player, entry.feeMoney)) return Denial.NeedMoney(entry.feeMoney)
        }
        entry.feeItem?.let { spec ->
            if (countMatching(player, def) < entry.feeItemAmount) {
                return Denial.NeedItem(spec.label(), entry.feeItemAmount)
            }
        }
        return null
    }

    /** 가방과 배낭(core CarriedStorage — 2026-09-30)의 참가 아이템. 옛 아이템과 새 아이템에 둘 다 맞는 묶음도 한 번만 센다. */
    private fun countMatching(player: Player, def: GameDefinition): Int {
        val spec = def.entry.feeItem ?: return 0
        val old = NgRoles.legacy[def.id]
        return ng.itemMatcher.count(player) { ng.itemMatcher.matches(it, spec) || (old != null && ng.itemMatcher.matches(it, old)) }
    }

    /** Takes the fee. Only call after [check] returned null; returns false if something raced. */
    fun charge(player: Player, def: GameDefinition): Boolean {
        val entry = def.entry
        if (entry.feeMoney > 0.0 && ng.economy.isEnabled) {
            if (!ng.economy.withdraw(player, entry.feeMoney, entry.currency)) return false
            ng.messages.send(player, "entry-paid-money", Ph.of().money(entry.feeMoney))
        }
        val spec = entry.feeItem
        if (spec != null) {
            var taken = 0
            val old = NgRoles.legacy[def.id]
            repeat(entry.feeItemAmount) {
                // 커스텀아이템으로 옮기기 전의 옛 참가 아이템도 받는다(NgRoles).
                if (ng.itemMatcher.consumeOne(player, spec) || (old != null && ng.itemMatcher.consumeOne(player, old))) taken++
            }
            if (taken < entry.feeItemAmount) {
                // Half a fee is worse than no fee: hand back both the money and the items that
                // were already pulled, so a failed charge leaves the player exactly as it found
                // them rather than quietly eating part of the price.
                if (taken > 0) giveBack(player, spec, taken)
                if (entry.feeMoney > 0.0) ng.economy.deposit(player, entry.feeMoney, entry.currency)
                return false
            }
            ng.messages.send(
                player, "entry-paid-item",
                Ph.of().item(spec.label()).amount(entry.feeItemAmount),
            )
        }
        return true
    }

    /**
     * Returns [amount] copies of a fee item, dropping whatever will not fit.
     *
     * Rebuilt from the stored reference rather than kept aside, which is the same route a reward
     * takes - so a MMOItems fee comes back as a live item rather than a stale copy.
     */
    private fun giveBack(player: Player, spec: kr.inmc.core.item.StoredItem, amount: Int) {
        var left = amount
        while (left > 0) {
            val stack = ng.itemResolver.create(spec, left) ?: return
            left -= stack.amount
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
            // create() clamps to the stack size, so a large refund needs more than one pass.
            if (stack.amount <= 0) return
        }
    }

    /**
     * Hands the stake back.
     *
     * Normally only when the game is configured to refund losses. [force] overrides that for the
     * case where the game never actually happened - a voided event round, say - because
     * "실패 시 환불: 꺼짐" is a rule about losing, not a licence to keep money for a round that
     * was cancelled.
     */
    fun refund(player: Player, def: GameDefinition, force: Boolean = false) {
        val entry = def.entry
        if (!force && !entry.refundOnFail) return
        var refunded = false
        if (entry.feeMoney > 0.0 && ng.economy.isEnabled) {
            ng.economy.deposit(player, entry.feeMoney, entry.currency)
            refunded = true
        }
        entry.feeItem?.let { spec ->
            giveBack(player, spec, entry.feeItemAmount)
            refunded = true
        }
        if (refunded) ng.messages.send(player, "entry-refunded")
    }

    /** Counts one started game against the allowance and the cooldown. */
    fun recordStart(playerId: UUID, def: GameDefinition, now: Long = System.currentTimeMillis()) {
        val record = recordOf(playerId, def.id)
        synchronized(record) {
            syncDay(record, def, now)
            record.plays++
            record.todayCount++
            record.lastPlayAt = now
        }
        markDirty()
    }

    /** True when this is the player's first clear of the current game-day. */
    fun recordClear(playerId: UUID, def: GameDefinition, now: Long = System.currentTimeMillis()): Boolean {
        val record = recordOf(playerId, def.id)
        val today = dayIndex(now, def.entry.dailyResetHour)
        synchronized(record) {
            val first = record.lastClearIndex != today
            record.lastClearIndex = today
            markDirty()
            return first
        }
    }

    /**
     * 오프라인 플레이어의 이름. core 의 `profile` 이 유일한 출처다.
     *
     * 예전에는 이 클래스가 `names` 맵을 따로 들고 `rememberName` 으로 채웠는데, 그 메서드가
     * dirty 를 세우지 않아 크래시하면 잃는 상태였다. 이제 core 의 ProfileListener 가
     * 접속·퇴장에서 적으므로 여기서 기억할 것이 없다.
     */
    fun nameOf(playerId: UUID): String? = Profile.nameOf(CorePlugin.get().players, playerId)

    // --- admin resets ----------------------------------------------------------

    /**
     * Clears one player's daily counter and cooldown.
     *
     * [gameId] null wipes every game for that player. Lifetime [Record.plays] is deliberately
     * kept - it is a statistic, not an allowance, and resetting an allowance should not rewrite
     * history.
     */
    fun resetPlayer(playerId: UUID, gameId: String?): Int {
        var touched = 0
        val suffix = gameId?.lowercase()
        for ((mapKey, record) in records) {
            if (!mapKey.startsWith(playerId.toString() + "|")) continue
            if (suffix != null && mapKey.substringAfter('|') != suffix) continue
            synchronized(record) {
                record.todayCount = 0
                record.todayIndex = Long.MIN_VALUE
                record.lastPlayAt = 0
            }
            touched++
        }
        if (touched > 0) markDirty()
        return touched
    }

    /** Server-wide allowance reset. Returns how many player/game records were cleared. */
    fun resetAll(gameId: String?): Int {
        var touched = 0
        val suffix = gameId?.lowercase()
        for ((mapKey, record) in records) {
            if (suffix != null && mapKey.substringAfter('|') != suffix) continue
            synchronized(record) {
                record.todayCount = 0
                record.todayIndex = Long.MIN_VALUE
                record.lastPlayAt = 0
            }
            touched++
        }
        if (touched > 0) markDirty()
        return touched
    }

    fun describeDenial(denial: Denial): String = when (denial) {
        is Denial.DailyLimit -> "오늘 " + denial.limit + "회를 모두 사용했습니다"
        is Denial.Cooldown -> Durations.formatShort(denial.secondsLeft) + " 후 가능"
        is Denial.NeedMoney -> kr.inmc.core.util.Numbers.money(denial.amount) + "원 부족"
        is Denial.NeedItem -> denial.label + " " + denial.amount + "개 필요"
    }

    /** Sends the player the chat line matching a denial. */
    fun explain(player: Player, def: GameDefinition, denial: Denial) {
        when (denial) {
            is Denial.DailyLimit -> ng.messages.send(
                player, "entry-daily-limit", Ph.of().game(def.displayName).count(denial.limit),
            )
            is Denial.Cooldown -> ng.messages.send(
                player, "entry-cooldown", Ph.of().time(Durations.formatShort(denial.secondsLeft)),
            )
            is Denial.NeedMoney -> ng.messages.send(
                player, "entry-need-money", Ph.of().money(denial.amount),
            )
            is Denial.NeedItem -> ng.messages.send(
                player, "entry-need-item", Ph.of().item(denial.label).amount(denial.amount),
            )
        }
    }

    // --- persistence -----------------------------------------------------------

    override fun read(config: YamlConfiguration) {
        records.clear()
        // `names:` 는 더 이상 읽지 않는다 — core 의 profile 이 갖는다.
        // 남아 있는 섹션은 PlayNameImport 가 한 번 가져간 뒤 다음 저장에서 사라진다.
        config.getConfigurationSection("records")?.let { section ->
            for (raw in section.getKeys(false)) {
                val entry = section.getConfigurationSection(raw) ?: continue
                val record = Record()
                record.plays = entry.getLong("plays", 0L)
                record.todayCount = entry.getInt("today-count", 0)
                record.todayIndex = entry.getLong("today-index", Long.MIN_VALUE)
                record.lastPlayAt = entry.getLong("last-play", 0L)
                record.lastClearIndex = entry.getLong("last-clear-index", Long.MIN_VALUE)
                records[raw.replace('/', '|')] = record
            }
        }
    }

    override fun write(config: YamlConfiguration) {
        for ((mapKey, record) in records) {
            // YAML paths split on '.', so the composite key is stored with '/' as its separator.
            val path = "records." + mapKey.replace('|', '/')
            synchronized(record) {
                config.set("$path.plays", record.plays)
                config.set("$path.today-count", record.todayCount)
                config.set("$path.today-index", record.todayIndex)
                config.set("$path.last-play", record.lastPlayAt)
                config.set("$path.last-clear-index", record.lastClearIndex)
            }
        }
    }
}
