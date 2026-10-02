package com.inmc.numbergame.game.engine

import com.inmc.numbergame.game.ChoiceButton
import com.inmc.numbergame.game.GameDefinition
import com.inmc.numbergame.game.GameEngine
import com.inmc.numbergame.game.GameState
import com.inmc.numbergame.game.InputSpec
import kr.inmc.core.rank.Outcome
import com.inmc.numbergame.game.Screen
import com.inmc.numbergame.game.Session
import com.inmc.numbergame.game.StepResult
import com.inmc.numbergame.game.SubmitInput
import com.inmc.numbergame.game.settings.BoolSetting
import com.inmc.numbergame.game.settings.DoubleSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.IntSetting
import com.inmc.numbergame.game.settings.Setting
import kr.inmc.core.util.Numbers
import org.bukkit.configuration.ConfigurationSection
import kotlin.random.Random

/**
 * Blackjack against the house.
 *
 * A real 52-card deck is shuffled per round rather than drawing from an infinite one, because
 * "I drew four aces" is a support ticket and the honest deck costs nothing. Aces count 11 until
 * that would bust, which is the rule everybody actually knows.
 */
object BlackjackEngine : GameEngine {

    val MIN_BET = DoubleSetting(
        key = "min-bet",
        label = "최소 베팅액",
        help = "한 판에 걸 수 있는 최소 금액입니다.",
        default = 100.0, min = 1.0, max = 1_000_000_000.0, suffix = "원",
    )

    val MAX_BET = DoubleSetting(
        key = "max-bet",
        label = "최대 베팅액",
        help = "한 판에 걸 수 있는 최대 금액입니다.",
        default = 10_000.0, min = 1.0, max = 1_000_000_000.0, suffix = "원",
    )

    val BLACKJACK_PAYOUT = DoubleSetting(
        key = "blackjack-payout",
        label = "블랙잭 배당",
        help = "처음 두 장으로 21을 만들었을 때의 배당입니다. 1.5 가 표준입니다.",
        default = 1.5, min = 1.0, max = 5.0, suffix = "배",
    )

    val DEALER_STANDS_ON = IntSetting(
        key = "dealer-stands-on",
        label = "딜러가 멈추는 수",
        help = "딜러는 이 숫자 이상이 되면 더 뽑지 않습니다. 17 이 표준이며, 낮출수록 플레이어가 유리합니다.",
        default = 17, min = 12, max = 21,
    )

    val ALLOW_DOUBLE = BoolSetting(
        key = "allow-double",
        label = "더블다운 허용",
        help = "첫 선택에서 베팅을 두 배로 올리고 한 장만 더 받습니다.",
        default = true,
    )

    val ROUNDS = IntSetting(
        key = "rounds",
        label = "판 수",
        help = "한 번 시작하면 이만큼 하고 끝납니다.",
        default = 5, min = 1, max = 50, suffix = "판",
    )

    val TIME_LIMIT = DurationSetting(
        key = "time-limit",
        label = "제한 시간",
        help = "0 이면 시간 제한이 없습니다.",
        default = 600L, min = 0L, max = 3600L,
    )

    override val schema: List<Setting<*>> = listOf(
        MIN_BET, MAX_BET, BLACKJACK_PAYOUT, DEALER_STANDS_ON, ALLOW_DOUBLE, ROUNDS, TIME_LIMIT,
    )

    enum class Phase { BETTING, PLAYING }

    class State(
        var phase: Phase = Phase.BETTING,
        var round: Int = 1,
        var net: Double = 0.0,
        var wins: Int = 0,
        var bet: Double = 0.0,
        var lastBet: Double = 0.0,
        var deck: MutableList<Int> = mutableListOf(),
        var player: MutableList<Int> = mutableListOf(),
        var dealer: MutableList<Int> = mutableListOf(),
        /** Double-down is only offered before any card has been taken. */
        var acted: Boolean = false,
    ) : GameState {
        override fun save(section: ConfigurationSection) {
            section.set("phase", phase.name)
            section.set("round", round)
            section.set("net", net)
            section.set("wins", wins)
            section.set("bet", bet)
            section.set("last-bet", lastBet)
            section.set("deck", deck.toList())
            section.set("player", player.toList())
            section.set("dealer", dealer.toList())
            section.set("acted", acted)
        }
    }

