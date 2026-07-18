# 앱 모드 인계 (App Cast / scrcpy → WebRTC)

> 작성: 2026-07-18 · 대상: Claude / 후속 작업자  
> 전체화면 모드 **실차 성공** 이후 다음 작업용.

## 한 줄 목표

선택한 앱을 **헤드리스(scrcpy 가상 디스플레이)** 로 띄우고,  
**전체화면과 같은 경로**(Cloudflare Worker 시그널링 + WebRTC 데이터채널 + **공인 ICE**)로  
테슬라/노트북 브라우저에 보여 준다. 터치 역제어 포함.

## 전체화면에서 이미 끝난 고비 (다시 하지 말 것)

| 항목 | 결과 |
|------|------|
| 테슬라 사설 IP / LAN WebRTC | 차단·격리 확정 |
| VpnService 가짜 IP 가로채기 | S936N 비루트 **실패·소진** (`docs/vpn-gateway-status.md`) |
| **공인 ICE(STUN) + 워커** | 노트북·**실차 성공** (v0.6.4) |
| 뷰어 | `cloudflare/worker.js` — JPEG 캔버스, 좌중우·시계 |

→ 앱 모드도 **같은 전송 스택**을 써야 함.  
→ 로컬 `http://10.x:8080` / 사설만 쓰는 경로는 **테슬라 불가** (노트북 핫스팟 사설 전용 디버그는 가능하나 실차 검증 아님).

## 노트북 테스트 전략 (사용자 의도)

1. 폰 핫스팟 + 노트북을 핫스팟 클라이언트로  
2. 앱: **「테슬라 동일 경로 (공인만)」 ON**  
3. Chrome: 워커 URL (`/?id=<deviceId>`)  
4. 여기서 되면 → 테슬라도 동일 조건으로 될 가능성 큼 (이미 전체화면에서 검증됨)

**주의:** 「공인만」 OFF(사설 host)로 노트북만 붙이는 건 테슬라 검증이 아님.

## 앱 모드 현재 코드 (골격만)

```
MainActivity (앱 모드 UI, 앱 선택, ADB 페어링)
  → AppCastService
       → ScrcpyController (내장 ADB + scrcpy-server.jar)
       → MirrorServer (Ktor, 로컬 H.264 WebSocket)  ← 테슬라 불가
  → AppViewerHtml (로컬 뷰어, WebCodecs/MSE)
```

| 파일 | 역할 |
|------|------|
| `AppCastService.kt` | FGS, scrcpy + MirrorServer 기동 |
| `scrcpy/ScrcpyController.kt` | server push, 소켓, 프레임/컨트롤 |
| `scrcpy/ScrcpyProtocol.kt` | 터치/키 인코딩 |
| `adb/AdbManager.kt` | 무선 ADB 페어링/연결 |
| `server/MirrorServer.kt` | 로컬 WS H.264 (폐기 후보·재사용 비권장 for Tesla) |
| `server/AppViewerHtml.kt` | 로컬 뷰어 HTML |

**미연동:** WebRtcSession / RendezvousUpdater / 워커 뷰어.

## 권장 구현 순서 (단계별, 노트북 검증)

### Phase 1 — 영상만 (최소 성공)

1. `AppCastService`에서 **MirrorServer 대신** 전체화면과 동일한 시그널링+WebRTC 경로 사용.
2. scrcpy H.264 프레임을 바로 넣기보다 **1차는 JPEG 재인코딩** 권장  
   (워커 뷰어가 JPEG 데이터채널 전용, 테슬라 WebCodecs 불확실).
   - 옵션 A: scrcpy 끄고 virtual display 캡처 → 기존 MjpegCapturer 패턴 (복잡)  
   - 옵션 B: H.264 디코드 → Bitmap → JPEG → 데이터채널 (CPU↑)  
   - 옵션 C: 데이터채널에 H.264 바이너리 + 워커에 디코더 추가 (테슬라 호환 리스크)
3. **옵션 C보다 B 또는 “scrcpy 대신 MediaProjection은 앱 모드 취지에 안 맞음”**  
   실용 추천: scrcpy surface/프레임 파이프라인 유지 + **JPEG 변환 후 기존 `WebRtcSession` 송신 로직 재사용**.
4. offer 게시·answer 폴링·`internetPath=true` 기본 = 전체화면과 동일.
5. 노트북: 워커 URL로 화면 보이면 Phase 1 완료.

### Phase 2 — 터치 역제어

1. 워커 뷰어에 터치 좌표 전송 (데이터채널 역방향 또는 별도 메시지 타입).
2. 폰에서 scrcpy control 소켓으로 `ScrcpyProtocol.injectTouch` 등 전달.
3. 좌표 스케일: 뷰어 표시 영역 ↔ scrcpy video 해상도 (`DISPLAY_WIDTH/HEIGHT` 1280×800).

### Phase 3 — 다듬기

- 앱 전용 해상도/fps UI
- ADB 미연결 시 안내
- 전체화면과 동시 실행 금지
- dispose() 금지 규칙 유지 (WebRTC)
- 릴리스 버전 bump + GitHub Release

## 절대 하지 말 것

- 테슬라용으로 다시 사설 IP Ktor 서버 의존
- VpnService 가로채기 재탕 (데스크 소진)
- WebRTC `dispose()` 호출
- 노트북 사설-only 성공을 “테슬라 완료”로 착각

## 사용자 환경

- 갤럭시 S936N / Android 16, 비루트
- 핫스팟 + 테슬라 Wi‑Fi, Remain connected in Drive
- 릴리스 v0.6.4 (`com.example.teslamirror`), 시크릿 SharedPreferences
- 워커: `https://teslamirror.dkjung7131.workers.dev`
- 개발: Windows, USB/무선 ADB

## 검증 체크리스트 (노트북)

- [ ] 무선 ADB 페어링 후 앱 모드 시작 성공 (logcat `AppCastService` / `ScrcpyController`)
- [ ] 워커에 offer 게시 (GET `/offer?id=...` 200)
- [ ] Chrome 워커 URL에서 프레임 표시
- [ ] 「테슬라 동일 경로」ON 상태에서 연결 (사설 후보 없음)
- [ ] (Phase 2) 클릭/드래그가 앱에 반영

## 관련 문서

- `CLAUDE.md` — 프로젝트 컨텍스트
- `docs/vpn-gateway-status.md` — VPN 실패 확정 기록
- 릴리스: https://github.com/dkjung7131-ctrl/TeslaMirror/releases/tag/v0.6.4
