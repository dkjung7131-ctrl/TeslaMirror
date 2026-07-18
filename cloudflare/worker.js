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
      await env.PHONES.put('offer:' + b.deviceId, JSON.stringify({ offerId: b.offerId, sdp: b.sdp, ts: Date.now() }), { expirationTtl: SIGNAL_TTL });
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
      const matches = entries.filter((e) => samePeer(e.publicIp, myIp));
      const pick = matches.length >= 1 ? matches[0] : entries.length === 1 ? entries[0] : null;
      if (pick) return html(viewerHtml(pick.deviceId));
      return html(chooserHtml(entries, myIp));
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
function ageText(ts) {
  const m = Math.floor((Date.now() - ts) / 60000);
  if (m < 1) return '방금 등록';
  if (m < 60) return m + '분 전 등록';
  const h = Math.floor(m / 60);
  if (h < 24) return h + '시간 전 등록';
  return Math.floor(h / 24) + '일 전 등록';
}

function chooserHtml(entries, myIp) {
  const items = entries
    .map((e) => {
      const same = samePeer(e.publicIp, myIp);
      const badge = same
        ? '<b style="color:#4ade80">✓ 이 화면과 같은 네트워크</b>'
        : '<b style="color:#fbbf24">⚠ 다른 네트워크</b>';
      return `<a class="btn" href="/?id=${encodeURIComponent(e.deviceId)}">${escapeHtml(e.name)}<span>${ageText(e.ts)}</span>${badge}</a>`;
    })
    .join('\n');
  return `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>TeslaMirror</title><style>
  body{font-family:sans-serif;background:#111;color:#eee;display:flex;flex-direction:column;align-items:center;padding:40px 16px;gap:16px}
  h1{font-size:28px;margin:0 0 8px}
  .btn{display:flex;flex-direction:column;gap:4px;width:100%;max-width:480px;background:#2563eb;color:#fff;text-decoration:none;padding:22px 24px;border-radius:14px;font-size:24px;font-weight:600;text-align:center}
  .btn span{font-size:15px;font-weight:400;opacity:.8}.btn b{font-size:14px;font-weight:600}
  p{opacity:.7;font-size:17px}
</style></head><body>
<h1>TeslaMirror</h1>
${items || '<p>등록된 폰이 없습니다.<br>폰에서 핫스팟을 켜고 앱을 실행하세요.</p>'}
${items ? '<p>접속할 폰을 선택하세요</p>' : ''}
</body></html>`;
}

