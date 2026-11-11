# Car Message

깜빡이 뒤에 숨은 의도를, 한 문장의 메시지로.
방향지시등·비상등·메시지 버튼 조작에 맞춰 정형 메시지를 고르면, 주변 차량 앱에 화면과 음성으로 전달되는 Pleos Connect 앱.

> 현대자동차그룹 차량용 앱 개발 공모전 출품작

## 어떻게 동작하나

```
A차 앱 ── send ──▶ 중계 서버 ── msg ──▶ B차 앱
A차 앱 ◀─ status ─ 중계 서버 ◀─ ack ─── B차 앱
```

모든 연결은 WebSocket이다. 차량끼리 직접 통신하는 API가 없어서 서버가 중계한다.
서버는 메시지 **코드**(`U_TURN` 등)만 넘기고, 화면 문구·TTS 문장은 앱이 코드표(`docs/messages.json`)에서 꺼낸다.

## 폴더 구조

```
car-message/
├── fe/                       Pleos Connect 앱 (Android, Kotlin·Java, 패키지명 com.hyundaiproject.messaging)
│   └── app/src/main/java/com/hyundaiproject/messaging/
│       ├── MainActivity.kt               시작점, 설정값(차 ID·서버 주소), 차량 신호 → 화면
│       ├── MessageBoard.kt               화면 (Figma 시안)
│       ├── MessagingController.java      화면 상태 (선택·확인창·전송·수신 알림)
│       ├── MessageCatalog.java           메시지 목록 (messages.json과 같은 코드)
│       ├── WebSocketMessageGateway.kt    중계 서버 연결
│       └── CarDevices.kt                 차량 신호(Vehicle SDK·adb), 음성 읽기(/speak)
├── be/                       메시지 중계 서버 (Kotlin, Ktor)
│   └── src/main/kotlin/carmessage/
│       ├── Application.kt    서버 시작, /ws/{vehicleId} 접속 처리
│       ├── Protocol.kt       앱과 주고받는 메시지 형식
│       └── Relay.kt          중계 로직 (ack 3초 대기, 재전송, 답장)
├── docs/
│   ├── protocol.md           앱 ↔ 서버 규약 ← 앱 개발 전에 필독
│   ├── messages.json         메시지 코드표 (코드 → 문구, 트리거)
│   └── device-signals.md     앱 ↔ 차량 신호·맥 음성 브리지 (adb, 아두이노)
├── bridge/                   맥 음성 브리지: 아두이노 버튼·음성 인식·읽기 (사용법은 bridge/README.md)
├── firmware/                 아두이노 핸들 버튼
├── infra/                    서버 배포 (예정)
└── apk/                      제출용 release APK
```

`fe/`와 `be/`는 서로 독립된 Gradle 프로젝트다. Android Studio에서는 저장소 루트가 아니라 `fe/`를 연다.

## 개발 환경

| 도구 | 버전 | 필요한 사람 | 비고 |
|---|---|---|---|
| Git | — | 전원 | |
| JDK | **21** | 서버·앱을 빌드하는 사람 | 서버(`be/`)는 21 이상 아무거나. 앱(`fe/`)은 Gradle 8.11이라 17~23만 된다 (Android Studio 최신 내장 JDK 25는 앱 빌드에 못 씀) |
| Android Studio | 최신 | 앱 | |
| Pleos Connect Emulator | 2.1.0 (AAOS, API 34) | 앱 | Apple Silicon 맥은 ARM64 이미지 |
| Android 프로젝트 설정 | minSdk 28 이상, Gradle 8 이상 | 앱 | 공모전 요건 |

Gradle·Kotlin·Ktor는 따로 설치하지 않는다. `./gradlew`가 처음 실행될 때 알아서 받는다 (첫 실행만 몇 분).

### Pleos 개발자 등록 (앱 담당)

