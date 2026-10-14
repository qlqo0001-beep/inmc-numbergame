package com.inmc.numbergame.gui

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * The one screen that is not a dialog.
 *
 * A dialog cannot accept a dragged item, and asking an admin to type `mmoitems:SWORD:EXCALIBUR`
 * to register a reward would throw away the whole point of the item-reference layer. So item
 * *pickup* happens in an inventory window - Shift+click an item in your own inventory and a copy
 * becomes a reward (사용자 요청 2026-10-14 — 가방의 것은 빠지지 않는다) - and every property of
 * the resulting reward is then edited back in a dialog form.
 *
 * Items are captured through [kr.inmc.core.item.ItemResolver.capture], so a MMOItems
 * weapon is stored as a live reference and keeps updating when its definition changes.
 */
class RewardItemMenu(
    ng: Ng,
    private val def: GameDefinition,
    private val bundle: RewardBundle,
    private val label: String,
    /** Where to send the admin once they are done here. */
    private val onDone: (Player) -> Unit,
) : Menu(ng, SIZE, Text.renderFlat("<dark_gray>보상 아이템 등록 <gray>|</gray> " + label + "</dark_gray>")) {

    override fun draw() {
        clear()

        // 지금 등록된 아이템 보상(보기만) — Shift+클릭할 때마다 여기에 늘어난다.
        for ((slot, entry) in bundle.entries.filter { it.item != null }.take(CONTENT_SIZE).withIndex()) {
            val icon = ng.itemResolver.icon(entry.item!!)
            set(slot, Icon.annotate(icon.stack, lore = icon.notes()))
        }

        for (slot in CONTENT_SIZE until SIZE) set(slot, Icon.EDGE)

        set(
            OPEN_SLOT_HINT,
            Icon.of(
                Material.PAPER, "<yellow>등록 방법</yellow>",
                "<yellow>▶ 아래 가방의 아이템을 Shift+클릭: 추가</yellow>",
                "<dark_gray>가방의 아이템은 빠지지 않습니다(사본).</dark_gray>",
                "",
                "<gray>현재 등록된 보상: <white>" + bundle.entries.size + "개</white></gray>",
                "",
                "<dark_gray>확률·수량·명령어는 등록 후</dark_gray>",
                "<dark_gray>다이얼로그에서 설정합니다.</dark_gray>",
            )
        )

        set(
            CONFIRM_SLOT,
            Icon.confirm("<green>✔ 완료</green>", listOf("<gray>보상 목록으로 돌아갑니다.</gray>")),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            player.closeInventory()
            onDone(player)
        }
    }

    /** 가방의 아이템을 Shift+클릭 — 그 사본을 보상으로 더한다(개수 그대로). 가방의 것은 그대로. */
    override fun onPlayerInventoryClick(event: InventoryClickEvent): Boolean {
        if (!event.isShiftClick) return false
        val stack = event.currentItem?.takeIf { !it.type.isAir }?.clone() ?: return false
        bundle.entries.add(
            RewardEntry(
                item = ng.itemResolver.capture(stack),
                chance = 100.0,
                minAmount = stack.amount,
                maxAmount = stack.amount,
            )
        )
        ng.games.markDirty(def)
        (event.whoClicked as? Player)?.let { it.sendMessage(Text.render("<green>보상 1개를 등록했습니다.</green>", null, it)) }
        refresh()
        return true
    }

    private companion object {
        const val SIZE = 54
        const val CONTENT_SIZE = 45
        const val OPEN_SLOT_HINT = 49
        const val CONFIRM_SLOT = 53
    }
}