    override fun start(def: GameDefinition, rng: Random): GameState = State()

    override fun loadState(section: ConfigurationSection): GameState? {
        if (!section.contains("round")) return null
        return State(
            phase = runCatching { Phase.valueOf(section.getString("phase") ?: "BETTING") }
                .getOrDefault(Phase.BETTING),
            round = section.getInt("round", 1),
            net = section.getDouble("net", 0.0),
            wins = section.getInt("wins", 0),
            bet = section.getDouble("bet", 0.0),
            lastBet = section.getDouble("last-bet", 0.0),
            deck = section.getIntegerList("deck").toMutableList(),
            player = section.getIntegerList("player").toMutableList(),
            dealer = section.getIntegerList("dealer").toMutableList(),
            acted = section.getBoolean("acted", false),
        )
    }

    override fun timeLimitSeconds(def: GameDefinition): Long = def.settings[TIME_LIMIT]

    override fun minimumStake(def: GameDefinition): Double = def.settings[MIN_BET]

    override fun screen(def: GameDefinition, session: Session): Screen {
        val state = session.state as? State ?: return Screen(def.displayName, listOf("준비 중..."))
        val body = mutableListOf<String>()

        body.add(
            "<gray>" + state.round + "판째 <dark_gray>/ " + def.settings[ROUNDS] + "판</dark_gray>" +
                "  <dark_gray>|</dark_gray>  손익: " + signed(state.net) + "</gray>"
        )
        body.add("<gray>소지금: <gold>" + Numbers.money(session.balance) + "원</gold></gray>")
        body.add("")

        if (state.phase == Phase.BETTING) {
            body.add(
                "<gray>베팅 한도: <white>" + Numbers.money(def.settings[MIN_BET]) + " ~ " +
                    Numbers.money(def.settings[MAX_BET]) + "원</white></gray>"
            )
            body.add("<dark_gray>딜러는 " + def.settings[DEALER_STANDS_ON] + " 이상이면 멈춥니다.</dark_gray>")
            if (session.history.isNotEmpty()) {
                body.add("")
                session.history.takeLast(4).forEach { body.add(it) }
            }
            return Screen(
                title = def.displayName,
                body = body,
                inputs = listOf(
                    InputSpec.Text(
                        key = "amount",
                        label = "베팅액",
                        maxLength = 12,
                        initial = openingStake(def, state).toLong().toString(),
                    )
                ),
                submitLabel = "베팅",
            )
        }

        body.add("<gray>딜러: <white>" + cards(state.dealer, hideSecond = true) + "</white></gray>")
        body.add("<gray>나: <white>" + cards(state.player) + "</white> <yellow>(" + score(state.player) + ")</yellow></gray>")
        body.add("")
        body.add("<gray>베팅: <gold>" + Numbers.money(state.bet) + "원</gold></gray>")

        val buttons = mutableListOf(
            ChoiceButton("hit", "히트", "한 장 더 받습니다."),
            ChoiceButton("stand", "스탠드", "여기서 멈추고 딜러 차례로 넘깁니다."),
        )
        if (def.settings[ALLOW_DOUBLE] && !state.acted && session.balance >= state.bet) {
            buttons.add(ChoiceButton("double", "더블다운", "베팅을 두 배로 올리고 한 장만 더 받습니다."))
        }

        return Screen(title = def.displayName, body = body, buttons = buttons)
    }

    private fun openingStake(def: GameDefinition, state: State): Double {
        val last = if (state.lastBet > 0.0) state.lastBet else def.settings[MIN_BET]
        return last.coerceIn(def.settings[MIN_BET], def.settings[MAX_BET])
    }

