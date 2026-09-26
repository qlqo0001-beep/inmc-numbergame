package com.inmc.numbergame.game

import com.inmc.numbergame.Ng
import kr.inmc.core.store.YamlFolder
import org.bukkit.configuration.file.YamlConfiguration
import java.util.concurrent.ConcurrentHashMap

/**
 * Every configured game, loaded from `games/<id>.yml` and held in memory.
 *
 * Lookups run out of a [ConcurrentHashMap] because they happen on every dialog draw. Writes go
 * through [markDirty], which only records that a file needs saving - the actual disk write is
 * batched by the ticker onto the I/O worker, so an admin dragging a slider does not stall the
 * main thread once per pixel.
 */
class GameRegistry(private val ng: Ng) {

    private val games = ConcurrentHashMap<String, GameDefinition>()

    /**
     * 폴더 저장 경로.
     *
     * dirty 키로 **원래 대소문자의 id** 를 쓴다. 맵 키는 소문자지만 파일명은 원래 표기라,
     * 소문자로 표시하면 다른 파일에 쓰게 되고 원본이 고아가 된다.
     */
    private val files = YamlFolder(ng.io, ng.logger, "games", what = "게임")

    val size: Int get() = games.size

    // --- reads -----------------------------------------------------------------

    fun get(id: String?): GameDefinition? = id?.let { games[it.lowercase()] }

    fun exists(id: String?): Boolean = get(id) != null

    fun all(): List<GameDefinition> =
        games.values.sortedWith(compareBy({ it.order }, { it.id }))

    fun enabled(): List<GameDefinition> = all().filter { it.enabled }

    /** Games that could run right now, ignoring who is asking. */
    fun playable(): List<GameDefinition> = enabled().filter { runnable(it) }

    /**
     * Games this particular player may see and start.
     *
     * A game with no permission set is open to everyone, which is what every bundled game does -
     * the field exists so an admin can put one behind a rank without having to disable it for
     * the rest of the server.
     */
    fun playableFor(player: org.bukkit.entity.Player): List<GameDefinition> =
        playable().filter { allowed(player, it) }

    fun allowed(player: org.bukkit.permissions.Permissible, def: GameDefinition): Boolean =
        def.permission.isBlank() || player.hasPermission(def.permission)

    /**
     * Whether a game could run at this moment.
     *
     * The economy check lives here rather than inside the engines so they stay free of Bukkit:
     * a betting game with no Vault installed is not broken, it is simply unavailable, and it
     * should disappear from the list rather than fail on the first stake.
     */
    fun runnable(def: GameDefinition): Boolean {
        if (def.type.needsEconomy && !ng.economy.isEnabled) return false
        return def.engine.available(def)
    }

    fun ids(): List<String> = all().map { it.id }

    // --- writes ----------------------------------------------------------------

    fun markDirty(def: GameDefinition) {
        files.markDirty(def.id)
    }

    /** Returns null when the id is taken or malformed. */
    fun create(id: String, type: GameType, displayName: String? = null): GameDefinition? {
        if (!GameDefinition.isValidId(id)) return null
        val key = id.lowercase()
        if (games.containsKey(key)) return null
        val def = GameDefinition.create(id, type, displayName)
        def.order = (games.values.maxOfOrNull { it.order } ?: 0) + 10
        games[key] = def
        markDirty(def)
        return def
    }

    fun delete(id: String): Boolean {
        val def = games.remove(id.lowercase()) ?: return false
        files.deleteFile(def.id)
        return true
    }

    // --- persistence -----------------------------------------------------------

    fun loadAll(then: (Int) -> Unit) {
        ng.io.async({
            files.readAll { id, config ->
                GameDefinition.load(id, config) ?: run {
                    ng.logger.warning("게임 '" + id + "' 을(를) 불러오지 못했습니다 - type 값을 확인해주세요")
                    null
                }
            }
        }) { loaded ->
            games.clear()
            files.clearDirty()
            loaded.forEach { (_, def) -> games[def.id.lowercase()] = def }
            then(games.size)
        }
    }

    /** Writes every game that changed since the last flush. Serialisation happens on this thread. */
    fun flushDirty() = files.flushDirty(::render)

    /** Shutdown path: same work, but the writes happen here rather than on the worker. */
    fun flushDirtyBlocking() = files.flushDirtyBlocking(::render)

    /** 메인 스레드에서 돈다. dirty 키는 원래 대소문자이므로 맵 조회 때만 소문자로 맞춘다. */
    private fun render(id: String): YamlConfiguration? {
        val def = games[id.lowercase()] ?: return null
        val config = YamlConfiguration()
        def.save(config)
        return config
    }
}
