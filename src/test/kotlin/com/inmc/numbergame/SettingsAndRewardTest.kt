package com.inmc.numbergame

import com.inmc.numbergame.game.GameType
import com.inmc.numbergame.game.settings.BoolSetting
import com.inmc.numbergame.game.settings.ChoiceSetting
import com.inmc.numbergame.game.settings.DurationSetting
import com.inmc.numbergame.game.settings.GameSettings
import com.inmc.numbergame.game.settings.IntSetting
import kr.inmc.core.util.Durations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The settings layer.
 *
 * Values arrive from YAML an admin may have hand-edited and from free-text dialog boxes, so the
 * contract is that nothing ever throws - a value that cannot be read falls back to its default.
 * These tests are the guarantee that a typo in a config file degrades instead of breaking a game.
 */
class SettingsAndRewardTest {

    @Test
    fun `int settings clamp and survive nonsense`() {
        val digits = IntSetting("digits", "자릿수", "", default = 3, min = 2, max = 8)

        assertEquals(4, digits.parse(4))
        assertEquals(4, digits.parse("4"))
        assertEquals(8, digits.parse(99), "상한을 넘겼는데 잘리지 않았습니다")
        assertEquals(2, digits.parse(-5), "하한 아래인데 올라오지 않았습니다")
        assertEquals(3, digits.parse("네개"), "숫자가 아닌 값이 기본값으로 떨어지지 않았습니다")
        assertEquals(3, digits.parse(null))
    }

    @Test
    fun `bool settings accept the spellings people actually type`() {
        val flag = BoolSetting("flag", "켜기", "", default = false)

        assertTrue(flag.parse(true))
        assertTrue(flag.parse("true"))
        assertTrue(flag.parse("yes"))
        assertTrue(flag.parse("켜짐"))
        assertEquals(false, flag.parse("아무말"))
        assertEquals("켜짐", flag.display(true))
    }

    @Test
    fun `duration settings read the config syntax`() {
        val limit = DurationSetting("time-limit", "제한 시간", "", default = 300L, min = 0L, max = 3600L)

        assertEquals(300L, limit.parse("5m"))
        assertEquals(90L, limit.parse("1m 30s"))
        assertEquals(3600L, limit.parse("10h"), "상한을 넘겼는데 잘리지 않았습니다")
        assertEquals(0L, limit.parse("0"))
        assertEquals("없음", limit.display(0L))
        assertEquals(300L, limit.parse("한참"))
    }

    @Test
    fun `choice settings reject options that are not on the list`() {
        val difficulty = ChoiceSetting(
            "difficulty", "난이도", "", default = "NORMAL",
            options = listOf("EASY" to "쉬움", "NORMAL" to "보통", "HARD" to "어려움"),
        )

        assertEquals("HARD", difficulty.parse("HARD"))
        assertEquals("HARD", difficulty.parse("hard"))
        assertEquals("NORMAL", difficulty.parse("IMPOSSIBLE"))
        assertEquals("어려움", difficulty.display("HARD"))
    }

    @Test
    fun `defaults fill in only what is missing`() {
        val a = IntSetting("a", "A", "", default = 1, min = 0, max = 10)
        val b = IntSetting("b", "B", "", default = 2, min = 0, max = 10)

        val settings = GameSettings()
        settings[a] = 7
        settings.applyDefaults(listOf(a, b))

        assertEquals(7, settings[a], "이미 있는 값이 기본값으로 덮였습니다")
        assertEquals(2, settings[b])
    }

    @Test
    fun `unknown keys survive a load and save round trip`() {
        // A newer version's setting must not be deleted by an older one that does not know it.
        val settings = GameSettings()
        settings.putRaw("some-future-setting", 42)
        val copy = settings.copyOf()
        assertEquals(42, copy.rawOrNull("some-future-setting"))
    }

    // --- schema sanity across every game --------------------------------------

    @Test
    fun `every game type declares a usable schema`() {
        for (type in GameType.entries) {
            val schema = type.engine.schema
            assertTrue(schema.isNotEmpty(), "$type 에 설정이 하나도 없습니다")

            val keys = schema.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "$type 에 중복된 설정 키가 있습니다: $keys")

            for (setting in schema) {
                assertTrue(setting.label.isNotBlank(), "$type 의 ${setting.key} 에 이름이 없습니다")
                assertTrue(setting.help.isNotBlank(), "$type 의 ${setting.key} 에 설명이 없습니다")
                assertTrue(
                    setting.key.matches(Regex("[a-z0-9-]+")),
                    "$type 의 설정 키가 YAML 규약(kebab-case)에 맞지 않습니다: ${setting.key}",
                )
                // Parsing the declared default must give the default back, or the admin screen
                // would show something different from what the game actually runs with.
                assertEquals(
                    setting.default, setting.parse(setting.default),
                    "$type 의 ${setting.key} 기본값이 파싱을 통과하지 못합니다",
                )
            }
        }
    }

    @Test
    fun `every game type is fully described`() {
        for (type in GameType.entries) {
            assertTrue(type.display.isNotBlank(), "$type 에 표시 이름이 없습니다")
            assertTrue(type.summary.isNotBlank(), "$type 에 설명이 없습니다")
            assertTrue(type.recordUnit.isNotBlank(), "$type 에 기록 단위가 없습니다")
            assertNotNull(GameType.parse(type.name), "$type 을 이름으로 되찾을 수 없습니다")
        }
    }

    /**
     * Every setting whose help promises something must have code that keeps the promise.
     *
     * The lotto and unique-bid prices both say "Vault 가 없으면 무료로 취급됩니다" while the
     * code refused entry outright when no economy was registered - so on a server without Vault
     * those two games appeared in the list and turned everyone away forever. This pins the
     * wording so the claim cannot drift back out of step with the behaviour unnoticed.
     */
    @Test
    fun `games priced in money say they are free without an economy`() {
        val priced = listOf(
            GameType.LOTTO to "ticket-price",
            GameType.UNIQUE_BID to "entry-price",
        )
        for ((type, key) in priced) {
            val setting = type.engine.schema.firstOrNull { it.key == key }
            assertNotNull(setting, "$type 에 $key 설정이 없습니다")
            assertTrue(
                setting.help.contains("무료"),
                "$type 의 $key 도움말이 Vault 없을 때 동작을 설명하지 않습니다: ${setting.help}",
            )
        }
    }

    @Test
    fun `only the betting types are gated on having an economy`() {
        // Everything else has to remain playable without Vault; the betting games are hidden
        // by GameRegistry.runnable instead of failing at the point of a stake.
        val gated = GameType.entries.filter { it.needsEconomy }.map { it.name }.sorted()
        assertEquals(listOf("BETTING", "BLACKJACK"), gated)
    }

    @Test
    fun `duration formatting round-trips`() {
        for (seconds in listOf(0L, 1L, 59L, 60L, 3600L, 86400L, 90061L)) {
            assertEquals(seconds, Durations.parse(Durations.format(seconds), -1L))
        }
    }
}
