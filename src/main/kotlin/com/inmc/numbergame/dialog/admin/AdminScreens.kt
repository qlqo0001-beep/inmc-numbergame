package com.inmc.numbergame.dialog.admin

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameType
import com.inmc.numbergame.game.settings.BoolSetting
import com.inmc.numbergame.game.settings.ChoiceSetting
import com.inmc.numbergame.game.settings.DoubleSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import com.inmc.numbergame.game.settings.TextSetting
import kr.inmc.core.gui.Paging
import kr.inmc.core.rank.RankMode
import kr.inmc.core.rank.ResetPolicy
import kr.inmc.core.rank.ResetSchedule
import kr.inmc.core.util.Durations
import com.inmc.numbergame.util.Ph
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.DayOfWeek

/**
 * The admin surface, in dialogs.
 *
 * The interesting part is [settingsDialog]: it renders **any** game's settings without knowing
 * which game it is, by walking that engine's [Setting] schema. Ten game types therefore cost one
 * settings screen rather than ten, and a new type gets a full editor for free the moment it
 * declares a schema.
 *
 * Only item pickup falls back to an inventory GUI - a dialog cannot accept a dragged item, which
 * is exactly the "다이얼로그가 애매한 경우" the spec carves out.
 */
class AdminScreens(private val ng: Ng) {

    private val dlg get() = ng.dlg

    /** Settings per page; more than this and the form scrolls past the buttons. */
    private val perPage = 5

    // --- root ------------------------------------------------------------------

    fun showRoot(player: Player) {
        dlg.push(player, { ng.screens.mainDialog(player) }, rootDialog(player))
    }

    fun rootDialog(player: Player): Dialog = dlg.menu(
        title = "<light_purple>숫자게임 관리</light_purple>",
        lines = listOf(
            "<gray>등록된 게임 <white>" + ng.games.size + "종</white> " +
                "<dark_gray>(활성 " + ng.games.enabled().size + "종)</dark_gray></gray>",
            "<gray>진행 중인 게임 <white>" + ng.sessions.size + "건</white></gray>",
        ),
        buttons = listOf(
            dlg.button("<white>게임 목록</white>", "게임별 설정·보상·랭킹") { p, _ ->
                dlg.push(p, { rootDialog(p) }, gameListDialog(p, 0))
            },
            dlg.button("<green>새 게임 만들기</green>", "게임 종류를 고르고 새로 추가합니다.") { p, _ ->
                dlg.push(p, { rootDialog(p) }, createTypeDialog(p))
            },
            dlg.button("<yellow>플레이 초기화</yellow>", "일일 도전 횟수를 되돌립니다.") { p, _ ->
                dlg.push(p, { rootDialog(p) }, resetDialog(p))
            },
            dlg.button("<white>최근 기록</white>", "누가 언제 무엇을 했는지") { p, _ ->
                dlg.push(p, { rootDialog(p) }, logDialog(p, null, 0))
            },
            dlg.button("<gray>설정 리로드</gray>", "config.yml 과 games/<id>.yml 을 다시 읽습니다.") { p, _ ->
                ng.messages.send(p, "reloading")
                ng.reload { count ->
                    ng.messages.send(p, "reloaded", Ph.of().count(count))
                    dlg.reshow(p) { rootDialog(p) }
                }
            },
            dlg.button("<gold>어드민 메뉴로</gold>", "각 플러그인 설정 허브로 돌아갑니다.") { p, _ ->
                p.performCommand("메뉴 어드민")
            },
            dlg.backButton(),
        ),
        columns = 2,
        exit = dlg.closeButton(),
    )

    // --- game list -------------------------------------------------------------