    override fun submit(
        def: GameDefinition,
        session: Session,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val state = session.state as? State ?: return StepResult.Rejected("게임 상태가 손상되었습니다.")

        if (state.phase == Phase.BETTING) return placeBet(def, session, state, input, rng)

        return when (input.button) {
            "hit" -> hit(def, session, state, rng)
            "stand" -> stand(def, session, state, rng)
            "double" -> double(def, session, state, rng)
            else -> StepResult.Rejected("히트·스탠드 중에서 골라주세요.")
        }
    }

    private fun placeBet(
        def: GameDefinition,
        session: Session,
        state: State,
        input: SubmitInput,
        rng: Random,
    ): StepResult {
        val amount = input.double("amount") ?: return StepResult.Rejected("베팅액을 숫자로 입력해주세요.")
        val min = def.settings[MIN_BET]
        val max = def.settings[MAX_BET]
        if (amount < min || amount > max) {
            return StepResult.Rejected(
                Numbers.money(min) + " ~ " + Numbers.money(max) + "원 사이로 걸어주세요."
            )
        }
        if (amount > session.balance) {
            return StepResult.Rejected("소지금이 부족합니다. (보유 " + Numbers.money(session.balance) + "원)")
        }

        session.touch()
        state.bet = amount
        state.lastBet = amount
        state.acted = false
        state.deck = freshDeck(rng)
        state.player = mutableListOf(draw(state, rng), draw(state, rng))
        state.dealer = mutableListOf(draw(state, rng), draw(state, rng))
        state.phase = Phase.PLAYING

        // A natural 21 settles immediately - there is nothing left to decide.
        if (score(state.player) == 21) return settle(def, session, state, playerStood = true, natural = true, rng = rng)

        return StepResult.Continue(null)
    }

    private fun hit(def: GameDefinition, session: Session, state: State, rng: Random): StepResult {
        state.acted = true
        session.touch()
        state.player.add(draw(state, rng))
        if (score(state.player) > 21) return settle(def, session, state, playerStood = false, natural = false, rng = rng)
        return StepResult.Continue(null)
    }

    private fun stand(def: GameDefinition, session: Session, state: State, rng: Random): StepResult {
        state.acted = true
        session.touch()
        return settle(def, session, state, playerStood = true, natural = false, rng = rng)
    }

    private fun double(def: GameDefinition, session: Session, state: State, rng: Random): StepResult {
        if (!def.settings[ALLOW_DOUBLE]) return StepResult.Rejected("이 게임에서는 더블다운을 쓸 수 없습니다.")
        if (state.acted) return StepResult.Rejected("첫 선택에서만 더블다운을 할 수 있습니다.")
        if (session.balance < state.bet) return StepResult.Rejected("소지금이 부족해 더블다운을 할 수 없습니다.")

        state.acted = true
        session.touch()
        state.bet *= 2.0
        state.player.add(draw(state, rng))
        // Double-down takes exactly one card, bust or not.
        return settle(def, session, state, playerStood = score(state.player) <= 21, natural = false, rng = rng)
    }

    /** Plays the dealer out, works out who won, books the money and moves to the next round. */
    private fun settle(
        def: GameDefinition,
        session: Session,
        state: State,
        playerStood: Boolean,
        natural: Boolean,
        rng: Random,
    ): StepResult {
        val playerScore = score(state.player)
        val standsOn = def.settings[DEALER_STANDS_ON]

        // The dealer only bothers drawing when the player is still alive.
        if (playerStood) {
            while (score(state.dealer) < standsOn) state.dealer.add(draw(state, rng))
        }
        val dealerScore = score(state.dealer)

        val delta: Double
        val verdict: String
        when {
            playerScore > 21 -> {
                delta = -state.bet
                verdict = "<red>버스트</red>"
            }

            natural && dealerScore != 21 -> {
                delta = state.bet * def.settings[BLACKJACK_PAYOUT]
                verdict = "<gold>블랙잭!</gold>"
            }

            dealerScore > 21 -> {
                delta = state.bet
                verdict = "<green>딜러 버스트</green>"
            }

            playerScore > dealerScore -> {
                delta = state.bet
                verdict = "<green>승리</green>"
            }

            playerScore < dealerScore -> {
                delta = -state.bet
                verdict = "<red>패배</red>"
            }

            else -> {
                delta = 0.0
                verdict = "<yellow>무승부</yellow>"
            }
        }

        if (delta > 0.0) state.wins++
        state.net += delta
        session.pendingMoney += delta
        // Mirrored onto the session so that abandoning mid-run reports the same numbers a
        // finished run would.
        session.score = Math.round(state.net)
        session.netMoney = state.net
        session.attempts++

        session.addHistory(
            "<dark_gray>" + state.round + ".</dark_gray> 나 <white>" + playerScore +
                "</white> <dark_gray>vs</dark_gray> 딜러 <white>" + dealerScore + "</white> " +
                verdict + " " + signed(delta),
            limit = 8,
        )

        val note = verdict + " <gray>(나 " + playerScore + " / 딜러 " + dealerScore + ")</gray> " + signed(delta)

        if (state.round >= def.settings[ROUNDS]) return finish(def, session, state)

        state.round++
        state.phase = Phase.BETTING
        state.player.clear()
        state.dealer.clear()
        return StepResult.Continue(note)
    }

