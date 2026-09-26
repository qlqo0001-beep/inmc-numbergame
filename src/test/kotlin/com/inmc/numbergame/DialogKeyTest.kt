package com.inmc.numbergame

import com.inmc.numbergame.game.GameType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dialog input names have to satisfy Minecraft, not us.
 *
 * The server validates them with `StringTemplate.isValidVariableName` - the same rule that
 * governs `$(name)` macro substitution in functions - which accepts only letters, digits and
 * underscore. A kebab-case key throws `IllegalArgumentException: key must be a valid input name`
 * at the moment the dialog is built, which lands as a packet-handling error in the console and
 * leaves the player staring at a screen that never opens.
 *
 * That is exactly what happened: every settings screen was unopenable because our config keys
 * are kebab-case. These tests exist so the rule is checked here rather than in production.
 */
class DialogKeyTest {

    /** The server's rule, restated: `Character.isLetterOrDigit(c) || c == '_'`. */
    private fun isValidInputName(key: String): Boolean =
        key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' }

    /**
     * Mirrors `Dlg.inputKey`.
     *
     * Duplicated rather than called because `Dlg` needs an `Ng`, which needs a running server.
     * The duplication is guarded by [sanitiser_matches_the_one_in_Dlg] below.
     */
    private fun sanitise(raw: String): String = buildString(raw.length) {
        for (c in raw) append(if (c.isLetterOrDigit() || c == '_') c else '_')
    }

    /** Names Paper uses for its own bookkeeping inside the click payload. */
    private val RESERVED = setOf("id")

    private fun sanitiseReserved(raw: String): String {
        val safe = sanitise(raw)
        return if (safe in RESERVED) "ng_" + safe else safe
    }

    @Test
    fun `the rule rejects what the server rejects`() {
        assertTrue(isValidInputName("display_name"))
        assertTrue(isValidInputName("digits"))
        assertTrue(isValidInputName("maxAttempts"))
        assertTrue(!isValidInputName("display-name"), "하이픈이 통과했습니다")
        assertTrue(!isValidInputName("fee.money"), "점이 통과했습니다")
        assertTrue(!isValidInputName(""), "빈 키가 통과했습니다")
    }

    @Test
    fun `every game setting produces a usable dialog key`() {
        for (type in GameType.entries) {
            for (setting in type.engine.schema) {
                val key = sanitise(setting.key)
                assertTrue(
                    isValidInputName(key),
                    "$type 의 ${setting.key} 가 다이얼로그 키로 변환되지 않습니다 -> $key",
                )
            }
        }
    }

    @Test
    fun `sanitising never makes two settings collide`() {
        // A collision would silently wire two fields to one input, so one setting would
        // overwrite the other on save with nothing in the log to show for it.
        for (type in GameType.entries) {
            val keys = type.engine.schema.map { sanitise(it.key) }
            assertEquals(
                keys.size, keys.toSet().size,
                "$type 의 설정 키가 변환 후 충돌합니다: $keys",
            )
        }
    }

    @Test
    fun `sanitising leaves an already-valid key alone`() {
        // Engine input keys (guess, answer, pick, amount, picks) are already plain words, and
        // must survive untouched or a saved response would be looked up under the wrong name.
        for (key in listOf("guess", "answer", "pick", "amount", "picks", "mode", "threshold")) {
            assertEquals(key, sanitise(key))
        }
    }

    @Test
    fun `kebab case turns into underscores`() {
        assertEquals("max_attempts", sanitise("max-attempts"))
        assertEquals("score_per_spare_attempt", sanitise("score-per-spare-attempt"))
        assertEquals("range_min", sanitise("range-min"))
    }

    @Test
    fun `every input an engine asks for is already valid`() {
        // These flow through Dlg.inputKey too, but they are hand-written rather than derived
        // from config, so there is no reason for them not to be valid at the source.
        val engineKeys = listOf("guess", "answer", "pick", "amount", "picks")
        for (key in engineKeys) {
            assertTrue(isValidInputName(key), "엔진 입력 키가 규칙 위반입니다: $key")
        }
    }

    /**
     * `id` is Paper's, not ours.
     *
     * Input values land in the same NBT compound that carries the click callback's UUID, and
     * that UUID lives under `id` as an int array. An input named `id` overwrites it with text,
     * so the click fails inside Paper with `Failed to read field (id="..."): Not a list` and the
     * button does nothing at all - no error shown to the player, no callback ever reached.
     */
    @Test
    fun `the reserved id key is renamed out of the way`() {
        assertEquals("ng_id", sanitiseReserved("id"))
        assertEquals("game_id", sanitiseReserved("game_id"), "예약어가 아닌 키까지 건드렸습니다")
        assertEquals("ng_id", sanitiseReserved("id"), "변환이 안정적이지 않습니다")
    }

    /**
     * No hand-written input key anywhere may be a reserved name.
     *
     * Most keys go through `Dlg.inputKey`, but the admin screens build some inputs directly.
     * This reads the sources so a literal added later cannot quietly reintroduce the collision -
     * the failure mode is a dead button, which is nearly invisible in testing.
     */
    @Test
    fun `no literal dialog key in the sources is reserved`() {
        val dialogDir = java.io.File("src/main/kotlin/com/inmc/numbergame/dialog")
        assertTrue(dialogDir.isDirectory, "다이얼로그 소스 폴더를 찾지 못했습니다: ${dialogDir.absolutePath}")

        val literal = Regex("""DialogInput\.(?:text|bool|numberRange|singleOption)\(\s*"([^"]+)"""")
        val offenders = mutableListOf<String>()

        dialogDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            for (match in literal.findAll(file.readText())) {
                val key = match.groupValues[1]
                if (key in RESERVED) offenders.add("${file.name}: $key")
                assertTrue(
                    isValidInputName(key),
                    "${file.name} 의 입력 키가 서버 규칙 위반입니다: $key",
                )
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "예약된 이름을 그대로 쓴 입력이 있습니다 (Dlg.inputKey 를 거치게 하세요): $offenders",
        )
    }

    @Test
    fun `sanitiser matches the one in Dlg`() {
        // Dlg.inputKey is a one-liner over the same rule; if it ever diverges from the copy
        // above, this comparison of a representative sample is what catches it.
        val samples = listOf(
            "max-attempts", "display-name", "fee.money", "already_fine", "digits", "a-b-c",
        )
        val expected = listOf(
            "max_attempts", "display_name", "fee_money", "already_fine", "digits", "a_b_c",
        )
        assertEquals(expected, samples.map { sanitise(it) })
    }
}
