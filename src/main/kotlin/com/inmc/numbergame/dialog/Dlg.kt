package com.inmc.numbergame.dialog

import com.inmc.numbergame.Ng
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.gui.Dialogs
import kr.inmc.core.integration.PluginClasses
import kr.inmc.core.util.Text
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only place that touches Paper's dialog API.
 *
 * Dialogs are built on the fly and shown directly rather than registered into the dialog
 * registry: every screen in this plugin depends on live state (a leaderboard, a half-finished
 * game, a settings form), so a registered immutable value would be stale the moment it existed.
 *
 * Three details here are load-bearing and easy to get wrong:
 *
 *  - **Callbacks are not guaranteed to be on the main thread.** [button] hops to it before
 *    running anything, so callers can touch Bukkit freely.
 *  - **A dialog cannot be edited once shown.** Screens that change - a guess history growing a
 *    line - close and reopen. [reshow] does that one tick later, because a re-show in the same
 *    tick as the close is dropped by the client.
 *  - **Callbacks default to a single use.** Without [Dialogs.CALLBACK_OPTIONS] a player who
 *    double-clicks a button finds the second click silently dead.
 */
class Dlg(private val ng: Ng) {

    private val backStacks = ConcurrentHashMap<UUID, ArrayDeque<() -> Dialog?>>()

    // --- building --------------------------------------------------------------

    fun button(
        label: String,
        tooltip: String? = null,
        width: Int = DEFAULT_BUTTON_WIDTH,
        onClick: (Player, DialogResponseView) -> Unit,
    ): ActionButton {
        val builder = ActionButton.builder(Text.renderFlat(label))
        if (tooltip != null) builder.tooltip(Text.renderFlat(tooltip))
        builder.width(width.coerceIn(1, 1024))
        builder.action(
            DialogAction.customClick({ view, audience ->
                val player = audience as? Player ?: return@customClick
                onMain { if (player.isOnline) onClick(player, view) }
            }, Dialogs.CALLBACK_OPTIONS)
        )
        return builder.build()
    }

    /** A button that runs nothing - used for a plain close. */
    fun closeButton(label: String = "<gray>닫기</gray>"): ActionButton =
        button(label) { _, _ -> }

    fun backButton(label: String = "<gray>◀ 뒤로</gray>"): ActionButton =
        button(label) { player, _ -> back(player) }

    /**
     * Body text.
     *
     * Every line becomes one row of a single message body rather than one body each: a guess
     * history of forty lines would otherwise be forty separate blocks with forty gaps.
     */
    fun body(lines: List<String>, width: Int = DEFAULT_BODY_WIDTH): List<DialogBody> {
        if (lines.isEmpty()) return emptyList()
        var joined: Component = Component.empty()
        lines.forEachIndexed { index, line ->
            if (index > 0) joined = joined.append(Component.newline())
            joined = joined.append(Text.renderFlat(line))
        }
        return listOf(DialogBody.plainMessage(joined, width.coerceIn(1, 1024)))
    }

    /**
     * Converts our key into one Minecraft will accept.
     *
     * Dialog input names feed the same macro substitution that `$(name)` uses in functions, and
     * the server validates them with `StringTemplate.isValidVariableName` - letters, digits and
     * underscore only. Our config keys are kebab-case (`max-attempts`) because that is the
     * convention every other Bukkit YAML follows, so the hyphen is translated here rather than
     * making the config files ugly to match a client-side rule.
     *
     * A handful of names are also reserved and get a prefix. Input values are written into the
     * very same NBT compound that Paper uses to carry the click callback's own bookkeeping, and
     * that bookkeeping lives under `id` - the callback UUID, stored as an int array. An input
     * literally named `id` therefore replaces the UUID with the player's typed text, and the
     * click fails with `Failed to read field (id="..."): Not a list` before it ever reaches us.
     * The button simply does nothing, which is the worst kind of bug to be looking at.
     *
     * Creation and reading both go through this, so the two can never disagree.
     */
    fun inputKey(raw: String): String = Dialogs.inputKey(raw, RESERVED_PREFIX)

