# 차량 신호·음성 연결 (앱 ↔ 에뮬레이터·STM32·맥)

서버 규약([`protocol.md`](protocol.md))과 별개로, 앱이 차량·핸들 버튼·음성과 주고받는 신호다.
앱 패키지명은 `com.carmessage.app`. 아래 이름은 이 패키지명을 앞에 붙인다.

## 한눈에

| 신호 | 방향 | 형식 |
|---|---|---|
| 방향지시등 | 차량 → 앱 | Vehicle SDK `registerTurnSignalState`, 값 `LEFT` / `RIGHT` / `OFF` |
| 비상등 | 터미널(adb) → 앱 | broadcast `com.carmessage.app.HAZARD`, `state` = `ON` / `OFF` |
| 핸들(말하기) 버튼 | STM32 → 맥 음성 브리지 → 앱 | broadcast `com.carmessage.app.PTT`, `state` = `DOWN` / `UP` |
| 들은 말 | 맥 음성 브리지 → 앱 | broadcast `com.carmessage.app.VOICE_RESULT`, `text` = 들은 글자 |
| 음성 읽기 | 앱 → 맥 음성 브리지 | `POST http://10.0.2.2:8090/speak` |

왜 이렇게 하나 (에뮬레이터 2.1.0, Vehicle SDK 2.3.2, STT·TTS 3.3.1에서 확인)

- 비상등: Vehicle SDK에 비상등을 읽는 API가 없다. Virtual Console에서 비상등을 켜면 계기판엔 뜨지만 앱은 모른다.
- 핸들 버튼: Pleos에 핸들 버튼을 읽는 공개 API가 없다.
- 음성: Pleos STT·TTS는 에뮬레이터에서 초기화 뒤 `ON_DEVICE_ENGINE_ERROR`가 난다. 그래서 시연은 맥이 듣고 읽고, 앱은 글자만 주고받는다.
  실차용으로는 Pleos STT·TTS 구현을 같이 두고 설정값 `VOICE_ENGINE`(`HOST` 시연 / `PLEOS` 실차)으로 고른다.

## adb broadcast (비상등·버튼·들은 말)

```
adb -s emulator-5554 shell am broadcast -a com.carmessage.app.HAZARD -p com.carmessage.app --es state ON
adb -s emulator-5554 shell am broadcast -a com.carmessage.app.PTT -p com.carmessage.app --es state DOWN
adb -s emulator-5554 shell am broadcast -a com.carmessage.app.VOICE_RESULT -p com.carmessage.app --es text "출발합니다"
```

- B차는 `emulator-5556`. 차가 한 대뿐인 노트북이면 `-s`는 생략해도 된다.
- `-p com.carmessage.app`을 꼭 붙인다. Android 8 이상은 대상 앱을 지정하지 않은 broadcast를 매니페스트 리시버에 안 보낸다.
- 앱 쪽: 앱이 떠 있는 동안 리시버를 코드로 등록한다. adb(셸)에서 오는 신호라 exported로 등록해야 한다.
  ```kotlin
  ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
  ```
- 앱은 신호가 어디서 왔는지 모른다. Vehicle SDK 콜백과 broadcast를 앱 안에서 같은 이벤트로 바꿔 쓴다.
- `VOICE_RESULT`의 글자를 화면 후보 중 가장 비슷한 것에 연결하는 건 앱이 한다. 터치로 고른 것과 같은 "선택됨"으로 처리한다.

## STM32 → 맥 음성 브리지

- USB 시리얼 115200bps, 한 줄에 이벤트 하나(`\n`으로 끝).
- `BTN:DOWN`, `BTN:UP`. (`TURN:L`·`TURN:R`·`TURN:OFF`·`HAZARD:ON`·`HAZARD:OFF`는 예약, 지금은 안 씀)

## 맥 음성 브리지 (`bridge/`)

노트북마다 하나씩, 그 노트북의 에뮬레이터·마이크·스피커를 쓴다.

1. `BTN:DOWN` → `PTT DOWN` broadcast, 마이크 녹음 시작
2. `BTN:UP` → `PTT UP` broadcast, 녹음 끝, 한국어 인식 → `VOICE_RESULT` broadcast
3. `POST /speak` 받으면 그 노트북 스피커로 한국어로 읽는다

`/speak` 형식

```json
{"car":"B","text":"출발합니다"}
```

- 응답 `204`. 포트 기본 `8090`(환경변수로 바꿀 수 있게).
- 에뮬레이터 안에서 `10.0.2.2`는 그 에뮬레이터를 돌리는 맥이다. `http://`라서 앱의 `usesCleartextTraffic="true"`가 필요하다(서버 `ws://`와 같은 설정).
- `car`는 맥 한 대에 차 두 대를 띄울 때 어느 차의 소리인지 구분용이다.

## 앱 설정값

| 이름 | 예 | 설명 |
|---|---|---|
| `VEHICLE_ID` | `A` / `B` | 서버 접속 ID |
| `SERVER_URL` | `ws://10.0.2.2:8080/ws/` | 현장은 AWS 주소 |
| `VOICE_ENGINE` | `HOST` / `PLEOS` | 시연은 `HOST` |
| `BRIDGE_URL` | `http://10.0.2.2:8090` | `VOICE_ENGINE = HOST`일 때 |
| `ADDITIONAL_ON` | `true` / `false` | Additional 메시지를 화면에 띄울지. 꺼도 ack는 보낸다 |
