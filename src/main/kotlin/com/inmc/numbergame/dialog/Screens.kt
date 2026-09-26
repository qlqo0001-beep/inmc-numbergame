package com.inmc.numbergame.dialog

import com.inmc.numbergame.Ng
import com.inmc.numbergame.Permissions
import com.inmc.numbergame.event.EventService
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameType
import com.inmc.numbergame.game.InputMode
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.gui.Paging
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.engine.LottoEngine
import kr.inmc.core.rank.RankMode
import kr.inmc.core.util.Durations
import com.inmc.numbergame.util.Ph
import kr.inmc.core.util.Text
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import org.bukkit.entity.Player

/**
 * Every player-facing screen.
 *
 * Screens are built as suppliers wherever they can be, so "back" and "refresh" rebuild from
 * current state instead of restoring a stale snapshot.
 */
class Screens(private val ng: Ng) {

    private val dlg get() = ng.dlg

    // --- main list -------------------------------------------------------------

    fun showMain(player: Player, page: Int = 0) {
        dlg.clearStack(player.uniqueId)
        dlg.show(player, mainDialog(player, page))
    }

    fun mainDialog(player: Player, page: Int = 0): Dialog {
        val games = ng.games.playableFor(player)
        val pageSize = ng.config.listPageSize
        val pages = Paging.pageCount(games.size, pageSize)
        val current = page.coerceIn(0, pages - 1)

        val buttons = mutableListOf<ActionButton>()
        Paging.slice(games, current, pageSize).forEach { def ->
            buttons.add(
                dlg.button(
                    label = "<white>" + def.displayName + "</white>",
                    tooltip = gameTooltip(player, def),
                ) { clicker, _ -> ng.gameService.open(clicker, def) }
            )
        }

        if (pages > 1) {
            if (current > 0) {
                buttons.add(dlg.button("<yellow>◀ 이전</yellow>") { p, _ ->
                    dlg.reshow(p) { mainDialog(p, current - 1) }
                })
            }
            if (current < pages - 1) {
                buttons.add(dlg.button("<yellow>다음 ▶</yellow>") { p, _ ->
                    dlg.reshow(p) { mainDialog(p, current + 1) }
                })
            }
        }

        buttons.add(dlg.button("<aqua>랭킹</aqua>", "게임별 순위와 다음 초기화 시각") { p, _ ->
            dlg.push(p, { mainDialog(p, current) }, rankingListDialog(p))
        })

        buttons.add(dlg.button("<gray>도움말</gray>", "명령어와 게임 설명") { p, _ ->
            dlg.push(p, { mainDialog(p, current) }, helpDialog(p))
        })

        val mail = ng.mailbox.countOf(player.uniqueId)
        buttons.add(
            dlg.button(
                if (mail > 0) "<gold>우편함 (" + mail + ")</gold>" else "<gray>우편함</gray>",
                "받지 않은 보상을 수령합니다.",
            ) { p, _ -> dlg.push(p, { mainDialog(p, current) }, mailboxDialog(p)) }
        )

        if (player.hasPermission(Permissions.ADMIN)) {
            buttons.add(dlg.button("<light_purple>관리</light_purple>", "게임·보상·랭킹 설정") { p, _ ->
                ng.admin.showRoot(p)
            })
        }

        val body = mutableListOf<String>()
        if (games.isEmpty()) {
            body.add("<gray>플레이할 수 있는 게임이 없습니다.</gray>")
            if (player.hasPermission(Permissions.ADMIN)) {
                body.add("<dark_gray>관리 → 새 게임 만들기 에서 추가할 수 있습니다.</dark_gray>")
            }
        } else {
            body.add("<gray>플레이할 게임을 선택하세요.</gray>")
            if (pages > 1) {
                body.add("<dark_gray>" + (current + 1) + " / " + pages + " 페이지</dark_gray>")
            }
        }

        return dlg.menu(
            title = "<gradient:#5ec8ff:#b48bff>숫자 미니게임</gradient>",
            lines = body,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton("<gray>닫기</gray>"),
        )
    }

