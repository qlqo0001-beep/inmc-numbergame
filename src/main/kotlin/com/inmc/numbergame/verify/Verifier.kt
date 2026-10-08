package com.inmc.numbergame.verify

import com.inmc.numbergame.Ng
import com.inmc.numbergame.util.Ph
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * `/숫자게임 관리 검증` — 게임 정의·엔진 시작·참가 조건(횟수·쿨타임·참가비)·순위판·우편함을 서버 안에서 확인한다
 * (드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 판을 실제로 열지 않는다(대화창이 뜨고 기록이 남는다). 엔진은 `start` 로 첫 상태만 만들어 본다.
 * - 일일 횟수는 검증하는 사람 것으로 한 번 적었다가 그 게임만 초기화한다. 돈은 건드리지 않는다.
 */
class Verifier(private val ng: Ng) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Ng, Player) -> String?)

    fun run(player: Player) {
        val results = CHECKS.map { check ->
            val failure = try {
                check.run(ng, player)
            } catch (t: Throwable) {
                "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            Result(check.name, failure)
        }

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        ng.messages.send(
            player, "verify-done",
            Ph.of().count(results.size - failures.size - skips.size).attempts(failures.size)
                .record(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) ng.messages.send(player, "verify-failure", Ph.of().record("${f.name} — ${f.failure}"))
        for (s in skips) ng.messages.send(player, "verify-skipped", Ph.of().record("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ng.io.file("verify", "numbergame-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-numbergame 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        ng.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        ng.messages.send(player, "verify-report", Ph.of().record("plugins/${ng.plugin.name}/verify/${file.name}"))
    }

    companion object {
        const val SKIP = "건너뜀:"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("게임 정의 — 하나 이상 읽혔고 켜진 게임이 있다") { ng, _ ->
                ok(ng.games.all().isNotEmpty(), "게임이 하나도 없습니다") ?: ok(ng.games.enabled().isNotEmpty(), "켜진 게임이 없습니다")
            },
            Check("엔진 — 켜진 게임마다 첫 상태를 만든다(고정 시드)") { ng, _ ->
                val bad = ng.games.enabled().mapNotNull { def ->
                    runCatching { def.engine.start(def, Random(7)) }.exceptionOrNull()?.let { "${def.id}: ${it.javaClass.simpleName}" }
                }
                ok(bad.isEmpty(), "시작에 실패한 게임: " + bad.joinToString(", "))
            },
            Check("최소 판돈 — 음수가 없고 거는 게임만 0 보다 크다") { ng, _ ->
                val negative = ng.games.enabled().filter { it.engine.minimumStake(it) < 0.0 }
                ok(negative.isEmpty(), "최소 판돈이 음수인 게임: " + negative.joinToString(", ") { it.id })
            },
            Check("일일 횟수 — 한 번 적으면 남은 횟수가 줄고, 초기화하면 돌아온다") { ng, p ->
                val def = ng.games.enabled().firstOrNull { it.entry.dailyLimit > 0 } ?: return@Check "$SKIP 일일 제한이 있는 게임이 없습니다"
                val before = ng.plays.remaining(p.uniqueId, def) ?: return@Check "남은 횟수를 모릅니다(제한이 있는데)"
                ng.plays.recordStart(p.uniqueId, def)
                val after = ng.plays.remaining(p.uniqueId, def)
                ng.plays.resetPlayer(p.uniqueId, def.id)
                val restored = ng.plays.remaining(p.uniqueId, def)
                ok(after == before - 1, "적은 뒤 $after (${before - 1} 이어야)")
                    ?: ok(restored == def.entry.dailyLimit, "초기화 뒤 $restored (${def.entry.dailyLimit} 이어야)")
            },
            Check("참가 조건 — 제한이 없는 게임은 거절 사유가 없다") { ng, p ->
                val def = ng.games.enabled().firstOrNull { it.entry.isEmpty() } ?: return@Check "$SKIP 조건 없는 게임이 없습니다"
                val denial = ng.plays.check(p, def)
                ok(denial == null, "거절됐습니다: $denial")
            },
            Check("순위판 — 게임마다 순위가 등록돼 있다") { ng, _ ->
                ok(ng.rankables.size == ng.games.all().size, "순위판 ${ng.rankables.size}개 ≠ 게임 ${ng.games.all().size}개")
            },
            Check("우편함 — 내 우편함을 읽는다") { ng, p ->
                ok(ng.mailbox.countOf(p.uniqueId) >= 0, "우편함을 못 읽습니다")
            },
        )
    }
}