    override fun onTimeout(def: GameDefinition, session: Session): StepResult {
        val state = session.state as? State
            ?: return StepResult.Failed(
                Outcome(0L, session.elapsedMillis(), 0L, listOf("<red>게임이 종료되었습니다.</red>")),
                "시간 초과",
            )
        // A hand still in progress is simply cancelled; the stake was never taken.
        return finish(def, session, state)
    }

    private fun finish(def: GameDefinition, session: Session, state: State): StepResult {
        val outcome = Outcome(
            record = Math.round(state.net),
            tiebreak = session.elapsedMillis(),
            score = Math.round(state.net),
            summary = listOf(
                if (state.net > 0.0) "<green>이득으로 마쳤습니다!</green>"
                else if (state.net == 0.0) "<yellow>본전입니다.</yellow>"
                else "<red>손해로 끝났습니다.</red>",
                "<gray>전적: <green>" + state.wins + "승</green> / " + state.round + "판</gray>",
                "<gray>손익: " + signed(state.net) + "</gray>",
            ),
            netMoney = state.net,
        )
        return if (state.net > 0.0) StepResult.Cleared(outcome)
        else StepResult.Failed(outcome, if (state.net == 0.0) "본전" else "손실")
    }

    // --- pure logic, exercised directly by the tests ---------------------------

    /** A shuffled 52-card deck as ranks 1..13, four of each. */
    fun freshDeck(rng: Random): MutableList<Int> {
        val deck = ArrayList<Int>(52)
        repeat(4) { for (rank in 1..13) deck.add(rank) }
        for (i in deck.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val tmp = deck[i]
            deck[i] = deck[j]
            deck[j] = tmp
        }
        return deck
    }

    private fun draw(state: State, rng: Random): Int {
        if (state.deck.isEmpty()) state.deck = freshDeck(rng)
        return state.deck.removeAt(state.deck.size - 1)
    }

    /**
     * Hand value with aces counted high where that helps.
     *
     * Every ace starts as 11 and is demoted to 1 one at a time while the hand is bust, which is
     * exactly the rule at a real table and avoids the classic bug where A-A-9 scores 31.
     */
    fun score(hand: List<Int>): Int {
        var total = 0
        var aces = 0
        for (rank in hand) {
            when {
                rank == 1 -> {
                    aces++
                    total += 11
                }

                rank >= 10 -> total += 10
                else -> total += rank
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10
            aces--
        }
        return total
    }

    fun cardName(rank: Int): String = when (rank) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> rank.toString()
    }

    private fun cards(hand: List<Int>, hideSecond: Boolean = false): String =
        hand.mapIndexed { index, rank ->
            if (hideSecond && index > 0) "?" else cardName(rank)
        }.joinToString(" ")

    private fun signed(amount: Double): String =
        if (amount >= 0.0) "<green>+" + Numbers.money(amount) + "원</green>"
        else "<red>-" + Numbers.money(-amount) + "원</red>"
}
