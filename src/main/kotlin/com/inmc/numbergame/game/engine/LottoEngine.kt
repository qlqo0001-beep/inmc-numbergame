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
import kotlin.random.Random

/**
 * A server-wide lottery: buy a ticket, wait for the scheduled draw.
 *
 * The prize table deliberately reuses the rank brackets that season rewards already use, by
 * mapping match count onto a placing - all six matched is 1위, five is 2위, and so on. That
 * means the admin edits lotto prizes in exactly the same screen as everything else and there is
 * no second reward editor to keep in step.
 *
 * Nothing about this game runs through [Session]; [com.inmc.numbergame.event.EventService] owns
 * the round. The session methods here exist only to satisfy the interface and are inert.
 */
object LottoEngine : GameEngine {

    val POOL_SIZE = IntSetting(
        key = "pool-size",
        label = "번호 범위",
        help = "1 부터 이 숫자까지 중에서 고릅니다.",
        default = 45, min = 5, max = 99, slider = false,
    )

    val PICK_COUNT = IntSetting(
        key = "pick-count",
        label = "고를 개수",
        help = "티켓 하나에 고르는 번호 개수입니다.",
        default = 6, min = 1, max = 10, suffix = "개",
    )

    val MIN_MATCH = IntSetting(
        key = "min-match",
        label = "최소 당첨 개수",
        help = "이 개수 이상 맞아야 등수에 듭니다.",
        default = 3, min = 1, max = 10, suffix = "개",
    )

    val DRAW_INTERVAL = DurationSetting(
        key = "draw-interval",
        label = "추첨 주기",
        help = "이 주기마다 자동으로 추첨합니다. 예: 1d, 12h",
        default = 86400L, min = 60L, max = 30L * 86400L,
    )

    val TICKET_PRICE = DoubleSetting(
        key = "ticket-price",
        label = "티켓 가격",
        help = "0 이면 무료입니다. Vault 가 없으면 무료로 취급됩니다.",
        default = 1000.0, min = 0.0, max = 1_000_000_000.0, suffix = "원",
    )

    val SCORE_PER_MATCH = IntSetting(
        key = "score-per-match",
        label = "일치 번호당 점수",
        help = "누적 점수 랭킹에 더해집니다. 최소 당첨 개수를 넘긴 경우에만 적립됩니다.",
        default = 20, min = 0, max = 10000, slider = false, suffix = "점",
    )

    override val schema: List<Setting<*>> = listOf(
        POOL_SIZE, PICK_COUNT, MIN_MATCH, DRAW_INTERVAL, TICKET_PRICE, SCORE_PER_MATCH,
    )

    // --- pure logic, exercised directly by the tests ---------------------------

    /** [count] distinct numbers from 1..[pool], ascending. */
    fun draw(pool: Int, count: Int, rng: Random): List<Int> {
        val size = pool.coerceAtLeast(1)
        val take = count.coerceIn(1, size)
        val numbers = (1..size).toMutableList()
        for (i in numbers.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = numbers[i]
            numbers[i] = numbers[j]
            numbers[j] = tmp
        }
        return numbers.take(take).sorted()
    }

    fun matches(ticket: List<Int>, result: List<Int>): Int {
        val winning = result.toHashSet()
        return ticket.count { winning.contains(it) }
    }

    /**
     * Placing for a match count, or null when the ticket did not win anything.
     *
     * Matching everything is 1위 and each miss drops one place, which is how lotteries are
     * described everywhere and lets the existing 순위 보상 editor serve as the prize table.
     */
    fun rankOf(matched: Int, pickCount: Int, minMatch: Int): Int? {
        if (matched < minMatch.coerceAtLeast(1)) return null
        if (matched > pickCount) return null
        return pickCount - matched + 1
    }

    /** Validates a typed ticket. Returns the sorted numbers, or an error message. */
    fun parseTicket(raw: String?, pool: Int, count: Int): Result<List<Int>> {
        val parts = raw.orEmpty().split(',', ' ', '/', '-')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size != count) {
            return Result.failure(IllegalArgumentException(count.toString() + "개의 번호를 입력해주세요."))
        }
        val numbers = mutableListOf<Int>()
        for (part in parts) {
            val value = part.toIntOrNull()
                ?: return Result.failure(IllegalArgumentException("'" + part + "' 은(는) 숫자가 아닙니다."))
            if (value < 1 || value > pool) {
                return Result.failure(IllegalArgumentException("번호는 1 ~ " + pool + " 사이여야 합니다."))
            }
            if (numbers.contains(value)) {
                return Result.failure(IllegalArgumentException(value.toString() + " 이(가) 중복됐습니다."))
            }
            numbers.add(value)
        }
        return Result.success(numbers.sorted())
    }

    fun format(numbers: List<Int>): String = numbers.joinToString(", ")

    // --- inert session hooks ---------------------------------------------------

    private object Inert : GameState {
        override fun save(section: ConfigurationSection) = Unit
    }

    override fun start(def: GameDefinition, rng: Random): GameState = Inert

    override fun loadState(section: ConfigurationSection): GameState? = null

    override fun screen(def: GameDefinition, session: Session): Screen =
        Screen(def.displayName, listOf("<gray>이 게임은 추첨 이벤트로 진행됩니다.</gray>"))

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult = StepResult.Rejected("이 게임은 추첨 이벤트로 진행됩니다.")

    override fun onTimeout(def: GameDefinition, session: Session): StepResult =
        StepResult.Failed(Outcome(0L, 0L, 0L, emptyList()), "이벤트 게임")
}
