package com.inmc.numbergame.config

import kr.inmc.core.util.Durations
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `config.yml`, read once into an immutable snapshot.
 *
 * Anything that belongs to a single game lives in `games/<id>.yml` instead - this file only
 * holds the server-wide knobs that no game should be able to disagree about.
 */
class PluginConfig(
    /** Recent-play entries kept in `data/stats.yml`. */
    val logSize: Int,
    /** Closed seasons kept per game in `data/seasons.yml`. */
    override val seasonArchiveLimit: Int,
    /** A session with no input for this long is abandoned. 0 disables the sweep. */
    val sessionIdleSeconds: Long,
    /** Minimum gap between two submissions, in milliseconds. Blunts macro spam. */
    val submitCooldownMillis: Long,
    /** Unclaimed mailbox entries kept per player before the oldest are dropped. */
    override val mailboxLimit: Int,
    /** Age at which an unclaimed mailbox entry is discarded. 0 keeps them forever. */
    override val mailboxExpireSeconds: Long,
    /**
     * How often the ticker writes changed state to disk.
     *
     * Serialising is main-thread work, so doing it every second is a real cost on a busy server.
     * Shutdown and reload always flush regardless, and nothing that matters for fairness rides
     * on this - only accumulated counters, never a settlement.
     */
    val saveIntervalSeconds: Long,
    /** Announce reward entries flagged `announce` to the whole server. */
    override val broadcastRewards: Boolean,
    /** Drop rewards on the floor when the inventory is full; otherwise they go to the mailbox. */
    override val dropWhenInventoryFull: Boolean,
    /** Rows shown per page in list dialogs. */
    val listPageSize: Int,
) : kr.inmc.core.reward.RewardSettings {

    companion object {

        fun from(config: YamlConfiguration): PluginConfig = PluginConfig(
            logSize = config.getInt("stats.log-size", 100).coerceIn(0, 5000),
            seasonArchiveLimit = config.getInt("ranking.season-archive-limit", 12).coerceIn(0, 200),
            sessionIdleSeconds = Durations.parse(config.getString("session.idle-timeout"), 600L)
                .coerceIn(0L, 86400L),
            submitCooldownMillis = config.getLong("session.submit-cooldown-ms", 250L)
                .coerceIn(0L, 10_000L),
            mailboxLimit = config.getInt("mailbox.max-entries", 100).coerceIn(1, 2000),
            mailboxExpireSeconds = Durations.parse(config.getString("mailbox.expire-after"), 90L * 86400L)
                .coerceIn(0L, 3650L * 86400L),
            saveIntervalSeconds = Durations.parse(config.getString("storage.save-interval"), 30L)
                .coerceIn(1L, 3600L),
            broadcastRewards = config.getBoolean("rewards.broadcast", true),
            dropWhenInventoryFull = config.getBoolean("rewards.drop-when-full", false),
            listPageSize = config.getInt("dialog.list-page-size", 8).coerceIn(3, 20),
        )
    }
}
