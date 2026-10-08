package com.inmc.numbergame.play

import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers
import org.bukkit.configuration.ConfigurationSection

/** What a player must have, and must not have used up, before a game will start. */
class EntryConfig(
    dailyLimit: Int = 0,
    dailyResetHour: Int = 4,
    var cooldownSeconds: Long = 0L,
    var feeMoney: Double = 0.0,
    var feeItem: StoredItem? = null,
    feeItemAmount: Int = 1,
    /** Hand the fee back when the player loses. Off by default - the fee is the stake. */
    var refundOnFail: Boolean = false,
    /** 참가비 화폐 id — 비우면 기본 화폐(2026-10-08). 판 안에서 거는 돈(베팅·블랙잭)도 이 화폐. */
    var currency: String = "",
) {

    /** 0 means unlimited. */
    var dailyLimit: Int = dailyLimit.coerceAtLeast(0)
        set(value) {
            field = value.coerceIn(0, 10000)
        }

    /**
     * Hour at which a player's daily allowance refills.
     *
     * Counted against the server clock rather than each player's first play, because a daily
     * allowance that drifts per player is impossible to explain and impossible to reset fairly
     * server-wide - and a server-wide reset is exactly what the admin tooling offers.
     */
    var dailyResetHour: Int = dailyResetHour.coerceIn(0, 23)
        set(value) {
            field = value.coerceIn(0, 23)
        }

    var feeItemAmount: Int = feeItemAmount.coerceIn(1, 64)
        set(value) {
            field = value.coerceIn(1, 64)
        }

    fun hasAnyFee(): Boolean = feeMoney > 0.0 || feeItem != null

    /** 참가비 글 — 화폐가 있으면 그 화폐 형식으로, 없으면 "1,000원". */
    fun feeLabel(): String = currency.takeIf { it.isNotBlank() }
        ?.let { id -> kr.inmc.core.economy.Currencies.get(id)?.format(Math.round(feeMoney)) ?: (Numbers.money(feeMoney) + " " + id) }
        ?: (Numbers.money(feeMoney) + "원")

    fun describe(): List<String> = buildList {
        add(if (dailyLimit <= 0) "<gray>일일 제한: <white>무제한</white></gray>"
            else "<gray>일일 제한: <white>${dailyLimit}회</white> <dark_gray>(매일 ${dailyResetHour}시 초기화)</dark_gray></gray>")
        if (cooldownSeconds > 0L) {
            add("<gray>재플레이 대기: <white>${Durations.formatShort(cooldownSeconds)}</white></gray>")
        }
        if (feeMoney > 0.0) add("<gray>참가비: <gold>${feeLabel()}</gold></gray>")
        feeItem?.let { add("<gray>참가 아이템: <white>${it.label()} ${feeItemAmount}개</white></gray>") }
        if (isEmpty()) add("<dark_gray>참가 조건 없음</dark_gray>")
    }

    fun isEmpty(): Boolean = dailyLimit <= 0 && cooldownSeconds <= 0L && !hasAnyFee()

    fun copyOf(): EntryConfig = EntryConfig(
        dailyLimit, dailyResetHour, cooldownSeconds, feeMoney, feeItem, feeItemAmount, refundOnFail, currency,
    )

    fun save(section: ConfigurationSection) {
        section.set("daily-limit", dailyLimit)
        section.set("daily-reset-hour", dailyResetHour)
        section.set("cooldown", Durations.format(cooldownSeconds))
        section.set("refund-on-fail", refundOnFail)
        val fee = section.createSection("fee")
        fee.set("money", feeMoney)
        fee.set("currency", currency.takeIf { it.isNotBlank() })
        fee.set("amount", feeItemAmount)
        feeItem?.save(fee)
    }

    companion object {
        fun load(section: ConfigurationSection?): EntryConfig {
            if (section == null) return EntryConfig()
            val fee = section.getConfigurationSection("fee")
            return EntryConfig(
                dailyLimit = section.getInt("daily-limit", 0),
                dailyResetHour = section.getInt("daily-reset-hour", 4),
                cooldownSeconds = Durations.parse(section.getString("cooldown"), 0L),
                feeMoney = fee?.getDouble("money", 0.0) ?: 0.0,
                currency = fee?.getString("currency").orEmpty(),
                feeItem = fee?.let { StoredItem.load(it) },
                feeItemAmount = fee?.getInt("amount", 1) ?: 1,
                refundOnFail = section.getBoolean("refund-on-fail", false),
            )
        }
    }
}
