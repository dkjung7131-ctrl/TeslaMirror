// TeslaMirror 시그널링 서버 — Cloudflare Worker
//
// 테슬라 브라우저는 사설 IP 직접 접속을 막는다(about:blank#blocked) + LAN WebRTC UDP도
// 로컬로 못 냄(실차 실측). 공개 HTTPS 페이지 + 시그널링은 워커가 담당.
// 미디어: 비루트 VpnService 가로채기 실패 → 기본은 STUN 공인 ICE(인터넷 경로, 지연↑).
// PC 핫스팟 로컬 테스트는 앱에서 "인터넷 ICE"를 끄면 사설 host 후보만 사용.
//
// 엔드포인트:
//   POST /register  {deviceId,name,hotspotIp}  (Bearer SECRET) — 폰 등록(발견용)
//   GET  /                                       — 뷰어 페이지(공인 IP로 폰 자동 선택)
//   GET  /?id=<deviceId>                         — 특정 폰 뷰어
//   POST /offer     {deviceId,offerId,sdp}      (Bearer SECRET) — 폰이 오퍼 게시
//   GET  /offer?id=<deviceId>                    — 테슬라가 오퍼 가져감
//   POST /answer    {deviceId,offerId,sdp}       — 테슬라가 앤서 게시
//   GET  /answer?id=<deviceId>                   — 폰이 앤서 폴링
//
// 바인딩: KV namespace PHONES, Secret SECRET

const SIGNAL_TTL = 120; // 초

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const p = url.pathname;

    // ---- 폰 등록 (발견용) ----
    if (p === '/register' && request.method === 'POST') {
      if (!authed(request, env)) return new Response('unauthorized', { status: 401 });
      const body = await json(request);
      const { deviceId, name, hotspotIp } = body || {};
      if (!deviceId) return new Response('bad request', { status: 400 });
      const entry = {
        deviceId: String(deviceId).slice(0, 64),
        name: String(name || 'phone').slice(0, 40),
        hotspotIp: hotspotIp || '',
        publicIp: request.headers.get('CF-Connecting-IP') || '',
        ts: Date.now(),
      };
      await env.PHONES.put('dev:' + entry.deviceId, JSON.stringify(entry), { expirationTtl: 86400 });
      return new Response('OK');
    }

    // ---- WebRTC 시그널링 ----
    if (p === '/offer' && request.method === 'POST') {
      if (!authed(request, env)) return new Response('unauthorized', { status: 401 });
      const b = await json(request);
      if (!b || !b.deviceId || !b.sdp) return new Response('bad request', { status: 400 });
      await env.PHONES.put('offer:' + b.deviceId, JSON.stringify({
        offerId: b.offerId,
        sdp: b.sdp,
        mode: b.mode || 'full', // 'app' | 'full' — 뷰어 UI 분기
        ts: Date.now(),
      }), { expirationTtl: SIGNAL_TTL });
      return json200({ ok: true });
    }
    if (p === '/offer' && request.method === 'GET') {
      const id = url.searchParams.get('id');
      const v = id ? await env.PHONES.get('offer:' + id, 'json') : null;
      if (!v) return new Response('no offer', { status: 404 });
      return json200(v);
    }
    if (p === '/answer' && request.method === 'POST') {
      const b = await json(request);
      if (!b || !b.deviceId || !b.sdp) return new Response('bad request', { status: 400 });
      await env.PHONES.put('answer:' + b.deviceId, JSON.stringify({ offerId: b.offerId, sdp: b.sdp, ts: Date.now() }), { expirationTtl: SIGNAL_TTL });
      return json200({ ok: true });
    }
    if (p === '/answer' && request.method === 'GET') {
      const id = url.searchParams.get('id');
      const v = id ? await env.PHONES.get('answer:' + id, 'json') : null;
      if (!v) return new Response('no answer', { status: 404 });
      return json200(v);
    }

    // ---- 뷰어 페이지 ----
    if (p === '/' && request.method === 'GET') {
      const forced = url.searchParams.get('id');
      if (forced) {
        return html(viewerHtml(forced));
      }
      const entries = await listPhones(env);
      const myIp = request.headers.get('CF-Connecting-IP') || '';
      // 1) 같은 공인 IP  2) 지금 offer 올려둔 폰(미러링 중)  3) 등록 1대  4) 선택 화면
      const pick = await pickPhone(env, entries, myIp);
      if (pick) return html(viewerHtml(pick.deviceId));
      return html(chooserHtml(entries, myIp, url.origin));
    }

    return new Response('not found', { status: 404 });
  },
};

function authed(request, env) {
  return (request.headers.get('Authorization') || '') === `Bearer ${env.SECRET}`;
}
async function json(request) { try { return await request.json(); } catch { return null; } }
function json200(obj) {
  return new Response(JSON.stringify(obj), { headers: { 'content-type': 'application/json' } });
}
function html(s) { return new Response(s, { headers: { 'content-type': 'text/html; charset=utf-8' } }); }

async function listPhones(env) {
  const list = await env.PHONES.list({ prefix: 'dev:' });
  const raw = (await Promise.all(list.keys.map((k) => env.PHONES.get(k.name, 'json')))).filter(Boolean);
  raw.sort((a, b) => b.ts - a.ts);
  // 같은 폰이 여러 deviceId(릴리스/디버그 서명키 차이)로 등록될 수 있어 핫스팟 IP로 중복 제거
  const entries = [], seen = new Set();
  for (const e of raw) {
    const key = e.hotspotIp || e.deviceId;
    if (seen.has(key)) continue;
    seen.add(key);
    entries.push(e);
  }
  return entries;
}

