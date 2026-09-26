package com.inmc.numbergame.gui

import com.inmc.numbergame.Ng
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent

/**
 * Picks exactly one item - the entry fee, or the icon of a command-only reward.
 *
 * Same reasoning as [RewardItemMenu]: identifying an item by holding it is the only gesture that
 * works for MMOItems and ItemsAdder alike, and a dialog cannot take a dragged stack.
 */
class SingleItemMenu(
    ng: Ng,
    title: String,
    private val hint: List<String>,
    private val onPick: (Player, StoredItem) -> Unit,
    private val onCancel: (Player) -> Unit,
) : Menu(ng, SIZE, Text.renderFlat("<dark_gray>" + title + "</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)
        set(SLOT, null)

        set(
            HINT_SLOT,
            Icon.of(Material.PAPER, "<yellow>안내</yellow>", hint + listOf("", "<dark_gray>가운데 칸에 아이템을 올리세요.</dark_gray>")),
        )

        set(CONFIRM_SLOT, Icon.confirm("<green>✔ 지정</green>")) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val stack = inventory.getItem(SLOT)
            if (stack == null || stack.type.isAir) {
                player.sendMessage(Text.render("<red>가운데 칸에 아이템을 올려주세요.</red>", null, player))
                return@set
            }
            val stored = ng.itemResolver.capture(stack)
            inventory.setItem(SLOT, null)
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
            player.closeInventory()
            onPick(player, stored)
        }

        set(CANCEL_SLOT, Icon.cancel("<red>✖ 취소</red>")) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            player.closeInventory()
            onCancel(player)
        }
    }

    override fun isSlotEditable(slot: Int): Boolean = slot == SLOT

    override fun onClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let { returnStaged(it) }
    }

    private fun returnStaged(player: Player) {
        val stack = inventory.getItem(SLOT) ?: return
        if (stack.type.isAir) return
        inventory.setItem(SLOT, null)
        player.inventory.addItem(stack).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
    }

    private companion object {
        const val SIZE = 27
        const val SLOT = 13
        const val HINT_SLOT = 11
        const val CONFIRM_SLOT = 15
        const val CANCEL_SLOT = 26
    }
}
