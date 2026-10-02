package com.inmc.numbergame.game

import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.rank.Outcome
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * The contract every minigame implements.
 *
 * Engines are deliberately kept free of Paper's dialog API: they describe what they want shown
 * as a [Screen] and receive answers as a [SubmitInput], and the dialog layer does the
 * translation. That keeps all ten of them plain testable logic - a JUnit test can play a whole
 * game of number baseball without a server running - and means a future non-dialog frontend
 * would not touch a single engine.
 *
 * Engines hold **no per-player state**. Everything mutable lives in [Session]; the engine
 * objects are singletons.
 */
interface GameEngine {

    /** The tunable knobs this game exposes in the admin dialog. */
    val schema: List<Setting<*>>

    /** Rolls a fresh puzzle. The answer lives only in the returned state - never in a packet. */
    fun start(def: GameDefinition, rng: Random): GameState

    /** What the player should see right now. */
    fun screen(def: GameDefinition, session: Session): Screen

    /** Applies one answer. Implementations may mutate [session] (attempts, history, state). */
    fun submit(def: GameDefinition, session: Session, input: SubmitInput, rng: Random): StepResult

    /** Called by the ticker when a session runs out of time. */
    fun onTimeout(def: GameDefinition, session: Session): StepResult

    /** Restores engine state from `data/sessions.yml`. Null discards the session. */
    fun loadState(section: ConfigurationSection): GameState?

    /**
     * Last chance to fix up a session that has just come back from disk.
     *
     * Runs after the clock has been re-anchored, so an engine can adjust a deadline it owns.
     * Most games need nothing here; the ones that do are those whose current screen depended on
     * something the player can no longer see.
     */
    fun onRestore(def: GameDefinition, session: Session) = Unit

    /** Whole-session time budget in seconds; 0 means untimed. */
    fun timeLimitSeconds(def: GameDefinition): Long = 0L

    /** False when the game cannot run right now (e.g. betting with no economy installed). */
    fun available(def: GameDefinition): Boolean = true

    /**
     * The least a player must be able to stake for one round, for games that bet inside the session (betting,
     * blackjack); 0 for the rest. Checked before the session starts - starting counts against the daily
     * allowance, and a player who cannot cover a single bet would spend a play on a game they cannot play.
     */
    fun minimumStake(def: GameDefinition): Double = 0.0
}

/** Engine-specific puzzle state. Must survive a restart, so it serialises itself. */
interface GameState {
    fun save(section: ConfigurationSection)
}

/** What the player is shown for one turn. */
data class Screen(
    val title: String,
    val body: List<String>,
    /** Fields to fill in. Most games ask for one; a bet asks for type, pick and stake. */
    val inputs: List<InputSpec> = emptyList(),
    /** Extra action buttons, e.g. NIM's 1/2/3 or blackjack's 히트/스탠드. */
    val buttons: List<ChoiceButton> = emptyList(),
    val submitLabel: String = "제출",
    val canGiveUp: Boolean = true,
    /**
     * Advance on a timer rather than waiting for a click, in milliseconds; 0 waits.
     *
     * This is what makes a memory game a memory game: the digits show for a fixed moment and
     * then the screen moves on by itself. It is a single delayed task per turn, not a repeating
     * one, so the plugin still has exactly one recurring job.
     */
    val autoAdvanceMillis: Long = 0L,
    /** Button id delivered when [autoAdvanceMillis] elapses. */
    val autoAdvanceButton: String = "auto",
    /**
     * One-line form of this screen, for [com.inmc.numbergame.game.InputMode.FAST].
     *
     * The action bar has room for a question and a running score and nothing else, so an engine
     * that supports fast input says here what the essential line is. Null falls back to the
     * first non-blank body line, which is serviceable but rarely as tight.
     */
    val actionBar: String? = null,
)

/** The one input field a turn may ask for. */
sealed interface InputSpec {
    val key: String
    val label: String

    data class Number(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
        val initial: Int? = null,
    ) : InputSpec

    data class Text(
        override val key: String,
        override val label: String,
        val maxLength: Int,
        val initial: String? = null,
    ) : InputSpec

    data class Choice(
        override val key: String,
        override val label: String,
        /** id to display label. */
        val options: List<Pair<String, String>>,
    ) : InputSpec
}

data class ChoiceButton(val id: String, val label: String, val tooltip: String? = null)

/**
 * One turn's answers, keyed by [InputSpec.key].
 *
 * A map rather than named fields because the number of questions per turn is the engine's
 * business, not the framework's: baseball asks for one string, a bet asks for three things at
 * once, and 31 asks for nothing but a button.
 */
data class SubmitInput(
    val values: Map<String, String> = emptyMap(),
    /** Id of the [ChoiceButton] pressed, when the turn was driven by a button. */
    val button: String? = null,
) {

    fun text(key: String): String? = values[key]?.takeIf { it.isNotBlank() }?.trim()

    fun int(key: String): Int? = text(key)?.replace(",", "")?.toIntOrNull()

    fun long(key: String): Long? = text(key)?.replace(",", "")?.toLongOrNull()

    fun double(key: String): Double? = text(key)?.replace(",", "")?.toDoubleOrNull()

    /** The only answer, for the common single-question turn. */
    fun single(): String? = values.values.firstOrNull { it.isNotBlank() }?.trim()

    companion object {
        fun of(key: String, value: String?): SubmitInput =
            SubmitInput(values = if (value == null) emptyMap() else mapOf(key to value))
    }
}


sealed interface StepResult {

    /** The input could not be used. The attempt is **not** counted and nothing is charged. */
    data class Rejected(val reason: String) : StepResult

    /** Turn accepted, game continues. */
    data class Continue(val note: String? = null) : StepResult

    data class Cleared(val outcome: Outcome) : StepResult

    data class Failed(val outcome: Outcome, val reason: String) : StepResult
}
