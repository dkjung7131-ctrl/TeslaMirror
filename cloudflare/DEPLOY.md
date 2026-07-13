# Cloudflare Worker 배포 가이드 (접선 서버)

테슬라가 북마크 하나로 "지금 붙어 있는 폰"을 찾아가게 해주는 서버.
전부 무료 플랜으로 동작한다 (하루 10만 요청 / KV 쓰기 1,000회 — 실사용량의 수백 배).

## 1. 계정 만들기

https://dash.cloudflare.com/sign-up 에서 무료 가입 (이메일만 있으면 됨).

## 2. Worker 만들기

1. 대시보드 왼쪽 메뉴 **Workers & Pages** → **Create** → **Create Worker**
2. 이름 입력 (예: `teslamirror`) → **Deploy** (기본 Hello World 상태로 일단 배포)
3. 배포 후 **Edit code** 클릭 → 에디터의 기존 코드를 전부 지우고
   이 폴더의 `worker.js` 내용을 통째로 붙여넣기 → **Deploy**

## 3. KV 스토리지 만들고 연결

1. 왼쪽 메뉴 **Storage & Databases** → **KV** → **Create namespace**
   - 이름: `PHONES`
2. 다시 **Workers & Pages** → 방금 만든 워커 → **Settings** → **Bindings**
   → **Add** → **KV namespace**
   - Variable name: `PHONES` (대문자, 코드와 동일해야 함)
   - KV namespace: 방금 만든 `PHONES` 선택
   → 저장

## 4. 시크릿 설정

폰 앱만 등록할 수 있게 막는 비밀번호. 아무 문자열이나 길게 만들면 된다.

1. 워커 → **Settings** → **Variables and Secrets** → **Add**
   - Type: **Secret**
   - Variable name: `SECRET` (대문자, 코드와 동일해야 함)
   - Value: 원하는 비밀 문자열 (예: 비밀번호 생성기로 20자)
   → 저장 (배포에 반영되는지 확인)
2. 이 값은 나중에 **각 폰의 앱에 그대로 입력**한다. 어딘가에 적어둘 것.

## 5. 확인

- 워커 URL은 `https://<워커이름>.<계정서브도메인>.workers.dev` 형태.
  워커 화면 상단에 표시된다.
- 브라우저에서 그 URL을 열었을 때 **"등록된 폰이 없습니다"** 페이지가 나오면 성공.
  (아직 폰이 등록 전이니 정상)

## 6. 앱 연결

- `RendezvousUpdater.kt`의 `WORKER_URL` 상수를 실제 워커 URL로 교체 후 릴리스.
- 각 폰: 앱 설치 → 시크릿 입력 → 저장. 이후 자동.
- 테슬라 북마크: 워커 URL 하나면 끝 (모든 차량 공통).
