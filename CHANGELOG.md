# inmc-numbergame 변경 기록

---

## 2026-09-25 — 커스텀아이템 연동

- 게임의 참가 아이템은 커스텀아이템의 "참가 아이템" 역할을 맡은 아이템이다(바닐라 그대로인 것은 옮기지 않는다). 옛 것도 계속 받는다

## 2026-09-23 — 검증

- 테스트 **101개 통과**. 권한 선언(`ng.admin`) · 명령어 가드 · 메시지 키 · 설정 키를 점검했고 결함은 없었습니다
- `GUIDE.md` 추가

## 2026-09-11 — core 승격 작업

- 다이얼로그 목록의 페이지 계산 4곳 → core `Paging`
- 랭킹·보상·우편함은 core `RankService` · `RewardService` · `Mailbox` (13단계에서 `put/drop` 이 더해졌지만
  이 플러그인이 쓰는 누적 `record()` 경로는 그대로이고 테스트가 지킵니다)
- 플레이어 이름 캐시 → core `profile` (옛 기록은 `PlayNameImport` 가 한 번 옮김, 한 릴리스 더 유지)
- `Ticker` · `Messages` · `Ph` 공통부를 core 로. `Ticker` 가 `printStackTrace` 로 이중 기록하던 것을 로거 하나로
- `Setting` 스키마는 core 로 올리지 않았습니다 — 소비자가 이 플러그인 하나이고 다이얼로그 전용입니다
