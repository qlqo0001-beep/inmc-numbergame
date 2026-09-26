package com.inmc.numbergame.play

import kr.inmc.core.store.PlayerStore
import kr.inmc.core.store.Profile
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * `data/plays.yml` 의 `names:` 를 core 의 `profile` 로 한 번 옮긴다.
 *
 * 이 플러그인은 uuid→이름 캐시를 자기 파일에 들고 있었고, urb 도 `stats.yml` 에 따로 들고
 * 있었다. 같은 값을 두 벌 관리하던 것을 core 한 곳으로 모은다.
 *
 * **별도의 완료 표시가 필요 없다.** `PlayService.serialize` 가 더 이상 `names:` 를 쓰지
 * 않으므로 다음 저장에서 그 섹션이 자연히 사라진다. 그 전에 서버가 다시 떠서 임포트가 또
 * 돌아도, `names:` 에는 시각이 없어 `seen = 0` 으로 들어가 어떤 실제 기록에도 지므로
 * 결과가 바뀌지 않는다.
 */
object PlayNameImport {

    /** 한 명분. [seen] 은 그 사람의 마지막 플레이 시각이며, 없으면 0. */
    data class Entry(val playerId: UUID, val name: String, val seen: Long)

    /**
     * Bukkit 없이 도는 순수 파싱.
     *
     * `names:` 에는 타임스탬프가 없다. 그래서 같은 파일 `records:` 의 `<uuid>/<게임id>` 항목들
     * 중 그 사람의 `last-play` 최댓값을 [Entry.seen] 으로 쓴다. 아무것도 없으면 0 이고,
     * 그건 어떤 실제 기록에도 지는 값이라 안전하다.
     */
    fun parse(config: YamlConfiguration): List<Entry> {
        val lastPlay = HashMap<UUID, Long>()
        config.getConfigurationSection("records")?.let { section ->
            for (raw in section.getKeys(false)) {
                // 키는 `<uuid>/<게임id>` 다. 게임 id 에도 `/` 가 들어갈 수 있으므로
                // 마지막 `/` 가 아니라 **UUID 길이(36자)** 로 자른다.
                if (raw.length <= 36 || raw[36] != '/') continue
                val id = runCatching { UUID.fromString(raw.substring(0, 36)) }.getOrNull() ?: continue
                val at = section.getConfigurationSection(raw)?.getLong("last-play", 0L) ?: 0L
                lastPlay[id] = maxOf(lastPlay[id] ?: 0L, at)
            }
        }

        val out = ArrayList<Entry>()
        config.getConfigurationSection("names")?.let { section ->
            for (raw in section.getKeys(false)) {
                val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
                val name = section.getString(raw) ?: continue
                out.add(Entry(id, name, lastPlay[id] ?: 0L))
            }
        }
        return out
    }

    /** 파싱 결과를 저장소에 반영한다. 이미 더 최신인 기록이 있으면 건드리지 않는다. */
    fun apply(store: PlayerStore, entries: List<Entry>): Int {
        var written = 0
        for (entry in entries) {
            val merged = Profile.merge(
                existingName = Profile.nameOf(store, entry.playerId),
                existingSeen = Profile.seenAt(store, entry.playerId),
                incomingName = entry.name,
                incomingSeen = entry.seen,
            ) ?: continue
            Profile.touch(store, entry.playerId, merged.first!!, merged.second)
            written++
        }
        return written
    }
}
