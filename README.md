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
├── fe/                       Pleos Connect 앱 (Android, Kotlin) — 앱 담당이 생성 예정
├── be/                       메시지 중계 서버 (Kotlin, Ktor)
│   └── src/main/kotlin/carmessage/
│       ├── Application.kt    서버 시작, /ws/{vehicleId} 접속 처리
│       ├── Protocol.kt       앱과 주고받는 메시지 형식
│       └── Relay.kt          중계 로직 (ack 3초 대기, 재전송, 답장)
├── docs/
│   ├── protocol.md           앱 ↔ 서버 규약 ← 앱 개발 전에 필독
│   └── messages.json         메시지 코드표 (코드 → 문구, 트리거)
├── infra/                    서버 배포 (예정)
└── apk/                      제출용 release APK
```

`fe/`와 `be/`는 서로 독립된 Gradle 프로젝트다. Android Studio에서는 저장소 루트가 아니라 `fe/`를 연다.

## 개발 환경

| 도구 | 버전 | 필요한 사람 | 비고 |
|---|---|---|---|
| Git | — | 전원 | |
| JDK | **21 이상** | 서버를 로컬에서 띄우는 사람 | Android Studio 내장 JDK로 대체 가능 (아래) |
| Android Studio | 최신 | 앱 | |
| Pleos Connect Emulator | 2.1.0 (AAOS, API 34) | 앱 | Apple Silicon 맥은 ARM64 이미지 |
| Android 프로젝트 설정 | minSdk 28 이상, Gradle 8 이상 | 앱 | 공모전 요건 |

Gradle·Kotlin·Ktor는 따로 설치하지 않는다. `./gradlew`가 처음 실행될 때 알아서 받는다 (첫 실행만 몇 분).

### Pleos 개발자 등록 (앱 담당)

1. [developers.pleos.ai](https://developers.pleos.ai) 가입 → Pleos Playground에서 프로젝트 생성
2. Vehicle SDK(방향지시등 조회)·NaviHelper SDK는 신청 없이 바로 사용 가능
3. TTS·STT SDK는 아직 "준비 중" → 1차는 안드로이드 기본 `TextToSpeech` 사용
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

### 3. 앱에서 접속

- 에뮬레이터 → 같은 맥에서 띄운 서버: `ws://10.0.2.2:8080/ws/A` (B차는 `/ws/B`)
- 맥북 2대로 시연할 때: AWS 서버 주소
- `AndroidManifest.xml`에 아래 설정이 필요하다. 없으면 Android 9+가 `ws://` 접속을 막는다.
  ```xml
  <application android:usesCleartextTraffic="true" ...>
  ```

### 4. 에뮬레이터 1대로 테스트하기

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
- `msg`를 받으면 화면에 띄운 직후 `ack`를 보낸다. **같은 메시지가 두 번 와도 ack는 보낸다** (화면엔 한 번만)
- 끊기면 재접속한다 (1s → 2s → 4s … 최대 10s). "전송 중"이던 메시지는 **같은 id로** 다시 보낸다
- 문구는 서버가 아니라 `messages.json`에서 꺼낸다

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

- Pleos Connect Emulator 2대 (A차, B차) — 같은 APK
- 서버 주소: 위 [앱에서 접속](#3-앱에서-접속) 참고
- TODO: Virtual Console 설정, 필요한 권한

## APK

`apk/` — TODO: 파일명 `팀명_앱이름_release.apk`
