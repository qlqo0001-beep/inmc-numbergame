package com.inmc.numbergame.game

import com.inmc.numbergame.game.engine.BaseballEngine
import com.inmc.numbergame.game.engine.BettingEngine
import com.inmc.numbergame.game.engine.BlackjackEngine
import com.inmc.numbergame.game.engine.LottoEngine
import com.inmc.numbergame.game.engine.MemoryEngine
import com.inmc.numbergame.game.engine.NimEngine
import com.inmc.numbergame.game.engine.SequenceEngine
import com.inmc.numbergame.game.engine.SpeedMathEngine
import com.inmc.numbergame.game.engine.UniqueBidEngine
import com.inmc.numbergame.game.engine.UpDownEngine
import kr.inmc.core.rank.Better
import kr.inmc.core.rank.RankMode
import org.bukkit.Material

/**
 * The catalogue of minigames.
 *
 * A type is a *kind* of game; a [GameDefinition] is one configured instance of it. That split
 * is what lets an admin run `baseball3`, `baseball4` and `baseball5` side by side, each with its
 * own rewards, leaderboard and season, without any of them being special-cased in code.
 *
 * [engine] is resolved lazily because the engine objects reference `GameType` right back, and
 * touching them from an enum constant's initialiser would run their static init half-built.
 */
enum class GameType(
    val display: String,
    val summary: String,
    val icon: Material,
    /** Direction of "better" for BEST_RECORD leaderboards. */
    val better: Better,
    val defaultRankMode: RankMode,
    /** Unit shown after a record value: "회", "점", "자리"... */
    val recordUnit: String,
    val kind: Kind,
    /** True when the game cannot run without Vault. */
    val needsEconomy: Boolean,
    private val engineRef: () -> GameEngine,
) {

    BASEBALL(
        display = "숫자야구",
        summary = "서로 다른 숫자를 맞히세요. 자리와 숫자가 모두 맞으면 스트라이크, 숫자만 맞으면 볼입니다.",
        icon = Material.SNOWBALL,
        better = Better.LOWER,
        defaultRankMode = RankMode.BEST_RECORD,
        recordUnit = "회",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { BaseballEngine },
    ),

    UPDOWN(
        display = "업다운",
        summary = "정해진 범위 안의 숫자를 맞히세요. 입력할 때마다 위인지 아래인지 알려줍니다.",
        icon = Material.COMPARATOR,
        better = Better.LOWER,
        defaultRankMode = RankMode.BEST_RECORD,
        recordUnit = "회",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { UpDownEngine },
    ),

    SPEED_MATH(
        display = "빠른 계산",
        summary = "제한 시간 안에 사칙연산 문제를 최대한 많이 푸세요.",
        icon = Material.CLOCK,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "문제",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { SpeedMathEngine },
    ),

    MEMORY(
        display = "숫자 기억",
        summary = "잠깐 보여준 숫자를 그대로 입력하세요. 단계마다 한 자리씩 길어집니다.",
        icon = Material.BOOK,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "단계",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { MemoryEngine },
    ),

    SEQUENCE(
        display = "수열 규칙 찾기",
        summary = "숫자들의 규칙을 찾아 다음에 올 수를 맞히세요.",
        icon = Material.KNOWLEDGE_BOOK,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "문제",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { SequenceEngine },
    ),

    NIM(
        display = "31 게임",
        summary = "서버와 번갈아 1~3을 더합니다. 31을 말하는 쪽이 집니다.",
        icon = Material.TARGET,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "승",
        kind = Kind.SESSION,
        needsEconomy = false,
        engineRef = { NimEngine },
    ),

    BETTING(
        display = "홀짝 베팅",
        summary = "홀짝·하이로우·주사위에 돈을 걸고 배당을 받습니다.",
        icon = Material.GOLD_INGOT,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "원",
        kind = Kind.SESSION,
        needsEconomy = true,
        engineRef = { BettingEngine },
    ),

    BLACKJACK(
        display = "블랙잭 21",
        summary = "21을 넘기지 않고 딜러보다 높은 수를 만드세요.",
        icon = Material.PAPER,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "원",
        kind = Kind.SESSION,
        needsEconomy = true,
        engineRef = { BlackjackEngine },
    ),

    LOTTO(
        display = "로또",
        summary = "번호를 골라두면 정해진 주기마다 자동으로 추첨합니다.",
        icon = Material.EMERALD,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "위",
        kind = Kind.EVENT,
        needsEconomy = false,
        engineRef = { LottoEngine },
    ),

    UNIQUE_BID(
        display = "최저 유일 숫자",
        summary = "아무도 고르지 않은 가장 작은 숫자를 고른 사람이 이깁니다.",
        icon = Material.AMETHYST_SHARD,
        better = Better.HIGHER,
        defaultRankMode = RankMode.CUMULATIVE_SCORE,
        recordUnit = "위",
        kind = Kind.EVENT,
        needsEconomy = false,
        engineRef = { UniqueBidEngine },
    );

    val engine: GameEngine by lazy(LazyThreadSafetyMode.PUBLICATION) { engineRef() }

    /** How a game is played out. */
    enum class Kind {
        /** One player, one private session, ends when they clear or fail. */
        SESSION,

        /** Server-wide round on a timer: everyone enters, then one draw settles it. */
        EVENT,
    }

    companion object {
        fun parse(raw: String?): GameType? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