// WebRTC 뷰어 — 문자열 연결로 생성(큰 템플릿 리터럴은 CF 대시보드 붙여넣기에서 깨지기 쉬움).
// 좌/중/우 정렬 + 여백 시계.
function viewerHtml(deviceId) {
  var idLit = JSON.stringify(String(deviceId));
  var css =
    'html,body{margin:0;height:100%;background:#000;overflow:hidden;' +
    'font-family:system-ui,sans-serif;color:#fff;-webkit-user-select:none;user-select:none}' +
    '#stage{position:fixed;inset:0;background:#000}' +
    '#c{position:absolute;top:50%;transform:translateY(-50%);' +
    'max-width:100%;max-height:100%;width:auto;height:auto;background:#000;z-index:2}' +
    '#c.pos-left{left:0;right:auto}' +
    '#c.pos-center{left:50%;right:auto;transform:translate(-50%,-50%)}' +
    '#c.pos-right{left:auto;right:0}' +
    '#clkL,#clkR{position:fixed;top:0;bottom:0;width:28%;display:flex;flex-direction:column;' +
    'align-items:center;justify-content:center;z-index:1;pointer-events:none;opacity:0;transition:opacity .25s}' +
    '#clkL{left:0}#clkR{right:0}#clkL.show,#clkR.show{opacity:1}' +
    '.time{font-size:48px;font-weight:300;letter-spacing:.04em;opacity:.92}' +
    '.date{font-size:16px;opacity:.55;margin-top:10px}' +
    '#s{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);font-size:20px;' +
    'opacity:.85;text-align:center;line-height:1.6;z-index:5}' +
    '#st{position:fixed;left:8px;bottom:8px;font-size:12px;font-family:monospace;color:#7f7;' +
    'background:rgba(0,0,0,.5);padding:4px 8px;border-radius:6px;z-index:9}' +
    '#bar{position:fixed;right:10px;bottom:10px;display:flex;gap:8px;z-index:10}' +
    '#bar button{width:48px;height:48px;border-radius:12px;border:1px solid rgba(255,255,255,.28);' +
    'background:rgba(0,0,0,.45);color:#fff;font-size:18px;cursor:pointer}' +
    '#bar button.on{background:rgba(37,99,235,.75);border-color:rgba(147,197,253,.8)}';

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
    'window.addEventListener("resize", updateClocksVisible);\n' +
    'function iceDone(pc){ return new Promise(function(res){\n' +
    '  if(pc.iceGatheringState==="complete") return res();\n' +
    '  var t=setTimeout(res,3000);\n' +
    '  pc.addEventListener("icegatheringstatechange",function(){\n' +
    '    if(pc.iceGatheringState==="complete"){ clearTimeout(t); res(); }\n' +
    '  });\n' +
    '}); }\n' +
    'var pc=null, tries=0, drawing=false, fpsCount=0, lastFpsT=Date.now(), fps=0;\n' +
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
    'async function connect(){\n' +
    '  try{\n' +
    '    var r=await fetch("/offer?id="+encodeURIComponent(DEVICE_ID),{cache:"no-store"});\n' +
    '    if(!r.ok){ st("폰 대기 중... (앱에서 미러링 시작)"); return schedule(); }\n' +
    '    var offer=await r.json();\n' +
    '    if(pc){ try{pc.close();}catch(e){} }\n' +
    '    pc=new RTCPeerConnection({iceServers:[\n' +
    '      {urls:"stun:stun.l.google.com:19302"},\n' +
    '      {urls:"stun:stun1.l.google.com:19302"}\n' +
    '    ]});\n' +
    '    window.pc=pc;\n' +
    '    pc.ondatachannel=function(e){\n' +
    '      var dc=e.channel; dc.binaryType="arraybuffer";\n' +
    '      dc.onmessage=function(ev){ drawJpeg(ev.data); };\n' +
    '    };\n' +
    '    pc.onconnectionstatechange=function(){\n' +
    '      if(pc.connectionState==="connected") st("");\n' +
    '      if(pc.connectionState==="failed"||pc.connectionState==="disconnected"||pc.connectionState==="closed"){\n' +
    '        st("재연결 중..."); schedule();\n' +
    '      }\n' +
    '    };\n' +
    '    await pc.setRemoteDescription({type:"offer",sdp:offer.sdp});\n' +
    '    var ans=await pc.createAnswer();\n' +
    '    await pc.setLocalDescription(ans);\n' +
    '    await iceDone(pc);\n' +
    '    await fetch("/answer",{method:"POST",headers:{"content-type":"application/json"},\n' +
    '      body:JSON.stringify({deviceId:DEVICE_ID,offerId:offer.offerId,sdp:pc.localDescription.sdp})});\n' +
    '  }catch(e){ st("오류: "+e.message); schedule(); }\n' +
    '}\n' +
    'function schedule(){ tries++; setTimeout(connect, Math.min(700+tries*400,3000)); }\n' +
    'connect();\n' +
    'setInterval(async function(){\n' +
    '  if(!pc || pc.connectionState!=="connected"){ stEl.textContent=""; return; }\n' +
    '  try{\n' +
    '    var stats=await pc.getStats(), pair=null, cands={};\n' +
    '    stats.forEach(function(x){ if(x.type==="local-candidate"||x.type==="remote-candidate") cands[x.id]=x; });\n' +
    '    stats.forEach(function(x){ if(x.type==="candidate-pair" && x.nominated && x.state==="succeeded") pair=x; });\n' +
    '    if(!pair){ stats.forEach(function(x){ if(x.type==="transport" && x.selectedCandidatePairId) pair=stats.get(x.selectedCandidatePairId); }); }\n' +
    '    var rtt="?"; if(pair && pair.currentRoundTripTime!=null) rtt=Math.round(pair.currentRoundTripTime*1000)+"ms";\n' +
    '    var lt=pair?(cands[pair.localCandidateId]||{}).candidateType:"?";\n' +
    '    stEl.textContent=lt+" rtt "+rtt+" | "+fps+"fps";\n' +
    '  }catch(e){}\n' +
    '}, 1000);\n' +
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
    '<div id="s">연결 중...</div><div id="st"></div>' +
    '<div id="bar">' +
    '<button type="button" id="bL" title="left">&lt;</button>' +
    '<button type="button" id="bC" title="center">o</button>' +
    '<button type="button" id="bR" title="right">&gt;</button>' +
    '</div>' +
    '<script>' + js + '</script></body></html>'
  );
}
