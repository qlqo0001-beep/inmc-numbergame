package com.inmc.numbergame.game.settings

import org.bukkit.configuration.ConfigurationSection

/**
 * The values behind a game's [Setting] schema.
 *
 * Nothing is validated on the way in - [Setting.parse] runs on every read instead, so a
 * hand-edited YAML with `digits: "네개"` degrades to the default rather than refusing to load
 * the whole game. Unknown keys are preserved on save so downgrading the plugin does not
 * silently delete settings a newer version wrote.
 */
class GameSettings(private val values: MutableMap<String, Any> = LinkedHashMap()) {

    operator fun <T : Any> get(setting: Setting<T>): T = setting.parse(values[setting.key])

    operator fun <T : Any> set(setting: Setting<T>, value: T) {
        values[setting.key] = value
    }

    /** Writes a raw value, used by the dialog layer which holds settings only as `Setting<*>`. */
    fun putRaw(key: String, value: Any) {
        values[key] = value
    }

    fun rawOrNull(key: String): Any? = values[key]

    fun displayOf(setting: Setting<*>): String = setting.display(values[setting.key])

    fun copyOf(): GameSettings = GameSettings(LinkedHashMap(values))

    /** Fills in any schema key that has no stored value yet. */
    fun applyDefaults(schema: List<Setting<*>>) {
        for (setting in schema) values.putIfAbsent(setting.key, setting.default)
    }

    fun save(section: ConfigurationSection) {
        for ((key, value) in values) section.set(key, value)
    }

    companion object {
        fun load(section: ConfigurationSection?): GameSettings {
            val values = LinkedHashMap<String, Any>()
            if (section != null) {
                for (key in section.getKeys(false)) {
                    section.get(key)?.let { values[key] = it }
                }
            }
            return GameSettings(values)
        }
    }
}
