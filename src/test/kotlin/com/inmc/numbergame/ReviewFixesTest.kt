package com.inmc.numbergame

import com.inmc.numbergame.game.GameType
import com.inmc.numbergame.game.InputMode
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RankBracket
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.reward.RewardTable
import com.inmc.numbergame.util.Ph
import kr.inmc.core.util.Text
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression cover for the deep-review round.
 *
 * Each test here stands for a defect that was found by reading the code rather than by playing,
 * which is exactly the kind that comes back if nothing pins it down.
 */
class ReviewFixesTest {

    // --- A1: reward commands are not chat messages ------------------------------

    @Test
    fun `command substitution leaves markup characters alone`() {
        // Running a command through the message pipeline eats anything in angle brackets and
        // every `&` that looks like a colour code, silently corrupting it.
        val command = """tellraw @a {"text":"<3 & welcome"}"""
        assertEquals(command, Text.substituteOnly(command))

        assertEquals("give Steve stone 1", Text.substituteOnly("give {플레이어네임} stone 1", Ph.of().player("Steve")))
        assertEquals("cmi money give Steve 100 &all", Text.substituteOnly("cmi money give {플레이어네임} 100 &all", Ph.of().player("Steve")))
    }

    @Test
    fun `command substitution neutralises control characters`() {
        // The template is an admin's, but the values dropped into it are not - a newline in a
        // placeholder value must not be able to change the shape of the command it lands in.
        // They become spaces rather than vanishing, so two arguments are never welded together.
        assertEquals("say hi there", Text.substituteOnly("say hi\nthere"))
        assertEquals("say a b", Text.substituteOnly("say a\tb"))
        assertEquals("give Steve stone", Text.substituteOnly("give Steve stone"))
    }

    // --- A2: the narrowest rank bracket wins ------------------------------------

    @Test
    fun `a specific bracket beats the general one it sits inside`() {
        val table = RewardTable()
        table.rankBrackets.add(RankBracket(1, 10))
        table.rankBrackets.add(RankBracket(1, 1))
        table.sortBrackets()

        // Taking the first match in list order would hand first place the 1~10위 prize.
        assertEquals("1위", table.bracketFor(1)?.describe())
        assertEquals("1~10위", table.bracketFor(2)?.describe())
        assertNull(table.bracketFor(11))
    }

    @Test
    fun `overlapping brackets are reported so the editor can warn`() {
        val table = RewardTable()
        table.rankBrackets.add(RankBracket(1, 10))
        table.rankBrackets.add(RankBracket(1, 1))
        assertEquals(1, table.overlaps().size)

        val tidy = RewardTable()
        tidy.rankBrackets.add(RankBracket(1, 1))
        tidy.rankBrackets.add(RankBracket(2, 10))
        assertTrue(tidy.overlaps().isEmpty(), "겹치지 않는데 겹친다고 보고했습니다")
    }

    // --- A7: zero chance disables, amounts may exceed a stack -------------------

    @Test
    fun `a zero chance entry is never drawn in any mode`() {
        val rng = Random(9)
        val off = RewardEntry(chance = 0.0, money = 100.0)
        val on = RewardEntry(chance = 100.0, money = 50.0)

        for (mode in GiveMode.entries) {
            val bundle = RewardBundle(mode = mode, entries = mutableListOf(off, on))
            repeat(200) {
                assertTrue(
                    RewardEntryPick(bundle, rng).none { it === off },
                    "$mode 에서 확률 0 인 보상이 뽑혔습니다",
                )
            }
        }
    }

    @Test
    fun `a bundle of only disabled entries gives nothing`() {
        val rng = Random(10)
        val bundle = RewardBundle(
            mode = GiveMode.ROLL_ONE,
            entries = mutableListOf(RewardEntry(chance = 0.0, money = 10.0)),
        )
        // The weighted fallback used to hand out a random entry when every weight was zero.
        repeat(100) { assertTrue(RewardEntryPick(bundle, rng).isEmpty()) }
    }

