# VpnService 게이트웨이 (테슬라 LAN 격리 우회) — 진행 상태

> 브랜치: `feature/vpn-gateway` · 최종 갱신: 2026-07-18 · 상태: **순수 앱 가로채기 소진 (확정)**

## 목표
테슬라 브라우저로 폰 화면 미러링. 브라우저는 **사설 IP 차단** + 핫스팟에서도
**로컬 WebRTC UDP 불가**(실차). 가짜/가상 공인 계열 IP로 로컬 경로를 만드는 게 목표.

## 데스크 최종 실측 (S936N / Android 16 / 비루트)

조건: PC=`10.185.144.100` · 폰 AP=`10.185.144.171` · 게이트웨이=폰 · 핫스팟 경로 정상.

| # | 시도 | 결과 |
|---|------|------|
| 1 | PC → **실제 AP** UDP/TCP | ✅ 수신 (`PROBE RECV[C-ap]`, HTTP 200) |
| 2 | ROUTE_ONLY `203.0.113.7` (미소유+route) | ❌ tun `rx=0` |
| 3 | OWN `203.0.113.7` (이전) | ❌ 소켓·tun 0 |
| 4 | **OWN_CGNAT `100.99.9.9`** (상용 TeslaMirror FAQ IP) + TCP:3333 | ❌ PC→가상IP HTTP 타임아웃 · UDP 미수신 · `tunRx=0` |
| 4b | 동일 세션 **폰 자체** → `100.99.9.9:3333` | ✅ HTTP 200 (소유·로컬 서빙은 됨) |
| 5 | 동일 세션 PC→**실제 AP**:3333 | ✅ HTTP 200 (대조군) |
| 6 | `pm grant MANAGE_TEST_NETWORKS` | ❌ `prot=signature` — 변경 불가 |
| 7 | Shizuku/`adb shell` `ip`/`iptables` | ❌ root 필요 (이전) |

### 확정 결론
1. 핫스팟·앱 소켓·PC 라우팅은 **정상**.
2. 클라이언트가 **가상/가짜 IP**로 보낸 트래픽은 이 기기에서 **앱까지 배달되지 않음**
   (UDP·TCP 동일, TEST-NET·CGNAT 동일).
3. 상용 앱이 FAQ에 적은 `100.99.9.9` **소유 방식만으로는 이 갤럭시에서 재현 실패**.
4. **순수 앱 `VpnService` 가로채기 = 소진.**

## 상용/경쟁 앱에서 알아낸 것 (자동 조사)

### hustmobile TeslaMirror (Play: `com.hustmobile.teslamirror`)
- FAQ: Android 가상 IP **`100.99.9.9`**, MJPEG `http://100.99.9.9:3333`
- iOS: **`240.3.3.3`**
- 주장: 공개 VPN 서버 없음, 핫스팟 LAN만
- **단, H.264 모드는 `https://TSL6.com`** → DNS = **Cloudflare 공인 IP**
  (`104.21.x`, `172.67.x`). 로컬 전용 주장과 별개로 **공개 도메인 경로 존재**.
- `tslamirror.com` A 레코드 = `240.3.3.3` (가상 IP를 DNS에 박음)

→ 최신 H.264는 **공개 HTTPS(CF) 경유**일 가능성 큼. MJPEG 가상 IP 경로가
  삼성/Android 16에서 실제로 되는지는 미검증(우리 재현은 실패).

### Tesor (arter97, `com.arter97.tesor`)
- 스토어: 로컬 VpnService, 서버 VPN 없음, Shizuku 언급 사례 있음
- 공개 매뉴얼/APK 상세 메커니즘 **미확보** (사이트 타임아웃)
- 폰에 미설치 → 패킷 관측 불가

### MANAGE_TEST_NETWORKS / TestNetworkManager
- 권한 **`prot=signature`** (시스템 서명만)
- `adb pm grant` **불가** → “1회 ADB로 특수권한” 경로 **이 권한으로는 막힘**

## 남는 선택지

| # | 옵션 | 자동 검증 | 비고 |
|---|------|-----------|------|
| A | **공개 경로 미디어** (CF Worker/도메인, TSL6 유사) | 시그널링 워커 이미 있음 | 지연↑, 내비 타협 필요 |
| B | Tesor/상용 앱 **실설치 후 PCAP·후보 관측** | 사용자 설치 필요 | 진짜 메커니즘 확정 |
| C | 루팅 + `ip addr`/NAT | 비희망 | 기술적으로 확실 |
| D | 프로젝트 전제 재검토 | — | 브라우저 미러링 유지 여부 |

**권장 다음 (코드 반영됨, 실차 검증 대기): 인터넷 ICE(STUN)**  
- 앱 기본: 「인터넷 ICE (실차용)」 ON → Google STUN + 사설 후보 제거  
- 워커 뷰어: `iceServers` STUN 추가 (`cloudflare/worker.js` — **재배포 필요**)  
- 지연은 로컬보다 큼. 실차에서 연결·RTT 측정 후 TURN/품질 타협 여부 결정.  
- 로컬 가로채기(VpnService)는 이 기기 비루트로 **종료**.

## 재시도 금지
- 순수 앱 `addAddress` / `addRoute` only 가로채기 (TEST-NET·CGNAT 포함)
- `MANAGE_TEST_NETWORKS` adb grant
- Shizuku 셸 `ip`/`iptables`

## 프로브 재현
```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.teslamirror.debug/com.example.teslamirror.MainActivity \
  -a com.example.teslamirror.START_VPN_PROBE
# PC on hotspot:
.\scripts\vpn-probe.ps1
adb logcat -s GatewayVpn:I
```

## 코드 상태 (이 브랜치)
- `GatewayVpnService`: OWN_CGNAT 프로브 (`100.99.9.9`, TCP:3333, UDP 프로브)
- `MainActivity`: `START_VPN_PROBE` adb 인텐트 + UI 「VPN 프로브만」
- `scripts/vpn-probe.ps1`: 대조군 + 가상IP UDP/TCP
