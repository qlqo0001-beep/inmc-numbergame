package com.inmc.numbergame

import com.inmc.numbergame.play.PlayNameImport
import com.inmc.numbergame.util.Ph
import kr.inmc.core.CorePlugin
import kr.inmc.core.rank.RankService
import kr.inmc.core.rank.Rankable
import kr.inmc.core.reward.Mailbox
import kr.inmc.core.reward.RewardHost
import kr.inmc.core.reward.RewardService
import kr.inmc.core.reward.RewardSettings
import kr.inmc.core.util.Placeholders
import net.kyori.adventure.text.Component
import kr.inmc.core.config.ConfigService
import com.inmc.numbergame.config.Messages
import com.inmc.numbergame.config.PluginConfig
import com.inmc.numbergame.dialog.Dlg
import com.inmc.numbergame.dialog.Screens
import com.inmc.numbergame.dialog.admin.AdminRewards
import com.inmc.numbergame.dialog.admin.AdminScreens
import com.inmc.numbergame.event.EventService
import com.inmc.numbergame.game.GameRegistry
import com.inmc.numbergame.game.GameService
import com.inmc.numbergame.game.SessionManager
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.MMOItemsHook
import com.inmc.numbergame.integration.PapiHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import com.inmc.numbergame.play.PlayService
import com.inmc.numbergame.stats.PlayLog
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * Service locator wiring the plugin together.
 *
 * Everything is built once and reached as `ng.<service>`. A reload swaps the two volatile config
 * objects and re-reads the game files, but never rebuilds a service - so a dialog callback or a
 * listener captured before the reload still points at something live.
 */
class Ng(override val plugin: JavaPlugin) : RewardHost {

    val logger: java.util.logging.Logger = plugin.logger
    override val io = ConfigService(plugin)

    /** core 의 ChatPrompt·MenuListener·보상 서비스가 메시지를 보낼 때 쓰는 통로. */
    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    override fun messageComponent(key: String, ph: Placeholders?): Component =
        messages.component(key, ph as? Ph)

    /**
     * core 가 쓰는 의미 이름을 이 플러그인의 토큰으로 옮긴다.
     * core 는 `{게임}` 을 모르고 알 필요도 없다 — `subject` 라고만 말한다.
     */
    override fun placeholders(vararg pairs: Pair<String, String>): Placeholders {
        val ph = Ph.of()
        for ((name, value) in pairs) when (name) {
            "subject" -> ph.game(value)
            "player" -> ph.player(value)
            "item" -> ph.item(value)
            "rank" -> ph.rank(value.toInt())
            "season" -> ph.season(value.toInt())
            "count" -> ph.count(value.toInt())
            else -> ph.raw(name, value)
        }
        return ph
    }

    // --- integrations (all optional) -------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)
    override val economy = EconomyHook(logger)
    val papi = PapiHook(this)

