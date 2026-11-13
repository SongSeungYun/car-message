# Car Talk 앱

현대차 공모전의 차량 간 송신 화면과 수신 알림을 Kotlin Android UI와 Java 상태 처리 코드로 구현한 프로젝트입니다. [Figma 시안](https://www.figma.com/design/JL8VTuKsb84VYhsgHhTJP0/HyundaiProject2026?node-id=97-4538)

## 앱 화면 확인

1. `app/build/outputs/apk/debug/app-debug.apk`를 Android 9 이상 휴대폰이나 태블릿에 복사합니다.
2. 기기의 파일 앱에서 APK를 열어 설치합니다. 설치 권한 안내가 뜨면 APK를 연 앱에 설치 권한을 허용합니다.
3. 앱 목록에서 `Car Talk`을 열고 오른쪽 위 `시연 ⋮`에서 확인할 Flow를 선택합니다.

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

## 시연 메뉴

| 흐름 | 실행 방법 |
| --- | --- |
| Flow 1 · 비상등 작동 중 | 비상등 메시지 목록을 엽니다. 목록 안의 `additional messages` 카드에서 6~10번 메시지를 볼 수 있습니다. |
| Flow 2 · 좌회전 방향지시등 작동 중 | 좌회전 메시지 목록을 엽니다. |
| Flow 3 · 경고 수신 | 비상등 목록 위에 빨간 `전방 특이사항 발생` 경고를 표시합니다. |
| Flow 4 · 음성 인식 시연 | 비상등 목록에서 `1번 메시지 보내줘`를 인식한 뒤 전송 확인창을 엽니다. |
| Flow 5 · 다음 전송 결과 - 성공 | 다음 메시지 전송 1회를 성공으로 설정합니다. |
| Flow 6 · 다음 전송 결과 - 실패 | 다음 메시지 전송 1회를 실패로 설정합니다. |
| Flow 7 · mainpage | 메인페이지를 엽니다. 메시지 카드는 화면 표시용입니다. |

Flow 5와 6은 화면을 이동해도 다음 전송까지 적용됩니다. 예를 들어 메인페이지에서 Flow 6을 선택한 뒤 Flow 1이나 2를 열어 메시지를 보내면 실패 안내가 뜹니다. 실패 안내 3초 뒤 재전송 확인이 표시되며, `확인`을 누르면 같은 메시지를 다시 전송합니다. 전송 결과는 한 번 사용하면 기본값인 성공으로 돌아갑니다.

전송 완료 안내는 3초 뒤 닫히고 해당 메시지 목록으로 돌아갑니다. 빨간 경고는 표시되는 4초 동안 두 강조 효과를 번갈아 보여주며, 탭하면 바로 닫힙니다. Android API 28~30에서는 배경을 어둡게 하고 API 31 이상에서는 흐림 효과를 함께 적용합니다.

## 플로우 디버깅

Android Studio에서 `app`을 Debug로 실행하고, `시연`에서 확인할 플로우를 선택합니다. `MessagingController.java`의 `publish()`에 브레이크포인트를 두면 공통 화면 상태인 `page`, `phase`, `alert`를 볼 수 있습니다.

- Flow 1과 2는 `resolve()`에서 전송 성공·실패를, `openRetry()`에서 재전송 진입을 확인합니다.
- Flow 3은 `receive()`에서 `CRITICAL` 수신을, `toggleAlertGlow()`에서 경고 강조 상태 전환을 확인합니다.
- Flow 4는 `recognize()`에서 음성 명령이 어느 메시지를 선택하는지 확인합니다.

## 연동 위치

- `MessageGateway.java`는 송신과 수신을 연결하는 인터페이스입니다. `send()`에는 메시지 코드, 문구, 요청 ID가 전달됩니다. 백엔드가 정한 전송 성공 시점에 `SUCCESS`를 돌려주고, 실패 시 `FAILURE`를 돌려주세요. 앱은 8초 동안 응답이 없을 때도 실패로 처리합니다. 재전송은 같은 요청 ID를 사용하므로 서버에서도 이 ID로 중복 전송을 막을 수 있습니다.
- `subscribe()`의 수신 이벤트에는 `CRITICAL` 또는 `NOTICE`, 문구, 필요한 경우 가린 발신자 표시를 전달합니다. 실제 연동에서는 `DemoMessageGateway`를 서버 어댑터로 교체합니다.
- 실제 음성 인식 서비스에서는 인식 시작과 종료, 인식 결과를 `MessagingController.startListening()`, `stopListening()`, `recognize()`에 메인 스레드에서 전달합니다. 시연 앱은 해당 이벤트를 메뉴에서 발생시킵니다.
- 앱 화면 바깥의 시스템 알림, 실제 차량 신호, 마이크와 서버는 연결 전입니다. 앱의 `시연` 메뉴에서 시나리오를 실행할 수 있습니다.
