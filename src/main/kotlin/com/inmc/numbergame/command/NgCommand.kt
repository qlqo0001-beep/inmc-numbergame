package com.inmc.numbergame.command

import com.inmc.numbergame.Ng
import com.inmc.numbergame.Permissions
import com.inmc.numbergame.util.Ph
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/숫자게임`, registered through Paper's Brigadier API so tab completion comes for free.
 *
 * Almost everything is reachable from the dialogs; these subcommands exist for the cases a
 * dialog cannot cover - a console operator resetting allowances, and a command block or a sign
 * pointing straight at one game.
 */
class NgCommand(private val ng: Ng) {

    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                tree().build(),
                "INMC 숫자 미니게임",
                listOf("ng", "numbergame", "미니게임"),
            )
        }
    }

    // --- suggestions -----------------------------------------------------------

    private val gameIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        ng.games.ids()
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val onlineNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        Bukkit.getOnlinePlayers()
            .map { it.name }
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    // --- tree ------------------------------------------------------------------

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("숫자게임")
            .executes { ctx -> root(ctx.source.sender) }

            .then(
                Commands.literal("랭킹")
                    .executes { ctx -> ranking(ctx.source.sender, null) }
                    .then(
                        Commands.argument("game", StringArgumentType.word()).suggests(gameIds)
                            .executes { ctx -> ranking(ctx.source.sender, ctx.arg("game")) }
                    )
            )
            .then(Commands.literal("우편함").executes { ctx -> mailbox(ctx.source.sender) })
            .then(Commands.literal("도움말").executes { ctx -> help(ctx.source.sender) })

            .then(
                Commands.literal("관리").requires(::isAdmin)
                    .executes { ctx -> admin(ctx.source.sender) }
                    // 서버 안 자동 검증(2026-10-08) — 정의·엔진 시작·최소 판돈·일일 횟수·참가 조건·순위판·우편함.
                    .then(
                        Commands.literal("검증").executes { ctx ->
                            (ctx.source.sender as? org.bukkit.entity.Player)?.let { com.inmc.numbergame.verify.Verifier(ng).run(it) }
                            Command.SINGLE_SUCCESS
                        },
                    )
                    .then(
                        Commands.literal("초기화")
                            .then(
                                Commands.literal("플레이어")
                                    .then(
                                        Commands.argument("name", StringArgumentType.word()).suggests(onlineNames)
                                            .executes { ctx ->
                                                resetPlayer(ctx.source.sender, ctx.arg("name"), null)
                                            }
                                            .then(
                                                Commands.argument("game", StringArgumentType.word()).suggests(gameIds)
                                                    .executes { ctx ->
                                                        resetPlayer(
                                                            ctx.source.sender,
                                                            ctx.arg("name"),
                                                            ctx.arg("game"),
                                                        )
                                                    }
                                            )
                                    )
                            )
                            .then(
                                Commands.literal("전체")
                                    .executes { ctx -> resetAll(ctx.source.sender, null) }
                                    .then(
                                        Commands.argument("game", StringArgumentType.word()).suggests(gameIds)
                                            .executes { ctx -> resetAll(ctx.source.sender, ctx.arg("game")) }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("시즌종료")
                            .then(
                                Commands.argument("game", StringArgumentType.word()).suggests(gameIds)
                                    .executes { ctx -> closeSeason(ctx.source.sender, ctx.arg("game")) }
                            )
                    )
            )
            .then(Commands.literal("리로드").requires(::isAdmin).executes { ctx -> reload(ctx.source.sender) })

            // Last, so a game id never shadows a subcommand name.
            .then(
                Commands.argument("game", StringArgumentType.word()).suggests(gameIds)
                    .executes { ctx -> play(ctx.source.sender, ctx.arg("game")) }
            )

    // --- handlers --------------------------------------------------------------

    private fun root(sender: CommandSender): Int {
        val player = requirePlayer(sender) ?: return 0
        if (!ready(player)) return 0
        ng.screens.showMain(player)
        return Command.SINGLE_SUCCESS
    }

    private fun play(sender: CommandSender, id: String): Int {
        val player = requirePlayer(sender) ?: return 0
        if (!ready(player)) return 0
        val def = ng.games.get(id)
        if (def == null) {
            ng.messages.send(player, "unknown-game", Ph.of().game(id))
            // The id is also where a mistyped subcommand lands, so point at the real ones.
            ng.messages.send(player, "usage")
            return 0
        }
        ng.gameService.open(player, def)
        return Command.SINGLE_SUCCESS
    }

    private fun ranking(sender: CommandSender, id: String?): Int {
        val player = requirePlayer(sender) ?: return 0
        if (!ready(player)) return 0
        if (id == null) {
            ng.screens.showRankingList(player)
            return Command.SINGLE_SUCCESS
        }
        val def = ng.games.get(id)
        if (def == null) {
            ng.messages.send(player, "unknown-game", Ph.of().game(id))
            return 0
        }
        ng.dlg.push(player, { ng.screens.mainDialog(player) }, ng.screens.rankingDialog(player, def, false))
        return Command.SINGLE_SUCCESS
    }

    private fun help(sender: CommandSender): Int {
        val player = sender as? Player
        if (player == null) {
            // Console gets the one-line form; the full screen is a dialog.
            ng.messages.send(sender, "usage")
            return Command.SINGLE_SUCCESS
        }
        if (!ready(player)) return 0
        ng.screens.showHelp(player)
        return Command.SINGLE_SUCCESS
    }

    private fun mailbox(sender: CommandSender): Int {
        val player = requirePlayer(sender) ?: return 0
        if (!ready(player)) return 0
        ng.screens.showMailbox(player)
        return Command.SINGLE_SUCCESS
    }

    private fun admin(sender: CommandSender): Int {
        val player = requirePlayer(sender) ?: return 0
        if (!ready(player)) return 0
        ng.admin.showRoot(player)
        return Command.SINGLE_SUCCESS
    }

    /** Console-friendly: this one does not need a player, which is the point of having it. */
    private fun resetPlayer(sender: CommandSender, name: String, gameId: String?): Int {
        val target = Bukkit.getPlayerExact(name) ?: Bukkit.getOfflinePlayerIfCached(name)
        if (target == null) {
            ng.messages.sendRaw(sender, "<red>'" + name + "' 플레이어를 찾을 수 없습니다.</red>")
            return 0
        }
        if (gameId != null && !ng.games.exists(gameId)) {
            ng.messages.send(sender, "unknown-game", Ph.of().game(gameId))
            return 0
        }
        ng.plays.resetPlayer(target.uniqueId, gameId)
        ng.messages.send(sender, "admin-plays-reset-player", Ph.of().player(target.name ?: name))
        return Command.SINGLE_SUCCESS
    }

    private fun resetAll(sender: CommandSender, gameId: String?): Int {
        if (gameId != null && !ng.games.exists(gameId)) {
            ng.messages.send(sender, "unknown-game", Ph.of().game(gameId))
            return 0
        }
        val touched = ng.plays.resetAll(gameId)
        ng.messages.send(sender, "admin-plays-reset-all", Ph.of().count(touched))
        return Command.SINGLE_SUCCESS
    }

    private fun closeSeason(sender: CommandSender, gameId: String): Int {
        val def = ng.games.get(gameId)
        if (def == null) {
            ng.messages.send(sender, "unknown-game", Ph.of().game(gameId))
            return 0
        }
        val result = ng.ranks.closeSeason(def, automatic = false)
        ng.messages.send(
            sender, "rank-reset-done",
            Ph.of().game(def.displayName).season(result.season),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun reload(sender: CommandSender): Int {
        ng.messages.send(sender, "reloading")
        ng.reload { count -> ng.messages.send(sender, "reloaded", Ph.of().count(count)) }
        return Command.SINGLE_SUCCESS
    }

    // --- helpers ---------------------------------------------------------------

    private fun isAdmin(source: CommandSourceStack): Boolean =
        source.sender.hasPermission(Permissions.ADMIN)

    private fun requirePlayer(sender: CommandSender): Player? {
        val player = sender as? Player
        if (player == null) ng.messages.send(sender, "player-only")
        return player
    }

    private fun ready(player: Player): Boolean {
        if (ng.ready) return true
        ng.messages.send(player, "not-ready")
        return false
    }

    private fun CommandContext<CommandSourceStack>.arg(name: String): String =
        StringArgumentType.getString(this, name)

}
