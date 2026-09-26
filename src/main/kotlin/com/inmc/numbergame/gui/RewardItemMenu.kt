package com.inmc.numbergame.gui

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent

/**
 * The one screen that is not a dialog.
 *
 * A dialog cannot accept a dragged item, and asking an admin to type `mmoitems:SWORD:EXCALIBUR`
 * to register a reward would throw away the whole point of the item-reference layer. So item
 * *pickup* happens in an inventory window - drop stacks in, press 등록 - and every property of
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

        for (slot in CONTENT_SIZE until SIZE) set(slot, Icon.EDGE)

        set(
            OPEN_SLOT_HINT,
            Icon.of(
                Material.PAPER, "<yellow>등록 방법</yellow>",
                "<gray>위쪽 빈 칸에 아이템을 올린 뒤</gray>",
                "<gray><green>등록</green> 을 누르세요.</gray>",
                "",
                "<gray>현재 등록된 보상: <white>" + bundle.entries.size + "개</white></gray>",
                "",
                "<dark_gray>확률·수량·명령어는 등록 후</dark_gray>",
                "<dark_gray>다이얼로그에서 설정합니다.</dark_gray>",
            )
        )

        set(
            CONFIRM_SLOT,
            Icon.confirm("<green>✔ 등록</green>", listOf("<gray>올려둔 아이템을 보상으로 추가합니다.</gray>")),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val added = capture()
            ng.games.markDirty(def)
            if (added > 0) {
                player.sendMessage(Text.render("<green>보상 " + added + "개를 등록했습니다.</green>", null, player))
            }
            player.closeInventory()
            onDone(player)
        }

        set(
            CANCEL_SLOT,
            Icon.cancel("<red>✖ 취소</red>", listOf("<gray>올려둔 아이템을 돌려받고 나갑니다.</gray>")),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            player.closeInventory()
            onDone(player)
        }
    }

    override fun isSlotEditable(slot: Int): Boolean = slot < CONTENT_SIZE

    override fun acceptsShiftInsert(): Boolean = true

    override fun onClose(event: InventoryCloseEvent) {
        // Closing with Escape must not eat the admin's items.
        (event.player as? Player)?.let { returnStaged(it) }
    }

    /** Turns every stack sitting in the window into a reward entry. */
    private fun capture(): Int {
        var added = 0
        for (slot in 0 until CONTENT_SIZE) {
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            bundle.entries.add(
                RewardEntry(
                    item = ng.itemResolver.capture(stack),
                    chance = 100.0,
                    minAmount = stack.amount,
                    maxAmount = stack.amount,
                )
            )
            inventory.setItem(slot, null)
            added++
        }
        return added
    }

    private fun returnStaged(player: Player) {
        for (slot in 0 until CONTENT_SIZE) {
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            inventory.setItem(slot, null)
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
    }

    private companion object {
        const val SIZE = 54
        const val CONTENT_SIZE = 45
        const val OPEN_SLOT_HINT = 49
        const val CONFIRM_SLOT = 53
        const val CANCEL_SLOT = 45
    }
}
