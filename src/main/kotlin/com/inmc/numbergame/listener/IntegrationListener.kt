package com.inmc.numbergame.listener

import com.inmc.numbergame.Ng
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent

/**
 * Re-probes the optional integrations when one of them comes or goes.
 *
 * `paper-plugin.yml` deliberately declares no load order, so MMOItems or Vault may well enable
 * after us. Without this, a server that happens to boot in that order would run all session with
 * item references it could have resolved.
 */
class IntegrationListener(private val ng: Ng) : Listener {

    @EventHandler
    fun onEnable(event: PluginEnableEvent) {
        if (event.plugin.name in WATCHED) ng.refreshIntegrations()
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (event.plugin.name in WATCHED) ng.refreshIntegrations()
    }

    /**
     * Picks up an economy that registers after our startup probe.
     *
     * Watching plugin names is not enough here: the Vault economy is registered by whoever
     * supplies it, and that plugin may not be one we know to watch. A real server showed this -
     * Vault present, CMI enabled, and no provider at all - so the safe signal is the service
     * registration itself rather than a guess at which plugin will perform it.
     *
     * The service is matched by class name so that nothing here touches a Vault class, which
     * would fail to link on a server that has no Vault at all.
     */
    @EventHandler
    fun onServiceRegister(event: ServiceRegisterEvent) {
        if (event.provider.service.name.endsWith(ECONOMY_SERVICE)) ng.refreshEconomy()
    }

    private companion object {
        const val ECONOMY_SERVICE = ".Economy"

        val WATCHED = setOf(
            "Vault", "PlaceholderAPI", "MMOItems", "MythicLib",
            "ItemsAdder", "Nexo", "Oraxen", "EcoItems",
        )
    }
}
