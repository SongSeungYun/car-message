# 차량 메시지 시연 앱

현대차 공모전의 차량 간 송신 화면과 수신 알림을 Kotlin Android UI와 Java 상태 처리 코드로 구현한 프로젝트입니다. [Figma 시안](https://www.figma.com/design/JL8VTuKsb84VYhsgHhTJP0/HyundaiProject2026?node-id=97-4538)

## 앱 화면 확인

1. `app/build/outputs/apk/debug/app-debug.apk`를 Android 9 이상 휴대폰이나 태블릿에 복사합니다.
2. 기기의 파일 앱에서 APK를 열어 설치합니다. 설치 권한 안내가 뜨면 APK를 연 앱에 설치 권한을 허용합니다.
3. 앱 목록에서 `차량 메시지 시연`을 열고 오른쪽 위 `시연 ⋮`에서 Flow 1, 2, 3을 각각 선택합니다.

PC에서는 Android Studio의 Device Manager에서 가상 기기를 실행하고, 에뮬레이터 창에 APK를 끌어 놓아 설치할 수 있습니다. 프로젝트를 수정하며 확인하려면 Android Studio에서 이 `fe` 폴더를 열고 Gradle 동기화 후 `app`과 실행 기기를 선택해 Run을 누릅니다.

## 소스에서 빌드

빌드에는 JDK 17과 Android SDK 35가 필요합니다. Windows 터미널에서 이 폴더를 열고 다음 명령을 실행합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

연결된 Android 기기나 실행 중인 에뮬레이터에 APK를 설치하려면 Android SDK의 `platform-tools`를 `PATH`에 추가하고 다음 명령을 실행합니다.

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.hyundaiproject.messaging/.MainActivity
```

## 시연 순서

| 흐름 | 실행 방법 |
| --- | --- |
| Flow 1 · 메시지 송신 | `시연` 메뉴에서 `Flow 1 · 메시지 송신`을 선택하면 로고 자리 표시가 있는 홈이 열립니다. `principal messages`는 비상등 메시지 목록, 홈의 `additional messages`는 좌회전 메시지 목록으로 연결됩니다. 비상등 목록의 `additional messages` 카드로 6~10번 메시지를 열 수 있습니다. |
| Flow 2 · 경고 수신 | `시연` 메뉴에서 `Flow 2 · 경고 수신`을 선택하면 빨간 `전방 특이사항 발생` 경고가 뜹니다. |
| Flow 3 · 일반 알림 수신 | `시연` 메뉴에서 `Flow 3 · 일반 알림 수신`을 선택하면 발신자 표시와 `유턴합니다` 알림이 뜹니다. |
| Flow 1의 전송 실패와 재전송 | Flow 1을 연 뒤 `다음 전송 결과 · 실패`를 선택하고 메시지를 보냅니다. 3초 뒤 재전송 확인이 표시됩니다. `확인`을 누르면 같은 메시지를 다시 전송합니다. 최초 전송 확인창과 재전송 확인창에서 `취소`를 누르면 직전 메시지 목록으로 돌아갑니다. 다음 요청의 기본 응답은 성공입니다. |
| Flow 1의 음성 인식 | `음성 인식 시연`으로 상단 표시와 인식 문구, 메시지 강조, 확인 화면을 순서대로 봅니다. 전송 실패 안내에서 `음성 재전송 시연`을 선택하면 음성 이벤트로 재전송 확인을 띄울 수 있습니다. |

세 플로우는 각각 `시연` 메뉴에서 시작할 수 있습니다. 메뉴에서 다른 플로우를 고르면 진행 중인 시연을 종료하고 해당 시작 화면을 엽니다. Flow 2와 3은 비상등 목록 위에 알림을 표시하며, 알림은 탭하거나 자동 닫힘 시간이 지나면 사라집니다.

전송 완료 안내는 3초 뒤 홈으로 돌아갑니다. 빨간 경고는 표시되는 4초 동안 두 강조 효과를 번갈아 보여주며, 일반 알림은 3초 동안 표시됩니다. Android API 28~30에서는 배경을 어둡게 하고 API 31 이상에서는 흐림 효과를 함께 적용합니다. 홈의 회색 `설정` 카드는 후속 화면 시안이 없어 표시만 합니다.

## 플로우 디버깅

Android Studio에서 `app`을 Debug로 실행하고, `시연`에서 확인할 플로우를 선택합니다. `MessagingController.java`의 `publish()`에 브레이크포인트를 두면 공통 화면 상태인 `page`, `phase`, `alert`를 볼 수 있습니다.

- Flow 1은 `resolve()`에서 전송 성공·실패를, `openRetry()`에서 재전송 진입을 확인합니다.
- Flow 2는 `receive()`에서 `CRITICAL` 수신을, `toggleAlertGlow()`에서 경고 강조 상태 전환을 확인합니다.
- Flow 3은 `receive()`에서 `NOTICE` 수신을, `dismissAlert()`에서 닫힘을 확인합니다.

## 연동 위치

- `MessageGateway.java`는 송신과 수신을 연결하는 인터페이스입니다. `send()`에는 메시지 코드, 문구, 요청 ID가 전달됩니다. 백엔드가 정한 전송 성공 시점에 `SUCCESS`를 돌려주고, 실패 시 `FAILURE`를 돌려주세요. 앱은 8초 동안 응답이 없을 때도 실패로 처리합니다. 재전송은 같은 요청 ID를 사용하므로 서버에서도 이 ID로 중복 전송을 막을 수 있습니다.
- `subscribe()`의 수신 이벤트에는 `CRITICAL` 또는 `NOTICE`, 문구, 필요한 경우 가린 발신자 표시를 전달합니다. 실제 연동에서는 `DemoMessageGateway`를 서버 어댑터로 교체합니다.
- 실제 음성 인식 서비스에서는 인식 시작과 종료, 인식 결과를 `MessagingController.startListening()`, `stopListening()`, `recognize()`에 메인 스레드에서 전달합니다. 시연 앱은 해당 이벤트를 메뉴에서 발생시킵니다.
- 앱 화면 바깥의 시스템 알림, 실제 차량 신호, 마이크와 서버는 연결 전입니다. 현재 `시연` 메뉴로 세 흐름을 재현할 수 있습니다.
