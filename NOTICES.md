# Third-party components

TeslaMirror의 "앱 모드"(헤드리스 캐스트)는 다음 오픈소스를 번들/사용합니다.

- **scrcpy-server** (Genymobile/scrcpy) — Apache-2.0.
  `app/src/main/assets/scrcpy-server.jar` (v4.1). 선택한 앱을 가상 디스플레이에서
  렌더링하고 H.264로 인코딩하는 서버측 컴포넌트.
- **libadb-android** (MuntashirAkon) — GPL-3.0-or-later / Apache-2.0 듀얼 라이선스.
  무선 디버깅 페어링 및 ADB 셸 연결.
- **sun-security-android** (MuntashirAkon) — X509 인증서 생성.
- **Conscrypt** (Google) — Apache-2.0. TLS.

개인 사용 목적의 앱이며 Play Store 배포 계획은 없습니다.
