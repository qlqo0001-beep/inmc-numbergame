package com.inmc.numbergame.dialog.admin

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.gui.RewardItemMenu
import com.inmc.numbergame.gui.SingleItemMenu
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RankBracket
import kr.inmc.core.reward.RecordTier
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.reward.RewardTrigger
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import org.bukkit.entity.Player

/**
 * Reward editing.
 *
 * Split from [AdminScreens] because it is the one part of the admin surface that crosses back
 * into an inventory GUI, and because the same bundle editor is reached from four different
 * places - a trigger, a record tier, a rank bracket, and the entry fee.
 */
class AdminRewards(private val ng: Ng) {

    private val dlg get() = ng.dlg

    // --- trigger list ----------------------------------------------------------

    fun triggerListDialog(player: Player, def: GameDefinition): Dialog {
        val buttons = mutableListOf<ActionButton>()

        for (trigger in RewardTrigger.entries) {
            val bundle = def.rewards.bundle(trigger)
            buttons.add(
                dlg.button(
                    label = countLabel(trigger.display, bundle),
                    tooltip = trigger.help,
                ) { p, _ ->
                    dlg.push(
                        p, { triggerListDialog(p, def) },
                        bundleDialog(p, def, bundle, trigger.display),
                    )
                }
            )
        }

        buttons.add(
            dlg.button(
                "<gold>기록 구간 보상 (" + def.rewards.recordTiers.size + ")</gold>",
                "몇 회 이내에 클리어했는지에 따라 추가로 주는 보상입니다.",
            ) { p, _ -> dlg.push(p, { triggerListDialog(p, def) }, tierListDialog(p, def)) }
        )
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<gold>" + def.displayName + " <gray>보상</gray></gold>",
            lines = listOf(
                "<gray>어떤 상황에 무엇을 줄지 정합니다.</gray>",
                "<dark_gray>아이템은 인벤토리 창에서 올려 등록하고,</dark_gray>",
                "<dark_gray>확률·수량·명령어는 여기서 설정합니다.</dark_gray>",
            ),
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun countLabel(name: String, bundle: RewardBundle): String {
        val count = bundle.entries.size
        val colour = if (count > 0) "<white>" else "<dark_gray>"
        return colour + name + " (" + count + ")</white>"
    }

    // --- one bundle ------------------------------------------------------------

    /**
     * The bundle editor: how entries are drawn, and the list of entries themselves.
     *
     * Navigation is the shared back-stack rather than a supplier passed down the call chain:
     * the stack already rebuilds each screen on the way out, so a round trip through the
     * inventory GUI comes back to a freshly drawn list.
     */
    fun bundleDialog(
        player: Player,
        def: GameDefinition,
        bundle: RewardBundle,
        label: String,
    ): Dialog {
        val lines = mutableListOf(
            "<gray>지급 방식: <white>" + bundle.mode.display + "</white></gray>",
            "<dark_gray>" + bundle.mode.help + "</dark_gray>",
            "",
        )
        if (bundle.entries.isEmpty()) {
            lines.add("<gray>등록된 보상이 없습니다.</gray>")
        } else {
            bundle.entries.forEach { entry ->
                lines.add("<gray>· <white>" + entry.label() + "</white> " + describe(entry) + "</gray>")
            }
            if (bundle.mode == GiveMode.ALL) {
                val expected = bundle.entries.sumOf { it.chance } / 100.0
                lines.add("")
                lines.add(
                    "<dark_gray>확률 합 " + Numbers.chance(bundle.entries.sumOf { it.chance }) +
                        "% - 1회당 평균 " + Numbers.chance(expected) + "종이 나옵니다.</dark_gray>"
                )
            }
        }

        val buttons = mutableListOf<ActionButton>()
        buttons.add(
            dlg.button("<green>아이템 등록</green>", "인벤토리 창이 열립니다.") { p, _ ->
                openItemMenu(p, def, bundle, label)
            }
        )
        buttons.add(
            dlg.button("<white>명령어/돈 보상 추가</white>", "아이템 없이 명령어나 금액만 주는 항목입니다.") { p, _ ->
                val entry = RewardEntry(chance = 100.0, giveItem = false)
                bundle.entries.add(entry)
                ng.games.markDirty(def)
                dlg.push(
                    p, { bundleDialog(p, def, bundle, label) },
                    entryDialog(p, def, bundle, entry, label),
                )
            }
        )
        buttons.add(
            dlg.button("<aqua>지급 방식</aqua>", "전부 지급 / 하나만 추첨 / N개 추첨") { p, _ ->
                dlg.push(
                    p, { bundleDialog(p, def, bundle, label) },
                    modeDialog(p, def, bundle, label),
                )
            }
        )

        bundle.entries.take(MAX_ENTRY_BUTTONS).forEach { entry ->
            buttons.add(
                dlg.button("<yellow>· " + entry.label() + "</yellow>", "이 보상의 상세 설정") { p, _ ->
                    dlg.push(
                        p, { bundleDialog(p, def, bundle, label) },
                        entryDialog(p, def, bundle, entry, label),
                    )
                }
            )
        }
        if (bundle.entries.size > MAX_ENTRY_BUTTONS) {
            lines.add("<dark_gray>… 상세 설정 버튼은 " + MAX_ENTRY_BUTTONS + "개까지만 표시됩니다.</dark_gray>")
        }
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<gold>" + label + "</gold>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun describe(entry: RewardEntry): String {
        val parts = mutableListOf<String>()
        parts.add(Numbers.chance(entry.chance) + "%")
        if (entry.item != null && entry.giveItem) {
            parts.add(
                if (entry.minAmount == entry.maxAmount) entry.minAmount.toString() + "개"
                else entry.minAmount.toString() + "~" + entry.maxAmount + "개"
            )
        }
        if (entry.money > 0.0) parts.add(Numbers.money(entry.money) + "원")
        if (entry.commands.isNotEmpty()) parts.add("명령어 " + entry.commands.size)
        if (entry.announce) parts.add("공지")
        return "<dark_gray>(" + parts.joinToString(", ") + ")</dark_gray>"
    }

    private fun modeDialog(
        player: Player,
        def: GameDefinition,
        bundle: RewardBundle,
        label: String,
    ): Dialog = dlg.menu(
        title = "<aqua>지급 방식</aqua>",
        lines = GiveMode.entries.map { "<yellow>" + it.display + "</yellow> <dark_gray>- " + it.help + "</dark_gray>" },
        buttons = listOf(
            dlg.button("<green>저장</green>") { p, view ->
                view.getText("mode")?.let { bundle.mode = GiveMode.parse(it) }
                view.getText("roll_count")?.trim()?.toIntOrNull()?.let { bundle.rollCount = it }
                ng.games.markDirty(def)
                dlg.back(p)
            },
            dlg.backButton("<gray>취소</gray>"),
        ),
        inputs = listOf(
            DialogInput.singleOption(
                "mode", Text.renderFlat("지급 방식"),
                GiveMode.entries.map {
                    SingleOptionDialogInput.OptionEntry.create(
                        it.name, Text.renderFlat(it.display), it == bundle.mode,
                    )
                },
            ).width(240).build(),
            DialogInput.text("roll_count", Text.renderFlat("추첨 개수 (N개 추첨일 때)"))
                .initial(bundle.rollCount.toString()).maxLength(3).width(240).build(),
        ),
        columns = 2,
        exit = dlg.closeButton(),
    )

    // --- one entry -------------------------------------------------------------

    fun entryDialog(
        player: Player,
        def: GameDefinition,
        bundle: RewardBundle,
        entry: RewardEntry,
        label: String,
    ): Dialog {
        val lines = mutableListOf(
            "<gray>보상: <white>" + entry.label() + "</white></gray>",
        )
        entry.item?.let { lines.add("<dark_gray>" + it.ref.serialize() + "</dark_gray>") }
        if (entry.isEmpty()) {
            lines.add("<red>⚠ 이 항목은 아무것도 지급하지 않습니다.</red>")
        }
        lines.add("")
        lines.add("<dark_gray>명령어는 | 로 여러 개를 넣을 수 있고,</dark_gray>")
        lines.add("<dark_gray>{플레이어네임} 이 실행 대상으로 치환됩니다.</dark_gray>")

        return dlg.menu(
            title = "<gold>보상 상세</gold>",
            lines = lines,
            buttons = listOf(
                dlg.button("<green>저장</green>") { p, view ->
                    view.getText("chance")?.trim()?.replace(",", "")?.toDoubleOrNull()
                        ?.let { entry.chance = it }
                    view.getText("min")?.trim()?.toIntOrNull()?.let { entry.minAmount = it }
                    view.getText("max")?.trim()?.toIntOrNull()?.let { entry.maxAmount = it }
                    view.getText("money")?.trim()?.replace(",", "")?.toDoubleOrNull()
                        ?.let { entry.money = it.coerceAtLeast(0.0) }
                    view.getText("currency")?.let { entry.currency = if (it == "-") "" else it }
                    view.getText("commands")?.let { raw ->
                        entry.commands = raw.split('|').map { it.trim().removePrefix("/") }
                            .filter { it.isNotEmpty() }.toMutableList()
                    }
                    view.getBoolean("give_item")?.let { entry.giveItem = it }
                    view.getBoolean("announce")?.let { entry.announce = it }
                    ng.games.markDirty(def)
                    dlg.back(p)
                },
                dlg.button("<white>아이콘 아이템 지정</white>", "명령어 보상에 보여줄 아이템입니다.") { p, _ ->
                    SingleItemMenu(
                        ng,
                        title = "보상 아이템 지정",
                        hint = listOf("<gray>이 보상으로 줄 아이템입니다.</gray>"),
                        onPick = { picker, stored ->
                            entry.item = stored
                            ng.games.markDirty(def)
                            dlg.reshow(picker) { entryDialog(picker, def, bundle, entry, label) }
                        },
                        onCancel = { canceller ->
                            dlg.reshow(canceller) { entryDialog(canceller, def, bundle, entry, label) }
                        },
                    ).open(p)
                },
                dlg.button("<red>이 보상 삭제</red>") { p, _ ->
                    bundle.entries.remove(entry)
                    ng.games.markDirty(def)
                    dlg.reshow(p) { bundleDialog(p, def, bundle, label) }
                },
                dlg.backButton(),
            ),
            inputs = listOfNotNull(
                DialogInput.text("chance", Text.renderFlat("확률 (%)"))
                    .initial(Numbers.chance(entry.chance)).maxLength(8).width(240).build(),
                DialogInput.text("min", Text.renderFlat("최소 수량"))
                    .initial(entry.minAmount.toString()).maxLength(3).width(240).build(),
                DialogInput.text("max", Text.renderFlat("최대 수량"))
                    .initial(entry.maxAmount.toString()).maxLength(3).width(240).build(),
                DialogInput.text("money", Text.renderFlat("지급 금액"))
                    .initial(entry.money.toString()).maxLength(16).width(240).build(),
                // 화폐가 여럿일 때만(2026-10-08). "-" = 기본 화폐.
                DialogInput.singleOption(
                    "currency", Text.renderFlat("지급 화폐"),
                    (listOf("-" to "기본 화폐") + ng.economy.currencies()).map { (id, name) ->
                        SingleOptionDialogInput.OptionEntry.create(id, Text.renderFlat(name), id == entry.currency.ifBlank { "-" })
                    },
                ).width(240).build().takeIf { ng.economy.multiCurrency },
                DialogInput.text("commands", Text.renderFlat("실행 명령어 ( | 로 구분 )"))
                    .initial(entry.commands.joinToString(" | ")).maxLength(256).width(240).build(),
                DialogInput.bool("give_item", Text.renderFlat("아이템 지급")).initial(entry.giveItem).build(),
                DialogInput.bool("announce", Text.renderFlat("획득 시 전체 공지")).initial(entry.announce).build(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- record tiers ----------------------------------------------------------

    private fun tierListDialog(player: Player, def: GameDefinition): Dialog {
        val unit = def.type.recordUnit
        val buttons = mutableListOf<ActionButton>()

        def.rewards.recordTiers.forEach { tier ->
            buttons.add(
                dlg.button(
                    "<white>" + tier.describe(def.type.better, unit) + " (" + tier.bundle.entries.size + ")</white>",
                ) { p, _ ->
                    dlg.push(p, { tierListDialog(p, def) }, tierDialog(p, def, tier))
                }
            )
        }
        buttons.add(
            dlg.button("<green>구간 추가</green>") { p, _ ->
                val tier = RecordTier(threshold = defaultThreshold(def))
                def.rewards.recordTiers.add(tier)
                ng.games.markDirty(def)
                dlg.push(p, { tierListDialog(p, def) }, tierDialog(p, def, tier))
            }
        )
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<gold>기록 구간 보상</gold>",
            lines = listOf(
                "<gray>조건을 만족하는 <white>모든</white> 구간이 함께 지급됩니다.</gray>",
                "<dark_gray>예: 5회 이내 · 3회 이내를 둘 다 만들면</dark_gray>",
                "<dark_gray>2회에 맞혔을 때 둘 다 받습니다.</dark_gray>",
                "",
                if (def.type.better == kr.inmc.core.rank.Better.LOWER) {
                    "<gray>이 게임은 <white>" + unit + " 수가 적을수록</white> 좋은 기록입니다.</gray>"
                } else {
                    "<gray>이 게임은 <white>" + unit + " 수가 많을수록</white> 좋은 기록입니다.</gray>"
                },
            ),
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun defaultThreshold(def: GameDefinition): Long =
        if (def.type.better == kr.inmc.core.rank.Better.LOWER) 3L else 10L

    private fun tierDialog(player: Player, def: GameDefinition, tier: RecordTier): Dialog {
        val unit = def.type.recordUnit
        val label = tier.describe(def.type.better, unit) + " 보상"
        return dlg.menu(
            title = "<gold>기록 구간</gold>",
            lines = listOf(
                "<gray>조건: <white>" + tier.describe(def.type.better, unit) + "</white></gray>",
                "<gray>등록된 보상: <white>" + tier.bundle.entries.size + "개</white></gray>",
            ),
            buttons = listOf(
                dlg.button("<green>기준 저장</green>") { p, view ->
                    view.getText("threshold")?.trim()?.toLongOrNull()?.let { tier.threshold = it }
                    ng.games.markDirty(def)
                    dlg.reshow(p) { tierDialog(p, def, tier) }
                },
                dlg.button("<gold>보상 편집</gold>") { p, _ ->
                    dlg.push(
                        p, { tierDialog(p, def, tier) },
                        bundleDialog(p, def, tier.bundle, label),
                    )
                },
                dlg.button("<red>구간 삭제</red>") { p, _ ->
                    def.rewards.recordTiers.remove(tier)
                    ng.games.markDirty(def)
                    dlg.reshow(p) { tierListDialog(p, def) }
                },
                dlg.backButton(),
            ),
            inputs = listOf(
                DialogInput.text("threshold", Text.renderFlat("기준 (" + unit + ")"))
                    .initial(tier.threshold.toString()).maxLength(10).width(240).build(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- rank brackets ---------------------------------------------------------

    fun bracketListDialog(player: Player, def: GameDefinition): Dialog {
        val buttons = mutableListOf<ActionButton>()

        def.rewards.rankBrackets.forEach { bracket ->
            buttons.add(
                dlg.button("<white>" + bracket.describe() + " (" + bracket.bundle.entries.size + ")</white>") { p, _ ->
                    dlg.push(p, { bracketListDialog(p, def) }, bracketDialog(p, def, bracket))
                }
            )
        }
        buttons.add(
            dlg.button("<green>구간 추가</green>") { p, _ ->
                val next = (def.rewards.rankBrackets.maxOfOrNull { it.to } ?: 0) + 1
                val bracket = RankBracket(next, next)
                def.rewards.rankBrackets.add(bracket)
                def.rewards.sortBrackets()
                ng.games.markDirty(def)
                dlg.push(p, { bracketListDialog(p, def) }, bracketDialog(p, def, bracket))
            }
        )
        buttons.add(dlg.backButton())

        val lines = mutableListOf(
            "<gray>시즌이 끝날 때 순위에 따라 지급합니다.</gray>",
            "<gray>초기화 주기: <white>" + def.ranking.reset.describe() + "</white></gray>",
            "<dark_gray>오프라인 대상자에게는 우편함으로 보냅니다.</dark_gray>",
            "<dark_gray>최소 " + def.ranking.minPlays + "회 이상 플레이한 사람만 받습니다.</dark_gray>",
        )
        // Overlap is allowed - the narrowest range wins - but it should never be a surprise.
        val overlaps = def.rewards.overlaps()
        if (overlaps.isNotEmpty()) {
            lines.add("")
            lines.add("<yellow>⚠ 겹치는 구간이 있습니다. 더 좁은 구간이 우선합니다.</yellow>")
            overlaps.take(3).forEach { (a, b) ->
                lines.add("<dark_gray>· " + a.describe() + " ↔ " + b.describe() + "</dark_gray>")
            }
        }

        return dlg.menu(
            title = "<gold>순위 보상</gold>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun bracketDialog(player: Player, def: GameDefinition, bracket: RankBracket): Dialog = dlg.menu(
        title = "<gold>" + bracket.describe() + " 보상</gold>",
        lines = listOf(
            "<gray>이 구간에 든 모든 플레이어가 같은 보상을 받습니다.</gray>",
            "<gray>등록된 보상: <white>" + bracket.bundle.entries.size + "개</white></gray>",
        ),
        buttons = listOf(
            dlg.button("<green>구간 저장</green>") { p, view ->
                view.getText("from")?.trim()?.toIntOrNull()?.let { bracket.from = it }
                view.getText("to")?.trim()?.toIntOrNull()?.let { bracket.to = it }
                def.rewards.sortBrackets()
                ng.games.markDirty(def)
                dlg.reshow(p) { bracketDialog(p, def, bracket) }
            },
            dlg.button("<gold>보상 편집</gold>") { p, _ ->
                dlg.push(
                    p, { bracketDialog(p, def, bracket) },
                    bundleDialog(p, def, bracket.bundle, bracket.describe() + " 보상"),
                )
            },
            dlg.button("<red>구간 삭제</red>") { p, _ ->
                def.rewards.rankBrackets.remove(bracket)
                ng.games.markDirty(def)
                dlg.reshow(p) { bracketListDialog(p, def) }
            },
            dlg.backButton(),
        ),
        inputs = listOf(
            DialogInput.text("from", Text.renderFlat("시작 순위"))
                .initial(bracket.from.toString()).maxLength(5).width(240).build(),
            DialogInput.text("to", Text.renderFlat("끝 순위"))
                .initial(bracket.to.toString()).maxLength(5).width(240).build(),
        ),
        columns = 2,
        exit = dlg.closeButton(),
    )

    // --- inventory GUI bridges -------------------------------------------------

    private fun openItemMenu(
        player: Player,
        def: GameDefinition,
        bundle: RewardBundle,
        label: String,
    ) {
        dlg.close(player)
        RewardItemMenu(ng, def, bundle, label) { done ->
            dlg.reshow(done) { bundleDialog(done, def, bundle, label) }
        }.open(player)
    }

    fun openFeeItemMenu(player: Player, def: GameDefinition) {
        dlg.close(player)
        SingleItemMenu(
            ng,
            title = "참가비 아이템 지정",
            hint = listOf(
                "<gray>게임을 시작할 때 소모할 아이템입니다.</gray>",
                "<gray>개수는 참가 조건 화면에서 정합니다.</gray>",
            ),
            onPick = { picker, stored ->
                // 커스텀아이템이 있으면 그리로(바닐라 그대로인 것은 제외) — 커스텀아이템의 "참가 아이템" 역할에서도 보인다.
                if (!com.inmc.numbergame.play.NgRoles.set(ng, def, stored)) {
                    def.entry.feeItem = stored
                    ng.games.markDirty(def)
                }
                picker.sendMessage(
                    Text.render("<green>참가비 아이템을 " + stored.label() + " 로 지정했습니다.</green>", null, picker)
                )
                dlg.reshow(picker) { ng.admin.gameDialog(picker, def) }
            },
            onCancel = { canceller -> dlg.reshow(canceller) { ng.admin.gameDialog(canceller, def) } },
        ).open(player)
    }

    private companion object {
        /** More than this and the button grid pushes the body off the screen. */
        const val MAX_ENTRY_BUTTONS = 8
    }
}
