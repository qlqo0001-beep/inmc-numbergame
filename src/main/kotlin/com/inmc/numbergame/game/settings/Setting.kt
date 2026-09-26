package com.inmc.numbergame.game.settings

import kr.inmc.core.util.Durations
import kr.inmc.core.util.Numbers

/**
 * One tunable knob on a game.
 *
 * The point of this type is that the admin dialog never knows what game it is editing. Each
 * game declares a list of settings; [com.inmc.numbergame.dialog.admin.GameSettingsDialog]
 * turns any such list into a dialog form and writes the answers straight back. Adding a new
 * game type therefore costs zero new screens - which is what makes ten of them affordable.
 *
 * [parse] is deliberately forgiving: values arrive from YAML an admin may have hand-edited and
 * from dialog text fields, so anything unreadable falls back to [default] rather than throwing.
 */
sealed interface Setting<T : Any> {
    val key: String
    val label: String
    val help: String
    val default: T

    /** Coerces a raw YAML/dialog value into this setting's type. Never throws. */
    fun parse(raw: Any?): T

    /** Human readable current value, used in dialog bodies and lore. */
    fun display(value: Any?): String = parse(value).toString()
}

class IntSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: Int,
    val min: Int,
    val max: Int,
    /** Rendered as a slider when the span is small enough to be usable as one. */
    val slider: Boolean = (max - min) in 1..100,
    val suffix: String = "",
) : Setting<Int> {

    override fun parse(raw: Any?): Int {
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().replace(",", "").toDoubleOrNull()?.toInt()
            else -> null
        } ?: default
        return value.coerceIn(min, max)
    }

    override fun display(value: Any?): String = "${parse(value)}$suffix"
}

class DoubleSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: Double,
    val min: Double,
    val max: Double,
    val suffix: String = "",
) : Setting<Double> {

    override fun parse(raw: Any?): Double {
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().replace(",", "").toDoubleOrNull()
            else -> null
        } ?: default
        return Numbers.round2(value.coerceIn(min, max))
    }

    override fun display(value: Any?): String = Numbers.chance(parse(value)) + suffix
}

class BoolSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: Boolean,
) : Setting<Boolean> {

    override fun parse(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is String -> raw.trim().lowercase() in TRUE_WORDS
        is Number -> raw.toInt() != 0
        else -> default
    }

    override fun display(value: Any?): String = if (parse(value)) "켜짐" else "꺼짐"

    private companion object {
        val TRUE_WORDS = setOf("true", "yes", "on", "1", "켜짐", "켬", "예")
    }
}

class TextSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: String,
    val maxLength: Int = 64,
    val allowEmpty: Boolean = true,
) : Setting<String> {

    override fun parse(raw: Any?): String {
        val text = (raw as? String)?.trim() ?: raw?.toString()?.trim() ?: return default
        if (text.isEmpty() && !allowEmpty) return default
        return text.take(maxLength)
    }
}

/** Stored as whole seconds; edited as `30s` / `5m` / `1d` text. */
class DurationSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: Long,
    val min: Long = 0L,
    val max: Long = 365L * 86400L,
) : Setting<Long> {

    override fun parse(raw: Any?): Long {
        val seconds = when (raw) {
            is Number -> raw.toLong()
            is String -> Durations.parse(raw, default)
            else -> default
        }
        return seconds.coerceIn(min, max)
    }

    override fun display(value: Any?): String {
        val seconds = parse(value)
        return if (seconds <= 0L) "없음" else Durations.formatShort(seconds)
    }
}

/**
 * A fixed set of choices, stored as the option id.
 *
 * String-backed rather than generic over a Kotlin enum so YAML stays readable and the dialog's
 * `singleOption` input maps across without a conversion table.
 */
class ChoiceSetting(
    override val key: String,
    override val label: String,
    override val help: String,
    override val default: String,
    /** id to display label, in the order they should appear. */
    val options: List<Pair<String, String>>,
) : Setting<String> {

    override fun parse(raw: Any?): String {
        val text = raw?.toString()?.trim() ?: return default
        return options.firstOrNull { it.first.equals(text, ignoreCase = true) }?.first ?: default
    }

    override fun display(value: Any?): String {
        val id = parse(value)
        return options.firstOrNull { it.first == id }?.second ?: id
    }
}