    // --- item layer -------------------------------------------------------------
    override val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)

    // --- configuration ----------------------------------------------------------
    @Volatile
    var config: PluginConfig = PluginConfig.from(YamlConfiguration())

    override val rewardSettings: RewardSettings get() = config

    override val rankables: List<Rankable> get() = games.all()

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    // --- domain services --------------------------------------------------------
    val games = GameRegistry(this)
    override val mailbox = Mailbox(this)
    override val rewards = RewardService(this)
    override val ranks = RankService(this)
    val plays = PlayService(this)
    val log = PlayLog(this)
    val sessions = SessionManager(this)
    val events = EventService(this)

    // --- presentation -----------------------------------------------------------
    val dlg = Dlg(this)
    val screens = Screens(this)
    val admin = AdminScreens(this)
    val adminRewards = AdminRewards(this)
    val gameService = GameService(this)

    /** False until persisted state has finished loading; interactions are held off until then. */
    @Volatile
    var ready: Boolean = false
        private set

    /** Full startup: config and games first, then everything that references a game by id. */
    fun enable(then: () -> Unit) {
        reload { gameCount ->
            logger.info("게임 " + gameCount + "종을 불러왔습니다")
            ranks.load {
                plays.load {
                    mailbox.load {
                        log.load {
                            events.load {
                                // Sessions last: restoring one needs its game to exist already.
                                sessions.load {
                                    // Event rounds belong to the server clock, not to whoever
                                    // opens the screen first.
                                    events.openMissingRounds()
                                    // core 의 저장소가 실제로 준비된 뒤에야 ready 를 올린다.
                                    // load: BEFORE 는 플러그인 enable 순서만 정하지 저장소
                                    // 내용이 준비됐다는 뜻이 아니다 — 콜백이 첫 틱에야 돈다.
                                    CorePlugin.get().players.whenReady {
                                        importPlayerNames()
                                        ready = true
                                        then()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * `plays.yml` 의 `names:` 를 core 의 `profile` 로 한 번 옮긴다.
     *
     * `enable` 에서만 부른다. `reload` 에 넣으면 리로드마다 다시 돌게 된다 — 결과가 같긴
     * 하지만(seen = 0 은 항상 지므로) 헛일이다.
     *
     * 여기서 디스크를 다시 읽는 것은 `PlayService.load` 가 이미 `names:` 를 안 읽기
     * 때문이다. 워커 한 번 더 도는 값은 부팅 1회뿐이라 싸다.
     */
    private fun importPlayerNames() {
        val store = CorePlugin.get().players
        io.async({
            val file = io.file("data", "plays.yml")
            if (file.exists()) PlayNameImport.parse(io.load(file)) else emptyList()
        }) { entries ->
            if (entries.isEmpty()) return@async
            val written = PlayNameImport.apply(store, entries)
            if (written > 0) logger.info("플레이어 이름 " + written + "건을 inmc-core 로 옮겼습니다")
        }
    }

    /** Re-reads config.yml, messages.yml and every game file. */
    fun reload(then: (Int) -> Unit) {
        io.async({
            val configFile = io.file("config.yml")
            val messagesFile = io.file("messages.yml")
            io.copyDefault("config.yml", configFile)
            io.copyDefault("messages.yml", messagesFile)
            copyBundledGames()
            io.load(configFile) to io.load(messagesFile)
        }) { (rawConfig, rawMessages) ->
            config = PluginConfig.from(rawConfig)
            messages = Messages.from(rawMessages)
            setupIntegrations()
            // Integrations may have come or gone, so cached menu icons are no longer trustworthy.
            itemResolver.clearIconCache()
            games.loadAll { count ->
                // A schedule edit or a first boot needs its reset instant computed now, not on
                // the first play - otherwise a game nobody touches never closes its season.
                val now = System.currentTimeMillis()
                games.all().forEach { ranks.ensureSchedule(it, now) }
                // 커스텀아이템이 있으면 참가 아이템을 그 역할에 맞춘다(처음이면 옮긴다).
                com.inmc.numbergame.play.NgRoles.sync(this)
                then(count)
            }
        }
    }

    /** Ships the starter games on first boot. Never overwrites what an admin has edited. */
    private fun copyBundledGames() {
        for (name in BUNDLED_GAMES) {
            io.copyDefault("games/" + name + ".yml", io.file("games", name + ".yml"))
        }
    }

    fun refreshIntegrations() {
        setupIntegrations()
        itemResolver.clearIconCache()
    }

    /**
     * Re-probes only the economy, for when a provider registers after we looked.
     *
     * Vault being installed is not the same as an economy existing: the provider is registered
     * by whichever plugin supplies it, and that can land after our startup probe - CMI enabling
     * its economy module, or anything hot-loaded through PlugManX. Without this, the betting
     * games would stay hidden for the rest of the session with nothing in the log to explain it.
     */
    fun refreshEconomy() {
        val had = economy.isEnabled
        economy.setup()
        if (!had && economy.isEnabled) {
            logger.info("경제 플러그인이 뒤늦게 등록되어 연동했습니다 - 베팅 게임을 사용할 수 있습니다")
        }
    }

    /** Must run on the main thread - all of these touch the plugin manager. */
    private fun setupIntegrations() {
        mmoItems.setup()
        customItems.setup()
        economy.setup()
        papi.setup()
    }

    fun shutdown() {
        papi.teardown()
        games.flushDirtyBlocking()
        sessions.flushBlocking()
        events.flushBlocking()
        ranks.flushBlocking()
        plays.flushBlocking()
        mailbox.flushBlocking()
        log.flushBlocking()
        io.shutdown()
    }

    companion object {
        /**
         * Default game definitions bundled in the jar under the resources games folder.
         *
         * Copied once on first boot and never again, so an admin who deletes one is not fighting
         * the plugin to keep it deleted. `betting` and `blackjack` ship disabled - they move real
         * server currency, and that should be a deliberate decision rather than a default.
         */
        val BUNDLED_GAMES = listOf(
            "baseball3", "baseball4", "updown100", "speedmath", "memory",
            "sequence", "nim31", "betting", "blackjack", "lotto", "uniquebid",
        )
    }
}
