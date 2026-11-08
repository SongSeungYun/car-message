# Car Message

깜빡이 뒤에 숨은 의도를, 한 문장의 메시지로.
방향지시등·비상등·메시지 버튼 조작에 맞춰 정형 메시지를 고르면, 주변 차량 앱에 화면과 음성으로 전달되는 Pleos Connect 앱.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작

## 구성

| 폴더 | 내용 |
|---|---|
| `fe/` | Pleos Connect 앱 (Android, Kotlin) |
| `be/` | 메시지 중계 서버 (Kotlin, Ktor, WebSocket) |
| `docs/` | 앱↔서버 규약(`protocol.md`), 메시지 코드표(`messages.json`) |
| `infra/` | 서버 배포 |
| `apk/` | 제출용 release APK |

## 실행 방법

### 서버

```bash
cd be
./gradlew run          # ws://localhost:8080/ws/{vehicleId}
./gradlew test
```

### 앱

TODO

## 테스트 환경

- Pleos Connect Emulator 2대 (A차, B차) — 같은 APK
- 에뮬레이터에서 서버 주소: `ws://10.0.2.2:8080/ws/A`, `ws://10.0.2.2:8080/ws/B`
- TODO: Virtual Console 설정, 필요한 권한

## APK

`apk/` — TODO: 파일명 `팀명_앱이름_release.apk`