1. [developers.pleos.ai](https://developers.pleos.ai) 가입 → Pleos Playground에서 프로젝트 생성
2. Vehicle SDK(방향지시등 조회)·NaviHelper SDK는 신청 없이 바로 사용 가능
3. TTS·STT SDK는 콘솔에 아직 "준비 중"이고, 에뮬레이터에서는 엔진 오류(`ON_DEVICE_ENGINE_ERROR`)가 난다 → 시연은 맥 음성 브리지가 듣고 읽는다 ([`docs/device-signals.md`](docs/device-signals.md)). 실차용으로는 Pleos STT·TTS 구현을 같이 두고 설정값 `VOICE_ENGINE`으로 고른다
4. 에뮬레이터 설치, Virtual Console(차량 신호 시뮬레이션) 사용법은 같은 사이트 가이드를 따른다

## 시작하기

### 1. 받기

```bash
git clone git@github.com:SongSeungYun/car-message.git
cd car-message
```

SSH 키가 없으면 `https://github.com/SongSeungYun/car-message.git`로 받는다.
디자인(Figma)·기획 문서 링크는 공개 저장소에 두지 않으니 팀 채널에서 받는다.

### 2. 서버 띄우기

```bash
cd be
./gradlew run              # ws://localhost:8080/ws/{vehicleId}
PORT=18080 ./gradlew run   # 8080이 이미 쓰이고 있을 때
./gradlew test             # 테스트
```

JDK를 따로 안 깔았다면 Android Studio 내장 JDK로 실행한다.

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew run
```

### 3. 앱 빌드·설치

에뮬레이터 두 대가 켜져 있어야 한다 (아래 [테스트 환경](#테스트-환경)).

```bash
cd fe
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
```

처음 실행할 때 차 ID와 서버 주소를 넘긴다. 한 번 넘기면 저장되어, 다음부터는 앱 아이콘으로 열어도 된다.

```bash
adb -s emulator-5554 shell am start -S -n com.hyundaiproject.messaging/.MainActivity --es car A --es server ws://10.0.2.2:8080
adb -s emulator-5556 shell am start -S -n com.hyundaiproject.messaging/.MainActivity --es car B --es server ws://10.0.2.2:8080
```

| 설정 | 기본값 | 설명 |
|---|---|---|
| `car` | `A` | 내 차 ID. 두 차는 반드시 다르게 (같으면 서로 연결을 끊어낸다) |
| `server` | `ws://10.0.2.2:8080` | 중계 서버. `10.0.2.2`는 에뮬레이터에서 본 맥. 2차 PT는 AWS 주소. `demo`를 주면 서버 없이 가짜 응답으로 화면만 시연 |
| `bridge` | `http://10.0.2.2:8090` | 맥 음성 브리지. 받은 메시지를 여기로 보내 읽힌다 |

### 4. 시연 (1차 장면 1·2)

1. 서버: `cd be && ./gradlew run`
2. 맥 음성 브리지: [`bridge/README.md`](bridge/README.md). 읽기만 확인할 때는 `python3 bridge/voice_bridge.py --speak-only`
3. **장면 1 (방향지시등):** A차 Virtual Console(`http://127.0.0.1:8088`)에서 Turn Signal State → Blink left
   → A차에 좌회전 목록이 뜬다 → "출발합니다" → 확인 → B차에 카드 + 맥 스피커로 읽기
4. **장면 2 (비상등):** 아래 명령 → A차에 비상등 목록 → "전방 특이사항 발생" → 확인 → B차에 빨간 경고 + 읽기
   ```bash
   adb -s emulator-5554 shell am broadcast -a com.hyundaiproject.messaging.HAZARD -p com.hyundaiproject.messaging --es state ON
   ```
5. **말로 고르기:** 핸들 버튼(아두이노, 없으면 브리지 `--keyboard`)을 누른 채 "1번 메시지 보내줘" → 확인창

신호를 끄면(Blink → Off, `state OFF`) 목록에 머물러 있던 화면은 홈으로 돌아간다. 화면 오른쪽 위 `시연` 메뉴로도 목록을 열 수 있다.

### 5. 에뮬레이터 1대로 테스트하기

B차 앱 대신 크롬으로 B차를 흉내 낼 수 있다. 주소창에 `about:blank` → F12 → Console에 붙여 넣는다.

```js
const ws = new WebSocket('ws://localhost:8080/ws/B'); // 서버 포트를 바꿨으면 여기도
ws.onmessage = e => {
  console.log('받음', e.data);
  const m = JSON.parse(e.data);
  if (m.type === 'msg') ws.send(JSON.stringify({ type: 'ack', id: m.id })); // 받으면 자동 ack
};

// A차에게 답장 보내기
ws.send(JSON.stringify({ type: 'send', id: 'b-' + Date.now(), code: 'I_YIELD', to: 'A' }));
```

## 앱 개발 시 꼭 지킬 것

기준 문서는 [`docs/protocol.md`](docs/protocol.md)다. 특히 아래는 어기면 메시지가 사라지거나 계속 실패한다.

- 메시지 `id`는 `UUID.randomUUID()`로 만든다. 1, 2, 3 같은 번호는 금지
- `msg`를 받으면 화면에 띄운 직후 `ack`를 보낸다. **같은 메시지가 두 번 와도, Additional 수신을 꺼서 화면에 안 띄워도 ack는 보낸다** (화면엔 한 번만)
- 끊기면 재접속한다 (1s → 2s → 4s … 최대 10s). "전송 중"이던 메시지는 **같은 id로** 다시 보낸다
- 문구는 서버가 아니라 `messages.json`에서 꺼낸다
- 앱 패키지명은 `com.hyundaiproject.messaging`. 비상등·핸들 버튼·음성 신호는 [`docs/device-signals.md`](docs/device-signals.md)

## 협업 규칙

- `main`에 직접 push한다 (브랜치·PR 없음).
- push 전에 `git pull --rebase`로 최신을 받는다.
- 각자 자기 폴더에서 작업한다 (`fe/` 앱, `be/` 서버). `docs/`의 규약·코드표는 앱과 서버가 같이 쓰므로 바꾸기 전에 팀에 공유한다.

## 주의: 공개 저장소

이 저장소는 Public이다. 아래는 커밋하지 않는다.

- 팀 내부 자료 (기획 PDF, Figma·내부 문서 링크, 팀원 실명)
- 서명 키(`.jks`, `.keystore`), Pleos Client Secret 같은 비밀값

대부분 `.gitignore`에 걸려 있지만, `git add` 전에 `git status`로 한 번 확인한다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (AAOS, API 34, 2560×1440) 2대. A차 `emulator-5554`, B차 `emulator-5556`, 같은 APK |
| 확인한 환경 | Apple Silicon 맥 1대에 에뮬레이터 2대 + 서버 + 음성 브리지. 장면 1·2 번갈아 10회 연속 성공 (11/11) |
| 앱 권한 | `INTERNET`, `pleos.car.permission.CAR_LIGHTS`(방향지시등). 둘 다 설치할 때 자동 허용된다 |
| 차량 신호 | 방향지시등은 Virtual Console → Vehicle SDK. 비상등·핸들 버튼·들은 말은 adb broadcast ([`docs/device-signals.md`](docs/device-signals.md)) |
| 음성 | Pleos STT·TTS는 에뮬레이터에서 엔진 오류가 나서, 맥 음성 브리지가 듣고 읽는다 |

### 에뮬레이터 준비 (Apple Silicon 맥, 한 번만)

1. Android Studio → SDK Manager → SDK Update Sites에 아래 주소 추가 → SDK Platforms에서 Android 14 (API 34) 아래 Pleos Connect 이미지 설치
   `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. Device Manager → Automotive → Pleos Connect v2.1.0으로 가상 기기 2개(`Pleos`, `PleosB`). Boot는 Cold, RAM 4GB
3. 아래 명령으로 켠다. Device Manager의 재생 버튼으로 켜면 Vulkan 문제로 부팅 뒤 죽는다.
   `emulator`·`adb`는 `~/Library/Android/sdk/emulator`, `~/Library/Android/sdk/platform-tools`에 있다
   ```bash
   emulator -avd Pleos  -port 5554 -no-snapshot -gpu host -feature -Vulkan &
   emulator -avd PleosB -port 5556 -no-snapshot -gpu host -feature -Vulkan &
   adb -s emulator-5554 forward tcp:8088 tcp:8088
   adb -s emulator-5556 forward tcp:8089 tcp:8088
   ```
   Virtual Console은 A차 `http://127.0.0.1:8088`, B차 `http://127.0.0.1:8089`. **이 페이지가 브라우저에 열려 있어야** 차량 신호가 들어간다 (닫혀 있으면 `503 emulator_inactive`)
4. 깜빡이를 켜면 후측방 카메라 화면이 앱을 가리므로 차마다 한 번 끈다. `10`은 `adb -s emulator-5554 shell am get-current-user`로 나온 값
   ```bash
   adb -s emulator-5554 shell pm disable-user --user 10 ai.umos.car.evs
   ```

### 알려진 제한 (1차)

- 우측 깜빡이 후보 화면은 아직 없다 (1차 장면은 좌측만)
- 말로 고르기는 번호("N번 메시지 보내줘")만 알아듣는다

## APK

`apk/팀명_앱이름_release.apk` — 정식 서명한 release 빌드를 11/13에 넣는다. 그전에는 위 [앱 빌드·설치](#3-앱-빌드설치)로 debug APK를 만든다.