    fun gameListDialog(player: Player, page: Int): Dialog {
        val games = ng.games.all()
        val pages = Paging.pageCount(games.size, perPage)
        val current = page.coerceIn(0, pages - 1)

        val buttons = mutableListOf<ActionButton>()
        Paging.slice(games, current, perPage).forEach { def ->
            val mark = if (def.enabled) "<green>●</green>" else "<red>●</red>"
            buttons.add(
                dlg.button(
                    mark + " <white>" + def.displayName + "</white>",
                    def.id + " · " + def.type.display,
                ) { p, _ -> dlg.push(p, { gameListDialog(p, current) }, gameDialog(p, def)) }
            )
        }
        if (current > 0) {
            buttons.add(dlg.button("<yellow>◀ 이전</yellow>") { p, _ -> dlg.reshow(p) { gameListDialog(p, current - 1) } })
        }
        if (current < pages - 1) {
            buttons.add(dlg.button("<yellow>다음 ▶</yellow>") { p, _ -> dlg.reshow(p) { gameListDialog(p, current + 1) } })
        }
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<light_purple>게임 목록</light_purple>",
            lines = if (games.isEmpty()) listOf("<gray>등록된 게임이 없습니다.</gray>")
            else listOf("<dark_gray>" + (current + 1) + " / " + pages + " 페이지</dark_gray>"),
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- one game --------------------------------------------------------------

    fun gameDialog(player: Player, def: GameDefinition): Dialog {
        val boards = ng.ranks.boardsOf(def.id)
        val lines = mutableListOf(
            "<gray>종류: <white>" + def.type.display + "</white>  <dark_gray>(" + def.id + ")</dark_gray></gray>",
            "<gray>상태: </gray>" + if (def.enabled) "<green>활성</green>" else "<red>비활성</red>",
            "<gray>랭킹: <white>" + def.ranking.mode.display + "</white> · 초기화 <white>" +
                def.ranking.reset.describe() + "</white></gray>",
            "<gray>시즌 <white>" + boards.seasonNumber + "</white> · 참가자 <white>" +
                boards.season.size + "명</white></gray>",
        )
        if (!ng.games.runnable(def)) {
            lines.add("<red>지금은 실행할 수 없습니다: " + ng.gameService.unavailableReason(def) + "</red>")
        }

        return dlg.menu(
            title = "<light_purple>" + def.displayName + "</light_purple>",
            lines = lines,
            buttons = listOf(
                dlg.button("<white>기본 설정</white>", "이름·활성화·정렬·설명") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, basicDialog(p, def))
                },
                dlg.button("<white>게임 규칙</white>", def.type.display + " 전용 설정") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, settingsDialog(p, def, 0))
                },
                dlg.button("<white>참가 조건</white>", "일일 제한·쿨다운·참가비") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, entryDialog(p, def))
                },
                dlg.button("<gold>보상 설정</gold>", "참가·클리어·실패·기록 구간 보상") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, ng.adminRewards.triggerListDialog(p, def))
                },
                dlg.button("<aqua>랭킹 설정</aqua>", "순위 기준·초기화 주기") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, rankingDialog(p, def))
                },
                dlg.button("<gold>순위 보상</gold>", "1위·2~3위 등 구간별 보상") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, ng.adminRewards.bracketListDialog(p, def))
                },
                dlg.button("<yellow>시즌 관리</yellow>", "현재 순위·즉시 종료·지난 시즌") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, seasonDialog(p, def))
                },
                dlg.button("<white>이 게임 기록</white>", "이 게임의 최근 플레이 내역") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, logDialog(p, def.id, 0))
                },
                dlg.button(
                    if (def.type.kind == GameType.Kind.EVENT) "<yellow>회차 관리</yellow>" else "<dark_gray>회차 관리</dark_gray>",
                    if (def.type.kind == GameType.Kind.EVENT) "현재 회차 현황과 즉시 추첨"
                    else "추첨형 게임에서만 사용합니다.",
                ) { p, _ ->
                    if (def.type.kind != GameType.Kind.EVENT) return@button
                    dlg.push(p, { gameDialog(p, def) }, eventRoundDialog(p, def))
                },
                dlg.button("<red>게임 삭제</red>", "설정 파일까지 지웁니다.") { p, _ ->
                    dlg.push(p, { gameDialog(p, def) }, deleteDialog(p, def))
                },
                dlg.backButton(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- basic settings --------------------------------------------------------

    private fun basicDialog(player: Player, def: GameDefinition): Dialog {
        val inputs = listOf(
            DialogInput.text("display_name", Text.renderFlat("표시 이름"))
                .initial(def.displayName).maxLength(48).width(240).build(),
            DialogInput.bool("enabled", Text.renderFlat("활성화")).initial(def.enabled).build(),
            DialogInput.numberRange("order", Text.renderFlat("목록 순서"), 0f, 999f)
                .step(1f).initial(def.order.toFloat()).width(240).build(),
            DialogInput.text("description", Text.renderFlat("설명 ( | 로 줄바꿈 )"))
                .initial(def.description.joinToString(" | ")).maxLength(256).width(240).build(),
            DialogInput.text("permission", Text.renderFlat("필요 권한 (비우면 누구나)"))
                .initial(def.permission).maxLength(64).width(240).build(),
        )

        return dlg.menu(
            title = "<light_purple>기본 설정</light_purple>",
            lines = listOf(
                "<gray>게임 목록에 보이는 정보입니다.</gray>",
                "",
                "<gray>권한을 넣으면 그 권한이 있는 사람에게만 목록에 보이고</gray>",
                "<gray>시작도 막힙니다. <dark_gray>예: group.vip</dark_gray></gray>",
            ),
            buttons = listOf(
                dlg.button("<green>저장</green>") { p, view ->
                    view.getText("display_name")?.takeIf { it.isNotBlank() }?.let { def.displayName = it.trim() }
                    view.getBoolean("enabled")?.let { def.enabled = it }
                    view.getFloat("order")?.let { def.order = it.toInt() }
                    view.getText("description")?.let { raw ->
                        def.description = raw.split('|').map { it.trim() }
                            .filter { it.isNotEmpty() }.toMutableList()
                    }
                    view.getText("permission")?.let { def.permission = it.trim() }
                    ng.games.markDirty(def)
                    dlg.back(p)
                },
                dlg.backButton("<gray>취소</gray>"),
            ),
            inputs = inputs,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- schema-driven game rules ----------------------------------------------

    /**
     * The generic settings editor.
     *
     * Knows nothing about any particular game: it reads [GameEngine.schema], renders one input
     * per setting, and writes the answers back through [Setting.parse]. Paged because a form
     * taller than the screen pushes its own save button out of reach.
     */
    fun settingsDialog(player: Player, def: GameDefinition, page: Int): Dialog {
        val schema = def.type.engine.schema
        val pages = Paging.pageCount(schema.size, perPage)
        val current = page.coerceIn(0, pages - 1)
        val visible = Paging.slice(schema, current, perPage)

        val lines = mutableListOf<String>()
        lines.add("<gray>" + def.type.summary + "</gray>")
        lines.add("")
        visible.forEach { setting ->
            lines.add(
                "<yellow>" + setting.label + "</yellow> <dark_gray>=</dark_gray> <white>" +
                    def.settings.displayOf(setting) + "</white>"
            )
            lines.add("<dark_gray>" + setting.help + "</dark_gray>")
        }
        lines.addAll(advisories(def))
        if (pages > 1) lines.add("<dark_gray>" + (current + 1) + " / " + pages + " 페이지</dark_gray>")

        val buttons = mutableListOf<ActionButton>()
        buttons.add(
            dlg.button("<green>저장</green>") { p, view ->
                visible.forEach { apply(it, view, def) }
                ng.games.markDirty(def)
                dlg.reshow(p) { settingsDialog(p, def, current) }
            }
        )
        if (current > 0) {
            buttons.add(dlg.button("<yellow>◀ 이전</yellow>", "이 페이지를 저장하고 이동합니다.") { p, view ->
                visible.forEach { apply(it, view, def) }
                ng.games.markDirty(def)
                dlg.reshow(p) { settingsDialog(p, def, current - 1) }
            })
        }
        if (current < pages - 1) {
            buttons.add(dlg.button("<yellow>다음 ▶</yellow>", "이 페이지를 저장하고 이동합니다.") { p, view ->
                visible.forEach { apply(it, view, def) }
                ng.games.markDirty(def)
                dlg.reshow(p) { settingsDialog(p, def, current + 1) }
            })
        }
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<light_purple>" + def.displayName + " <gray>규칙</gray></light_purple>",
            lines = lines,
            buttons = buttons,
            inputs = visible.map { inputFor(it, def) },
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    /** Warnings that only make sense once several settings are read together. */
    private fun advisories(def: GameDefinition): List<String> {
        val notes = mutableListOf<String>()
        if (def.type == GameType.UPDOWN) {
            val (low, high) = com.inmc.numbergame.game.engine.UpDownEngine.bounds(def)
            val needed = com.inmc.numbergame.game.engine.UpDownEngine.suggestedAttempts(high - low + 1)
            val allowed = def.settings[com.inmc.numbergame.game.engine.UpDownEngine.MAX_ATTEMPTS]
            notes.add("")
            notes.add(
                if (allowed >= needed) {
                    "<dark_gray>이 범위는 이론상 " + needed + "회면 항상 맞힐 수 있습니다.</dark_gray>"
                } else {
                    "<red>⚠ 이 범위는 최소 " + needed + "회가 필요합니다. 지금 설정(" + allowed +
                        "회)으로는 운이 없으면 못 맞힙니다.</red>"
                }
            )
        }
        if (def.type == GameType.BASEBALL) {
            val digits = def.settings[com.inmc.numbergame.game.engine.BaseballEngine.DIGITS]
            if (!def.settings[com.inmc.numbergame.game.engine.BaseballEngine.ALLOW_DUPLICATE] && digits > 10) {
                notes.add("<red>⚠ 중복을 허용하지 않으면 10자리를 넘을 수 없습니다.</red>")
            }
        }
        return notes
    }

    private fun inputFor(setting: Setting<*>, def: GameDefinition): DialogInput {
        val label = Text.renderFlat(setting.label)
        // Config keys are kebab-case; dialog input names may not be. See Dlg.inputKey.
        val key = dlg.inputKey(setting.key)
        return when (setting) {
            is BoolSetting -> DialogInput.bool(key, label)
                .initial(def.settings[setting]).build()

            is IntSetting ->
                if (setting.slider) {
                    DialogInput.numberRange(key, label, setting.min.toFloat(), setting.max.toFloat())
                        .step(1f).initial(def.settings[setting].toFloat()).width(240).build()
                } else {
                    DialogInput.text(key, label)
                        .initial(def.settings[setting].toString()).maxLength(12).width(240).build()
                }

            is DoubleSetting -> DialogInput.text(key, label)
                .initial(def.settings[setting].toString()).maxLength(16).width(240).build()

            is DurationSetting -> DialogInput.text(key, label)
                .initial(Durations.format(def.settings[setting])).maxLength(24).width(240).build()

            is ChoiceSetting -> DialogInput.singleOption(
                key, label,
                setting.options.map { (id, display) ->
                    SingleOptionDialogInput.OptionEntry.create(
                        id, Text.renderFlat(display), id == def.settings[setting],
                    )
                },
            ).width(240).build()

            is TextSetting -> DialogInput.text(key, label)
                .initial(def.settings[setting]).maxLength(setting.maxLength).width(240).build()
        }
    }

    private fun apply(setting: Setting<*>, view: DialogResponseView, def: GameDefinition) {
        when (setting) {
            is BoolSetting -> dlg.boolOf(view, setting.key)?.let { def.settings.putRaw(setting.key, it) }

            is IntSetting ->
                if (setting.slider) {
                    dlg.floatOf(view, setting.key)?.let { def.settings.putRaw(setting.key, setting.parse(it)) }
                } else {
                    dlg.textOf(view, setting.key)?.let { def.settings.putRaw(setting.key, setting.parse(it)) }
                }

            else -> dlg.textOf(view, setting.key)?.let { def.settings.putRaw(setting.key, setting.parse(it)) }
        }
    }

    // --- entry conditions ------------------------------------------------------

    private fun entryDialog(player: Player, def: GameDefinition): Dialog {
        val entry = def.entry
        val lines = mutableListOf("<gray>참가 조건을 설정합니다.</gray>", "")
        lines.addAll(entry.describe())
        entry.feeItem?.let {
            lines.add("<dark_gray>참가 아이템은 아래 [참가비 아이템] 에서 바꿉니다.</dark_gray>")
        }

        val inputs = listOfNotNull(
            DialogInput.text("daily_limit", Text.renderFlat("일일 플레이 횟수 (0 = 무제한)"))
                .initial(entry.dailyLimit.toString()).maxLength(6).width(240).build(),
            DialogInput.numberRange("reset_hour", Text.renderFlat("일일 초기화 시각 (시)"), 0f, 23f)
                .step(1f).initial(entry.dailyResetHour.toFloat()).width(240).build(),
            DialogInput.text("cooldown", Text.renderFlat("재플레이 대기시간 (예: 30s)"))
                .initial(Durations.format(entry.cooldownSeconds)).maxLength(24).width(240).build(),
            DialogInput.text("fee_money", Text.renderFlat("참가비 (돈)"))
                .initial(entry.feeMoney.toString()).maxLength(16).width(240).build(),
            // 화폐가 여럿일 때만(2026-10-08). "-" = 기본 화폐(빈 id 는 선택지 id 로 못 쓴다).
            DialogInput.singleOption(
                "currency", Text.renderFlat("참가비 화폐"),
                (listOf("-" to "기본 화폐") + ng.economy.currencies()).map { (id, name) ->
                    SingleOptionDialogInput.OptionEntry.create(id, Text.renderFlat(name), id == entry.currency.ifBlank { "-" })
                },
            ).width(240).build().takeIf { ng.economy.multiCurrency },
            DialogInput.text("fee_amount", Text.renderFlat("참가 아이템 개수"))
                .initial(entry.feeItemAmount.toString()).maxLength(4).width(240).build(),
            DialogInput.bool("refund", Text.renderFlat("실패 시 참가비 환불")).initial(entry.refundOnFail).build(),
        )

        return dlg.menu(
            title = "<light_purple>참가 조건</light_purple>",
            lines = lines,
            buttons = listOf(
                dlg.button("<green>저장</green>") { p, view ->
                    view.getText("daily_limit")?.trim()?.toIntOrNull()?.let { entry.dailyLimit = it }
                    view.getFloat("reset_hour")?.let { entry.dailyResetHour = it.toInt() }
                    view.getText("cooldown")?.let { entry.cooldownSeconds = Durations.parse(it, 0L) }
                    view.getText("fee_money")?.trim()?.replace(",", "")?.toDoubleOrNull()
                        ?.let { entry.feeMoney = it.coerceAtLeast(0.0) }
                    view.getText("currency")?.let { entry.currency = if (it == "-") "" else it }
                    view.getText("fee_amount")?.trim()?.toIntOrNull()?.let { entry.feeItemAmount = it }
                    view.getBoolean("refund")?.let { entry.refundOnFail = it }
                    ng.games.markDirty(def)
                    dlg.reshow(p) { entryDialog(p, def) }
                },
                dlg.button("<white>참가비 아이템</white>", "인벤토리 창에서 아이템을 올려 지정합니다.") { p, _ ->
                    ng.adminRewards.openFeeItemMenu(p, def)
                },
                dlg.button("<red>참가비 아이템 제거</red>") { p, _ ->
                    entry.feeItem = null
                    ng.games.markDirty(def)
                    dlg.reshow(p) { entryDialog(p, def) }
                },
                dlg.backButton(),
            ),
            inputs = inputs,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- ranking settings ------------------------------------------------------

    private fun rankingDialog(player: Player, def: GameDefinition): Dialog {
        val ranking = def.ranking
        val reset = ranking.reset
        val next = ng.ranks.nextResetAt(def)

        val lines = mutableListOf(
            "<gray>순위 기준: <white>" + ranking.mode.display + "</white></gray>",
            "<dark_gray>" + ranking.mode.help + "</dark_gray>",
            "",
            "<gray>초기화: <white>" + reset.describe() + "</white></gray>",
        )
        if (next > 0L) {
            lines.add(
                "<gray>다음 초기화까지: <yellow>" +
                    Durations.formatShort(((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)) +
                    "</yellow></gray>"
            )
        }
        lines.add("<dark_gray>요일은 매주, 날짜는 매월 정책에서만 쓰입니다.</dark_gray>")

        val inputs = listOf(
            DialogInput.singleOption(
                "mode", Text.renderFlat("순위 기준"),
                RankMode.entries.map {
                    SingleOptionDialogInput.OptionEntry.create(
                        it.name, Text.renderFlat(it.display), it == ranking.mode,
                    )
                },
            ).width(240).build(),
            DialogInput.singleOption(
                "policy", Text.renderFlat("초기화 정책"),
                ResetPolicy.entries.map {
                    SingleOptionDialogInput.OptionEntry.create(
                        it.name, Text.renderFlat(it.display), it == reset.policy,
                    )
                },
            ).width(240).build(),
            DialogInput.numberRange("hour", Text.renderFlat("초기화 시각 (시)"), 0f, 23f)
                .step(1f).initial(reset.hour.toFloat()).width(240).build(),
            DialogInput.numberRange("minute", Text.renderFlat("초기화 시각 (분)"), 0f, 59f)
                .step(1f).initial(reset.minute.toFloat()).width(240).build(),
            DialogInput.singleOption(
                "weekday", Text.renderFlat("요일 (매주)"),
                DayOfWeek.entries.map {
                    SingleOptionDialogInput.OptionEntry.create(
                        it.name,
                        Text.renderFlat(ResetSchedule.WEEKDAY_NAMES[it] ?: it.name),
                        it == reset.weekday,
                    )
                },
            ).width(240).build(),
            DialogInput.numberRange("day", Text.renderFlat("날짜 (매월)"), 1f, 31f)
                .step(1f).initial(reset.dayOfMonth.toFloat()).width(240).build(),
            DialogInput.text("interval", Text.renderFlat("주기 (예: 7d)"))
                .initial(Durations.format(reset.intervalSeconds)).maxLength(24).width(240).build(),
            DialogInput.text("min_plays", Text.renderFlat("순위 보상 최소 플레이 수"))
                .initial(ranking.minPlays.toString()).maxLength(6).width(240).build(),
            DialogInput.bool("announce", Text.renderFlat("초기화 시 전체 공지"))
                .initial(ranking.announceReset).build(),
        )

        return dlg.menu(
            title = "<light_purple>랭킹 설정</light_purple>",
            lines = lines,
            buttons = listOf(
                dlg.button("<green>저장</green>") { p, view ->
                    view.getText("mode")?.let { ranking.mode = RankMode.parse(it) }
                    view.getText("policy")?.let { reset.policy = ResetPolicy.parse(it) }
                    view.getFloat("hour")?.let { reset.hour = it.toInt() }
                    view.getFloat("minute")?.let { reset.minute = it.toInt() }
                    view.getText("weekday")?.let { reset.weekday = ResetSchedule.parseWeekday(it) }
                    view.getFloat("day")?.let { reset.dayOfMonth = it.toInt() }
                    view.getText("interval")?.let {
                        reset.intervalSeconds = Durations.parse(it, 7L * 86400L)
                    }
                    view.getText("min_plays")?.trim()?.toIntOrNull()?.let { ranking.minPlays = it }
                    view.getBoolean("announce")?.let { ranking.announceReset = it }
                    ng.games.markDirty(def)
                    // The clock only moves when told to; without this the old instant stands.
                    ng.ranks.rescheduleNow(def)
                    dlg.reshow(p) { rankingDialog(p, def) }
                },
                dlg.backButton(),
            ),
            inputs = inputs,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- season management -----------------------------------------------------

    private fun seasonDialog(player: Player, def: GameDefinition): Dialog {
        val boards = ng.ranks.boardsOf(def.id)
        val standings = ng.ranks.top(def, 10)
        val lines = mutableListOf(
            "<gray>현재 시즌: <white>" + boards.seasonNumber + "</white> · 참가자 <white>" +
                boards.season.size + "명</white></gray>",
            "",
        )
        if (standings.isEmpty()) {
            lines.add("<gray>아직 기록이 없습니다.</gray>")
        } else {
            standings.forEachIndexed { index, entry ->
                val eligible = entry.plays >= def.ranking.minPlays
                lines.add(
                    "<gray>" + (index + 1) + "위</gray> <white>" + entry.name + "</white> " +
                        "<yellow>" + ng.ranks.formatValue(def, entry) + "</yellow>" +
                        if (eligible) "" else " <red>(플레이 수 미달)</red>"
                )
            }
        }
        val archived = ng.ranks.seasonsOf(def.id)
        if (archived.isNotEmpty()) {
            lines.add("")
            lines.add("<dark_gray>보관된 지난 시즌 " + archived.size + "개</dark_gray>")
        }

        return dlg.menu(
            title = "<light_purple>시즌 관리</light_purple>",
            lines = lines,
            buttons = listOf(
                dlg.button("<red>지금 시즌 종료</red>", "순위 보상을 지급하고 시즌 랭킹을 비웁니다.") { p, _ ->
                    dlg.push(p, { seasonDialog(p, def) }, confirmCloseDialog(p, def))
                },
                dlg.button("<yellow>지난 시즌 보기</yellow>") { p, _ ->
                    dlg.push(p, { seasonDialog(p, def) }, ng.screens.pastSeasonsDialog(def))
                },
                dlg.button("<red>통산 랭킹 초기화</red>", "보상 없이 통산 기록만 지웁니다.") { p, _ ->
                    dlg.push(
                        p, { seasonDialog(p, def) },
                        dlg.confirm(
                            title = "<red>통산 랭킹 초기화</red>",
                            lines = listOf(
                                "<gray><white>" + def.displayName + "</white> 의 통산 랭킹을 모두 지웁니다.</gray>",
                                "<red>되돌릴 수 없습니다. 보상은 지급되지 않습니다.</red>",
                            ),
                        ) { clicker ->
                            ng.ranks.wipe(def, allTime = true)
                            dlg.reshow(clicker) { seasonDialog(clicker, def) }
                        }
                    )
                },
                dlg.backButton(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun confirmCloseDialog(player: Player, def: GameDefinition): Dialog {
        val eligible = ng.ranks.top(def, def.ranking.boardSize)
            .count { it.plays >= def.ranking.minPlays }
        return dlg.confirm(
            title = "<red>시즌 종료</red>",
            lines = listOf(
                "<gray><white>" + def.displayName + "</white> 시즌을 지금 종료합니다.</gray>",
                "<gray>순위 보상 대상: <yellow>" + eligible + "명</yellow></gray>",
                "<gray>오프라인 대상자에게는 우편함으로 보냅니다.</gray>",
                "",
                "<red>시즌 랭킹은 비워지고 되돌릴 수 없습니다.</red>",
            ),
        ) { clicker ->
            val result = ng.ranks.closeSeason(def, automatic = false)
            ng.messages.send(
                clicker, "rank-reset-done",
                Ph.of().game(def.displayName).season(result.season),
            )
            dlg.reshow(clicker) { seasonDialog(clicker, def) }
        }
    }

    // --- recent play log -------------------------------------------------------

    /**
     * What actually happened lately.
     *
     * The log has been recorded since day one but had nowhere to be seen. It answers the one
     * question a leaderboard cannot - "why did I not get the reward" - because it shows the
     * individual result rather than the aggregate.
     */
    fun logDialog(player: Player, gameId: String?, page: Int): Dialog {
        val perPageRows = 10
        val entries = if (gameId == null) ng.log.recent() else ng.log.recentOf(gameId, Int.MAX_VALUE)
        val pages = Paging.pageCount(entries.size, perPageRows)
        val current = page.coerceIn(0, pages - 1)

        val lines = mutableListOf<String>()
        val scope = gameId?.let { ng.games.get(it)?.displayName ?: it } ?: "전체"
        lines.add("<gray>대상: <white>" + scope + "</white>  <dark_gray>|</dark_gray>  " + entries.size + "건 보관</gray>")
        lines.add("")

        if (entries.isEmpty()) {
            lines.add("<gray>아직 기록이 없습니다.</gray>")
        } else {
            val now = System.currentTimeMillis()
            Paging.slice(entries, current, perPageRows).forEach { entry ->
                val ago = Durations.formatShort(((now - entry.at) / 1000L).coerceAtLeast(0L))
                val mark = if (entry.cleared) "<green>성공</green>" else "<red>실패</red>"
                lines.add(
                    "<dark_gray>" + ago + " 전</dark_gray> <white>" + entry.player + "</white> " +
                        "<gray>" + entry.gameName + "</gray> " + mark
                )
                if (entry.detail.isNotBlank()) {
                    lines.add("  <dark_gray>" + entry.detail + "</dark_gray>")
                }
            }
            if (pages > 1) {
                lines.add("")
                lines.add("<dark_gray>" + (current + 1) + " / " + pages + " 페이지</dark_gray>")
            }
        }

        val buttons = mutableListOf<ActionButton>()
        if (current > 0) {
            buttons.add(dlg.button("<yellow>◀ 이전</yellow>") { p, _ ->
                dlg.reshow(p) { logDialog(p, gameId, current - 1) }
            })
        }
        if (current < pages - 1) {
            buttons.add(dlg.button("<yellow>다음 ▶</yellow>") { p, _ ->
                dlg.reshow(p) { logDialog(p, gameId, current + 1) }
            })
        }
        if (gameId != null) {
            buttons.add(dlg.button("<white>전체 보기</white>") { p, _ ->
                dlg.reshow(p) { logDialog(p, null, 0) }
            })
        }
        buttons.add(
            dlg.button("<red>기록 비우기</red>", "보관된 기록을 지웁니다. 랭킹에는 영향이 없습니다.") { p, _ ->
                dlg.push(
                    p, { logDialog(p, gameId, current) },
                    dlg.confirm(
                        title = "<red>기록 비우기</red>",
                        lines = listOf(
                            "<gray>대상: <white>" + scope + "</white></gray>",
                            "<dark_gray>랭킹·시즌 기록은 그대로 남습니다.</dark_gray>",
                        ),
                    ) { clicker ->
                        ng.log.clear(gameId)
                        dlg.reshow(clicker) { logDialog(clicker, gameId, 0) }
                    }
                )
            }
        )
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<light_purple>최근 기록</light_purple>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- event rounds ----------------------------------------------------------

    /**
     * Current round of a lotto / unique-bid game, and the button that settles it now.
     *
     * A manual draw exists because an admin running a launch event should not have to wait for
     * the scheduled deadline, and because it is the only way to test a prize table without
     * editing the interval down to a minute and back.
     */
    private fun eventRoundDialog(player: Player, def: GameDefinition): Dialog {
        // Peek, not open: viewing the admin screen must not start a draw clock.
        val round = ng.events.peek(def)
        val lines = mutableListOf<String>()
        lines.addAll(ng.events.describe(def, null))
        lines.add("")
        when {
            round == null -> lines.add("<gray>아직 열린 회차가 없습니다.</gray>")

            round.entries.isEmpty() -> lines.add("<gray>아직 참가자가 없습니다.</gray>")

            else -> {
                lines.add("<gray>참가자 <white>" + round.size + "명</white></gray>")
                round.entries.values.take(8).forEach {
                    lines.add("<dark_gray>· " + it.name + " — " + it.picks.joinToString(", ") + "</dark_gray>")
                }
                if (round.size > 8) lines.add("<dark_gray>… 외 " + (round.size - 8) + "명</dark_gray>")
            }
        }
        lines.add("")
        lines.add("<dark_gray>당첨 보상은 [순위 보상] 화면의 구간을 그대로 사용합니다.</dark_gray>")

        return dlg.menu(
            title = "<light_purple>" + def.displayName + " <gray>회차</gray></light_purple>",
            lines = lines,
            buttons = listOf(
                dlg.button("<red>지금 추첨</red>", "이번 회차를 즉시 정산하고 다음 회차를 엽니다.") { p, _ ->
                    dlg.push(
                        p, { eventRoundDialog(p, def) },
                        dlg.confirm(
                            title = "<red>즉시 추첨</red>",
                            lines = listOf(
                                "<gray><white>" + def.displayName + "</white> " + (round?.number ?: 1) +
                                    "회차를 지금 정산합니다.</gray>",
                                "<gray>참가자 <yellow>" + (round?.size ?: 0) + "명</yellow></gray>",
                                "<dark_gray>오프라인 당첨자에게는 우편함으로 보냅니다.</dark_gray>",
                            ),
                        ) { clicker ->
                            val result = ng.events.draw(def, announce = true)
                            ng.messages.send(
                                clicker, "event-drawn-manual",
                                Ph.of().game(def.displayName).count(result.number).amount(result.winners),
                            )
                            dlg.reshow(clicker) { eventRoundDialog(clicker, def) }
                        }
                    )
                },
                dlg.button("<gold>순위 보상 편집</gold>", "등수별 당첨 보상을 설정합니다.") { p, _ ->
                    dlg.push(p, { eventRoundDialog(p, def) }, ng.adminRewards.bracketListDialog(p, def))
                },
                dlg.backButton(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- create / delete -------------------------------------------------------

    private fun createTypeDialog(player: Player): Dialog {
        val buttons = GameType.entries.map { type ->
            dlg.button("<white>" + type.display + "</white>", type.summary) { p, _ ->
                dlg.push(p, { createTypeDialog(p) }, createFormDialog(p, type))
            }
        }.toMutableList()
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<green>새 게임 만들기</green>",
            lines = listOf("<gray>만들 게임의 종류를 고르세요.</gray>"),
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun createFormDialog(player: Player, type: GameType): Dialog = dlg.menu(
        title = "<green>" + type.display + " 추가</green>",
        lines = listOf(
            "<gray>" + type.summary + "</gray>",
            "",
            "<gray>id 는 파일 이름이 됩니다. 영문/숫자/_/- 만 사용하세요.</gray>",
            "<dark_gray>예: baseball5, updown1000</dark_gray>",
        ),
        buttons = listOf(
            dlg.button("<green>만들기</green>") { p, view ->
                val id = dlg.textOf(view, "id")?.trim().orEmpty()
                if (!GameDefinition.isValidId(id)) {
                    ng.messages.send(p, "admin-game-invalid-id")
                    dlg.reshow(p) { createFormDialog(p, type) }
                    return@button
                }
                if (ng.games.exists(id)) {
                    ng.messages.send(p, "admin-game-exists", Ph.of().game(id))
                    dlg.reshow(p) { createFormDialog(p, type) }
                    return@button
                }
                val name = view.getText("name")?.trim()?.takeIf { it.isNotBlank() }
                val def = ng.games.create(id, type, name)
                if (def == null) {
                    ng.messages.send(p, "admin-game-invalid-id")
                    dlg.reshow(p) { createFormDialog(p, type) }
                    return@button
                }
                ng.ranks.rescheduleNow(def)
                ng.messages.send(p, "admin-game-created", Ph.of().game(def.displayName))
                dlg.reshow(p) { gameDialog(p, def) }
            },
            dlg.backButton("<gray>취소</gray>"),
        ),
        inputs = listOf(
            DialogInput.text(dlg.inputKey("id"), Text.renderFlat("게임 id"))
                .initial(type.name.lowercase()).maxLength(32).width(240).build(),
            DialogInput.text("name", Text.renderFlat("표시 이름"))
                .initial(type.display).maxLength(48).width(240).build(),
        ),
        columns = 2,
        exit = dlg.closeButton(),
    )

    private fun deleteDialog(player: Player, def: GameDefinition): Dialog = dlg.confirm(
        title = "<red>게임 삭제</red>",
        lines = listOf(
            "<gray><white>" + def.displayName + "</white> <dark_gray>(" + def.id + ")</dark_gray> 을(를) 삭제합니다.</gray>",
            "<red>games/" + def.id + ".yml 이 지워지며 되돌릴 수 없습니다.</red>",
            "<dark_gray>랭킹 기록은 data/ 에 남지만 더 이상 표시되지 않습니다.</dark_gray>",
        ),
    ) { clicker ->
        ng.games.delete(def.id)
        ng.messages.send(clicker, "admin-game-deleted", Ph.of().game(def.displayName))
        dlg.reshow(clicker) { gameListDialog(clicker, 0) }
    }

    // --- play allowance resets -------------------------------------------------

    /** The two admin resets: one player, or the whole server. */
    private fun resetDialog(player: Player): Dialog {
        val games = ng.games.all()
        val lines = listOf(
            "<gray>일일 플레이 횟수와 재플레이 쿨다운을 되돌립니다.</gray>",
            "<dark_gray>누적 플레이 수와 랭킹 기록은 건드리지 않습니다.</dark_gray>",
            "",
            "<gray>게임을 <white>전체</white> 로 두면 모든 게임에 적용됩니다.</gray>",
        )
        val gameOptions = mutableListOf(
            SingleOptionDialogInput.OptionEntry.create("*", Text.renderFlat("전체"), true)
        )
        games.forEach {
            gameOptions.add(
                SingleOptionDialogInput.OptionEntry.create(it.id, Text.renderFlat(it.displayName), false)
            )
        }

        return dlg.menu(
            title = "<yellow>플레이 초기화</yellow>",
            lines = lines,
            buttons = listOf(
                dlg.button("<green>이 플레이어만</green>", "닉네임을 입력한 사람의 기록을 초기화합니다.") { p, view ->
                    val name = view.getText("player")?.trim().orEmpty()
                    val target = Bukkit.getOfflinePlayerIfCached(name)
                    if (name.isEmpty() || target == null) {
                        p.sendMessage(Text.render("<red>'" + name + "' 플레이어를 찾을 수 없습니다.</red>", null, p))
                        dlg.reshow(p) { resetDialog(p) }
                        return@button
                    }
                    val gameId = view.getText("game")?.takeIf { it != "*" }
                    ng.plays.resetPlayer(target.uniqueId, gameId)
                    ng.messages.send(p, "admin-plays-reset-player", Ph.of().player(target.name ?: name))
                    dlg.reshow(p) { resetDialog(p) }
                },
                dlg.button("<red>서버 전체</red>", "모든 플레이어의 일일 횟수를 초기화합니다.") { p, view ->
                    val gameId = view.getText("game")?.takeIf { it != "*" }
                    dlg.push(
                        p, { resetDialog(p) },
                        dlg.confirm(
                            title = "<red>서버 전체 초기화</red>",
                            lines = listOf(
                                "<gray>대상: <white>" +
                                    (gameId?.let { ng.games.get(it)?.displayName ?: it } ?: "모든 게임") +
                                    "</white></gray>",
                                "<gray>모든 플레이어의 오늘 도전 횟수가 되살아납니다.</gray>",
                            ),
                        ) { clicker ->
                            val touched = ng.plays.resetAll(gameId)
                            ng.messages.send(clicker, "admin-plays-reset-all", Ph.of().count(touched))
                            dlg.reshow(clicker) { resetDialog(clicker) }
                        }
                    )
                },
                dlg.backButton(),
            ),
            inputs = listOf(
                DialogInput.text("player", Text.renderFlat("플레이어 닉네임"))
                    .maxLength(16).width(240).build(),
                DialogInput.singleOption("game", Text.renderFlat("대상 게임"), gameOptions)
                    .width(240).build(),
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }
}
