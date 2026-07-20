# 앱 모드 인계 (상태 요약)

> 갱신: 2026-07-21 · **구현 완료 → 사용 가이드는 `docs/app-mode.md`**

## 한 줄

scrcpy 가상 디스플레이 + **전체화면과 동일** WebRTC JPEG/워커/인터넷 ICE.  
터치 역제어·런처 홈·파이 제스처·화면 꽉 채우기 포함.

## 완료된 것 (이 브랜치 / v0.7.0)

- [x] AppCastService → AppWebRtcSession (MirrorServer 테슬라 경로 폐기)
- [x] H.264 → JPEG 재인코딩 (`H264ToJpeg`)
- [x] 터치/스크롤/텍스트(클립보드 붙여넣기)·Back/Home
- [x] 앱 선택 UI 제거 → `AppLauncherActivity` 홈 (내비 앱 우선)
- [x] ADB 자동 연결 + 무선 디버깅 설정 점프
- [x] 파이 컨트롤 (가장자리 2초, 런처에서는 비활성)
- [x] 뷰포트 학습 + 꽉 채우기 CSS (`worker.js`)
- [x] 연결 고착 시 자동 재연결

## 전체화면

부모님용. `ScreenCaptureService` / `WebRtcSession`.  
앱 모드와 **워커 뷰어는 공유**하나, 모드 선택만 전체화면이면 앱 모드 경로 미사용.

## 남은 선택 과제 (필수 아님)

- 실차에서 꽉 채우기 비율 재학습
- 릴리즈 노트/README 아키텍처 다이어그램 현행화 (구 Ktor 로컬 서버 설명 정리)
- Shizuku 등 무선 디버깅 대체 (미검토)

구 Phase 계획·VPN 소진 기록은 히스토리용으로만 참고. 실사용은 `docs/app-mode.md`.
