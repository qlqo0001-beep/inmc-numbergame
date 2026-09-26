package com.inmc.numbergame

import com.inmc.numbergame.play.PlayNameImport
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `plays.yml` 의 `names:` → core `profile` 임포트.
 *
 * 실서버 데이터로는 검증할 수 없어(이 트리에 `plays.yml` 이 없다) 합성 YAML 로만 지킨다.
 * 그래서 파싱을 Bukkit 없이 도는 순수 함수로 떼어놓았다.
 */
class PlayNameImportTest {

    private val steve = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val alex = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun yaml(build: YamlConfiguration.() -> Unit): YamlConfiguration =
        YamlConfiguration().apply(build).let {
            // 실제 저장 경로와 같아지도록 문자열을 거쳐 되읽는다.
            YamlConfiguration().apply { loadFromString(it.saveToString()) }
        }

    @Test
    fun `이름과 마지막 플레이 시각을 함께 뽑는다`() {
        val config = yaml {
            set("names.$steve", "Steve")
            set("records.$steve/baseball3.last-play", 1_700_000_000_000L)
            set("records.$steve/updown.last-play", 1_800_000_000_000L)
        }

        val entries = PlayNameImport.parse(config)

        assertEquals(1, entries.size)
        assertEquals(steve, entries[0].playerId)
        assertEquals("Steve", entries[0].name)
        assertEquals(1_800_000_000_000L, entries[0].seen, "여러 게임 중 가장 최근 플레이가 seen 이어야 한다")
    }

    @Test
    fun `기록이 없으면 seen 은 0 이다`() {
        // seen = 0 은 어떤 실제 기록에도 지므로, 이름만 있는 사람을 안전하게 넣을 수 있다.
        val config = yaml { set("names.$alex", "Alex") }

        val entries = PlayNameImport.parse(config)

        assertEquals(listOf(PlayNameImport.Entry(alex, "Alex", 0L)), entries)
    }

    @Test
    fun `UUID 가 아닌 키는 건너뛴다`() {
        // 손으로 고친 파일 하나 때문에 플러그인이 안 뜨는 일은 없어야 한다.
        val config = yaml {
            set("names.그냥이름", "Broken")
            set("names.$steve", "Steve")
        }

        val entries = PlayNameImport.parse(config)

        assertEquals(listOf(steve), entries.map { it.playerId })
    }

    @Test
    fun `게임 id 에 슬래시가 있어도 UUID 를 정확히 잘라낸다`() {
        // 복합키는 `<uuid>/<게임id>` 인데 게임 id 에도 `/` 가 들어갈 수 있다.
        // 마지막 `/` 로 자르면 틀리고, UUID 길이(36자)로 잘라야 맞다.
        val config = yaml {
            set("names.$steve", "Steve")
            set("records.$steve/mini/game.last-play", 1_500_000_000_000L)
        }

        val entries = PlayNameImport.parse(config)

        assertEquals(1_500_000_000_000L, entries.single().seen)
    }

    @Test
    fun `names 섹션이 없으면 아무것도 나오지 않는다`() {
        // 이미 옮긴 뒤의 파일이 이 모양이다. 다시 돌아도 no-op 이어야 한다.
        val config = yaml { set("records.$steve/baseball3.plays", 5L) }

        assertTrue(PlayNameImport.parse(config).isEmpty())
    }
}
