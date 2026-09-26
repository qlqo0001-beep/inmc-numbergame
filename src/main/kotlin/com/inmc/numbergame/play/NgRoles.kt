package com.inmc.numbergame.play

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material

/**
 * 숫자야구가 커스텀아이템에 내놓는 역할(core [ItemRoles]) — **참가 아이템**(값: 어느 게임의 참가비인지).
 *
 * 커스텀아이템이 있으면 게임의 참가 아이템은 그 역할을 맡은 커스텀아이템이다([sync]). 바닐라 그대로인 참가 아이템(다이아몬드 등)은
 * 옮기지 않는다 — 커스텀아이템으로 바꾸면 평범한 다이아몬드를 못 받는다. 옮긴 것도 이미 나가 있는 옛 아이템은 계속 받는다(legacy).
 */
object NgRoles {

    const val OWNER = "숫자야구"
    val FEE = "numbergame.fee"

    fun roles(ng: Ng): List<ItemRoles.Role> = listOf(
        ItemRoles.Role(
            FEE, OWNER, "참가 아이템", Material.PAPER,
            listOf("게임을 시작할 때 이 아이템을 냅니다(개수는 게임의 참가 조건에서)."),
            listOf(ItemRoles.Choice("game", "게임", { ng.games.all().map { it.id to it.displayName } })),
        ),
    )

    /** 게임 id → 옮기기 전의 참가 아이템. */
    @Volatile
    var legacy: Map<String, StoredItem> = emptyMap()
        private set

    private fun ours(ref: ItemRef?) = (ref as? ItemRef.Namespaced)?.namespace.equals("inmc", ignoreCase = true)

    private var syncing = false

    fun sync(ng: Ng) {
        if (!ItemRoles.active) {
            legacy = emptyMap()
            return
        }
        if (syncing) return
        syncing = true
        try {
            for (def in ng.games.all()) {
                val item = def.entry.feeItem ?: continue
                if (ours(item.ref) || item.ref is ItemRef.Vanilla) continue
                val stack = ng.itemResolver.create(item, 1)?.let { ItemRoles.sample(it, item) } ?: continue
                val ref = ItemRoles.adopt(stack, def.id + "_참가권") ?: continue
                ItemRoles.assign(ref, FEE, ItemRoles.withLegacy(mapOf("game" to def.id), item))
                ng.logger.info("'${def.id}' 게임의 참가 아이템을 커스텀아이템 '${ref.id}' 로 옮겼습니다")
            }
            val found = HashMap<String, StoredItem>()
            for (holder in ItemRoles.holders(FEE)) {
                val def = ng.games.all().firstOrNull { it.id == holder.values["game"] } ?: continue
                if (def.entry.feeItem?.ref != holder.ref) {
                    def.entry.feeItem = holder.item()
                    ng.games.markDirty(def)
                }
                holder.legacy()?.let { found[def.id] = it }
            }
            legacy = found
        } finally {
            syncing = false
        }
    }

    /** 관리 화면에서 고른 참가 아이템 — 바닐라가 아니면 커스텀아이템으로 만들어 역할을 붙인다. 아니면 false(그대로 게임에 적는다). */
    fun set(ng: Ng, def: GameDefinition, stored: StoredItem): Boolean {
        if (!ItemRoles.active) return false
        for (holder in ItemRoles.holders(FEE)) if (holder.values["game"] == def.id) ItemRoles.assign(holder.ref, FEE, null)
        if (stored.ref is ItemRef.Vanilla) return false
        val stack = ng.itemResolver.create(stored, 1)?.let { ItemRoles.sample(it, stored) } ?: return false
        val ref = ItemRoles.adopt(stack, def.id + "_참가권") ?: return false
        ItemRoles.assign(ref, FEE, ItemRoles.withLegacy(mapOf("game" to def.id), stored))
        return true
    }
}
