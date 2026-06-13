# TeslaMirror

안드로이드 화면을 테슬라 차량 디스플레이로 미러링하는 개인용 앱.
내비게이션 앱(T맵, 카카오내비, 네이버지도) 화면을 테슬라 브라우저에 띄우는 것을 목표로 합니다.

## 동작 방식

```
[Android 폰]                              [Tesla]
  화면 → MediaProjection                   브라우저
       → ImageReader/MediaCodec            ↓
       → Ktor HTTP 서버 (포트 8080)  ←  Wi-Fi 핫스팟
       → MJPEG 또는 H.264/WebSocket
```

폰에서 핫스팟을 켜고, 테슬라가 그 핫스팟에 접속한 뒤,
테슬라 브라우저에서 폰의 로컬 IP 주소(`http://192.168.x.x:8080`)에 접속하면 됩니다.

## 두 가지 스트리밍 모드

| 모드 | 장점 | 단점 | 권장 |
|------|------|------|------|
| **MJPEG** | 텍스트 선명, 모든 테슬라 호환, 구현 단순 | 대역폭 큼, 30fps 어려움 | 내비, MCU2 |
| **H.264** | 저지연, 30fps 가능, 대역폭 효율 | WebCodecs 지원 브라우저 필요 (MCU3 권장) | 동영상 미러링 |

내비 용도로는 **MJPEG 15fps** 가 사실 더 좋습니다. 텍스트가 흐려지지 않고, 분기점이 자주 변하지 않아 프레임레이트가 낮아도 문제없습니다.

## 빌드 방법

### 필요 환경
- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17
- 안드로이드 폰 (API 26 / Android 8.0 이상)

### 빌드 단계
1. Android Studio에서 이 폴더(`TeslaMirror`)를 Open
2. 우측 상단 디바이스에 폰을 USB 디버깅으로 연결
3. ▶ Run 버튼 클릭

또는 커맨드라인:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> 처음 빌드 시 Gradle Wrapper가 없으면 Android Studio에서 자동 생성됩니다.
> 또는 `gradle wrapper --gradle-version 8.10`으로 직접 만들어도 됩니다.

## 사용 방법

### 사전 설정 (한 번만)
1. 폰 설정 → **개인용 핫스팟** 활성화 (SSID/비밀번호 메모)
2. 테슬라 차량에서 Wi-Fi 메뉴 → 폰 핫스팟에 연결
3. **연결된 SSID를 길게 눌러** "Remain connected in Drive" **체크**
   - 이걸 안 하면 주행 중 자동으로 끊어집니다
4. 테슬라 브라우저 열기 (V12+ 풀스크린 가능)

### 매번 사용 시
1. 폰: 핫스팟 켜기 → TeslaMirror 앱 실행
2. 모드 선택 (MCU3이면 H.264, 아니면 MJPEG)
3. **미러링 시작** 버튼 → "이 앱이 화면을 캡처합니다" 시스템 다이얼로그에서 **시작** 누름
4. 앱 화면에 표시되는 URL을 테슬라 브라우저 주소창에 입력
   - 보통 `http://192.168.43.1:8080` 또는 `http://192.168.49.1:8080`
5. 폰에서 내비 앱 실행 → 테슬라 화면에 그대로 표시

### 가로모드 팁
- T맵/카카오내비를 가로 고정으로 쓰면 테슬라 와이드 화면에 꽉 찹니다
- 폰 자체를 가로로 누이거나, 내비 앱 설정에서 "가로 화면" 옵션을 켜세요

## 알려진 제약

- **DRM 보호 콘텐츠**(Netflix, Disney+, Prime Video)는 검은 화면으로 캡처됩니다. MediaProjection 한계라 우회 불가.
- **주행 중 영상 재생** 자체는 테슬라 정책 적용 (브라우저 내 영상은 일부 제한)
- **테슬라 브라우저 V12 미만**은 풀스크린이 어색할 수 있습니다.
- **MCU2(구형 Atom)는 H.264 60fps 부담**: H.264 모드를 쓰더라도 720p로 제한 권장.

