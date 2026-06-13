package com.example.teslamirror.server

object ViewerHtml {

    val HTML = """
<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>TeslaMirror</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'><rect width='32' height='32' rx='5' fill='%230F1B3A'/><rect x='6' y='8' width='20' height='4' fill='white'/><rect x='14' y='12' width='4' height='14' fill='white'/></svg>">
<style>
  html,body{margin:0;height:100%;overflow:hidden;color:#fff;
    background:linear-gradient(135deg,var(--bg1,#0a0a1f),var(--bg2,#1a1a3a));
    font-family:system-ui,-apple-system,'Apple SD Gothic Neo','Malgun Gothic',sans-serif}
  .wrap{display:flex;height:100vh;width:100vw;align-items:stretch}
  .wrap.right{flex-direction:row-reverse}
  .panel{position:relative;flex:1;display:flex;flex-direction:column;
    justify-content:center;align-items:center;padding:48px;box-sizing:border-box;
    min-width:0;text-align:center;gap:18px}
  .stream{height:100vh;width:auto;max-width:100%;display:block;flex-shrink:0}
  .clock{font-size:clamp(96px,13vw,220px);font-weight:200;letter-spacing:-0.02em;
    line-height:1;font-variant-numeric:tabular-nums}
  .date{font-size:clamp(32px,4.2vw,76px);font-weight:300;letter-spacing:-0.01em;line-height:1.1}
  .day{font-size:clamp(20px,2.4vw,40px);opacity:.55;font-weight:400}
  .toggle{position:absolute;top:18px;right:18px;width:48px;height:48px;
    border-radius:50%;background:rgba(255,255,255,.08);color:#fff;
    border:1px solid rgba(255,255,255,.18);font-size:22px;cursor:pointer;
    display:flex;align-items:center;justify-content:center;
    -webkit-tap-highlight-color:transparent;opacity:.55;transition:opacity .2s;
    padding:0;line-height:1;z-index:2}
  .wrap.right .toggle{right:auto;left:18px}
  .toggle:hover,.toggle:active{opacity:1}
  .celestial{position:absolute;width:clamp(70px,7vw,120px);
    height:clamp(70px,7vw,120px);pointer-events:none;
    z-index:0;transform:translate(-50%,-50%);
    transition:left 30s linear,top 30s linear,filter 5s linear}
  .celestial svg{width:100%;height:100%;display:block;overflow:visible}
  .celestial.sun{filter:drop-shadow(0 0 18px rgba(255,167,38,.65))
    drop-shadow(0 0 60px rgba(255,152,0,.4))}
  .celestial.moon{filter:drop-shadow(0 0 14px rgba(255,213,79,.5))}
  .clock,.date,.day{position:relative;z-index:1}
</style>
</head><body>
<div class="wrap" id="wrap">
  <img class="stream" src="/stream" alt="">
  <div class="panel">
    <div class="celestial sun" id="celestial">
      <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet">
        <defs>
          <radialGradient id="sunGrad" cx="35%" cy="35%">
            <stop offset="0%" stop-color="#FFF9C4"/>
            <stop offset="55%" stop-color="#FFB74D"/>
            <stop offset="95%" stop-color="#FFA726"/>
          </radialGradient>
          <radialGradient id="moonGrad" cx="35%" cy="35%">
            <stop offset="0%" stop-color="#FFF9C4"/>
            <stop offset="55%" stop-color="#FFD54F"/>
            <stop offset="95%" stop-color="#FFB300"/>
          </radialGradient>
        </defs>
        <circle id="celBg" cx="50" cy="50" r="50"
          fill="rgba(255,235,150,0.08)" style="display:none"/>
        <path id="celPath" fill="url(#sunGrad)"
          d="M 50,0 A 50,50 0 0,1 50,100 A 50,50 0 0,1 50,0 Z"/>
      </svg>
    </div>
    <button class="toggle" id="toggle" aria-label="좌우 전환">⇄</button>
    <div class="clock" id="clock">--:--</div>
    <div class="date" id="date">--월 --일</div>
    <div class="day" id="day">---요일</div>
  </div>
</div>
<script>
  const pad = n => String(n).padStart(2,'0');
  const days = ['일','월','화','수','목','금','토'];
  const elClock = document.getElementById('clock');
  const elDate = document.getElementById('date');
  const elDay = document.getElementById('day');
  const wrap = document.getElementById('wrap');
  const toggle = document.getElementById('toggle');

  function tick(){
    const d = new Date();
    elClock.textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
    elDate.textContent = (d.getMonth()+1) + '월 ' + d.getDate() + '일';
    elDay.textContent = days[d.getDay()] + '요일';
  }
  tick();
  setInterval(tick, 1000);

  // 시간대별 배경 그라데이션 — 새벽/아침/낮/노을/밤이 자연스럽게 흐름
  const palette = [
    [0,  '#0a0a1f', '#16213e'],  // 깊은 밤
    [5,  '#16213e', '#3a1c5a'],  // 새벽 직전
    [7,  '#5b3a6f', '#c97b7b'],  // 새벽 (보라→분홍)
    [10, '#2d4a6e', '#3a7ba8'],  // 아침
    [14, '#2a4060', '#3a5e7e'],  // 오후
    [17, '#c25a35', '#7a3a6a'],  // 노을
    [19, '#5a3a8a', '#3a2c5e'],  // 황혼
    [22, '#1a1a3a', '#0a0a1f'],  // 야간
    [24, '#0a0a1f', '#16213e']
  ];

  function lerpColor(a, b, t){
    const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
    const ar = (pa>>16)&0xff, ag = (pa>>8)&0xff, ab = pa&0xff;
    const br = (pb>>16)&0xff, bg = (pb>>8)&0xff, bb = pb&0xff;
    const r = Math.round(ar + (br-ar)*t);
    const g = Math.round(ag + (bg-ag)*t);
    const b2 = Math.round(ab + (bb-ab)*t);
    return '#' + ((r<<16)|(g<<8)|b2).toString(16).padStart(6,'0');
  }

  function updateBackground(){
    const d = new Date();
    const h = d.getHours() + d.getMinutes()/60;
    let i = 0;
    for (; i < palette.length-1; i++) {
      if (h < palette[i+1][0]) break;
    }
    const [h1, c1a, c1b] = palette[i];
    const [h2, c2a, c2b] = palette[i+1];
    const t = (h - h1) / (h2 - h1);
    document.documentElement.style.setProperty('--bg1', lerpColor(c1a, c2a, t));
    document.documentElement.style.setProperty('--bg2', lerpColor(c1b, c2b, t));
  }
  updateBackground();
  setInterval(updateBackground, 60000);

  // 해/달 호(arc) + 달 위상(phase). 6-18시: 해, 18-6시: 달.
  const elCel = document.getElementById('celestial');
  const elCelPath = document.getElementById('celPath');
  const elCelBg = document.getElementById('celBg');
  const SUN_PATH = 'M 50,0 A 50,50 0 0,1 50,100 A 50,50 0 0,1 50,0 Z';

  function moonPhase(date){
    // 0=신월, 0.25=상현, 0.5=보름, 0.75=하현
    const cycle = 29.53058867;
    const ref = Date.UTC(2000, 0, 6, 18, 14, 0); // 기준 신월
    const days = (date.getTime() - ref) / 86400000;
    const p = (days % cycle) / cycle;
    return p < 0 ? p + 1 : p;
  }

  function moonPathD(phase){
    // 두 호로 둘러싸인 path: 외곽 반원 + 종단선(terminator) 타원호
    const angle = phase * 2 * Math.PI;
    const cosV = Math.cos(angle);
    const rx = Math.abs(cosV) * 50;
    const isWaxing = phase < 0.5;
    const outerSweep = isWaxing ? 1 : 0;
    const isCrescent = cosV > 0;
    const termSweep = isCrescent ? outerSweep : 1 - outerSweep;
    return 'M 50,0 A 50,50 0 0,' + outerSweep
      + ' 50,100 A ' + rx + ',50 0 0,' + termSweep + ' 50,0 Z';
  }

  function updateCelestial(){
    const d = new Date();
    const h = d.getHours() + d.getMinutes()/60;
    let isDay, t;
    if (h >= 6 && h < 18) {
      isDay = true;
      t = (h - 6) / 12;
    } else {
      isDay = false;
      t = h < 6 ? (h + 6) / 12 : (h - 18) / 12;
    }
    const x = 15 + t * 70;
    const y = 40 - Math.sin(t * Math.PI) * 35;
    elCel.style.left = x + '%';
    elCel.style.top = y + '%';
    elCel.className = 'celestial ' + (isDay ? 'sun' : 'moon');
    if (isDay) {
      elCelPath.setAttribute('d', SUN_PATH);
      elCelPath.setAttribute('fill', 'url(#sunGrad)');
      elCelBg.style.display = 'none';
    } else {
      elCelPath.setAttribute('d', moonPathD(moonPhase(d)));
      elCelPath.setAttribute('fill', 'url(#moonGrad)');
      elCelBg.style.display = '';
    }
  }
  updateCelestial();
  setInterval(updateCelestial, 60000);

  // 좌/우 전환 + 사용자 선호 저장
  try {
    if (localStorage.getItem('mirrorSide') === 'right') wrap.classList.add('right');
  } catch(_) {}
  toggle.addEventListener('click', () => {
    wrap.classList.toggle('right');
    const side = wrap.classList.contains('right') ? 'right' : 'left';
    try { localStorage.setItem('mirrorSide', side); } catch(_) {}
  });
</script>
</body></html>
""".trimIndent()
}
