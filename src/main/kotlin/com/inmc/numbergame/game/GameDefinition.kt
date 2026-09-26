package com.inmc.numbergame.game

import com.inmc.numbergame.game.settings.GameSettings
import kr.inmc.core.rank.Better
import kr.inmc.core.rank.Rankable
import com.inmc.numbergame.play.EntryConfig
import kr.inmc.core.rank.RankConfig
import kr.inmc.core.reward.RewardTable
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * One configured game, backed by `games/<id>.yml`.
 *
 * Everything on here is editable in game: the dialog layer writes straight to these fields and
 * asks the registry to persist. [id] is the one exception - it is the file name, so renaming
 * means copying to a new definition.
 */
class GameDefinition(
    override val id: String,
    val type: GameType,
    override var displayName: String = type.display,
    var enabled: Boolean = true,
    var description: MutableList<String> = mutableListOf(),
    var icon: Material = type.icon,
    /** Position in the player-facing game list; lower comes first, ties break on [id]. */
    var order: Int = 100,
    /**
     * Permission required to see and play this game. Blank means everyone.
     *
     * Kept as free text rather than a generated node so an admin can point a game at a rank they
     * already have (`group.vip`, `essentials.kits.event`) instead of having to grant a new one.
     */
    var permission: String = "",
    val settings: GameSettings = GameSettings(),
    var entry: EntryConfig = EntryConfig(),
    override var ranking: RankConfig = RankConfig(mode = type.defaultRankMode),
    override var rewards: RewardTable = RewardTable(),
) : Rankable {

    // RankService 가 요구하는 두 가지. GameType 에 이미 있어 그대로 넘긴다.
    override val better: Better get() = type.better
    override val recordUnit: String get() = type.recordUnit

    val engine: GameEngine get() = type.engine

    /** Fills in any setting the schema knows about but the file did not carry. */
    fun normalise() {
        settings.applyDefaults(type.engine.schema)
        if (description.isEmpty()) description.add(type.summary)
    }

    fun summaryLines(): List<String> = buildList {
        addAll(description)
        add("")
        add("<gray>랭킹: <white>" + ranking.mode.display + "</white>  <dark_gray>|</dark_gray>  " +
            "초기화: <white>" + ranking.reset.describe() + "</white></gray>")
        addAll(entry.describe())
    }

    fun save(section: ConfigurationSection) {
        section.set("type", type.name)
        section.set("display-name", displayName)
        section.set("enabled", enabled)
        section.set("description", description)
        section.set("icon", icon.key().toString())
        section.set("order", order)
        section.set("permission", permission)
        settings.save(section.createSection("settings"))
        entry.save(section.createSection("entry"))
        ranking.save(section.createSection("ranking"))
        rewards.save(section.createSection("rewards"))
    }

    companion object {

        private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

        fun isValidId(raw: String?): Boolean = raw != null && ID_PATTERN.matches(raw)

        /** A brand new definition carrying nothing but its type's defaults. */
        fun create(id: String, type: GameType, displayName: String? = null): GameDefinition =
            GameDefinition(
                id = id,
                type = type,
                displayName = displayName?.takeIf { it.isNotBlank() } ?: type.display,
            ).also { it.normalise() }

        fun load(id: String, section: ConfigurationSection): GameDefinition? {
            val type = GameType.parse(section.getString("type")) ?: return null
            val icon = section.getString("icon")?.let { Material.matchMaterial(it) } ?: type.icon
            return GameDefinition(
                id = id,
                type = type,
                displayName = section.getString("display-name") ?: type.display,
                enabled = section.getBoolean("enabled", true),
                description = section.getStringList("description").toMutableList(),
                icon = icon,
                order = section.getInt("order", 100),
                permission = section.getString("permission").orEmpty(),
                settings = GameSettings.load(section.getConfigurationSection("settings")),
                entry = EntryConfig.load(section.getConfigurationSection("entry")),
                ranking = RankConfig.load(section.getConfigurationSection("ranking"), type.defaultRankMode),
                rewards = RewardTable.load(section.getConfigurationSection("rewards")),
            ).also { it.normalise() }
        }
    }
}
