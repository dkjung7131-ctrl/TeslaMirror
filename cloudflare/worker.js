// TeslaMirror 시그널링 서버 — Cloudflare Worker
//
// 테슬라 브라우저는 사설 IP(10.x/192.168.x) 직접 접속을 막는다(about:blank#blocked).
// 그래서 공개 HTTPS 페이지를 워커가 서빙하고, 폰↔테슬라를 WebRTC로 P2P 연결한다.
// ICE가 같은 핫스팟의 로컬 경로를 찾으므로 영상은 로컬 직통(저지연·무과금)으로 흐르고,
// 페이지 origin은 공개라 사설 IP 차단을 원천 회피한다. (Tesor와 동일한 접근)
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

// WebRTC 뷰어 — 공개 HTTPS 페이지. 폰의 오퍼를 받아 앤서를 올리고, JPEG 프레임을
// 데이터 채널로 받아 캔버스에 즉시 그린다(버퍼 없음 → 내비 실시간성).
function viewerHtml(deviceId) {
  return `<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>TeslaMirror</title><style>
  html,body{margin:0;height:100%;background:#000;overflow:hidden;font-family:sans-serif;color:#fff}
  #c{position:fixed;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
  #s{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);font-size:20px;opacity:.85;text-align:center;line-height:1.6}
  #st{position:fixed;left:8px;bottom:8px;font-size:12px;font-family:monospace;color:#7f7;background:rgba(0,0,0,.5);padding:4px 8px;border-radius:6px;z-index:9}
</style></head><body>
<canvas id="c"></canvas>
<div id="s">연결 중…</div>
<div id="st"></div>
<script>
(function(){
  var DEVICE_ID=${JSON.stringify(String(deviceId)).replace(/</g, '\\u003c')};
  var s=document.getElementById('s'), stEl=document.getElementById('st');
  var canvas=document.getElementById('c'), ctx=canvas.getContext('2d');
  function st(t){ s.style.display=t?'':'none'; s.textContent=t||''; }
  function iceDone(pc){ return new Promise(function(res){
    if(pc.iceGatheringState==='complete') return res();
    var t=setTimeout(res,3000);
    pc.addEventListener('icegatheringstatechange',function(){ if(pc.iceGatheringState==='complete'){clearTimeout(t);res();} });
  }); }
  var pc=null, tries=0, drawing=false, fpsCount=0, lastFpsT=Date.now(), fps=0;
  function drawJpeg(buf){
    if(drawing) return;              // 디코드 중이면 새 프레임은 버림(백로그 방지 → 최신 우선)
    drawing=true;
    var blob=new Blob([buf],{type:'image/jpeg'});
    createImageBitmap(blob).then(function(bmp){
      if(canvas.width!==bmp.width){ canvas.width=bmp.width; canvas.height=bmp.height; }
      ctx.drawImage(bmp,0,0); bmp.close(); drawing=false;
      st(''); fpsCount++;
      var now=Date.now(); if(now-lastFpsT>=1000){ fps=fpsCount; fpsCount=0; lastFpsT=now; }
    }).catch(function(){ drawing=false; });
  }
  async function connect(){
    try{
      var r=await fetch('/offer?id='+encodeURIComponent(DEVICE_ID),{cache:'no-store'});
      if(!r.ok){ st('폰 대기 중… (앱에서 미러링을 시작하세요)'); return schedule(); }
      var offer=await r.json();
      if(pc){ try{pc.close();}catch(e){} }
      pc=new RTCPeerConnection({iceServers:[]});   // STUN 없음: 로컬 host만
      window.pc=pc;
      pc.ondatachannel=function(e){
        var dc=e.channel; dc.binaryType='arraybuffer';
        dc.onmessage=function(ev){ drawJpeg(ev.data); };
      };
      pc.onconnectionstatechange=function(){
        if(pc.connectionState==='connected') st('');
        if(pc.connectionState==='failed'||pc.connectionState==='disconnected'||pc.connectionState==='closed'){ st('재연결 중…'); schedule(); }
      };
      await pc.setRemoteDescription({type:'offer',sdp:offer.sdp});
      var ans=await pc.createAnswer();
      await pc.setLocalDescription(ans);
      await iceDone(pc);
      await fetch('/answer',{method:'POST',headers:{'content-type':'application/json'},
        body:JSON.stringify({deviceId:DEVICE_ID,offerId:offer.offerId,sdp:pc.localDescription.sdp})});
    }catch(e){ st('오류: '+e.message); schedule(); }
  }
  function schedule(){ tries++; setTimeout(connect, Math.min(700+tries*400,3000)); }
  connect();
  // 경로/지연/fps 표시
  setInterval(async function(){
    if(!pc || pc.connectionState!=='connected'){ stEl.textContent=''; return; }
    try{
      var stats=await pc.getStats(), pair=null, cands={};
      stats.forEach(function(x){ if(x.type==='local-candidate'||x.type==='remote-candidate') cands[x.id]=x; });
      stats.forEach(function(x){ if(x.type==='candidate-pair' && x.nominated && x.state==='succeeded') pair=x; });
      if(!pair){ stats.forEach(function(x){ if(x.type==='transport' && x.selectedCandidatePairId) pair=stats.get(x.selectedCandidatePairId); }); }
      var rtt='?'; if(pair && pair.currentRoundTripTime!=null) rtt=Math.round(pair.currentRoundTripTime*1000)+'ms';
      var lt=pair?(cands[pair.localCandidateId]||{}).candidateType:'?';
      stEl.textContent=lt+' rtt '+rtt+' · '+fps+'fps';
    }catch(e){}
  }, 1000);
})();
</script>
</body></html>`;
}
