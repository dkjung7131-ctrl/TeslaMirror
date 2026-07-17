# VpnService 게이트웨이 (테슬라 LAN 격리 우회) — 진행 상태

> 브랜치: `feature/vpn-gateway` · 최종 갱신: 2026-07-17 · 상태: **미해결(마지막 변형 검증 대기)**

## 목표
테슬라 인포테인먼트 브라우저를 통해 폰 화면을 미러링. 테슬라 브라우저는 **사설 IP
(10/8·172.16-31/12·192.168/16) 목적지를 차단**하고, Wi-Fi로 폰 핫스팟에 붙어 있어도
**WebRTC UDP를 로컬로 못 보냄**(실차 실측). 그래서 뷰어에게 **가짜 공인 IP**를 후보로
광고하고, 폰이 그 IP로 오는 트래픽을 받아 로컬 WebRTC로 이어주는 게 핵심.

## 토폴로지 (차 = 폰 핫스팟의 클라이언트, 폰 = 게이트웨이)
```
[테슬라 브라우저] --(FAKE_IP로 UDP)--> [폰=게이트웨이/핫스팟] --?--> [폰의 libwebrtc]
                    (사설IP는 차단됨)          여기서 잡아채야 함
```
데스크 검증은 **이 PC를 차 대신** 사용(핫스팟 클라이언트, 게이트웨이=폰, 경로 동일).
`Find-NetRoute 203.0.113.7` → NextHop = 폰(10.110.236.230) 확인.

## 데스크 실측 결과 (2026-07-17, S936N/Android 16, 비루트)

| 시도 | 구성 | 결과 |
|------|------|------|
| 대조군 | PC→**폰 실제 AP IP** 10.110.236.230:9999, 앱 `0.0.0.0:9999` 소켓 | ✅ **수신됨** |
| addAddress 방식 | tun에 `addAddress(FAKE_IP)`+`addRoute(FAKE_IP)`, 소켓 `0.0.0.0`/`FAKE_IP` | ❌ 소켓·tun 어디에도 안 옴 |
| Shizuku 권한 | `adb shell`(=uid 2000) `ip addr add dev swlan0` / `iptables -t nat` | ❌ 둘 다 "must be root" |

**결론(확정):**
- tun에 **소유(addAddress)한** 주소로는 외부 클라이언트 패킷이 앱에 배달 안 됨(로컬 INPUT 처리→드롭).
- 폰이 클라이언트 패킷을 받는 자기 IP는 항상 사설 → 테슬라가 차단. 공인 IP를 실인터페이스에
  붙이려면 **root 필요**. Shizuku 셸 권한으로도 IP추가/NAT 불가.

## 마지막 미검증 변형 (v0.6.4-probe, 검증 대기)
**가설:** FAKE_IP를 `addAddress`로 **소유하지 않고** `addRoute(FAKE_IP/32)`만 건다
(tun 주소는 더미 `10.99.99.2`). 그러면 클라이언트의 FAKE_IP 패킷이 "로컬 INPUT"이 아니라
**"포워딩" 대상**이 되어 라우트 따라 **tun fd로 들어올** 수 있음.
- 되면: `GatewayVpnService.pump()`가 tun에서 읽어 `apAddr:dstPort`(libwebrtc 사설 후보 소켓)로
  릴레이 → 응답을 src=FAKE_IP로 tun 재주입 → 연결 성립 가능.
- 코드: `GatewayVpnService.establish()`가 `addAddress(TUN_ADDR)`+`addRoute(FAKE_IP)`로 변경됨.
  진단: `startProbeSocket()`(0.0.0.0:9999, FAKE_IP:9998) + pump `rx #` 로그.
- **검증 방법(데스크):** 앱에서 미러링 시작→화면공유 취소(VPN만 기동) → PC에서
  `203.0.113.7:9999`로 UDP 프로브 → logcat `GatewayVpn`에 **`rx #`** 뜨면 성공(포워딩→tun 캡처).

## v0.6.4 실패 시 남는 선택지 (순수 앱 VpnService 소진)
1. **Tesor 정확한 메커니즘 역공학** — Shizuku로 접근하는 특정 hidden system API. (조사 에이전트
   1차 실패. Tesor는 Play상 "no root + Shizuku"라는데 Shizuku 셸론 IP/NAT 불가가 확인됨 →
   system_server 측 권한을 쓰는 hidden binder API 추정, 미확인.)
2. **1회성 ADB 특수권한 부여** — `pm grant`로 `MANAGE_TEST_NETWORKS` 등 → `TestNetworkManager`로
   공인 IP 인터페이스 생성해 수신 가능한지. (영구 루팅 아님, 최초 1회 ADB 설정. 미검증.)
3. **루팅** — `ip addr add`/`iptables DNAT`로 즉시 해결되나 사용자가 루팅 비희망.
4. **접근 재검토** — 비루트로 테슬라 브라우저 경유가 불가면 프로젝트 방향 재고.

## 안 되는 것(재시도 금지)
- 순수 앱 VpnService `addAddress(FAKE_IP)` — 확정 불가(위 표).
- Shizuku/adb 셸의 `ip`/`iptables` — root 필요.
- 셀룰러 경유 TURN — 지연 폭증(내비 부적합), 설계상 배제.

## 기타 확정 사실 (별도 이슈, 이미 반영됨)
- WebRTC 네이티브 `dispose()` 금지 → SIGILL 크래시(close()만). memory 참고.
- 뷰어 동시 1개(PeerConnection 1). 12초 미연결 시 재offer(포트 바뀜) — VPN 경로에선 이 churn도
  재검토 필요.
- Android 16: 화면 잠금 시 MediaProjection 강제 종료.
- 갤럭시: 핫스팟+USB 디버깅 동시 사용 시 adb 끊김 잦음 → 무선 디버깅 권장.
