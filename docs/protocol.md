# Car Message 앱 ↔ 서버 규약 (v1)

앱(A차·B차)은 중계 서버와 WebSocket 하나로 연결된다. 서버는 메시지 **코드만** 전달하고,
문구·등급(principal/additional)·TTS 문장은 앱이 [`messages.json`](messages.json)에서 꺼낸다.

## 접속

```
ws://<host>:8080/ws/{vehicleId}
```

- `vehicleId`: 차량 가명 ID. 1차 시연은 `A`, `B`.
- 로컬 개발: 에뮬레이터에서 `ws://10.0.2.2:8080/ws/A`. **서버와 에뮬레이터가 같은 맥일 때만** 된다.
  다른 맥이면 서버 맥의 LAN IP, 시연은 AWS 서버 주소.
- **Android 9+는 암호화 안 된 `ws://`를 기본 차단한다.** 1차는 `AndroidManifest.xml`에 아래 한 줄.
  AWS에 TLS를 붙이면 `wss://`로 바꾸고 지운다.
  ```xml
  <application android:usesCleartextTraffic="true" ...>
  ```
- 접속 = 등록. 별도 hello 메시지 없음.
- 같은 ID로 다시 접속하면 이전 연결은 서버가 닫는다(재접속, close 코드 1000 `replaced`).
  두 앱이 같은 ID를 쓰면 서로 끊어내므로 A·B를 꼭 다르게 설정한다.
- 서버가 15초마다 ping을 보낸다. 앱은 끊기면 재접속한다 (권장: 1s → 2s → 4s … 최대 10s).

## 메시지

모든 메시지는 JSON 텍스트 프레임이고 `type`으로 구분한다. 프레임은 64KB까지 (넘으면 서버가 close 1009로 끊는다).

### 앱 → 서버

**send** — 메시지 보내기

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `type` | `"send"` | O | |
| `id` | string | O | 앱이 만든 UUID. **재전송할 때 같은 id를 쓴다**. 1, 2, 3 같은 카운터 금지 — 앱 재시작 후 예전 id와 겹치면 서버가 "이미 전달됨"으로 보고 전달하지 않는다 |
| `code` | string | O | `messages.json`의 code. `^[A-Z_]{1,40}$` |
| `to` | string | | 받을 차량 ID. 생략하면 나를 뺀 접속 차량 전원 |
| `replyTo` | string | | 답장일 때 원래 메시지 id |

```json
{"type":"send","id":"7f3a2c1e-...","code":"U_TURN"}
```

**ack** — 받은 메시지 확인. `msg`를 받으면 화면에 띄운 직후 바로 보낸다.

```json
{"type":"ack","id":"7f3a2c1e-..."}
```

### 서버 → 앱

**msg** — 메시지 수신

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | string | 보낸 쪽이 만든 id |
| `from` | string | 보낸 차량 ID |
| `code` | string | 메시지 코드. 코드표에 없으면 앱은 무시한다 |
| `replyTo` | string? | 답장이면 원래 메시지 id |
| `sentAt` | number | 서버 수신 시각 (epoch ms) |

```json
{"type":"msg","id":"7f3a2c1e-...","from":"A","code":"U_TURN","sentAt":1762650000000}
```

**status** — 보낸 메시지의 결과 (보낸 쪽에만)

| `state` | `reason` | 앱 화면 (Figma) |
|---|---|---|
| `delivered` | — | 전송 완료 |
| `failed` | `NO_TARGET` 받을 차량 없음 | 전송 실패 → 재전송 |
| `failed` | `TIMEOUT` 3초 내 ack 없음 | 전송 실패 → 재전송 |

```json
{"type":"status","id":"7f3a2c1e-...","state":"failed","reason":"TIMEOUT"}
```

**error** — 형식이 잘못된 메시지. 앱 버그이므로 로그만 남기면 된다.

```json
{"type":"error","id":"7f3a2c1e-...","reason":"BAD_FORMAT"}
```

## 흐름

### 1. 정상

```
A앱            서버             B앱
 │─ send ───────▶│                │
 │  (전송 중)     │─── msg ───────▶│  카드 + TTS
 │               │◀── ack ────────│
 │◀─ status ─────│                │
 │  delivered (전송 완료)
```

### 2. 받을 차량 없음

```
A ─ send ─▶ 서버   (B 미접속)
A ◀─ status failed NO_TARGET ─ 서버      → 전송 실패 → 재전송 창
```

### 3. ack 시간 초과 (3초)

```
A ─ send ─▶ 서버 ─ msg ─▶ B   (ack 안 옴)
            … 3초 …
A ◀─ status failed TIMEOUT ─ 서버
```

받을 차량이 여럿이면 **한 대라도 ack하면 delivered**.

### 4. 재전송 (같은 id)

- 이미 `delivered`된 id → 다시 전달하지 않고 `status delivered`만 다시 보낸다.
- 아직 ack 대기 중인 id → 무시 (결과 status는 한 번 온다).
- `failed`된 id → 새로 보낸 것처럼 다시 전달한다.
- 그래도 같은 id의 `msg`가 두 번 올 수 있으므로, 받는 앱은 최근 id로 중복을 걸러낸다.
  **중복이어도 ack는 보낸다** (화면엔 다시 안 띄움). 안 보내면 보낸 쪽은 계속 `TIMEOUT`을 받는다.

### 5. 답장 (좁은 길 장면)

```
A ─ send {id:"m1", code:"YIELD_PLEASE"} ─▶ 서버 ─ msg ─▶ B
B ─ send {id:"m2", code:"I_YIELD", to:"A", replyTo:"m1"} ─▶ 서버 ─ msg ─▶ A
```

### 6. 연결 끊김

- 끊긴 차량이 보낸 메시지도 서버는 그대로 처리한다 (ack가 오면 delivered로 기록).
- 앱은 재접속 후 "전송 중"이던 메시지를 **같은 id로 재전송**한다.
  그새 도착했으면 `status delivered`가 바로 오고, 아니면 흐름 1~3대로 진행된다.
- 끊긴 차량이 받아야 할 메시지는 `TIMEOUT`으로 보낸 쪽에 알린다.

## 범위 밖 (v1)

인증, 메시지 저장, 주변 차량 검색(GPS 반경), Emergency Chain 릴레이.
`to` 생략 = 전원 전송 규칙은 주변 차량 검색이 들어오면 "반경 내 차량"으로 바뀐다.
