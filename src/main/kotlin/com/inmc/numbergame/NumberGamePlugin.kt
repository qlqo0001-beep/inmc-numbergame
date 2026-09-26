package com.inmc.numbergame

import com.inmc.numbergame.command.NgCommand
import com.inmc.numbergame.listener.ChatInputListener
import com.inmc.numbergame.listener.IntegrationListener
import kr.inmc.core.listener.MenuListener
import com.inmc.numbergame.listener.PlayerSessionListener
import com.inmc.numbergame.scheduler.Ticker
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * INMC 숫자 미니게임.
 *
 * A collection of number games - baseball, up-and-down and friends - played entirely through
 * Paper's dialog API, with per-game rewards, leaderboards, seasons and entry limits. No plugin
 * dependency is required: Vault, PlaceholderAPI, MMOItems and the custom-item plugins are all
 * optional and the plugin boots with none of them installed.
 */
class NumberGamePlugin : JavaPlugin() {

    lateinit var ng: Ng
        private set

    private lateinit var ticker: Ticker

    override fun onEnable() {
        ng = Ng(this)
        ticker = Ticker(ng)

        // Commands register through the lifecycle manager, which must be called from onEnable.
        NgCommand(ng).register(this)
        // 커스텀아이템에 "참가 아이템" 역할을 내놓는다(core ItemRoles).
        for (role in com.inmc.numbergame.play.NgRoles.roles(ng)) kr.inmc.core.integration.ItemRoles.register(role)
        kr.inmc.core.integration.ItemRoles.listen(com.inmc.numbergame.play.NgRoles.OWNER) { role ->
            if (role == null || role == com.inmc.numbergame.play.NgRoles.FEE) com.inmc.numbergame.play.NgRoles.sync(ng)
        }

        Bukkit.getPluginManager().let { pm ->
            pm.registerEvents(MenuListener(ng), this)
            pm.registerEvents(PlayerSessionListener(ng), this)
            pm.registerEvents(IntegrationListener(ng), this)
            pm.registerEvents(ChatInputListener(ng), this)
        }

        // Config, games and persisted state all load off the main thread; the ticker and every
        // listener no-op until `ng.ready` flips.
        ng.enable { logger.info("inmc-numbergame 활성화 완료") }
        ticker.start()
    }

    override fun onDisable() {
        if (!::ng.isInitialized) return
        kr.inmc.core.integration.ItemRoles.unregisterAll(com.inmc.numbergame.play.NgRoles.OWNER)
        ticker.stop()
        ng.shutdown()
        logger.info("inmc-numbergame 비활성화")
    }
}