function samePeer(a, b) {
  if (!a || !b) return false;
  if (a === b) return true;
  if (a.includes(':') && b.includes(':')) return prefix64(a) === prefix64(b);
  return false;
}
function prefix64(ip6) {
  const parts = ip6.split('::');
  let head = parts[0] ? parts[0].split(':') : [];
  if (parts.length === 2) {
    const tail = parts[1] ? parts[1].split(':') : [];
    head = head.concat(Array(8 - head.length - tail.length).fill('0'), tail);
  }
  return head.slice(0, 4).map((h) => h.padStart(4, '0')).join(':').toLowerCase();
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
// UI 문자열은 \\u 이스케이프만 사용 — CF 대시보드 붙여넣기에서 한글 깨짐 방지
function ageText(ts) {
  const m = Math.floor((Date.now() - ts) / 60000);
  if (m < 1) return '\uBC29\uAE08 \uB4F1\uB85D';
  if (m < 60) return m + '\uBD84 \uC804 \uB4F1\uB85D';
  const h = Math.floor(m / 60);
  if (h < 24) return h + '\uC2DC\uAC04 \uC804 \uB4F1\uB85D';
  return Math.floor(h / 24) + '\uC77C \uC804 \uB4F1\uB85D';
}

/** 짧은 URL 접속 시 자동으로 붙일 폰 고르기 */
async function pickPhone(env, entries, myIp) {
  if (!entries.length) return null;
  const matches = entries.filter((e) => samePeer(e.publicIp, myIp));
  if (matches.length >= 1) return matches[0]; // 최신순 정렬됨
  // 미러링/캐스트 중(offer 있음)인 폰 우선 — 노트북이 집 Wi-Fi여도 짧은 URL로 바로 입장
  for (const e of entries) {
    const offer = await env.PHONES.get('offer:' + e.deviceId, 'json');
    if (offer && offer.sdp) return e;
  }
  if (entries.length === 1) return entries[0];
  return null;
}

function chooserHtml(entries, myIp, origin) {
  const base = (origin || '').replace(/\/$/, '');
  const items = entries
    .map((e) => {
      const same = samePeer(e.publicIp, myIp);
      const badge = same
        ? '<b style="color:#4ade80">\uAC19\uC740 \uB124\uD2B8\uC6CC\uD06C</b>'
        : '<b style="color:#fbbf24">\uB2E4\uB978 \uB124\uD2B8\uC6CC\uD06C</b>';
      // absolute URL
      const href = base + '/?id=' + encodeURIComponent(e.deviceId);
      return '<a class="btn" href="' + href + '">' + escapeHtml(e.name) +
        '<span>' + ageText(e.ts) + '</span>' + badge + '</a>';
    })
    .join('\n');
  return '<!doctype html><html lang="ko"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>TeslaMirror</title><style>' +
    'body{font-family:sans-serif;background:#111;color:#eee;display:flex;flex-direction:column;align-items:center;padding:40px 16px;gap:16px}' +
    'h1{font-size:28px;margin:0 0 8px}' +
    '.btn{display:flex;flex-direction:column;gap:4px;width:100%;max-width:480px;background:#2563eb;color:#fff;text-decoration:none;padding:22px 24px;border-radius:14px;font-size:24px;font-weight:600;text-align:center}' +
    '.btn span{font-size:15px;font-weight:400;opacity:.8}.btn b{font-size:14px;font-weight:600}' +
    'p{opacity:.7;font-size:17px;text-align:center;line-height:1.5}' +
    '</style></head><body><h1>TeslaMirror</h1>' +
    (items || '<p>\uB4F1\uB85D\uB41C \uD3F0\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.<br>\uD3F0\uC5D0\uC11C \uC571\uC744 \uC2E4\uD589\uD558\uC138\uC694.</p>') +
    (items ? '<p>\uD3F0\uC744 \uACE0\uB974\uC138\uC694.<br><b>\uBA3C\uC800 \uD3F0\uC5D0\uC11C \uBBF8\uB7EC\uB9C1/\uC571 \uCE90\uC2A4\uD2B8\uB97C \uCF24 \uB4A4</b> \uB4E4\uC5B4\uC624\uC138\uC694.</p>' : '') +
    '</body></html>';
}

// WebRTC 뷰어 — 문자열 연결로 생성(큰 템플릿 리터럴은 CF 대시보드 붙여넣기에서 깨지기 쉬움).
// 좌/중/우 정렬 + 여백 시계.
function viewerHtml(deviceId) {
  var idLit = JSON.stringify(String(deviceId));
  var css =
    'html,body{margin:0;height:100%;background:#000;overflow:hidden;' +
    'font-family:system-ui,sans-serif;color:#fff;-webkit-user-select:none;user-select:none;' +
    'touch-action:none}' +
    '#stage{position:fixed;inset:0;background:#000}' +
    '#c{position:absolute;top:50%;transform:translateY(-50%);' +
    'max-width:100%;max-height:100%;width:auto;height:auto;background:#000;z-index:2;' +
    'touch-action:none;cursor:crosshair}' +
    '#c.pos-left{left:0;right:auto}' +
    '#c.pos-center{left:50%;right:auto;transform:translate(-50%,-50%)}' +
    '#c.pos-right{left:auto;right:0}' +
    // 앱 모드: contain 여백 제거 — 스테이지 전체에 맞춤 (비율은 폰 VD 학습으로 근접)
    'body.appMode #c,body.appMode #c.pos-left,body.appMode #c.pos-center,body.appMode #c.pos-right{' +
    'left:0!important;right:0!important;top:0!important;bottom:0!important;' +
    'width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;' +
    'transform:none!important}' +
    'body.appMode #clkL,body.appMode #clkR{display:none!important}' +
    '#clkL,#clkR{position:fixed;top:0;bottom:0;width:28%;display:flex;flex-direction:column;' +
    'align-items:center;justify-content:center;z-index:1;pointer-events:none;opacity:0;transition:opacity .25s}' +
    '#clkL{left:0}#clkR{right:0}#clkL.show,#clkR.show{opacity:1}' +
    // 밝은 맵 위에서도 읽히게 검정 외곽 그림자
    '.time{font-size:48px;font-weight:300;letter-spacing:.04em;color:#fff;' +
    'text-shadow:0 0 4px #000,0 2px 10px #000,0 0 20px #000}' +
    '.date{font-size:16px;margin-top:10px;color:rgba(255,255,255,.85);' +
    'text-shadow:0 0 3px #000,0 1px 6px #000}' +
    '#s{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);font-size:20px;' +
    'opacity:.9;text-align:center;line-height:1.6;z-index:5;' +
    'text-shadow:0 0 4px #000,0 1px 8px #000}' +
    '#st{display:none}' +
    // 전체화면: 우하단 L/C/R. 앱모드: 가장자리 파이(Back/Home)
    '#bar{position:fixed;right:10px;bottom:10px;display:flex;gap:8px;z-index:10}' +
    '#bar button{width:48px;height:48px;border-radius:12px;border:1px solid rgba(255,255,255,.35);' +
    'background:rgba(0,0,0,.72);color:#fff;font-size:18px;cursor:pointer;' +
    'box-shadow:0 2px 8px rgba(0,0,0,.5)}' +
    '#bar button.on{background:rgba(37,99,235,.85);border-color:rgba(147,197,253,.9)}' +
    // Pie control (Tesor-like): edge hold + drag
    '#pie{position:fixed;width:260px;height:260px;margin-left:-130px;margin-top:-130px;' +
    'border-radius:50%;display:none;z-index:40;pointer-events:none;' +
    'background:radial-gradient(circle at center,rgba(40,40,48,.92) 0%,rgba(12,12,16,.94) 70%);' +
    'border:2px solid rgba(255,255,255,.22);box-shadow:0 12px 40px rgba(0,0,0,.55)}' +
    '#pie.on{display:block}' +
    '#pie .lab{position:absolute;color:rgba(255,255,255,.55);font-size:17px;font-weight:700;' +
    'letter-spacing:.04em;text-shadow:0 1px 4px #000;transition:color .12s,transform .12s}' +
    '#pie .lab.back{left:14px;top:50%;transform:translateY(-50%)}' +
    '#pie .lab.home{left:50%;top:20px;transform:translateX(-50%)}' +
    '#pie .lab.cancel{left:50%;bottom:18px;transform:translateX(-50%);font-size:15px;font-weight:600}' +
    '#pie.sel-back .lab.back{color:#90caf9}' +
    '#pie.sel-home .lab.home{color:#90caf9}' +
    '#pie.sel-cancel .lab.cancel{color:#ffccbc}' +
    '#pieDot{position:absolute;left:50%;top:50%;width:18px;height:18px;margin:-9px 0 0 -9px;' +
    'border-radius:50%;background:#fff;box-shadow:0 0 12px rgba(144,202,249,.8)}' +
    '#edgeL,#edgeR,#edgeB{position:fixed;z-index:8;pointer-events:none;opacity:0;' +
    'transition:opacity .25s;background:linear-gradient(90deg,rgba(144,202,249,.35),transparent)}' +
    // pie only when in-app (not on launcher home)
    'body.appMode.pieReady #edgeL,body.appMode.pieReady #edgeR,body.appMode.pieReady #edgeB{opacity:1}' +
    '#edgeL{left:0;top:18%;bottom:18%;width:6px;border-radius:0 4px 4px 0}' +
    '#edgeR{right:0;top:18%;bottom:18%;width:6px;border-radius:4px 0 0 4px;' +
    'background:linear-gradient(270deg,rgba(144,202,249,.35),transparent)}' +
    '#edgeB{left:22%;right:22%;bottom:0;height:6px;border-radius:4px 4px 0 0;' +
    'background:linear-gradient(0deg,rgba(144,202,249,.35),transparent)}' +
    '#pie.sel-cancel .lab.cancel{color:#ffab91}';

  var js =
    '(function(){\n' +
    'var DEVICE_ID=' + idLit + ';\n' +
    'var s=document.getElementById("s"), stEl=document.getElementById("st");\n' +
    'var canvas=document.getElementById("c"), ctx=canvas.getContext("2d");\n' +
    'var clkL=document.getElementById("clkL"), clkR=document.getElementById("clkR");\n' +
    'var POS_KEY="tm_pos";\n' +
    'var pos=localStorage.getItem(POS_KEY)||"center";\n' +
    'if(pos!=="left"&&pos!=="right"&&pos!=="center") pos="center";\n' +
    'function st(t){ s.style.display=t?"":"none"; s.textContent=t||""; }\n' +
    'function setPos(p){\n' +
    '  pos=p; localStorage.setItem(POS_KEY,p);\n' +
    '  canvas.className="pos-"+p;\n' +
    '  document.getElementById("bL").className=p==="left"?"on":"";\n' +
    '  document.getElementById("bC").className=p==="center"?"on":"";\n' +
    '  document.getElementById("bR").className=p==="right"?"on":"";\n' +
    '  updateClocksVisible();\n' +
    '}\n' +
    'document.getElementById("bL").onclick=function(){ setPos("left"); };\n' +
    'document.getElementById("bC").onclick=function(){ setPos("center"); };\n' +
    'document.getElementById("bR").onclick=function(){ setPos("right"); };\n' +
    'setPos(pos);\n' +
    'function pad(n){ return n<10?"0"+n:""+n; }\n' +
    'function tickClock(){\n' +
    '  var now=new Date();\n' +
    '  var t=pad(now.getHours())+":"+pad(now.getMinutes());\n' +
    '  var d=now.getFullYear()+"."+pad(now.getMonth()+1)+"."+pad(now.getDate());\n' +
    '  document.getElementById("tL").textContent=t;\n' +
    '  document.getElementById("tR").textContent=t;\n' +
    '  document.getElementById("dL").textContent=d;\n' +
    '  document.getElementById("dR").textContent=d;\n' +
    '}\n' +
    'tickClock(); setInterval(tickClock,1000);\n' +
    'function updateClocksVisible(){\n' +
    '  var vw=window.innerWidth||1, vh=window.innerHeight||1;\n' +
    '  var cw=canvas.width||0, ch=canvas.height||0;\n' +
    '  var scale=1;\n' +
    '  if(cw>0&&ch>0) scale=Math.min(vw/cw, vh/ch);\n' +
    '  var dw=cw*scale, gap=vw-dw;\n' +
    '  var show=gap>96;\n' +
    '  if(pos==="left"){ clkL.className=""; clkR.className=show?"show":""; }\n' +
    '  else if(pos==="right"){ clkR.className=""; clkL.className=show?"show":""; }\n' +
    '  else { clkL.className=show?"show":""; clkR.className=show?"show":""; }\n' +
    '}\n' +
    'function sendViewport(){\n' +
    '  if(!appMode) return;\n' +
    '  var w=window.innerWidth|0, h=window.innerHeight|0;\n' +
    '  try{\n' +
    '    if(window.visualViewport&&window.visualViewport.width>0){\n' +
    '      w=Math.round(window.visualViewport.width);\n' +
    '      h=Math.round(window.visualViewport.height);\n' +
    '    }\n' +
    '  }catch(e){}\n' +
    '  if(w<200||h<150) return;\n' +
    '  // 세로는 가로로 정규화 (잘못된 세로 학습 → 여백 폭증 방지)\n' +
    '  if(h>w){ var t=w; w=h; h=t; }\n' +
    '  var a=w/h;\n' +
    '  if(a<1.35||a>2.4) return;\n' +
    '  sendJson({t:"viewport",w:w,h:h});\n' +
    '}\n' +
    'var vpTimer=null;\n' +
    'function scheduleViewport(){\n' +
    '  if(vpTimer) clearTimeout(vpTimer);\n' +
    '  vpTimer=setTimeout(function(){ vpTimer=null; sendViewport(); }, 400);\n' +
    '}\n' +
    'window.addEventListener("resize", function(){ updateClocksVisible(); scheduleViewport(); });\n' +
    'function iceDone(pc){ return new Promise(function(res){\n' +
    '  var done=false, t=null, n=0;\n' +
    '  function finish(){ if(done) return; done=true; if(t) clearTimeout(t); res(); }\n' +
    '  if(pc.iceGatheringState==="complete") return finish();\n' +
    '  t=setTimeout(finish,1200);\n' +
    '  pc.addEventListener("icegatheringstatechange",function(){\n' +
    '    if(pc.iceGatheringState==="complete") finish();\n' +
    '  });\n' +
    '  // srflx/host 있으면 조기 answer (전체 gather 최대 3s 대기 안 함)\n' +
    '  function onC(ev){\n' +
    '    if(!ev||!ev.candidate) return;\n' +
    '    n++;\n' +
    '    var c=ev.candidate.candidate||"";\n' +
    '    if(c.indexOf("srflx")>=0||c.indexOf("host")>=0||n>=3) finish();\n' +
    '  }\n' +
    '  if(pc.addEventListener) pc.addEventListener("icecandidate", onC);\n' +
    '  else pc.onicecandidate=onC;\n' +
    '}); }\n' +
    'var pc=null, dc=null, tries=0, drawing=false, fpsCount=0, lastFpsT=Date.now(), fps=0;\n' +
    'var pressed=false, appMode=false, onLauncher=true, connectGen=0, connectTimer=null, watchTimer=null;\n' +
    'function applyAppModeUi(isApp){\n' +
    '  appMode=!!isApp;\n' +
    '  var bar=document.getElementById("bar");\n' +
    '  if(bar) bar.style.display=appMode?"none":"";\n' +
    '  if(document.body){\n' +
    '    if(appMode) document.body.classList.add("appMode");\n' +
    '    else document.body.classList.remove("appMode");\n' +
    '  }\n' +
    '  setOnLauncher(appMode?onLauncher:true);\n' +
    '  if(appMode) setPos("center");\n' +
    '}\n' +
    // launcher = first home screen: no pie edges. only when in-app.\n' +
    'function setOnLauncher(on){\n' +
    '  onLauncher=!!on;\n' +
    '  if(document.body){\n' +
    '    if(appMode&&!onLauncher) document.body.classList.add("pieReady");\n' +
    '    else document.body.classList.remove("pieReady");\n' +
    '  }\n' +
    '  if(onLauncher) pieHide();\n' +
    '}\n' +
    'function handleCtrlMsg(raw){\n' +
    '  try{\n' +
    '    var o=JSON.parse(raw);\n' +
    '    if(o&&o.t==="launcher") setOnLauncher(!!o.on);\n' +
    '  }catch(e){}\n' +
    '}\n' +
    // ---- Pie: long-press edge 2s (light tap = normal app touch) ----\n' +
    'var pieEl=document.getElementById("pie");\n' +
    'var pieOpen=false, piePending=false, piePtr=-1, pieCX=0, pieCY=0, pieAct=null, pieTimer=null;\n' +
    'var PIE_HOLD_MS=2000, PIE_MOVE_PX=24;\n' +
    'function pieAllowed(){ return appMode&&!onLauncher; }\n' +
    'function pieEdge(x,y){\n' +
    '  var m=72, vw=window.innerWidth||1, vh=window.innerHeight||1;\n' +
    '  return x<m || x>vw-m || y>vh-m || y<m;\n' +
    '}\n' +
    'function piePick(x,y){\n' +
    '  var dx=x-pieCX, dy=y-pieCY;\n' +
    '  var dist=Math.sqrt(dx*dx+dy*dy);\n' +
    '  if(dist<42) return "cancel";\n' +
    '  var deg=(Math.atan2(-dy,dx)*180/Math.PI+360)%360;\n' +
    '  if(deg>=45&&deg<135) return "home";\n' +
    '  if(deg>=135&&deg<225) return "back";\n' +
    '  if(deg>=225&&deg<315) return "cancel";\n' +
    '  return "cancel";\n' +
    '}\n' +
    'function pieClearTimer(){ if(pieTimer){ clearTimeout(pieTimer); pieTimer=null; } }\n' +
    'function pieShow(x,y){\n' +
    '  pieOpen=true; piePending=false; pieCX=x; pieCY=y; pieAct=null;\n' +
    '  pieClearTimer();\n' +
    '  pieEl.style.left=x+"px"; pieEl.style.top=y+"px";\n' +
    '  pieEl.className="on";\n' +
    '}\n' +
    'function pieHide(){\n' +
    '  pieOpen=false; piePending=false; piePtr=-1; pieAct=null;\n' +
    '  pieClearTimer();\n' +
    '  if(pieEl) pieEl.className="";\n' +
    '}\n' +
    'function pieSetAct(a){\n' +
    '  pieAct=a;\n' +
    '  pieEl.className="on"+(a==="back"?" sel-back":a==="home"?" sel-home":a==="cancel"?" sel-cancel":"");\n' +
    '}\n' +
    'function injectTap(cx,cy){\n' +
    '  var c=mapCoords(cx,cy); if(!c) return;\n' +
    '  sendJson({t:"touch",a:0,id:1,x:c.x,y:c.y});\n' +
    '  setTimeout(function(){ sendJson({t:"touch",a:1,id:1,x:c.x,y:c.y}); }, 30);\n' +
    '}\n' +
    'function injectDragStart(cx,cy){\n' +
    '  var c=mapCoords(cx,cy); if(!c) return;\n' +
    '  sendJson({t:"touch",a:0,id:1,x:c.x,y:c.y});\n' +
    '}\n' +
    'function ptXY(e){\n' +
    '  if(e.touches&&e.touches[0]) return {x:e.touches[0].clientX,y:e.touches[0].clientY,id:1};\n' +
    '  if(e.changedTouches&&e.changedTouches[0]) return {x:e.changedTouches[0].clientX,y:e.changedTouches[0].clientY,id:1};\n' +
    '  return {x:e.clientX,y:e.clientY,id:(e.pointerId|0)||1};\n' +
    '}\n' +
    'function onPieDown(e){\n' +
    '  if(!pieAllowed()||pieOpen||piePending) return;\n' +
    '  var p=ptXY(e);\n' +
    '  if(!pieEdge(p.x,p.y)) return;\n' +
    '  // edge: wait 2s hold — light tap will be forwarded as app touch on up\n' +
    '  piePtr=p.id; piePending=true; pieCX=p.x; pieCY=p.y;\n' +
    '  pieClearTimer();\n' +
    '  pieTimer=setTimeout(function(){\n' +
    '    if(!piePending) return;\n' +
    '    pieShow(pieCX, pieCY);\n' +
    '  }, PIE_HOLD_MS);\n' +
    '  try{ e.target&&e.target.setPointerCapture&&e.pointerId!=null&&e.target.setPointerCapture(e.pointerId); }catch(ex){}\n' +
    '  e.preventDefault(); e.stopPropagation();\n' +
    '}\n' +
    'function onPieMove(e){\n' +
    '  var p=ptXY(e);\n' +
    '  if(pieOpen){\n' +
    '    if(p.id!==piePtr&&e.pointerId!=null&&(e.pointerId|0)!==piePtr) return;\n' +
    '    pieSetAct(piePick(p.x,p.y));\n' +
    '    e.preventDefault(); e.stopPropagation(); return;\n' +
    '  }\n' +
    '  if(!piePending) return;\n' +
    '  if(p.id!==piePtr&&e.pointerId!=null&&(e.pointerId|0)!==piePtr) return;\n' +
    '  var dx=p.x-pieCX, dy=p.y-pieCY;\n' +
    '  if(dx*dx+dy*dy > PIE_MOVE_PX*PIE_MOVE_PX){\n' +
    '    // drag: cancel pie, treat as app touch drag\n' +
    '    piePending=false; pieClearTimer();\n' +
    '    injectDragStart(pieCX, pieCY);\n' +
    '    var c=mapCoords(p.x,p.y);\n' +
    '    if(c) sendJson({t:"touch",a:2,id:1,x:c.x,y:c.y});\n' +
    '    piePtr=-1;\n' +
    '  }\n' +
    '  e.preventDefault(); e.stopPropagation();\n' +
    '}\n' +
    'function onPieUp(e){\n' +
    '  var p=ptXY(e);\n' +
    '  if(pieOpen){\n' +
    '    if(e.pointerId!=null&&(e.pointerId|0)!==piePtr&&p.id!==piePtr) return;\n' +
    '    var a=piePick(p.x,p.y);\n' +
    '    if(a==="back") sendJson({t:"back"});\n' +
    '    else if(a==="home") sendJson({t:"home"});\n' +
    '    pieHide();\n' +
    '    e.preventDefault(); e.stopPropagation(); return;\n' +
    '  }\n' +
    '  if(piePending){\n' +
    '    // released before 2s = normal light tap to the app\n' +
    '    piePending=false; pieClearTimer();\n' +
    '    injectTap(pieCX, pieCY);\n' +
    '    piePtr=-1;\n' +
    '    e.preventDefault(); e.stopPropagation(); return;\n' +
    '  }\n' +
    '}\n' +
    // pointer + touch (Tesla old Chromium may lack PointerEvent)\n' +
    'document.addEventListener("pointerdown", onPieDown, true);\n' +
    'document.addEventListener("pointermove", onPieMove, true);\n' +
    'document.addEventListener("pointerup", onPieUp, true);\n' +
    'document.addEventListener("pointercancel", onPieUp, true);\n' +
    'document.addEventListener("touchstart", onPieDown, {capture:true,passive:false});\n' +
    'document.addEventListener("touchmove", onPieMove, {capture:true,passive:false});\n' +
    'document.addEventListener("touchend", onPieUp, {capture:true,passive:false});\n' +
    'document.addEventListener("touchcancel", onPieUp, {capture:true,passive:false});\n' +
    'document.addEventListener("mousedown", onPieDown, true);\n' +
    'document.addEventListener("mousemove", onPieMove, true);\n' +
    'document.addEventListener("mouseup", onPieUp, true);\n' +
    'function drawJpeg(buf){\n' +
    '  if(drawing) return;\n' +
    '  drawing=true;\n' +
    '  var blob=new Blob([buf],{type:"image/jpeg"});\n' +
    '  createImageBitmap(blob).then(function(bmp){\n' +
    '    if(canvas.width!==bmp.width){ canvas.width=bmp.width; canvas.height=bmp.height; }\n' +
    '    ctx.drawImage(bmp,0,0); bmp.close(); drawing=false;\n' +
    '    st(""); fpsCount++;\n' +
    '    var now=Date.now(); if(now-lastFpsT>=1000){ fps=fpsCount; fpsCount=0; lastFpsT=now; }\n' +
    '    updateClocksVisible();\n' +
    '  }).catch(function(){ drawing=false; });\n' +
    '}\n' +
    // 뷰어→폰 터치: 캔버스 표시 영역 → 영상 픽셀 좌표 (앱 모드 scrcpy inject)
    'function mapCoords(clientX, clientY){\n' +
    '  var r=canvas.getBoundingClientRect();\n' +
    '  var cw=canvas.width||1, ch=canvas.height||1;\n' +
    '  if(r.width<=0||r.height<=0) return null;\n' +
    '  var x=(clientX-r.left)/r.width*cw;\n' +
    '  var y=(clientY-r.top)/r.height*ch;\n' +
    '  if(x<0||y<0||x>=cw||y>=ch) return null;\n' +
    '  return {x:Math.max(0,Math.min(cw-1,Math.round(x))), y:Math.max(0,Math.min(ch-1,Math.round(y)))};\n' +
    '}\n' +
    'function sendJson(o){\n' +
    '  if(!dc||dc.readyState!=="open") return false;\n' +
    '  try{\n' +
    '    var s=JSON.stringify(o);\n' +
    '    if(typeof TextEncoder!=="undefined"){\n' +
    '      dc.send(new TextEncoder().encode(s));\n' +
    '    } else {\n' +
    '      dc.send(s);\n' +
    '    }\n' +
    '    return true;\n' +
    '  }catch(e){ return false; }\n' +
    '}\n' +
    // Pinch: unique pointer id per finger. Scrcpy server converts 2nd DOWN->POINTER_DOWN.\n' +
    'var activePtrs={};\n' +
    'function onPtrDown(e){\n' +
    '  if(pieOpen) return;\n' +
    '  // app mode: edge reserved for pie (capture phase already handled)\n' +
    '  if(pieAllowed()&&pieEdge(e.clientX,e.clientY)) return;\n' +
    '  var c=mapCoords(e.clientX,e.clientY); if(!c) return;\n' +
    '  var id=e.pointerId|0;\n' +
    '  activePtrs[id]={x:c.x,y:c.y};\n' +
    '  try{ canvas.setPointerCapture(e.pointerId); }catch(e2){}\n' +
    '  sendJson({t:"touch",a:0,id:id,x:c.x,y:c.y});\n' +
    '  e.preventDefault();\n' +
    '}\n' +
    'function onPtrMove(e){\n' +
    '  if(!(e.pointerId in activePtrs)) return;\n' +
    '  var c=mapCoords(e.clientX,e.clientY); if(!c) return;\n' +
    '  var id=e.pointerId|0;\n' +
    '  activePtrs[id]={x:c.x,y:c.y};\n' +
    '  sendJson({t:"touch",a:2,id:id,x:c.x,y:c.y});\n' +
    '  e.preventDefault();\n' +
    '}\n' +
    'function onPtrUp(e){\n' +
    '  if(!(e.pointerId in activePtrs)) return;\n' +
    '  var id=e.pointerId|0;\n' +
    '  var c=mapCoords(e.clientX,e.clientY);\n' +
    '  if(!c) c=activePtrs[id]||{x:0,y:0};\n' +
    '  delete activePtrs[id];\n' +
    '  sendJson({t:"touch",a:1,id:id,x:c.x,y:c.y});\n' +
    '  e.preventDefault();\n' +
    '}\n' +
    'function onWheel(e){\n' +
    '  var c=mapCoords(e.clientX,e.clientY); if(!c) return;\n' +
    '  var dy=e.deltaY||0;\n' +
    '  if(dy===0) return;\n' +
    '  var v=dy>0?-0.8:0.8;\n' +
    '  if(e.deltaMode===1) v*=3;\n' +
    '  if(e.ctrlKey) v*=1.5;\n' +
    '  sendJson({t:"scroll",x:c.x,y:c.y,h:0,v:v});\n' +
    '  e.preventDefault();\n' +
    '}\n' +
    'canvas.addEventListener("pointerdown", onPtrDown);\n' +
    'canvas.addEventListener("pointermove", onPtrMove);\n' +
    'canvas.addEventListener("pointerup", onPtrUp);\n' +
    'canvas.addEventListener("pointercancel", onPtrUp);\n' +
    'canvas.addEventListener("wheel", onWheel, {passive:false});\n' +
    'canvas.addEventListener("gesturestart", function(e){ e.preventDefault(); });\n' +
    'canvas.addEventListener("gesturechange", function(e){ e.preventDefault(); });\n' +
    'var discTimer=null;\n' +
    'function clearDiscTimer(){ if(discTimer){ clearTimeout(discTimer); discTimer=null; } }\n' +
    'function armWatch(gen, ms){\n' +
    '  if(watchTimer) clearTimeout(watchTimer);\n' +
    '  watchTimer=setTimeout(function(){\n' +
    '    if(gen!==connectGen) return;\n' +
    '    var cs=pc&&pc.connectionState;\n' +
    '    if(cs==="connected") return;\n' +
    '    // connecting 고착 포함 — 예전엔 45s 방치 + lastOk가 connecting을 정상 취급\n' +
    '    st("\\uC7AC\\uC5F0\\uACB0 \\uC911...");\n' +
    '    try{ if(pc) pc.close(); }catch(e){}\n' +
    '    schedule();\n' +
    '  }, ms||8000);\n' +
    '}\n' +
    // No async/await - older Tesla Chromium may not parse it (entire script dies)\n' +
    'function connect(){\n' +
    '  var gen=++connectGen;\n' +
    '  clearDiscTimer();\n' +
    '  armWatch(gen, 8000);\n' +
    '  st("\\uC5F0\\uACB0 \\uC911...");\n' +
    '  fetch("/offer?id="+encodeURIComponent(DEVICE_ID),{cache:"no-store"}).then(function(r){\n' +
    '    if(gen!==connectGen) return null;\n' +
    '    if(!r.ok){ st("\\uD3F0 \\uB300\\uAE30 \\uC911..."); schedule(); return null; }\n' +
    '    return r.json();\n' +
    '  }).then(function(offer){\n' +
    '    if(!offer||gen!==connectGen) return;\n' +
    '    applyAppModeUi(offer.mode==="app");\n' +
    '    if(pc){ try{pc.close();}catch(e){} }\n' +
    '    dc=null; pressed=false;\n' +
    '    pc=new RTCPeerConnection({iceServers:[\n' +
    '      {urls:"stun:stun.l.google.com:19302"},\n' +
    '      {urls:"stun:stun1.l.google.com:19302"}\n' +
    '    ]});\n' +
    '    window.pc=pc;\n' +
    '    pc.ondatachannel=function(e){\n' +
    '      dc=e.channel; dc.binaryType="arraybuffer";\n' +
    '      window.dc=dc;\n' +
    '      dc.onmessage=function(ev){\n' +
    '        var d=ev.data;\n' +
    '        if(typeof d==="string"){ handleCtrlMsg(d); return; }\n' +
    '        function asU8(buf){\n' +
    '          if(!buf) return null;\n' +
    '          if(buf instanceof ArrayBuffer) return new Uint8Array(buf);\n' +
    '          if(buf.buffer&&buf.byteLength!==undefined) return new Uint8Array(buf.buffer,buf.byteOffset,buf.byteLength);\n' +
    '          return null;\n' +
    '        }\n' +
    '        function tryCtrl(u8){\n' +
    '          if(!u8||u8.length<3||u8.length>512||u8[0]!==0x7b) return false;\n' +
    '          var s="";\n' +
    '          try{ s=(typeof TextDecoder!=="undefined")?new TextDecoder("utf-8").decode(u8):"";\n' +
    '            if(!s){ for(var i=0;i<u8.length;i++) s+=String.fromCharCode(u8[i]); }\n' +
    '          }catch(ex){ for(var j=0;j<u8.length;j++) s+=String.fromCharCode(u8[j]); }\n' +
    '          handleCtrlMsg(s); return true;\n' +
    '        }\n' +
    '        var u8=asU8(d);\n' +
    '        if(u8&&tryCtrl(u8)) return;\n' +
    '        if(d&&typeof Blob!=="undefined"&&d instanceof Blob){\n' +
    '          if(d.size>0&&d.size<=512){\n' +
    '            var fr=new FileReader();\n' +
    '            fr.onload=function(){ var u=asU8(fr.result); if(u&&tryCtrl(u)) return; drawJpeg(fr.result); };\n' +
    '            fr.readAsArrayBuffer(d); return;\n' +
    '          }\n' +
    '        }\n' +
    '        drawJpeg(d);\n' +
    '      };\n' +
    '    };\n' +
    '    pc.onconnectionstatechange=function(){\n' +
    '      if(gen!==connectGen) return;\n' +
    '      var cs=pc&&pc.connectionState;\n' +
    '      if(cs==="connected"){\n' +
    '        tries=0; clearDiscTimer(); st("");\n' +
    '        if(watchTimer){clearTimeout(watchTimer);watchTimer=null;}\n' +
    '        setTimeout(sendViewport, 300);\n' +
    '      } else if(cs==="failed"){\n' +
    '        clearDiscTimer(); st("\\uC7AC\\uC5F0\\uACB0 \\uC911..."); schedule();\n' +
    '      } else if(cs==="closed"){\n' +
    '        // 우리가 close 한 경우 schedule 이 이미 돌 수 있음 — 중복 방지\n' +
    '        clearDiscTimer();\n' +
    '        if(!connectTimer){ st("\\uC7AC\\uC5F0\\uACB0 \\uC911..."); schedule(); }\n' +
    '      } else if(cs==="disconnected"){\n' +
    '        // 일시 끊김 흔함 — 즉시 재연결하지 않고 15초 기다려 복구 기회\n' +
    '        st("\\uC5F0\\uACB0 \\uC720\\uC9C0 \\uC911...");\n' +
    '        clearDiscTimer();\n' +
    '        discTimer=setTimeout(function(){\n' +
    '          if(gen!==connectGen) return;\n' +
    '          if(pc && pc.connectionState==="connected") return;\n' +
    '          st("\\uC7AC\\uC5F0\\uACB0 \\uC911...");\n' +
    '          try{ if(pc) pc.close(); }catch(e){}\n' +
    '          schedule();\n' +
    '        }, 15000);\n' +
    '      } else if(cs==="connecting"){ st("\\uC5F0\\uACB0 \\uC911..."); }\n' +
    '    };\n' +
    '    return pc.setRemoteDescription({type:"offer",sdp:offer.sdp}).then(function(){\n' +
    '      if(gen!==connectGen) return null;\n' +
    '      return pc.createAnswer();\n' +
    '    }).then(function(ans){\n' +
    '      if(!ans||gen!==connectGen) return null;\n' +
    '      return pc.setLocalDescription(ans);\n' +
    '    }).then(function(){\n' +
    '      if(gen!==connectGen) return null;\n' +
    '      return iceDone(pc);\n' +
    '    }).then(function(){\n' +
    '      if(gen!==connectGen) return null;\n' +
    '      return fetch("/answer",{method:"POST",headers:{"content-type":"application/json"},\n' +
    '        body:JSON.stringify({deviceId:DEVICE_ID,offerId:offer.offerId,sdp:pc.localDescription.sdp})});\n' +
    '    }).then(function(){\n' +
    '      // answer 올린 뒤 8초 안에 connected 안 되면 자동 재시도 (수동 새로고침과 동일)\n' +
    '      if(gen===connectGen) armWatch(gen, 8000);\n' +
    '    });\n' +
    '  }).catch(function(e){\n' +
    '    if(gen===connectGen){ st("\\uC624\\uB958: "+(e&&e.message?e.message:e)); schedule(); }\n' +
    '  });\n' +
    '}\n' +
    'function schedule(){\n' +
    '  if(connectTimer) clearTimeout(connectTimer);\n' +
    '  if(watchTimer){ clearTimeout(watchTimer); watchTimer=null; }\n' +
    '  clearDiscTimer();\n' +
    '  tries++;\n' +
    '  connectTimer=setTimeout(function(){ connectTimer=null; connect(); }, Math.min(400+tries*400, 3000));\n' +
    '}\n' +
    'connect();\n' +
    'var lastOk=Date.now(), connectingSince=0;\n' +
    'setInterval(function(){\n' +
    '  var cs=pc&&pc.connectionState;\n' +
    '  if(cs==="connected"){ lastOk=Date.now(); connectingSince=0; return; }\n' +
    '  if(cs==="disconnected"){ lastOk=Date.now(); return; }\n' +
    '  // connecting 고착: 8초 넘으면 강제 재연결 (예전 버그 — connecting을 정상으로 취급)\n' +
    '  if(cs==="connecting"||cs==="new"){\n' +
    '    if(!connectingSince) connectingSince=Date.now();\n' +
    '    if(Date.now()-connectingSince<8000) return;\n' +
    '  } else { connectingSince=0; }\n' +
    '  if(Date.now()-lastOk>12000||(connectingSince&&Date.now()-connectingSince>=8000)){\n' +
    '    lastOk=Date.now(); connectingSince=0;\n' +
    '    st("\\uC7AC\\uC5F0\\uACB0 \\uC911...");\n' +
    '    try{ if(pc) pc.close(); }catch(e){}\n' +
    '    schedule();\n' +
    '  }\n' +
    '}, 1000);\n' +
    // 브라우저 다시 켤 때(탭 복귀) 즉시 재시도\n' +
    'document.addEventListener("visibilitychange",function(){\n' +
    '  if(document.visibilityState!=="visible") return;\n' +
    '  var cs=pc&&pc.connectionState;\n' +
    '  if(cs==="connected") return;\n' +
    '  st("\\uC7AC\\uC5F0\\uACB0 \\uC911...");\n' +
    '  try{ if(pc) pc.close(); }catch(e){}\n' +
    '  schedule();\n' +
    '});\n' +
    '})();';

  return (
    '<!doctype html><html lang="ko"><head><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">' +
    '<title>TeslaMirror</title><style>' + css + '</style></head><body>' +
    '<div id="stage">' +
    '<div id="clkL"><div class="time" id="tL">--:--</div><div class="date" id="dL"></div></div>' +
    '<canvas id="c" class="pos-center"></canvas>' +
    '<div id="clkR"><div class="time" id="tR">--:--</div><div class="date" id="dR"></div></div>' +
    '</div>' +
    // connecting... — \\u only so CF paste cannot mojibake the status text
    '<div id="s">\uC5F0\uACB0 \uC911...</div><div id="st"></div>' +
    '<div id="bar">' +
    '<button type="button" id="bL" title="left">&lt;</button>' +
    '<button type="button" id="bC" title="center">o</button>' +
    '<button type="button" id="bR" title="right">&gt;</button>' +
    '</div>' +
    '<div id="edgeL"></div><div id="edgeR"></div><div id="edgeB"></div>' +
    '<div id="pie">' +
    '<div class="lab back">&#9664; Back</div>' +
    '<div class="lab home">Home</div>' +
    '<div class="lab cancel">Cancel</div>' +
    '<div id="pieDot"></div>' +
    '</div>' +
    '<script>' + js + '</script></body></html>'
  );
}