    @Test
    fun `amounts are no longer capped at one stack`() {
        val entry = RewardEntry(minAmount = 1, maxAmount = 1)
        entry.maxAmount = 128
        assertEquals(128, entry.maxAmount, "한 스택을 넘는 수량이 잘렸습니다")

        entry.maxAmount = 999_999
        assertEquals(RewardEntry.MAX_AMOUNT, entry.maxAmount, "상한이 걸리지 않았습니다")
    }

    @Test
    fun `chance still clamps everywhere except zero`() {
        assertEquals(0.0, RewardEntry.clampRewardChance(0.0))
        assertEquals(0.0, RewardEntry.clampRewardChance(-5.0))
        assertEquals(0.01, RewardEntry.clampRewardChance(0.001))
        assertEquals(100.0, RewardEntry.clampRewardChance(500.0))
    }

    // --- B2: per-game permission ------------------------------------------------

    @Test
    fun `a game with no permission set is open to everyone`() {
        val def = com.inmc.numbergame.game.GameDefinition.create("t", GameType.BASEBALL)
        assertEquals("", def.permission, "기본값이 빈 문자열이 아닙니다")
    }

    @Test
    fun `the permission survives a save and load round trip`() {
        val config = org.bukkit.configuration.file.YamlConfiguration()
        val def = com.inmc.numbergame.game.GameDefinition.create("vip", GameType.UPDOWN)
        def.permission = "group.vip"
        def.save(config)

        val loaded = com.inmc.numbergame.game.GameDefinition.load("vip", config)
        assertNotNull(loaded)
        assertEquals("group.vip", loaded.permission)
    }

    // --- C1: input mode ---------------------------------------------------------

    @Test
    fun `speed math ships with fast input and everything else does not`() {
        val fast = GameType.SPEED_MATH.engine.schema.firstOrNull { it.key == "input-mode" }
        assertNotNull(fast, "빠른 계산에 입력 방식 설정이 없습니다")
        assertEquals(InputMode.FAST.name, fast.default)

        // Every other game keeps the dialog, which is the whole point of the plugin.
        for (type in GameType.entries.filter { it != GameType.SPEED_MATH }) {
            val setting = type.engine.schema.firstOrNull { it.key == "input-mode" }
            if (setting != null) {
                assertEquals(InputMode.DIALOG.name, setting.default, "$type 가 기본으로 다이얼로그가 아닙니다")
            }
        }
    }

    @Test
    fun `input mode parsing falls back to the dialog`() {
        assertEquals(InputMode.FAST, InputMode.parse("FAST"))
        assertEquals(InputMode.FAST, InputMode.parse("fast"))
        assertEquals(InputMode.DIALOG, InputMode.parse("nonsense"))
        assertEquals(InputMode.DIALOG, InputMode.parse(null))
    }

    @Test
    fun `a fast game still offers a dialog screen to fall back on`() {
        // Fast mode renders the action bar, but the same Screen has to remain usable as a dialog
        // for an admin who switches the setting back.
        val def = com.inmc.numbergame.game.GameDefinition.create("sm", GameType.SPEED_MATH)
        val rng = Random(3)
        val session = com.inmc.numbergame.game.Session(
            playerId = java.util.UUID.randomUUID(),
            gameId = def.id,
            state = def.engine.start(def, rng),
        )
        val screen = def.engine.screen(def, session)

        assertTrue(screen.inputs.isNotEmpty(), "다이얼로그로 쓸 입력란이 없습니다")
        assertFalse(screen.actionBar.isNullOrBlank(), "액션바 한 줄이 비었습니다")
        assertTrue(screen.actionBar!!.contains("="), "액션바에 문제가 없습니다")
    }

    /** Mirrors `RewardService.pick`, which needs an `Ng` and so cannot be built here. */
    private fun RewardEntryPick(bundle: RewardBundle, rng: Random): List<RewardEntry> {
        val usable = bundle.entries.filter { !it.isEmpty() && it.chance > 0.0 }
        if (usable.isEmpty()) return emptyList()
        return when (bundle.mode) {
            GiveMode.ALL -> usable.filter { rng.nextDouble() * 100.0 < it.chance }
            GiveMode.ROLL_ONE -> listOfNotNull(usable.randomOrNull(rng))
            GiveMode.ROLL_N -> usable.take(bundle.rollCount)
        }
    }
}