    fun textOf(view: DialogResponseView, key: String): String? = view.getText(inputKey(key))

    fun boolOf(view: DialogResponseView, key: String): Boolean? = view.getBoolean(inputKey(key))

    fun floatOf(view: DialogResponseView, key: String): Float? = view.getFloat(inputKey(key))

    fun input(spec: InputSpec): DialogInput = when (spec) {
        is InputSpec.Text -> DialogInput.text(inputKey(spec.key), Text.renderFlat(spec.label))
            .maxLength(spec.maxLength.coerceIn(1, 256))
            .width(DEFAULT_INPUT_WIDTH)
            .apply { spec.initial?.let { initial(it) } }
            .build()

        is InputSpec.Number -> DialogInput.numberRange(
            inputKey(spec.key),
            Text.renderFlat(spec.label),
            spec.min.toFloat(),
            spec.max.toFloat(),
        )
            .step(1.0f)
            .width(DEFAULT_INPUT_WIDTH)
            .apply { spec.initial?.let { initial(it.toFloat()) } }
            .build()

        is InputSpec.Choice -> DialogInput.singleOption(
            inputKey(spec.key),
            Text.renderFlat(spec.label),
            spec.options.mapIndexed { index, (id, display) ->
                SingleOptionDialogInput.OptionEntry.create(id, Text.renderFlat(display), index == 0)
            },
        )
            .width(DEFAULT_INPUT_WIDTH)
            .build()
    }

    /**
     * A list of actions, optionally with inputs above them.
     *
     * An empty button list is not a usable multi-action dialog - the server rejects it outright -
     * so it degrades to a notice instead. A screen with nothing to press is almost always a
     * mistake, but throwing here would surface as a scheduler exception one tick after the player
     * clicked something, which tells them nothing and leaves them on a dead screen.
     */
    fun menu(
        title: String,
        lines: List<String> = emptyList(),
        buttons: List<ActionButton>,
        inputs: List<DialogInput> = emptyList(),
        columns: Int = 2,
        exit: ActionButton? = null,
        canEscape: Boolean = true,
    ): Dialog {
        if (buttons.isEmpty()) {
            ng.logger.warning("버튼이 없는 다이얼로그를 안내창으로 대체했습니다: " + Text.plain(title))
            return notice(title, lines, exit ?: closeButton(), canEscape)
        }
        return Dialog.create { factory ->
            factory.empty()
                .base(baseOf(title, lines, inputs, canEscape))
                .type(
                    DialogType.multiAction(buttons)
                        .columns(columns.coerceIn(1, 4))
                        .apply { if (exit != null) exitAction(exit) }
                        .build()
                )
        }
    }

    /** A read-only screen with one dismiss button. */
    fun notice(
        title: String,
        lines: List<String>,
        dismiss: ActionButton? = null,
        canEscape: Boolean = true,
    ): Dialog = Dialog.create { factory ->
        factory.empty()
            .base(baseOf(title, lines, emptyList(), canEscape))
            .type(if (dismiss == null) DialogType.notice() else DialogType.notice(dismiss))
    }

    /** Yes / no. [onYes] runs on the main thread. */
    fun confirm(
        title: String,
        lines: List<String>,
        yesLabel: String = "<green>확인</green>",
        noLabel: String = "<red>취소</red>",
        onNo: ((Player) -> Unit)? = null,
        onYes: (Player) -> Unit,
    ): Dialog = Dialog.create { factory ->
        factory.empty()
            .base(baseOf(title, lines, emptyList(), canEscape = true))
            .type(
                DialogType.confirmation(
                    button(yesLabel) { player, _ -> onYes(player) },
                    button(noLabel) { player, _ -> onNo?.invoke(player) ?: back(player) },
                )
            )
    }

    private fun baseOf(
        title: String,
        lines: List<String>,
        inputs: List<DialogInput>,
        canEscape: Boolean,
    ): DialogBase = DialogBase.builder(Text.renderFlat(title))
        .canCloseWithEscape(canEscape)
        // Never pause: this is a multiplayer server, and a paused dialog on a single-player
        // world would stop the world ticking under a game that is itself on a timer.
        .pause(false)
        .afterAction(DialogBase.DialogAfterAction.CLOSE)
        .body(body(lines))
        .inputs(inputs)
        .build()

