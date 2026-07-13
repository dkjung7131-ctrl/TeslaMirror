// TeslaMirror 접선(rendezvous) 서버 — Cloudflare Worker
//
// 폰: 핫스팟 IP가 바뀔 때마다 POST /register 로 자기 IP를 등록
// 테슬라: GET / 하나만 북마크 — 워커가 요청의 공인 IP를 보고
//        "이 테슬라가 붙어 있는 폰"의 핫스팟 IP로 302 리다이렉트
//
// 원리: 테슬라의 인터넷 트래픽은 폰 핫스팟 → 폰 셀룰러로 나가므로,
// 폰의 등록 요청과 그 폰에 붙은 테슬라의 접속 요청은 같은 공인 IP로 보인다.
// 매칭이 애매하면(통신사 CGNAT 변수) 등록된 폰 목록을 버튼으로 보여준다.
//
// 필요한 바인딩 (DEPLOY.md 참고):
//   KV namespace: PHONES
//   Secret:       SECRET (앱에 입력하는 값과 동일)

const MIRROR_PORT = 8080;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/register' && request.method === 'POST') {
      if ((request.headers.get('Authorization') || '') !== `Bearer ${env.SECRET}`) {
        return new Response('unauthorized', { status: 401 });
      }
      let body;
      try { body = await request.json(); } catch { return new Response('bad json', { status: 400 }); }
      const { deviceId, name, hotspotIp } = body || {};
      if (!deviceId || !isPrivateIpv4(hotspotIp)) {
        return new Response('bad request', { status: 400 });
      }
      const entry = {
        name: String(name || 'phone').slice(0, 40),
        hotspotIp,
        publicIp: request.headers.get('CF-Connecting-IP') || '',
        ts: Date.now(),
      };
      // 24시간 뒤 자동 소멸 — 오래 안 쓴 폰은 목록에서 사라진다
      await env.PHONES.put('dev:' + String(deviceId).slice(0, 64), JSON.stringify(entry), {
        expirationTtl: 86400,
      });
      return new Response('OK');
    }

    if (request.method === 'GET') {
      const entries = [];
      const list = await env.PHONES.list({ prefix: 'dev:' });
      for (const k of list.keys) {
        const v = await env.PHONES.get(k.name, 'json');
        if (v) entries.push(v);
      }
      entries.sort((a, b) => b.ts - a.ts);

      const myIp = request.headers.get('CF-Connecting-IP') || '';
      const matches = entries.filter((e) => samePeer(e.publicIp, myIp));
      // 공인 IP가 정확히 한 폰과 일치하면 바로 그 폰으로.
      // 일치하는 폰이 없어도 등록된 폰이 하나뿐이면 그 폰으로 (1대 사용자의 CGNAT 변수 흡수).
      const pick =
        matches.length === 1 ? matches[0]
        : matches.length === 0 && entries.length === 1 ? entries[0]
        : null;
      if (pick) {
        return Response.redirect(`http://${pick.hotspotIp}:${MIRROR_PORT}/`, 302);
      }
      return new Response(chooserHtml(matches.length ? matches : entries), {
        headers: { 'content-type': 'text/html; charset=utf-8' },
      });
    }

    return new Response('not found', { status: 404 });
  },
};

function isPrivateIpv4(ip) {
  if (typeof ip !== 'string') return false;
  const m = ip.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return false;
  const [a, b, c, d] = m.slice(1).map(Number);
  if ([a, b, c, d].some((n) => n > 255)) return false;
  return a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31);
}

// 같은 폰 뒤에서 나온 트래픽인지 판단.
// IPv4는 정확히 일치해야 하고, IPv6는 /64 프리픽스만 비교한다
// (통신사 IPv6는 같은 단말이라도 뒷부분 주소가 요청마다 다를 수 있음).
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
  return s.replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

// 자동 매칭이 안 될 때: 폰 목록을 큰 버튼으로 — 테슬라 터치스크린 기준
function chooserHtml(entries) {
  const items = entries
    .map(
      (e) =>
        `<a class="btn" href="http://${e.hotspotIp}:${MIRROR_PORT}/">` +
        `${escapeHtml(e.name)}<span>${e.hotspotIp}</span></a>`
    )
    .join('\n');
  return `<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TeslaMirror</title>
<style>
  body{font-family:sans-serif;background:#111;color:#eee;display:flex;flex-direction:column;align-items:center;padding:40px 16px;gap:16px}
  h1{font-size:28px;margin:0 0 8px}
  .btn{display:flex;flex-direction:column;gap:4px;width:100%;max-width:480px;background:#2563eb;color:#fff;text-decoration:none;padding:22px 24px;border-radius:14px;font-size:24px;font-weight:600;text-align:center}
  .btn span{font-size:15px;font-weight:400;opacity:.8}
  p{opacity:.7;font-size:17px}
</style></head><body>
<h1>TeslaMirror</h1>
${items || '<p>등록된 폰이 없습니다.<br>폰에서 핫스팟을 켜고 앱을 실행하세요.</p>'}
${items ? '<p>접속할 폰을 선택하세요</p>' : ''}
</body></html>`;
}
