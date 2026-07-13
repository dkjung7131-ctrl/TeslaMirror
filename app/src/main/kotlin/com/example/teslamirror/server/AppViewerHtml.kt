package com.example.teslamirror.server

/**
 * 앱(H.264) 모드 뷰어. 테슬라 브라우저에서 열림.
 *
 * WebSocket /ws 로 H.264 패킷을 받아 재생:
 *   - WebCodecs(VideoDecoder) 사용 가능(보안 컨텍스트) → 저지연 경로
 *   - 아니면 MSE/fMP4 로 폴백 (구형 테슬라 브라우저, HTTP 환경)
 * 터치/키/뒤로가기 이벤트를 JSON 으로 서버에 역전송.
 *
 * 주의: JS 안에서 '$' 는 Kotlin 문자열 보간과 충돌하므로 사용하지 않는다
 * (템플릿 리터럴 대신 문자열 연결 사용).
 */
object AppViewerHtml {

    val HTML = """
<!doctype html>
<html lang="ko"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>TeslaMirror</title>
<style>
  html,body{margin:0;height:100%;background:#000;overflow:hidden;
    font-family:system-ui,-apple-system,'Malgun Gothic',sans-serif;color:#fff;
    -webkit-user-select:none;user-select:none;touch-action:none}
  #stage{position:fixed;inset:0;display:flex;align-items:center;justify-content:center}
  #video,#canvas{max-width:100%;max-height:100%;width:auto;height:auto;
    background:#000;display:none}
  #bar{position:fixed;left:0;top:0;bottom:0;width:56px;display:flex;
    flex-direction:column;justify-content:center;gap:16px;align-items:center;
    background:rgba(0,0,0,.35);z-index:5}
  .btn{width:44px;height:44px;border-radius:50%;border:1px solid rgba(255,255,255,.25);
    background:rgba(255,255,255,.08);color:#fff;font-size:20px;
    display:flex;align-items:center;justify-content:center;cursor:pointer;
    -webkit-tap-highlight-color:transparent}
  #status{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);
    font-size:20px;opacity:.8;text-align:center;line-height:1.6;z-index:4}
</style>
</head><body>
<div id="stage">
  <video id="video" playsinline muted autoplay></video>
  <canvas id="canvas"></canvas>
</div>
<div id="bar">
  <div class="btn" id="back" title="뒤로">‹</div>
  <div class="btn" id="reload" title="새로고침">⟳</div>
</div>
<div id="status">연결 중…</div>
<script>
(function(){
  var WS_PROTO = location.protocol === 'https:' ? 'wss' : 'ws';
  var dims = { w: 1280, h: 800 };
  var statusEl = document.getElementById('status');
  var videoEl = document.getElementById('video');
  var canvasEl = document.getElementById('canvas');
  var stageEl = document.getElementById('stage');
  var ws = null, decoder = null, activeEl = null;

  function setStatus(t){ statusEl.style.display = t ? '' : 'none'; statusEl.textContent = t || ''; }

  // ---------- NAL 유틸 ----------
  function findNals(buf){ // Annex-B → NAL 바이트 배열 목록(스타트코드 제외)
    var nals = [], i = 0, n = buf.length;
    function sc(p){ // 스타트코드 길이 반환(3/4), 아니면 0
      if (p+3 < n && buf[p]===0 && buf[p+1]===0 && buf[p+2]===0 && buf[p+3]===1) return 4;
      if (p+2 < n && buf[p]===0 && buf[p+1]===0 && buf[p+2]===1) return 3;
      return 0;
    }
    while (i < n){
      var s = sc(i);
      if (!s){ i++; continue; }
      var start = i + s;
      var j = start;
      while (j < n && !sc(j)) j++;
      nals.push(buf.subarray(start, j));
      i = j;
    }
    return nals;
  }

  var spsBytes = null, ppsBytes = null, codecStr = 'avc1.42e01e';
  function parseConfig(payload){
    var nals = findNals(payload);
    for (var k = 0; k < nals.length; k++){
      var t = nals[k][0] & 0x1f;
      if (t === 7) spsBytes = nals[k];
      else if (t === 8) ppsBytes = nals[k];
    }
    if (spsBytes){
      var h = function(b){ return ('0'+(b & 0xff).toString(16)).slice(-2); };
      codecStr = 'avc1.' + h(spsBytes[1]) + h(spsBytes[2]) + h(spsBytes[3]);
    }
  }

  // ---------- WebCodecs 경로 ----------
  function WebCodecsPlayer(){
    var vd = null, configured = false;
    canvasEl.width = dims.w; canvasEl.height = dims.h;
    var ctx = canvasEl.getContext('2d');
    activeEl = canvasEl; canvasEl.style.display = ''; videoEl.style.display = 'none';
    this.config = function(){
      vd = new VideoDecoder({
        output: function(frame){ ctx.drawImage(frame, 0, 0, canvasEl.width, canvasEl.height); frame.close(); },
        error: function(e){ setStatus('디코더 오류, 재접속…'); reconnect(); }
      });
      vd.configure({ codec: codecStr, optimizeForLatency: true });
      configured = true;
    };
    this.feed = function(payload, isKey){
      if (!configured) return;
      var data = payload;
      if (isKey && spsBytes && ppsBytes){
        // 키프레임 앞에 SPS/PPS 를 붙여 Annex-B 로 투입
        var sc = new Uint8Array([0,0,0,1]);
        data = concatArrays([sc, spsBytes, sc, ppsBytes, payload]);
      }
      try{
        vd.decode(new EncodedVideoChunk({
          type: isKey ? 'key' : 'delta', timestamp: performance.now()*1000, data: data
        }));
        setStatus('');
      }catch(e){ /* 키프레임 대기 중 delta 투입 실패 등 */ }
    };
    this.close = function(){ try{ vd && vd.close(); }catch(e){} };
  }

  // ---------- MSE / fMP4 경로 ----------
  function concatArrays(list){
    var len = 0, i; for (i=0;i<list.length;i++) len += list[i].length;
    var out = new Uint8Array(len), o = 0;
    for (i=0;i<list.length;i++){ out.set(list[i], o); o += list[i].length; }
    return out;
  }
  function u32(n){ return new Uint8Array([(n>>>24)&255,(n>>>16)&255,(n>>>8)&255,n&255]); }
  function u16(n){ return new Uint8Array([(n>>>8)&255,n&255]); }
  function str4(s){ return new Uint8Array([s.charCodeAt(0),s.charCodeAt(1),s.charCodeAt(2),s.charCodeAt(3)]); }
  function box(type){
    var kids = Array.prototype.slice.call(arguments,1);
    var body = concatArrays(kids);
    return concatArrays([u32(body.length+8), str4(type), body]);
  }
  function fullbox(type, version, flags, rest){
    var vf = new Uint8Array([version,(flags>>>16)&255,(flags>>>8)&255,flags&255]);
    return box(type, concatArrays([vf, rest]));
  }

  function MsePlayer(){
    var TS = 90000, DUR = 3000; // 30fps 가정(재생엔 무해)
    var ms = null, sb = null, queue = [], baseDts = 0, inited = false, started = false;
    activeEl = videoEl; videoEl.style.display = ''; canvasEl.style.display = 'none';

    function avcC(){
      return box('avcC', concatArrays([
        new Uint8Array([1, spsBytes[1], spsBytes[2], spsBytes[3], 0xff, 0xe1]),
        u16(spsBytes.length), spsBytes,
        new Uint8Array([1]), u16(ppsBytes.length), ppsBytes
      ]));
    }
    function avc1(){
      var pre = concatArrays([
        new Uint8Array([0,0,0,0,0,0]), u16(1),              // reserved, data_ref_index
        new Uint8Array(16),                                  // predefined/reserved
        u16(dims.w), u16(dims.h),
        u32(0x00480000), u32(0x00480000), u32(0),            // resolutions, reserved
        u16(1), new Uint8Array(32),                          // frame_count, compressorname
        u16(0x0018), u16(0xffff)                             // depth, predefined
      ]);
      return box('avc1', pre, avcC());
    }
    function initSegment(){
      var ftyp = box('ftyp', str4('isom'), u32(0x200), str4('isom'), str4('iso2'), str4('avc1'), str4('mp41'));
      var mvhd = fullbox('mvhd',0,0, concatArrays([u32(0),u32(0),u32(TS),u32(0),
        u32(0x00010000), u16(0x0100), u16(0),
        u32(0),u32(0),
        u32(0x00010000),u32(0),u32(0), u32(0),u32(0x00010000),u32(0), u32(0),u32(0),u32(0x40000000),
        u32(0),u32(0),u32(0),u32(0),u32(0),u32(0),
        u32(2)]));
      var tkhd = fullbox('tkhd',0,7, concatArrays([u32(0),u32(0),u32(1),u32(0),u32(0),
        u32(0),u32(0), u16(0),u16(0), u16(0),u16(0),
        u32(0x00010000),u32(0),u32(0), u32(0),u32(0x00010000),u32(0), u32(0),u32(0),u32(0x40000000),
        u16(dims.w),u16(0), u16(dims.h),u16(0)]));
      var mdhd = fullbox('mdhd',0,0, concatArrays([u32(0),u32(0),u32(TS),u32(0), u16(0x55c4),u16(0)]));
      var hdlr = fullbox('hdlr',0,0, concatArrays([u32(0),str4('vide'),u32(0),u32(0),u32(0),
        new Uint8Array([0x56,0x69,0x64,0x65,0x6f,0x48,0x61,0x6e,0x64,0x6c,0x65,0x72,0])])); // "VideoHandler\0"
      var vmhd = fullbox('vmhd',0,1, concatArrays([u16(0),u16(0),u16(0),u16(0)]));
      var dref = fullbox('dref',0,0, concatArrays([u32(1), fullbox('url ',0,1,new Uint8Array(0))]));
      var dinf = box('dinf', dref);
      var stsd = fullbox('stsd',0,0, concatArrays([u32(1), avc1()]));
      var stts = fullbox('stts',0,0, u32(0));
      var stsc = fullbox('stsc',0,0, u32(0));
      var stsz = fullbox('stsz',0,0, concatArrays([u32(0),u32(0)]));
      var stco = fullbox('stco',0,0, u32(0));
      var stbl = box('stbl', stsd, stts, stsc, stsz, stco);
      var minf = box('minf', vmhd, dinf, stbl);
      var mdia = box('mdia', mdhd, hdlr, minf);
      var trak = box('trak', tkhd, mdia);
      var trex = fullbox('trex',0,0, concatArrays([u32(1),u32(1),u32(0),u32(0),u32(0)]));
      var mvex = box('mvex', trex);
      var moov = box('moov', mvhd, trak, mvex);
      return concatArrays([ftyp, moov]);
    }
    function mediaSegment(avccData, isKey, seq){
      var flags = isKey ? 0x02000000 : 0x00010000;
      var mfhd = fullbox('mfhd',0,0, u32(seq));
      var tfhd = fullbox('tfhd',0,0x020000, u32(1)); // default-base-is-moof
      var tfdt = fullbox('tfdt',1,0, concatArrays([u32(0),u32(baseDts)]));
      // trun: flags 0x000f01 = data-offset + first-sample-flags + sample-duration + sample-size
      var trunHeaderLen = 8 /*box*/ + 4 /*ver+flags*/ + 4 /*count*/ + 4 /*dataoffset*/ + 4 /*firstflags*/ + 8 /*dur+size*/;
      var moofLen = 8 + mfhd.length + (8 + tfhd.length + tfdt.length + trunHeaderLen);
      var dataOffset = moofLen + 8; // moof + mdat header
      var trun = fullbox('trun',0,0x000f01, concatArrays([
        u32(1), u32(dataOffset), u32(flags), u32(DUR), u32(avccData.length)
      ]));
      var traf = box('traf', tfhd, tfdt, trun);
      var moof = box('moof', mfhd, traf);
      var mdat = box('mdat', avccData);
      baseDts += DUR;
      return concatArrays([moof, mdat]);
    }

    var self = this, seq = 1;
    function pump(){
      if (!sb || sb.updating || queue.length === 0) return;
      try { sb.appendBuffer(queue.shift()); } catch(e){ setStatus('재생 오류, 재접속…'); reconnect(); }
    }
    this.config = function(){
      ms = new MediaSource();
      videoEl.src = URL.createObjectURL(ms);
      ms.addEventListener('sourceopen', function(){
        sb = ms.addSourceBuffer('video/mp4; codecs="' + codecStr + '"');
        sb.addEventListener('updateend', pump);
        queue.push(initSegment());
        inited = true;
        pump();
      });
    };
    this.feed = function(payload, isKey){
      if (!inited) return;
      var nals = findNals(payload), parts = [], i;
      for (i=0;i<nals.length;i++){ parts.push(u32(nals[i].length)); parts.push(nals[i]); }
      var avccData = concatArrays(parts);
      queue.push(mediaSegment(avccData, isKey, seq++));
      pump();
      if (!started){ started = true; videoEl.play().catch(function(){}); setStatus(''); }
    };
    this.close = function(){ try{ if(ms && ms.readyState==='open') ms.endOfStream(); }catch(e){} };
  }

  // ---------- 디코더 선택 ----------
  function makePlayer(){
    var canWebCodecs = (typeof window.VideoDecoder === 'function') && window.isSecureContext;
    return canWebCodecs ? new WebCodecsPlayer() : new MsePlayer();
  }

  // ---------- 좌표 매핑 & 입력 ----------
  function mapCoords(clientX, clientY){
    var el = activeEl || stageEl;
    var r = el.getBoundingClientRect();
    // object-fit: contain 기준 렌더 영역 계산
    var elRatio = r.width / r.height, vidRatio = dims.w / dims.h, rw, rh, ox, oy;
    if (elRatio > vidRatio){ rh = r.height; rw = rh * vidRatio; ox = (r.width - rw)/2; oy = 0; }
    else { rw = r.width; rh = rw / vidRatio; ox = 0; oy = (r.height - rh)/2; }
    var x = (clientX - r.left - ox) / rw * dims.w;
    var y = (clientY - r.top - oy) / rh * dims.h;
    return { x: Math.max(0, Math.min(dims.w-1, Math.round(x))),
             y: Math.max(0, Math.min(dims.h-1, Math.round(y))) };
  }
  function sendJson(o){ try{ ws && ws.readyState===1 && ws.send(JSON.stringify(o)); }catch(e){} }

  var pressed = false;
  stageEl.addEventListener('pointerdown', function(e){
    pressed = true; try{ stageEl.setPointerCapture(e.pointerId); }catch(_){}
    var c = mapCoords(e.clientX, e.clientY); sendJson({t:'touch',a:0,x:c.x,y:c.y}); e.preventDefault();
  });
  stageEl.addEventListener('pointermove', function(e){
    if (!pressed) return;
    var c = mapCoords(e.clientX, e.clientY); sendJson({t:'touch',a:2,x:c.x,y:c.y});
  });
  function up(e){ if(!pressed) return; pressed=false; var c=mapCoords(e.clientX,e.clientY); sendJson({t:'touch',a:1,x:c.x,y:c.y}); }
  stageEl.addEventListener('pointerup', up);
  stageEl.addEventListener('pointercancel', up);

  window.addEventListener('keydown', function(e){
    if (e.key === 'Enter'){ sendJson({t:'key',a:0,code:66}); }
    else if (e.key === 'Backspace'){ sendJson({t:'key',a:0,code:67}); }
    else if (e.key && e.key.length === 1){ sendJson({t:'text',s:e.key}); }
  });
  window.addEventListener('keyup', function(e){
    if (e.key === 'Enter'){ sendJson({t:'key',a:1,code:66}); }
    else if (e.key === 'Backspace'){ sendJson({t:'key',a:1,code:67}); }
  });
  document.getElementById('back').addEventListener('click', function(){ sendJson({t:'back'}); });
  document.getElementById('reload').addEventListener('click', function(){ location.reload(); });

  // ---------- WebSocket ----------
  var reconnectTimer = null;
  function reconnect(){
    if (reconnectTimer) return;
    if (decoder){ try{ decoder.close(); }catch(e){} decoder = null; }
    reconnectTimer = setTimeout(function(){ reconnectTimer = null; connect(); }, 1200);
  }
  function connect(){
    setStatus('연결 중…');
    ws = new WebSocket(WS_PROTO + '://' + location.host + '/ws');
    ws.binaryType = 'arraybuffer';
    ws.onmessage = function(ev){
      if (typeof ev.data === 'string'){ try{ dims = JSON.parse(ev.data); }catch(e){} return; }
      var arr = new Uint8Array(ev.data); var type = arr[0]; var payload = arr.subarray(1);
      if (type === 0){ parseConfig(payload); decoder = makePlayer(); decoder.config(); }
      else if (decoder){ decoder.feed(payload, type === 1); }
    };
    ws.onclose = function(){ reconnect(); };
    ws.onerror = function(){ try{ ws.close(); }catch(e){} };
  }
  connect();
})();
</script>
</body></html>
""".trimIndent()
}