    // --- showing ---------------------------------------------------------------

    fun show(player: Player, dialog: Dialog) {
        onMain {
            if (!player.isOnline) return@onMain
            if (!supportsDialogs(player)) {
                // Sending it anyway would do literally nothing: an older client drops the packet
                // and the player is left staring at an unchanged screen with no idea why.
                ng.messages.send(player, "dialog-unsupported")
                return@onMain
            }
            player.showDialog(dialog)
        }
    }

    /**
     * Whether this client can render a dialog at all.
     *
     * Dialogs arrived in 1.21.6. A server running ViaVersion happily accepts older clients, and
     * for them every screen in this plugin is silently invisible. ViaVersion is the only thing
     * that knows a connection's real protocol version, so it is asked reflectively when present;
     * without it every client is assumed capable, which is correct for a server that is not
     * bridging old versions in the first place.
     */
    fun supportsDialogs(player: Player): Boolean {
        val api = viaApi ?: return true
        return runCatching {
            val version = api.javaClass.getMethod("getPlayerVersion", java.util.UUID::class.java)
                .invoke(api, player.uniqueId) as? Int ?: return true
            version >= DIALOG_PROTOCOL
        }.getOrDefault(true)
    }

    /** ViaVersion's API instance, or null when it is not installed. Resolved once. */
    private val viaApi: Any? by lazy {
        if (!PluginClasses.isEnabled("ViaVersion")) return@lazy null
        runCatching {
            PluginClasses.require("ViaVersion", "com.viaversion.viaversion.api.Via")
                .getMethod("getAPI")
                .invoke(null)
        }.onFailure {
            ng.logger.warning("ViaVersion 연동 실패 - 구버전 클라이언트 판별을 건너뜁니다: " + it.message)
        }.getOrNull()
    }

    /**
     * Replaces the screen a player is looking at.
     *
     * The one-tick delay matters: the client is still processing the close that its own click
     * triggered, and a dialog sent inside that window is discarded.
     */
    fun reshow(player: Player, supplier: () -> Dialog?) {
        Bukkit.getScheduler().runTaskLater(ng.plugin, Runnable {
            if (!player.isOnline) return@Runnable
            supplier()?.let { player.showDialog(it) }
        }, 1L)
    }

    fun close(player: Player) {
        onMain { if (player.isOnline) player.closeDialog() }
    }

    // --- navigation ------------------------------------------------------------

    /**
     * Opens a screen and remembers how to rebuild the one it came from.
     *
     * Suppliers rather than dialog instances, because going back to a leaderboard should show
     * the leaderboard as it is now, not as it was three screens ago.
     */
    fun push(player: Player, previous: (() -> Dialog?)?, next: Dialog) {
        if (previous != null) {
            backStacks.computeIfAbsent(player.uniqueId) { ArrayDeque() }.push(previous)
        }
        show(player, next)
    }

    fun back(player: Player) {
        val stack = backStacks[player.uniqueId]
        val previous = stack?.poll()
        if (previous == null) {
            close(player)
            return
        }
        reshow(player) { previous() }
    }

    fun clearStack(playerId: UUID) {
        backStacks.remove(playerId)
    }

    fun onMain(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block()
        else Bukkit.getScheduler().runTask(ng.plugin, Runnable { block() })
    }

    companion object {
        /**
         * Input names that would collide with Paper's own click-callback payload.
         *
         * `id` holds the callback UUID. Anything we write under the same name destroys it.
         */
        val RESERVED_INPUT_KEYS: Set<String> = Dialogs.RESERVED_INPUT_KEYS

        const val RESERVED_PREFIX = "ng_"

        /** Protocol version of 1.21.6, the release that introduced dialogs. */
        const val DIALOG_PROTOCOL = 771

        const val DEFAULT_BUTTON_WIDTH = 150
        const val DEFAULT_BODY_WIDTH = 320
        const val DEFAULT_INPUT_WIDTH = 240
    }
}