## 프로젝트 구조

```
app/src/main/kotlin/com/example/teslamirror/
├── MainActivity.kt          # Compose UI, 권한 요청, 시작/중지 버튼
├── ScreenCaptureService.kt  # Foreground service, 전체 라이프사이클 관리
├── capture/
│   └── MjpegCapturer.kt     # ImageReader → Bitmap → JPEG (MJPEG 모드)
├── encoder/
│   └── H264Encoder.kt       # MediaCodec H.264 인코더 (H.264 모드)
└── server/
    ├── MirrorServer.kt      # Ktor 서버 (HTTP/WebSocket)
    └── ViewerHtml.kt        # 테슬라 브라우저용 HTML/JS 뷰어
```

## 자동 업데이트 & 릴리스

앱은 실행 시 GitHub Releases의 최신 버전을 조회하고, 설치된 버전보다 높으면
다이얼로그로 알리고 APK를 받아 바로 설치합니다(하단 "업데이트 확인" 버튼으로 수동 확인도 가능).

> ⚠️ **자동 설치는 모든 릴리스 APK가 동일한 키로 서명돼야 동작합니다.** 서명이 다르면
> 안드로이드가 "앱이 설치되지 않음"으로 거부합니다. 그래서 릴리스 빌드는 아래 release 키스토어로 서명합니다.
> 처음 한 번은 Android Studio 디버그 빌드를 **삭제**하고 GitHub 릴리스 APK를 새로 설치하세요.

### 1) release 키스토어 한 번 생성 (로컬, 1회)

```bash
keytool -genkeypair -v -keystore release.keystore -alias teslamirror \
  -keyalg RSA -keysize 2048 -validity 10000
```
- 만든 `release.keystore` **파일과 비밀번호는 안전하게 보관**하세요. 잃어버리면 더 이상 같은 키로 업데이트할 수 없습니다.
- 이 파일은 `.gitignore`에 의해 커밋되지 않습니다.

### 2) GitHub 저장소 Secrets 등록 (Settings → Secrets and variables → Actions)

| Secret 이름 | 값 |
|------|------|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 결과 (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))`) |
| `KEYSTORE_PASSWORD` | 키스토어 비밀번호 |
| `KEY_ALIAS` | `teslamirror` |
| `KEY_PASSWORD` | 키 비밀번호 (보통 키스토어와 동일) |

### 3) 새 버전 배포 = 태그 push

버전은 **태그에서 자동 주입**되므로 `build.gradle.kts`를 손댈 필요 없습니다.

```bash
git tag v0.2.0
git push origin v0.2.0
```

→ GitHub Actions가 서명된 `TeslaMirror-v0.2.0.apk`를 빌드해 Release에 첨부 →
폰의 앱이 다음 실행(또는 "업데이트 확인") 시 이를 감지해 설치 안내.

### 로컬에서 릴리스 APK 직접 빌드 (선택)

`app/keystore.properties` 생성 후 `./gradlew assembleRelease`:
```properties
storeFile=../release.keystore
storePassword=...
keyAlias=teslamirror
keyPassword=...
```
(이 파일도 `.gitignore` 처리됨)

## 향후 개선 아이디어

- [ ] 음성 안내를 별도 트랙으로 송출 (현재는 폰 블루투스 → 차량 스피커 의존)
- [ ] 테슬라 화면 터치를 폰으로 되돌리는 원격 제어 (AccessibilityService.dispatchGesture)
- [ ] 내비 앱별 자동 가로 회전 프리셋
- [ ] 비트레이트 적응형 조정 (혼잡 시 fps 자동 감소)
- [ ] H.264 모드에서 fMP4 + MSE fallback (WebCodecs 미지원 브라우저용)