    private fun gameTooltip(player: Player, def: GameDefinition): String {
        val parts = mutableListOf(def.type.summary)
        val remaining = ng.plays.remaining(player.uniqueId, def)
        if (remaining != null) parts.add("오늘 남은 횟수: " + remaining + "회")
        val rank = ng.ranks.rankOf(def, player.uniqueId)
        if (rank != null) parts.add("내 순위: " + rank + "위")
        return parts.joinToString(" / ")
    }

    // --- briefing --------------------------------------------------------------

    /** The screen between picking a game and actually starting it. */
    fun showBriefing(player: Player, def: GameDefinition) {
        dlg.push(player, { mainDialog(player) }, briefingDialog(player, def))
    }

    fun briefingDialog(player: Player, def: GameDefinition): Dialog {
        val body = def.summaryLines().toMutableList()

        val remaining = ng.plays.remaining(player.uniqueId, def)
        if (remaining != null) {
            body.add("<gray>오늘 남은 횟수: <yellow>" + remaining + "회</yellow></gray>")
        }
        val denial = ng.plays.check(player, def)
        if (denial != null) {
            body.add("")
            body.add("<red>지금은 시작할 수 없습니다: " + ng.plays.describeDenial(denial) + "</red>")
        }

        val buttons = mutableListOf<ActionButton>()
        if (denial == null) {
            buttons.add(dlg.button("<green>시작</green>") { p, _ -> ng.gameService.start(p, def) })
        }
        buttons.add(dlg.button("<aqua>랭킹 보기</aqua>") { p, _ ->
            dlg.push(p, { briefingDialog(p, def) }, rankingDialog(p, def, allTime = false))
        })
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = def.displayName,
            lines = body,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- event entry -----------------------------------------------------------

    /**
     * The entry screen for a server-wide round.
     *
     * There is no play loop here: you commit numbers, and the round settles later on its own
     * clock. Re-entering simply overwrites the previous pick, which is why the screen always
     * shows what you currently hold.
     */
    fun showEventEntry(player: Player, def: GameDefinition, note: String?) {
        dlg.push(player, { mainDialog(player) }, eventDialog(player, def, note))
    }

    fun eventDialog(
        player: Player,
        def: GameDefinition,
        note: String?,
        /** Overrides what the number box starts with, so 자동 선택 can actually change it. */
        prefill: String? = null,
    ): Dialog {
        val lines = mutableListOf<String>()
        if (note != null) {
            lines.add(note)
            lines.add("")
        }
        lines.addAll(def.description)
        lines.add("")
        lines.addAll(ng.events.describe(def, player.uniqueId))

        val existing = ng.events.currentOrNull(def)?.entryOf(player.uniqueId)
        val label = if (def.type == GameType.LOTTO) "번호 ( , 로 구분 )" else "숫자"

        val buttons = listOf(
            dlg.button("<green>참가</green>", "입력한 번호로 참가합니다. 이미 참가했다면 번호만 바뀝니다.") { p, view ->
                when (val result = ng.events.enter(p, def, view.getText("picks"))) {
                    is EventService.EnterResult.Ok -> {
                        val message = if (result.replaced) "번호를 바꿨습니다: " else "참가했습니다: "
                        dlg.reshow(p) {
                            eventDialog(p, def, "<green>" + message + LottoEngine.format(result.picks) + "</green>")
                        }
                    }

                    is EventService.EnterResult.Rejected ->
                        dlg.reshow(p) { eventDialog(p, def, "<red>" + result.reason + "</red>") }
                }
            },
            dlg.button("<aqua>자동 선택</aqua>", "무작위 번호를 입력란에 채워 넣습니다.") { p, _ ->
                val picked = ng.events.autoPick(def)
                dlg.reshow(p) {
                    eventDialog(
                        p, def,
                        "<gray>자동 번호를 채웠습니다. [참가] 를 눌러 확정하세요.</gray>",
                        prefill = picked,
                    )
                }
            },
            dlg.button("<aqua>랭킹</aqua>") { p, _ ->
                dlg.push(p, { eventDialog(p, def, null) }, rankingDialog(p, def, allTime = false))
            },
            dlg.backButton(),
        )

        return dlg.menu(
            title = def.displayName,
            lines = lines,
            buttons = buttons,
            inputs = listOf(
                dlg.input(
                    InputSpec.Text(
                        key = "picks",
                        label = label,
                        maxLength = 48,
                        // Pre-filled with the current entry, or a machine pick to nudge people
                        // who have no idea what to type into a lottery box.
                        initial = prefill
                            ?: existing?.let { LottoEngine.format(it.picks) }
                            ?: ng.events.autoPick(def),
                    )
                )
            ),
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- resume ----------------------------------------------------------------

    fun showResume(player: Player, session: Session) {
        val def = ng.games.get(session.gameId)
        if (def == null) {
            ng.sessions.remove(player.uniqueId)
            showMain(player)
            return
        }
        dlg.show(
            player,
            dlg.menu(
                title = "<yellow>진행 중인 게임</yellow>",
                lines = listOf(
                    "<gray><white>" + def.displayName + "</white> 이(가) 진행 중입니다.</gray>",
                    "<gray>시도 <yellow>" + session.attempts + "회</yellow> · 경과 <yellow>" +
                        Durations.formatShort(session.elapsedMillis() / 1000L) + "</yellow></gray>",
                    "",
                    "<dark_gray>포기하면 참가 횟수는 돌려받지 못합니다.</dark_gray>",
                ),
                buttons = listOf(
                    dlg.button("<green>이어하기</green>") { p, _ ->
                        ng.messages.send(p, "session-resumed", Ph.of().game(def.displayName))
                        showPlay(p, session, null)
                    },
                    dlg.button("<red>포기하기</red>") { p, _ ->
                        ng.messages.send(p, "session-abandoned", Ph.of().game(def.displayName))
                        ng.gameService.giveUp(p)
                    },
                ),
                columns = 2,
                exit = dlg.closeButton(),
            )
        )
    }

    // --- play ------------------------------------------------------------------

    /** Redraws the in-progress screen. [note] is last turn's feedback, shown at the top. */
    fun showPlay(player: Player, session: Session, note: String?) {
        val def = ng.games.get(session.gameId) ?: return
        refreshBalance(player, session, def)
        // Built once and reused: the auto-advance timer needs the same screen the player is
        // looking at, and rebuilding it would quietly depend on `screen()` being pure.
        val screen = def.engine.screen(def, session)

        if (ng.gameService.inputModeOf(def) == InputMode.FAST) {
            showFastPlay(player, screen, note)
        } else {
            dlg.reshow(player) { playDialog(player, session, screen, note) }
        }
        scheduleAutoAdvance(player, session, screen)
    }

    /**
     * The no-flicker path: question on the action bar, answer typed into chat.
     *
     * The action bar is overwritten in place, so a player answering thirty questions in a minute
     * never sees the screen close. Feedback for the previous answer goes to chat so it does not
     * fight the question for the one line the action bar has.
     */
    private fun showFastPlay(player: Player, screen: Screen, note: String?) {
        dlg.onMain {
            if (!player.isOnline) return@onMain
            note?.let { player.sendMessage(Text.render(it, null, player)) }
            val line = screen.actionBar ?: screen.body.firstOrNull { it.isNotBlank() } ?: screen.title
            player.sendActionBar(Text.renderFlat(line))
        }
    }

    /**
     * Drives a turn that ends on a clock instead of a click (the memory game's reveal).
     *
     * The session token guards against a stale timer: if the player finished, gave up or started
     * something else in the meantime, the fired task finds a different session and does nothing.
     */
    private fun scheduleAutoAdvance(player: Player, session: Session, screen: Screen) {
        if (screen.autoAdvanceMillis <= 0L) return

        val marker = session.revision
        val delayTicks = (screen.autoAdvanceMillis / 50L).coerceAtLeast(1L)
        org.bukkit.Bukkit.getScheduler().runTaskLater(ng.plugin, Runnable {
            if (!player.isOnline) return@Runnable
            val live = ng.sessions.of(player.uniqueId) ?: return@Runnable
            if (live !== session || live.revision != marker) return@Runnable
            ng.gameService.submit(player, SubmitInput(button = screen.autoAdvanceButton))
        }, delayTicks)
    }

    fun playDialog(player: Player, session: Session, screen: Screen, note: String?): Dialog? {
        if (ng.games.get(session.gameId) == null) return null

        val lines = mutableListOf<String>()
        if (note != null) {
            lines.add(note)
            lines.add("")
        }
        lines.addAll(screen.body)

        val inputs = screen.inputs.map { dlg.input(it) }
        val buttons = mutableListOf<ActionButton>()

        if (screen.inputs.isNotEmpty()) {
            buttons.add(
                dlg.button("<green>" + screen.submitLabel + "</green>") { p, view ->
                    ng.gameService.submit(p, read(screen.inputs, view))
                }
            )
        }
        for (choice in screen.buttons) {
            buttons.add(
                dlg.button("<white>" + choice.label + "</white>", choice.tooltip) { p, view ->
                    // A button click carries the fields too, so a bet can pick its outcome with
                    // one press while the stake box is still filled in.
                    ng.gameService.submit(p, read(screen.inputs, view).copy(button = choice.id))
                }
            )
        }
        if (screen.canGiveUp) {
            buttons.add(
                dlg.button("<red>포기</red>", "게임을 끝내고 결과를 확인합니다.") { p, _ ->
                    ng.gameService.giveUp(p)
                }
            )
        }

        return dlg.menu(
            title = screen.title,
            lines = lines,
            buttons = buttons,
            inputs = inputs,
            columns = if (buttons.size > 3) 3 else 2,
            // No exit action: leaving is what 포기 is for, and Escape keeps the session alive.
            canEscape = true,
        )
    }

    /** Only games that deal in money pay the lookup cost. */
    private fun refreshBalance(player: Player, session: Session, def: GameDefinition) {
        if (!def.type.needsEconomy) return
        session.balance = if (ng.economy.isEnabled) ng.economy.balance(player) else 0.0
    }

    /** Pulls this turn's answers out of the dialog response, one entry per declared field. */
    private fun read(specs: List<InputSpec>, view: DialogResponseView): SubmitInput {
        val values = LinkedHashMap<String, String>()
        for (spec in specs) {
            val raw = when (spec) {
                // A number-range slider reports a float even when it steps by one.
                is InputSpec.Number -> dlg.floatOf(view, spec.key)?.toInt()?.toString()
                else -> dlg.textOf(view, spec.key)
            }
            // Keyed by our own name, not the sanitised one, so engines look up what they declared.
            if (raw != null) values[spec.key] = raw
        }
        return SubmitInput(values)
    }

    // --- result ----------------------------------------------------------------

    fun showResult(
        player: Player,
        def: GameDefinition,
        outcome: Outcome,
        cleared: Boolean,
        earned: List<String>,
        reason: String?,
    ) {
        dlg.reshow(player) { resultDialog(player, def, outcome, cleared, earned, reason) }
    }

    private fun resultDialog(
        player: Player,
        def: GameDefinition,
        outcome: Outcome,
        cleared: Boolean,
        earned: List<String>,
        reason: String?,
    ): Dialog {
        val lines = outcome.summary.toMutableList()
        if (!cleared && reason != null) lines.add("<dark_gray>(" + reason + ")</dark_gray>")

        if (def.ranking.mode == RankMode.CUMULATIVE_SCORE && outcome.score != 0L) {
            lines.add("<gray>획득 점수: <yellow>" + String.format("%,d", outcome.score) + "점</yellow></gray>")
        }

        val rank = ng.ranks.rankOf(def, player.uniqueId)
        if (rank != null) {
            lines.add("<gray>현재 시즌 순위: <yellow>" + rank + "위</yellow></gray>")
        }

        lines.add("")
        if (earned.isEmpty()) {
            lines.add("<dark_gray>받은 보상이 없습니다.</dark_gray>")
        } else {
            lines.add("<gold>획득 보상</gold>")
            earned.forEach { lines.add("<gray>· <white>" + it + "</white></gray>") }
        }

        val remaining = ng.plays.remaining(player.uniqueId, def)
        if (remaining != null) {
            lines.add("")
            lines.add("<gray>오늘 남은 횟수: <yellow>" + remaining + "회</yellow></gray>")
        }

        val buttons = mutableListOf<ActionButton>()
        if (remaining == null || remaining > 0) {
            buttons.add(dlg.button("<green>다시 하기</green>") { p, _ -> ng.gameService.start(p, def) })
        }
        buttons.add(dlg.button("<aqua>랭킹</aqua>") { p, _ ->
            dlg.push(p, { mainDialog(p) }, rankingDialog(p, def, allTime = false))
        })
        buttons.add(dlg.button("<gray>게임 목록</gray>") { p, _ -> showMain(p) })

        return dlg.menu(
            title = if (cleared) "<green>클리어!</green>" else "<red>게임 종료</red>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    // --- ranking ---------------------------------------------------------------

    fun showRankingList(player: Player) {
        dlg.push(player, { mainDialog(player) }, rankingListDialog(player))
    }

    fun rankingListDialog(player: Player): Dialog {
        val games = ng.games.enabled()
        val buttons = games.map { def ->
            dlg.button(
                "<white>" + def.displayName + "</white>",
                def.ranking.mode.display + " · 초기화 " + def.ranking.reset.describe(),
            ) { p, _ -> dlg.push(p, { rankingListDialog(p) }, rankingDialog(p, def, allTime = false)) }
        }.toMutableList()
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<aqua>랭킹</aqua>",
            lines = if (games.isEmpty()) listOf("<gray>등록된 게임이 없습니다.</gray>")
            else listOf("<gray>랭킹을 확인할 게임을 선택하세요.</gray>"),
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    fun rankingDialog(player: Player, def: GameDefinition, allTime: Boolean): Dialog {
        val boards = ng.ranks.boardsOf(def.id)
        val entries = ng.ranks.top(def, def.ranking.boardSize.coerceAtMost(20), allTime)
        val lines = mutableListOf<String>()

        lines.add(
            "<gray>기준: <white>" + def.ranking.mode.display + "</white>  <dark_gray>|</dark_gray>  " +
                (if (allTime) "<white>통산</white>" else "<white>시즌 " + boards.seasonNumber + "</white>") + "</gray>"
        )
        if (!allTime) {
            val next = boards.nextResetAt
            lines.add(
                if (next > 0L) {
                    "<gray>다음 초기화까지: <yellow>" +
                        Durations.formatShort(((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)) +
                        "</yellow> <dark_gray>(" + def.ranking.reset.describe() + ")</dark_gray></gray>"
                } else {
                    "<gray>초기화: <white>" + def.ranking.reset.describe() + "</white></gray>"
                }
            )
        }
        lines.add("")

        if (entries.isEmpty()) {
            lines.add("<gray>아직 기록이 없습니다.</gray>")
        } else {
            entries.forEachIndexed { index, entry ->
                val rank = index + 1
                val mine = entry.id == player.uniqueId
                val name = if (mine) "<green>" + entry.name + "</green>" else "<white>" + entry.name + "</white>"
                lines.add(
                    medal(rank) + " <gray>" + rank + "위</gray> " + name +
                        " <dark_gray>-</dark_gray> <yellow>" + ng.ranks.formatValue(def, entry) + "</yellow>"
                )
            }
        }

        val myRank = ng.ranks.rankOf(def, player.uniqueId, allTime)
        lines.add("")
        lines.add(
            if (myRank != null) "<gray>내 순위: <yellow>" + myRank + "위</yellow></gray>"
            else "<dark_gray>아직 순위에 오르지 않았습니다.</dark_gray>"
        )
        if (def.ranking.minPlays > 1) {
            lines.add("<dark_gray>순위 보상은 " + def.ranking.minPlays + "회 이상 플레이해야 받을 수 있습니다.</dark_gray>")
        }

        val buttons = mutableListOf<ActionButton>()
        buttons.add(
            dlg.button(
                if (allTime) "<aqua>시즌 랭킹</aqua>" else "<aqua>통산 랭킹</aqua>",
            ) { p, _ -> dlg.reshow(p) { rankingDialog(p, def, !allTime) } }
        )
        if (def.rewards.rankBrackets.isNotEmpty()) {
            buttons.add(dlg.button("<gold>순위 보상</gold>") { p, _ ->
                dlg.push(p, { rankingDialog(p, def, allTime) }, rankRewardDialog(def))
            })
        }
        if (ng.ranks.seasonsOf(def.id).isNotEmpty()) {
            buttons.add(dlg.button("<yellow>지난 시즌</yellow>", "이전 시즌의 상위 기록") { p, _ ->
                dlg.push(p, { rankingDialog(p, def, allTime) }, pastSeasonsDialog(def))
            })
        }
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = def.displayName + " <gray>랭킹</gray>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun rankRewardDialog(def: GameDefinition): Dialog {
        val lines = mutableListOf<String>("<gray>시즌이 끝나면 아래 보상이 지급됩니다.</gray>", "")
        def.rewards.rankBrackets.forEach { bracket ->
            lines.add("<gold>" + bracket.describe() + "</gold>")
            if (bracket.bundle.entries.isEmpty()) {
                lines.add("<dark_gray>· 등록된 보상 없음</dark_gray>")
            } else {
                bracket.bundle.entries.forEach { lines.add("<gray>· <white>" + it.label() + "</white></gray>") }
            }
        }
        return dlg.notice(
            title = def.displayName + " <gray>순위 보상</gray>",
            lines = lines,
            dismiss = dlg.backButton("<gray>◀ 뒤로</gray>"),
        )
    }

    /**
     * Closed seasons, shared by the ranking screen and the admin season screen.
     *
     * Players care about this at least as much as admins do - a season they won is the record
     * their name is on - so it lives here rather than behind the admin permission.
     */
    fun pastSeasonsDialog(def: GameDefinition): Dialog {
        val seasons = ng.ranks.seasonsOf(def.id)
        val lines = mutableListOf<String>()
        if (seasons.isEmpty()) {
            lines.add("<gray>보관된 지난 시즌이 없습니다.</gray>")
        } else {
            seasons.take(6).forEach { season ->
                lines.add("<gold>시즌 " + season.season + "</gold> <dark_gray>(" + season.mode.display + ")</dark_gray>")
                season.places.take(3).forEach { place ->
                    lines.add(
                        "<gray>  " + place.rank + "위 <white>" + place.name + "</white> " +
                            "<yellow>" + place.value + "</yellow></gray>"
                    )
                }
                lines.add("")
            }
        }
        return dlg.notice(
            title = def.displayName + " <gray>지난 시즌</gray>",
            lines = lines,
            dismiss = dlg.backButton(),
        )
    }

    private fun medal(rank: Int): String = when (rank) {
        1 -> "<gold>★</gold>"
        2 -> "<white>★</white>"
        3 -> "<#cd7f32>★</#cd7f32>"
        else -> "<dark_gray>·</dark_gray>"
    }

    // --- help ------------------------------------------------------------------

    fun showHelp(player: Player) {
        dlg.push(player, { mainDialog(player) }, helpDialog(player))
    }

    /**
     * What the plugin is and how to reach it.
     *
     * Lists the games actually available to *this* player rather than everything installed, so
     * a permission-gated game does not advertise itself to someone who cannot open it.
     */
    fun helpDialog(player: Player): Dialog {
        val lines = mutableListOf(
            "<gray>숫자로 하는 미니게임 모음입니다.</gray>",
            "",
            "<yellow>명령어</yellow>",
            "<gray>/숫자게임 <dark_gray>- 게임 목록</dark_gray></gray>",
            "<gray>/숫자게임 <white>[게임id]</white> <dark_gray>- 바로 시작</dark_gray></gray>",
            "<gray>/숫자게임 랭킹 <dark_gray>- 순위와 다음 초기화</dark_gray></gray>",
            "<gray>/숫자게임 우편함 <dark_gray>- 못 받은 보상 수령</dark_gray></gray>",
            "",
            "<yellow>지금 할 수 있는 게임</yellow>",
        )

        val games = ng.games.playableFor(player)
        if (games.isEmpty()) {
            lines.add("<dark_gray>없습니다.</dark_gray>")
        } else {
            games.forEach { def ->
                lines.add("<gray>· <white>" + def.displayName + "</white> <dark_gray>(" + def.id + ")</dark_gray></gray>")
                lines.add("  <dark_gray>" + def.type.summary + "</dark_gray>")
            }
        }

        if (player.hasPermission(Permissions.ADMIN)) {
            lines.add("")
            lines.add("<light_purple>관리자</light_purple>")
            lines.add("<gray>/숫자게임 관리 <dark_gray>- 설정 전부</dark_gray></gray>")
            lines.add("<gray>/숫자게임 관리 초기화 플레이어 <white>[닉]</white></gray>")
            lines.add("<gray>/숫자게임 관리 초기화 전체</gray>")
            lines.add("<gray>/숫자게임 관리 시즌종료 <white>[게임]</white></gray>")
        }

        return dlg.notice(
            title = "<gradient:#5ec8ff:#b48bff>숫자 미니게임 도움말</gradient>",
            lines = lines,
            dismiss = dlg.backButton(),
        )
    }

    // --- mailbox ---------------------------------------------------------------

    fun showMailbox(player: Player) {
        dlg.push(player, { mainDialog(player) }, mailboxDialog(player))
    }

    fun mailboxDialog(player: Player): Dialog {
        val entries = ng.mailbox.entriesOf(player.uniqueId)
        val lines = mutableListOf<String>()

        if (entries.isEmpty()) {
            lines.add("<gray>받을 보상이 없습니다.</gray>")
        } else {
            lines.add("<gray>받지 않은 보상 <yellow>" + entries.size + "건</yellow></gray>")
            lines.add("")
            entries.take(15).forEach { entry ->
                lines.add("<gray>· <white>" + entry.describe() + "</white></gray>")
                if (entry.source.isNotBlank()) {
                    lines.add("  <dark_gray>" + entry.source + "</dark_gray>")
                }
            }
            if (entries.size > 15) {
                lines.add("<dark_gray>… 외 " + (entries.size - 15) + "건</dark_gray>")
            }
            lines.add("")
            lines.add("<dark_gray>인벤토리 공간이 부족하면 남은 보상은 그대로 보관됩니다.</dark_gray>")
        }

        val buttons = mutableListOf<ActionButton>()
        if (entries.isNotEmpty()) {
            buttons.add(dlg.button("<green>전부 받기</green>") { p, _ -> claim(p) })
        }
        buttons.add(dlg.backButton())

        return dlg.menu(
            title = "<gold>우편함</gold>",
            lines = lines,
            buttons = buttons,
            columns = 2,
            exit = dlg.closeButton(),
        )
    }

    private fun claim(player: Player) {
        if (ng.mailbox.isEmpty(player.uniqueId)) {
            ng.messages.send(player, "mailbox-empty")
            return
        }
        val result = ng.mailbox.claimAll(player)
        if (result.claimed > 0) {
            ng.messages.send(player, "mailbox-claimed", Ph.of().count(result.claimed))
        }
        if (result.partial) ng.messages.send(player, "mailbox-partial")
        dlg.reshow(player) { mailboxDialog(player) }
    }

}
