package com.inmc.numbergame.config

import com.inmc.numbergame.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Message catalogue backed by `messages.yml`.
 *
 * Every key has a built-in Korean fallback, so deleting a line - or running a config that
 * predates a new key - degrades to a sensible default rather than an empty message.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = mapOf(
            PREFIX to "<gradient:#5ec8ff:#b48bff>[ 숫자게임 ]</gradient> ",

            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>이 명령어는 플레이어만 사용할 수 있습니다.</red>",
            "not-ready" to "<gray>플러그인이 아직 준비 중입니다. 잠시 후 다시 시도해주세요.</gray>",
            "unknown-game" to "<red>'{게임이름}' 게임을 찾을 수 없습니다.</red>",
            "game-disabled" to "<red>{게임이름} 은(는) 지금 비활성화되어 있습니다.</red>",
            "game-unavailable" to "<red>{게임이름} 을(를) 지금 실행할 수 없습니다. <gray>({사유})</gray></red>",
            "usage" to "<gray>/숫자게임 <white>[게임id|랭킹|우편함|도움말|관리|리로드]</white></gray>",
            "reloading" to "<gray>설정을 다시 읽는 중...</gray>",
            "reloaded" to "<green>설정을 다시 읽었습니다. <gray>(게임 {개수}개)</gray></green>",
            "dialog-unsupported" to "<red>이 클라이언트는 다이얼로그를 지원하지 않습니다. 최신 버전으로 접속해주세요.</red>",

            "session-resumed" to "<green>{게임이름} 을(를) 이어서 진행합니다.</green>",
            "session-abandoned" to "<gray>{게임이름} 을(를) 포기했습니다.</gray>",
            "session-timeout" to "<red>{게임이름} 시간이 초과되어 종료되었습니다.</red>",
            "session-idle" to "<gray>오래 입력이 없어 {게임이름} 이(가) 종료되었습니다.</gray>",
            "session-too-fast" to "<gray>너무 빠릅니다. 잠시 후 다시 입력해주세요.</gray>",
            "fast-input-start" to "<green>{게임이름} 시작!</green> <gray>답을 <white>채팅</white>으로 입력하세요. <dark_gray>(포기 하려면 '포기')</dark_gray></gray>",

            "entry-daily-limit" to "<red>{게임이름} 은(는) 오늘 {개수}회까지만 플레이할 수 있습니다.</red>",
            "entry-cooldown" to "<red>{시간} 후에 다시 플레이할 수 있습니다.</red>",
            "entry-need-money" to "<red>참가비 {금액}원이 필요합니다.</red>",
            "entry-need-item" to "<red>참가하려면 {아이템} {수량}개가 필요합니다.</red>",
            "entry-paid-money" to "<gray>참가비 <gold>{금액}원</gold>을 지불했습니다.</gray>",
            "entry-paid-item" to "<gray>{아이템} {수량}개를 사용했습니다.</gray>",
            "entry-refunded" to "<gray>참가비를 돌려받았습니다.</gray>",

            "reward-inventory-full" to "<yellow>인벤토리가 가득 차서 일부 보상을 우편함으로 보냈습니다.</yellow>",
            "reward-dropped" to "<yellow>인벤토리가 가득 차서 일부 보상을 바닥에 떨어뜨렸습니다.</yellow>",
            "reward-announce" to "<gold>★</gold> <yellow>{플레이어네임}</yellow>님이 <yellow>{게임이름}</yellow>에서 <aqua>{아이템}</aqua>을(를) 획득했습니다!",

            "mailbox-waiting" to "<yellow>받지 않은 보상이 {개수}개 있습니다. <gray>/숫자게임 우편함</gray></yellow>",
            "mailbox-empty" to "<gray>받을 보상이 없습니다.</gray>",
            "mailbox-claimed" to "<green>보상 {개수}개를 받았습니다.</green>",
            "mailbox-partial" to "<yellow>인벤토리가 부족해 일부만 받았습니다. 정리 후 다시 시도해주세요.</yellow>",
            "mailbox-money-held" to "<yellow>경제 플러그인 문제로 금액 보상을 지급하지 못했습니다. 우편함에 그대로 보관됩니다.</yellow>",

            "rank-reset-broadcast" to "<gold>▶</gold> <yellow>{게임이름}</yellow> <gray>시즌 {시즌} 이(가) 종료되었습니다. 순위 보상이 지급되었습니다.</gray>",
            "rank-reset-done" to "<green>{게임이름} 랭킹을 초기화했습니다. <gray>(시즌 {시즌})</gray></green>",
            "rank-reward-received" to "<gold>★</gold> <yellow>{게임이름}</yellow> <gray>시즌 종료 - </gray><white>{순위}위</white> <gray>보상을 받았습니다.</gray>",

            "event-drawn" to "<gold>▶</gold> <yellow>{게임이름}</yellow> <gray>{개수}회차 추첨 결과: <white>{기록}</white> · 1위 <white>{플레이어네임}</white></gray>",
            "event-prize" to "<gold>★</gold> <yellow>{게임이름}</yellow> <gray>{개수}회차 </gray><white>{순위}위</white> <gray>당첨! 보상을 받았습니다.</gray>",
            "event-voided" to "<gray>{게임이름} 회차가 인원 부족으로 무효 처리되어 참가비를 돌려드렸습니다.</gray>",
            "event-voided-broadcast" to "<gray>{게임이름} 이번 회차는 참가 인원이 부족해 무효 처리되었습니다.</gray>",
            "event-drawn-manual" to "<green>{게임이름} {개수}회차를 추첨했습니다. <gray>(당첨 {수량}명)</gray></green>",

            "admin-plays-reset-player" to "<green>{플레이어네임} 님의 플레이 기록을 초기화했습니다.</green>",
            "admin-plays-reset-all" to "<green>서버 전체 일일 플레이 횟수를 초기화했습니다. <gray>({개수}명)</gray></green>",
            "admin-game-created" to "<green>'{게임이름}' 게임을 만들었습니다.</green>",
            "admin-game-exists" to "<red>'{게임이름}' 은(는) 이미 존재합니다.</red>",
            "admin-game-invalid-id" to "<red>게임 id 는 영문/숫자/_/- 만 사용할 수 있습니다. (최대 32자)</red>",
            "admin-game-deleted" to "<yellow>'{게임이름}' 게임을 삭제했습니다.</yellow>",
        )
    }
}
