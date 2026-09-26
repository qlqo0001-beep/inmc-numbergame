package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.DoubleSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID
import kotlin.random.Random

/**
 * Lowest unique number wins.
 *
 * Everyone secretly picks a number; the winner is whoever chose the smallest number nobody else
 * chose. It is the only game in the set where the right answer depends on what other people
 * did - picking 1 is obviously best and obviously what everyone else is thinking - which is why
 * it works as a server-wide event and not as a solo round.
 *
 * Like the lottery, the round is owned by [com.inmc.numbergame.event.EventService] and the
 * session hooks below are inert.
 */
object UniqueBidEngine : GameEngine {

    val RANGE_MAX = IntSetting(
        key = "range-max",
        label = "고를 수 있는 최대 숫자",
        help = "1 부터 이 숫자까지 고를 수 있습니다.",
        default = 100, min = 5, max = 10000, slider = false,
    )

    val DRAW_INTERVAL = DurationSetting(
        key = "draw-interval",
        label = "정산 주기",
        help = "이 주기마다 결과를 발표합니다. 예: 1d, 6h",
        default = 86400L, min = 60L, max = 30L * 86400L,
    )

    val ENTRY_PRICE = DoubleSetting(
        key = "entry-price",
        label = "참가비",
        help = "0 이면 무료입니다. Vault 가 없으면 무료로 취급됩니다.",
        default = 500.0, min = 0.0, max = 1_000_000_000.0, suffix = "원",
    )

    val MIN_PLAYERS = IntSetting(
        key = "min-players",
        label = "성립 최소 인원",
        help = "참가자가 이보다 적으면 그 회차는 무효가 되고 참가비를 돌려줍니다.",
        default = 3, min = 1, max = 100, suffix = "명",
    )

    val SCORE_FOR_WIN = IntSetting(
        key = "score-for-win",
        label = "우승 점수",
        help = "누적 점수 랭킹에 더해집니다. 2위 이하는 순위에 따라 줄어듭니다.",
        default = 100, min = 0, max = 100000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        RANGE_MAX, DRAW_INTERVAL, ENTRY_PRICE, MIN_PLAYERS, SCORE_FOR_WIN,
    )

    // --- pure logic, exercised directly by the tests ---------------------------

    /**
     * Places every entrant whose number nobody else picked, smallest first.
     *
     * Duplicated numbers do not place at all - that is the whole game - so the returned list is
     * usually much shorter than the entry list, and can legitimately be empty when every single
     * number was picked by at least two people.
     */
    fun rank(entries: Map<UUID, Int>): List<UUID> {
        val counts = HashMap<Int, Int>()
        for (value in entries.values) counts[value] = (counts[value] ?: 0) + 1
        return entries.entries
            .filter { counts[it.value] == 1 }
            .sortedBy { it.value }
            .map { it.key }
    }

    /** The winning number, or null when nobody picked a unique one. */
    fun winningNumber(entries: Map<UUID, Int>): Int? {
        val counts = HashMap<Int, Int>()
        for (value in entries.values) counts[value] = (counts[value] ?: 0) + 1
        return counts.entries.filter { it.value == 1 }.minOfOrNull { it.key }
    }

    /** Points for a placing: the winner takes [SCORE_FOR_WIN], each place below halves it. */
    fun scoreFor(rank: Int, top: Int): Long {
        if (rank < 1) return 0L
        var value = top.toLong()
        repeat(rank - 1) { value /= 2 }
        return value
    }

    fun parseBid(raw: String?, max: Int): Result<Int> {
        val value = raw?.trim()?.replace(",", "")?.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("숫자를 입력해주세요."))
        if (value < 1 || value > max) {
            return Result.failure(IllegalArgumentException("1 ~ " + max + " 사이의 숫자를 입력해주세요."))
        }
        return Result.success(value)
    }

    // --- inert session hooks ---------------------------------------------------

    private object Inert : GameState {
        override fun save(section: ConfigurationSection) = Unit
    }

    override fun start(def: GameDefinition, rng: Random): GameState = Inert

    override fun loadState(section: ConfigurationSection): GameState? = null

    override fun screen(def: GameDefinition, session: Session): Screen =
        Screen(def.displayName, listOf("<gray>이 게임은 서버 이벤트로 진행됩니다.</gray>"))

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult = StepResult.Rejected("이 게임은 서버 이벤트로 진행됩니다.")

    override fun onTimeout(def: GameDefinition, session: Session): StepResult =
        StepResult.Failed(Outcome(0L, 0L, 0L, emptyList()), "이벤트 게임")
}
