/* global icon */
const $ = (s) => document.querySelector(s);
// ---------- platform ----------
const IS_MAC = window.vg.platform === 'darwin';
const REVEAL = IS_MAC ? 'Show in Finder' : 'Show in folder';
const kbd = (s) => IS_MAC ? s : s.replace(/⌘⇧/g, 'Ctrl+Shift+').replace(/⌘/g, 'Ctrl+');
document.body.classList.add(IS_MAC ? 'mac' : 'win');
if (!IS_MAC) document.querySelectorAll('[title]').forEach((e) => { e.title = kbd(e.title); });
const HOME = new URL('home.html', location.href).href;
const isHome = (u) => !u || u.startsWith(HOME.split('home.html')[0]) && u.includes('home.html');

// ---------- helpers ----------
function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function iconEl(name, size) { const s = el('span'); s.innerHTML = icon(name, size); return s.firstChild; }
const fmtSize = (b) => !b ? '' : b >= 1e9 ? (b / 1e9).toFixed(2) + ' GB' : b >= 1e6 ? Math.round(b / 1e6) + ' MB' : Math.round(b / 1e3) + ' KB';
const fmtDur = (s) => { if (!s) return ''; s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
const qLabel = (h) => h >= 4320 ? '8K' : h >= 2160 ? '4K' : h >= 1440 ? '2K' : h >= 720 ? 'HD' : '';
const qShort = (h) => h >= 4320 ? '8K' : h >= 2160 ? '4K' : h >= 1440 ? '1440p' : h + 'p';
const cleanSpeed = (s) => (s || '').replace(/iB/g, 'B').replace(/(\d)([KMG]?B\/s)/, '$1 $2').trim();
const cleanEta = (s) => (!s || s === 'NA' || s === 'Unknown') ? '' : s.replace(/^00:/, '') + ' left';
function toUrl(text) {
  const t = text.trim();
  if (!t) return null;
  if (/^[a-z]+:\/\//i.test(t)) return t;
  if (/^[^\s]+\.[a-z]{2,}(\/|:|\?|$)/i.test(t) || /^localhost(:\d+)?/.test(t)) return 'https://' + t;
  return 'https://www.google.com/search?q=' + encodeURIComponent(t);
}
const AUDIO_FORMATS = [
  { v: 'mp3', name: 'MP3', note: 'Plays everywhere' },
  { v: 'm4a', name: 'M4A', note: 'AAC · best for Apple' },
  { v: 'opus', name: 'Opus', note: 'Smallest file' },
  { v: 'flac', name: 'FLAC', note: 'Lossless container' },
];

// ---------- state ----------
const tabs = [];
let active = null;
let seq = 0;
let settings = {};

// ---------- startup loader: covers the first page until it has painted ----------
function hideBootLoader() {
  const b = document.getElementById('boot');
  if (!b) return;
  b.classList.add('done');
  setTimeout(() => b.remove(), 220);
}
setTimeout(hideBootLoader, 6000); // never leave it up if the first page fails

// ---------- tabs ----------
function createTab(url = HOME, { background = false } = {}) {
  const wv = document.createElement('webview');
  wv.setAttribute('partition', 'persist:browser');
  wv.setAttribute('allowpopups', '');
  wv.src = url;
  const tab = {
    id: ++seq, wv, url: '', title: 'New Tab', favicon: null, loading: false,
    info: null, probe: 'idle', probeMsg: '', probeSeq: 0, streams: new Map(), wcId: null,
    mode: 'video', height: null,
  };
  wv.addEventListener('did-attach', () => { try { tab.wcId = wv.getWebContentsId(); } catch {} });
  wv.addEventListener('dom-ready', hideBootLoader, { once: true });
  wv.addEventListener('dom-ready', () => { if (!tab.wcId) try { tab.wcId = wv.getWebContentsId(); } catch {} });
  wv.addEventListener('did-start-loading', () => { tab.loading = true; renderTabs(); tab === active && renderNav(); });
  wv.addEventListener('did-stop-loading', () => { tab.loading = false; renderTabs(); tab === active && renderNav(); });
  wv.addEventListener('did-navigate', (e) => { tab.streams.clear(); tab.favicon = null; onUrl(tab, e.url); });
  wv.addEventListener('did-navigate-in-page', (e) => e.isMainFrame && onUrl(tab, e.url));
  wv.addEventListener('page-title-updated', (e) => { tab.title = isHome(tab.url) ? 'New Tab' : e.title; renderTabs(); if (tab === active) document.title = tab.title; });
  wv.addEventListener('did-fail-load', (e) => {
    if (!e.isMainFrame || e.errorCode === -3) return; // -3 = aborted by a new navigation
    try { tab.title = new URL(e.validatedURL).hostname.replace(/^www\./, '') + ' — can’t load'; } catch {}
    renderTabs();
  });
  wv.addEventListener('page-favicon-updated', (e) => { tab.favicon = e.favicons && e.favicons[0]; renderTabs(); });
  $('#views').append(wv);
  const i = active ? tabs.indexOf(active) + 1 : tabs.length;
  tabs.splice(i, 0, tab);
  if (!background || !active) activate(tab);
  else renderTabs();
  return tab;
}

function activate(tab) {
  active = tab;
  for (const t of tabs) t.wv.classList.toggle('active', t === tab);
  document.title = tab.title;
  renderTabs(); renderNav(); renderPage(); renderStreams();
  // the new-tab page has its own search box; otherwise give the page keyboard focus
  if (document.activeElement === $('#addr')) $('#addr').blur();
  setTimeout(() => tab.wv.focus(), 0);
}

function closeTab(tab) {
  const i = tabs.indexOf(tab);
  if (i < 0) return;
  tabs.splice(i, 1);
  tab.probeSeq++;
  tab.wv.remove();
  if (!tabs.length) return createTab();
  if (tab === active) activate(tabs[Math.min(i, tabs.length - 1)]);
  else renderTabs();
}

function renderTabs() {
  const box = $('#tabs');
  box.replaceChildren();
  const narrow = tabs.length > 7;
  for (const t of tabs) {
    const node = el('div', 'tab' + (t === active ? ' active' : '') + (narrow ? ' narrow' : ''));
    node.title = t.title;
    const fav = el('span', 'tab-fav');
    if (t.loading) fav.append(spinner());
    else if (t.favicon) { const img = el('img'); img.src = t.favicon; img.onerror = () => img.replaceWith(iconEl('globe', 14)); fav.append(img); }
    else fav.append(iconEl(isHome(t.url) ? 'search' : 'globe', 14));
    const title = el('span', 'tab-title', t.title || t.url);
    node.append(fav, title);
    if (t.info || t.streams.size) { const d = el('span', 'tab-dot'); d.title = 'Video available'; node.append(d); }
    const close = el('button', 'tab-close'); close.innerHTML = icon('x'); close.title = kbd('Close Tab (⌘W)');
    // Don't let the tab's mousedown (which re-renders the strip) swallow the close click.
    close.onmousedown = (e) => e.stopPropagation();
    close.onclick = (e) => { e.stopPropagation(); closeTab(t); };
    node.append(close);
    node.onmousedown = (e) => {
      if (e.button === 1) { e.preventDefault(); closeTab(t); }
      else if (e.button === 0 && t !== active) activate(t);
    };
    box.append(node);
  }
}

function spinner() {
  const s = el('span');
  s.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 3a9 9 0 1 0 9 9" opacity=".9"><animateTransform attributeName="transform" type="rotate" from="0 12 12" to="360 12 12" dur=".8s" repeatCount="indefinite"/></path></svg>';
  return s.firstChild;
}

// ---------- navigation chrome ----------
$('#omnibox').addEventListener('submit', (e) => {
  e.preventDefault();
  const u = toUrl($('#addr').value);
  if (u) { active.wv.loadURL(u); active.wv.focus(); }
});
$('#addr').addEventListener('focus', () => $('#addr').select());
$('#addr').addEventListener('keydown', (e) => { if (e.key === 'Escape') { renderNav(true); active.wv.focus(); } });
$('#addr').addEventListener('blur', () => renderNav(true));
$('#back').onclick = () => active.wv.canGoBack() && active.wv.goBack();
$('#fwd').onclick = () => active.wv.canGoForward() && active.wv.goForward();
$('#reload').onclick = () => active.loading ? active.wv.stop() : active.wv.reload();
$('#home').onclick = () => active.wv.loadURL(HOME);
$('#new-tab').onclick = () => createTab();
$('#panel-btn').onclick = () => $('#sidebar').classList.toggle('collapsed');
$('#omni-pill').onclick = () => $('#sidebar').classList.remove('collapsed');
$('#tabs').addEventListener('dblclick', (e) => e.target === $('#tabs') && createTab());
document.querySelector('.drag-fill').addEventListener('dblclick', () => createTab());

function renderNav(forceAddr) {
  const t = active;
  if (!t) return;
  let canBack = false, canFwd = false;
  try { canBack = t.wv.canGoBack(); canFwd = t.wv.canGoForward(); } catch {}
  $('#back').disabled = !canBack;
  $('#fwd').disabled = !canFwd;
  $('#reload').innerHTML = icon(t.loading ? 'x' : 'reload');
  $('#reload').title = t.loading ? 'Stop' : kbd('Reload (⌘R)');
  if (forceAddr || document.activeElement !== $('#addr')) $('#addr').value = isHome(t.url) ? '' : t.url;
  const secure = /^https:/.test(t.url);
  $('#omni-icon').innerHTML = icon(isHome(t.url) || !t.url ? 'search' : secure ? 'lock' : 'globe');

  // address-bar status pill
  const pill = $('#omni-pill');
  pill.className = 'omni-pill';
  if (t.probe === 'loading') { pill.replaceChildren(spinner(), document.createTextNode('Detecting')); pill.classList.add('muted'); }
  else if (t.info) {
    const top = t.info.qualities[0];
    pill.replaceChildren(iconEl('video', 13), document.createTextNode(top ? `${qShort(top.height)} available` : 'Video available'));
  } else if (t.streams.size) {
    pill.replaceChildren(iconEl('stream', 13), document.createTextNode(`${t.streams.size} stream${t.streams.size > 1 ? 's' : ''}`));
  } else pill.classList.add('hidden');

  // primary download button
  const btn = $('#dl-btn');
  if (t.info) {
    btn.disabled = false;
    $('#dl-label').textContent = t.mode === 'audio' ? `Download ${t.audioFormat || settings.audioFormat || 'MP3'}`.replace(/mp3|m4a|opus|flac/, (m) => m.toUpperCase())
      : t.height ? `Download ${qShort(t.height)}` : 'Download';
  } else if (t.streams.size) { btn.disabled = false; $('#dl-label').textContent = 'Download stream'; }
  else { btn.disabled = true; $('#dl-label').textContent = 'Download'; }
}

// Same video even if the site tweaks tracking/time params in the URL.
const NOISE_PARAMS = ['t', 'pp', 'si', 'feature', 'ab_channel', 'start', 'time_continue', 'utm_source', 'utm_medium', 'utm_campaign', 'fbclid', 'igsh'];
function videoKey(url) {
  try {
    const u = new URL(url);
    const host = u.hostname.replace(/^(www|m|mobile)\./, '');
    // A pasted youtu.be link redirects to youtube.com/watch: it's the same video.
    if (host === 'youtu.be') return `youtube.com/watch?v=${u.pathname.slice(1)}`;
    NOISE_PARAMS.forEach((p) => u.searchParams.delete(p));
    u.searchParams.sort();
    return host + u.pathname + '?' + u.searchParams.toString();
  } catch { return url; }
}

const probeTimers = new Map();
const probeCache = new Map(); // video → result, so reopening a video is instant
const PROBE_CACHE_MS = 30 * 60e3;
function onUrl(tab, url) {
  if (url === tab.url) return;
  const same = tab.url && videoKey(url) === videoKey(tab.url);
  tab.url = url;
  if (same && (tab.info || tab.probe === 'loading')) { if (tab === active) renderNav(); return; }
  if (isHome(url)) tab.title = 'New Tab';
  clearTimeout(probeTimers.get(tab.id));
  tab.info = null;
  tab.probeSeq++;
  tab.probe = 'idle';
  tab.probeMsg = '';
  const hit = probeCache.get(videoKey(url));
  if (hit && Date.now() - hit.at < PROBE_CACHE_MS) { applyProbe(tab, hit.r); return; }
  if (/^https?:/.test(url) && !/\/\/(www\.)?google\.[a-z.]+\/(search|webhp)?(\?|$)/.test(url) && !/youtube\.com\/?(\?|$|results|feed)/.test(url)) {
    tab.probe = 'loading';
    probeTimers.set(tab.id, setTimeout(() => probe(tab, url), 150));
  }
  renderTabs();
  if (tab === active) { renderNav(); renderPage(); renderStreams(); }
}

// ---------- probe & page section ----------
async function probe(tab, url) {
  const mySeq = ++tab.probeSeq;
  const r = await window.vg.probe(url, tab.id);
  // Compare the video, not the exact URL: YouTube adds params to the address while it loads.
  if (mySeq !== tab.probeSeq || videoKey(url) !== videoKey(tab.url) || r.cancelled) return;
  if (!r.error) probeCache.set(videoKey(url), { r, at: Date.now() });
  applyProbe(tab, r);
}

function applyProbe(tab, r) {
  if (r.error) {
    tab.probe = 'none';
    tab.probeMsg = /Unsupported URL|No video|not a valid URL|Unable to extract|no video formats/i.test(r.error) ? '' : r.error;
  } else {
    tab.probe = 'ok';
    tab.info = r;
    // keep the user's pick when the same page is re-checked (e.g. YouTube tweaking its URL)
    if (!r.qualities.some((q) => q.height === tab.height)) tab.height = r.qualities[0] ? r.qualities[0].height : null;
  }
  renderTabs();
  if (tab === active) { renderNav(); renderPage(); renderStreams(); }
}

function renderPage() {
  const t = active;
  const body = $('#page-body');
  body.replaceChildren();
  if (t.probe === 'loading') {
    const sk = el('div', 'skeleton');
    const top = el('div', 'media');
    const a = el('div', 'sk'); a.style.cssText = 'width:120px;height:68px';
    const b = el('div'); b.style.cssText = 'flex:1;display:flex;flex-direction:column;gap:8px;padding-top:2px';
    for (const w of ['90%', '70%', '40%']) { const l = el('div', 'sk'); l.style.cssText = `height:11px;width:${w}`; b.append(l); }
    top.append(a, b);
    const c = el('div', 'sk'); c.style.cssText = 'height:30px';
    const d = el('div', 'sk'); d.style.cssText = 'height:108px';
    sk.append(top, c, d);
    body.append(sk);
    return;
  }
  if (!t.info) {
    const e = el('div', 'empty');
    e.append(iconEl(t.probeMsg ? 'alert' : 'video'));
    const txt = el('div');
    if (isHome(t.url) || !t.url) txt.append(el('b', null, 'Open a video'), 'Videos on the page are detected automatically.');
    else if (t.probeMsg) txt.append(el('b', null, 'Couldn’t read this page'), t.probeMsg);
    else if (t.streams.size) txt.append(el('b', null, 'No video page detected'), 'Use a detected stream below.');
    else txt.append(el('b', null, 'No video detected'), 'Play the video. If the site streams it, it will appear below.');
    e.append(txt);
    body.append(e);
    return;
  }
  const i = t.info;
  const media = el('div', 'media');
  const th = el('div', 'thumb');
  if (i.thumbnail) { const img = el('img'); img.src = i.thumbnail; img.onerror = () => img.remove(); th.append(img); }
  if (i.duration) th.append(el('span', 'dur', fmtDur(i.duration)));
  if (i.isLive) th.append(el('span', 'dur', 'LIVE'));
  const txt = el('div', 'min0');
  txt.append(el('div', 'media-title', i.title), el('div', 'media-meta', [i.uploader, i.site].filter(Boolean).join(' · ')));
  media.append(th, txt);

  const seg = el('div', 'segmented'); seg.id = 'mode-seg';
  for (const [m, label, ic] of [['video', 'Video', 'video'], ['audio', 'Audio only', 'music']]) {
    const b = el('button', t.mode === m ? 'on' : '');
    b.append(iconEl(ic, 14), document.createTextNode(label));
    b.onclick = () => { t.mode = m; renderPage(); renderNav(); };
    seg.append(b);
  }

  const opts = el('div', 'options');
  let selSize = 0;
  if (t.mode === 'video') {
    if (!i.qualities.length) {
      const o = el('button', 'opt on'); o.append(el('span', 'radio'), el('span', 'q', 'Best'), el('span', 'tags'));
      opts.append(o);
    }
    for (const q of i.qualities) {
      const on = q.height === t.height;
      if (on) selSize = q.size;
      const o = el('button', 'opt' + (on ? ' on' : ''));
      const tags = el('span', 'tags');
      if (qLabel(q.height)) tags.append(el('span', 'tag' + (q.height >= 2160 ? ' accent' : ''), qLabel(q.height)));
      if (q.fps > 30) tags.append(el('span', 'tag', Math.round(q.fps) + ' fps'));
      if (q.hdr) tags.append(el('span', 'tag accent', 'HDR'));
      o.append(el('span', 'radio'), el('span', 'q', q.height + 'p'), tags, el('span', 'size', fmtSize(q.size)));
      o.onclick = () => { t.height = q.height; renderPage(); renderNav(); };
      opts.append(o);
    }
  } else {
    const cur = t.audioFormat || settings.audioFormat || 'mp3';
    selSize = i.audioSize;
    for (const f of AUDIO_FORMATS) {
      const o = el('button', 'opt' + (f.v === cur ? ' on' : ''));
      o.append(el('span', 'radio'), el('span', 'q', f.name), el('span', 'tags'), el('span', 'size', f.note));
      o.onclick = () => { t.audioFormat = f.v; renderPage(); renderNav(); };
      opts.append(o);
    }
  }

  const go = el('button', 'btn primary block');
  go.append(iconEl('download', 15), document.createTextNode(
    (t.mode === 'audio' ? 'Download audio' : t.height ? `Download ${qShort(t.height)}` : 'Download') + (selSize ? ` · ${fmtSize(selSize)}` : '')));
  go.onclick = () => downloadPage(t);
  body.append(media, seg, opts, go);
}

function downloadPage(t) {
  const i = t.info;
  if (!i) return;
  const fmt = t.audioFormat || settings.audioFormat || 'mp3';
  const label = t.mode === 'audio' ? fmt.toUpperCase() : t.height ? qShort(t.height) : 'Best';
  startJob({ url: i.url || t.url, mode: t.mode, height: t.height, audioFormat: fmt }, { title: i.title, label, thumb: i.thumbnail, duration: i.duration, pageUrl: i.url || t.url });
}

$('#dl-btn').onclick = () => {
  const t = active;
  if (t.info) return downloadPage(t);
  const s = bestStream(t);
  if (s) downloadStream(t, s);
};

// ---------- sniffed streams ----------
window.vg.on('media-detected', (m) => {
  const tab = tabs.find((t) => t.wcId === m.webContentsId) || (m.webContentsId ? null : active);
  if (!tab) return;
  let key = m.url;
  try { const u = new URL(m.url); key = u.origin + u.pathname; } catch {}
  const prev = tab.streams.get(key);
  if (prev) { if (m.size > prev.size) { prev.size = m.size; tab === active && renderStreams(); } return; }
  tab.streams.set(key, { ...m, at: Date.now() });
  renderTabs();
  if (tab === active) { renderStreams(); renderNav(); if (!tab.info && tab.probe !== 'loading') renderPage(); }
});

function bestStream(t) {
  const list = [...t.streams.values()];
  const manifests = list.filter((s) => s.kind !== 'MP4').sort((a, b) => /master|playlist|index/i.test(b.url) - /master|playlist|index/i.test(a.url) || b.at - a.at);
  if (manifests.length) return manifests[0];
  return list.sort((a, b) => b.size - a.size)[0];
}
function streamName(s) {
  try {
    const u = new URL(s.url);
    const file = decodeURIComponent(u.pathname.split('/').filter(Boolean).pop() || u.hostname);
    return { file, host: u.hostname.replace(/^www\./, '') };
  } catch { return { file: s.url, host: '' }; }
}
function downloadStream(t, s) {
  const q = (s.url.match(/(\d{3,4})p?[^\d]/) || [])[1];
  const label = q && +q >= 240 && +q <= 4320 ? q + 'p' : s.kind;
  const title = isHome(t.url) ? streamName(s).file : t.title;
  startJob({ url: s.url, mode: 'video', referer: t.url, stream: true, title }, { title, label, thumb: t.info && t.info.thumbnail });
}

function renderStreams() {
  const t = active;
  const box = $('#streams');
  box.replaceChildren();
  const n = t.streams.size;
  $('#streams-count').textContent = n;
  $('#streams-count').classList.toggle('hidden', !n);
  if (!n) {
    const e = el('div', 'empty'); e.append(el('div', null, 'Streams that play on this page will show up here.'));
    box.append(e);
    return;
  }
  const list = [...t.streams.values()].sort((a, b) => b.at - a.at).slice(0, 20);
  for (const s of list) {
    const { file, host } = streamName(s);
    const row = el('div', 'row');
    const ic = el('div', 'row-icon'); ic.append(iconEl('stream'));
    const main = el('div', 'row-main');
    main.append(el('div', 'row-title', file));
    const sub = el('div', 'row-sub');
    sub.append(el('span', 'tag', s.kind), el('span', null, host));
    if (s.size) sub.append(el('span', 'sep'), el('span', null, fmtSize(s.size)));
    main.append(sub);
    const act = el('div', 'row-actions');
    const b = el('button', 'icon-btn xs'); b.innerHTML = icon('download'); b.title = 'Download';
    b.onclick = () => downloadStream(t, s);
    act.append(b);
    row.title = s.url;
    row.ondblclick = () => downloadStream(t, s);
    row.append(ic, main, act);
    box.append(row);
  }
}

// ---------- downloads ----------
const jobs = new Map(); // id -> job view
function renderJobsEmpty() {
  const has = $('#jobs').querySelector('.row');
  $('#jobs .empty')?.remove();
  if (!has) {
    const e = el('div', 'empty'); e.append(el('div', null, 'Downloads will appear here.'));
    $('#jobs').append(e);
  }
  const running = [...jobs.values()].filter((j) => !j.final).length;
  $('#badge').textContent = running;
  $('#badge').classList.toggle('hidden', !running);
  $('#jobs-count').textContent = running ? `${running} active` : '';
  $('#jobs-count').classList.toggle('hidden', !running);
  $('#clear-done').classList.toggle('hidden', ![...jobs.values()].some((j) => j.final));
}

async function startJob(opts, meta) {
  const r = await window.vg.download({ ...opts, meta: {
    title: meta.title, label: meta.label, thumb: meta.thumb, duration: meta.duration || 0,
    pageUrl: meta.pageUrl || opts.referer || opts.url,
  } });
  if (r.error) { alert(r.error); return; }
  const row = el('div', 'row keep');
  const ic = el('div', 'row-icon');
  if (meta.thumb) { const img = el('img'); img.src = meta.thumb; img.onerror = () => { img.remove(); ic.append(iconEl(opts.mode === 'audio' ? 'music' : 'video')); }; ic.append(img); }
  else ic.append(iconEl(opts.mode === 'audio' ? 'music' : 'video'));
  const main = el('div', 'row-main');
  const title = el('div', 'row-title', meta.title || 'Download');
  title.title = meta.title || '';
  const sub = el('div', 'row-sub');
  const bar = el('div', 'bar indeterminate'); const fill = el('i'); bar.append(fill);
  main.append(title, sub, bar);
  const act = el('div', 'row-actions');
  row.append(ic, main, act);
  $('#jobs').prepend(row);
  const j = { id: r.id, row, sub, bar, fill, act, title, opts, meta, final: false, total: 0 };
  jobs.set(r.id, j);
  setSub(j, [el('span', 'tag', meta.label), el('span', null, 'Starting…')]);
  setActions(j, [['x', 'Cancel', () => window.vg.cancel(j.id)]]);
  renderJobsEmpty();
  $('#sidebar').classList.remove('collapsed');
}
function setSub(j, parts) { j.sub.className = 'row-sub'; j.sub.replaceChildren(...parts); }
function setActions(j, list) {
  j.act.replaceChildren(...list.map(([ic, tip, fn]) => { const b = el('button', 'icon-btn xs'); b.innerHTML = icon(ic); b.title = tip; b.onclick = fn; return b; }));
}

window.vg.on('job-update', (u) => {
  const j = jobs.get(u.id);
  if (!j) return;
  if (u.file) j.file = u.file;
  if (u.total) j.total = Math.max(j.total, u.total);
  const tag = el('span', 'tag', j.meta.label);
  if (u.status === 'downloading') {
    j.bar.classList.remove('indeterminate');
    j.fill.style.width = u.percent + '%';
    const parts = [tag, el('span', null, `${u.percent.toFixed(0)}%`)];
    if (j.opts.mode === 'video' && u.stage > 1) parts.push(el('span', 'sep'), el('span', null, 'audio'));
    if (cleanSpeed(u.speed) && !/NA|Unknown/.test(u.speed)) parts.push(el('span', 'sep'), el('span', null, cleanSpeed(u.speed)));
    if (cleanEta(u.eta)) parts.push(el('span', 'sep'), el('span', null, cleanEta(u.eta)));
    setSub(j, parts);
  } else if (u.status === 'retrying') {
    setSub(j, [tag, el('span', null, `Reconnecting (attempt ${u.attempt})…`)]);
  } else if (u.status === 'processing') {
    j.bar.classList.add('indeterminate');
    setSub(j, [tag, el('span', null, j.opts.mode === 'audio' ? 'Converting…' : 'Finishing…')]);
  } else if (['done', 'error', 'cancelled'].includes(u.status)) {
    j.final = true;
    j.row.classList.remove('keep');
    j.row.classList.add(u.status);
    j.bar.remove();
    if (u.status === 'done') {
      const ext = (j.file || '').split('.').pop().toUpperCase();
      setSub(j, [tag, el('span', null, [ext, fmtSize(u.size)].filter(Boolean).join(' · '))]);
      j.title.title = j.file || '';
      j.title.onclick = () => window.vgPlay(j.file, j.meta.title, j.meta.thumb);
      setActions(j, [['play', 'Play', () => window.vgPlay(j.file, j.meta.title, j.meta.thumb)], ['folder', REVEAL, () => window.vg.reveal(j.file)]]);
      document.dispatchEvent(new Event('history-changed'));
    } else {
      if (u.status === 'error') { j.sub.className = 'row-sub err'; j.sub.textContent = u.error; j.sub.title = u.error; }
      else setSub(j, [tag, el('span', null, 'Cancelled')]);
      setActions(j, [['reload', 'Retry', () => { j.row.remove(); jobs.delete(j.id); startJob(j.opts, j.meta); }],
        ['x', 'Remove', () => { j.row.remove(); jobs.delete(j.id); renderJobsEmpty(); }]]);
    }
    renderJobsEmpty();
  }
});
$('#open-folder').onclick = () => window.vg.openFolder();
$('#clear-done').onclick = () => { for (const [id, j] of jobs) if (j.final) { j.row.remove(); jobs.delete(id); } renderJobsEmpty(); };

// ---------- context-menu downloads ----------
window.vg.on('download-link', ({ url, title, direct }) => {
  const t = active;
  if (direct) return startJob({ url, mode: 'video', referer: t.url, stream: true, title: t.title }, { title: t.title, label: 'Video' });
  startJob({ url, mode: 'video' }, { title: (title || '').trim() || url, label: 'Best' });
});

// ---------- new tabs from pages / menu commands ----------
window.vg.on('open-url', ({ url, background }) => createTab(url, { background }));
window.vg.on('command', (cmd) => {
  const t = active;
  const i = tabs.indexOf(t);
  if (cmd === 'new-tab') createTab();
  else if (cmd === 'close-tab') closeTab(t);
  else if (cmd === 'focus-address') { $('#addr').focus(); $('#addr').select(); }
  else if (cmd === 'reload') t.wv.reload();
  else if (cmd === 'back') t.wv.canGoBack() && t.wv.goBack();
  else if (cmd === 'forward') t.wv.canGoForward() && t.wv.goForward();
  else if (cmd === 'next-tab') activate(tabs[(i + 1) % tabs.length]);
  else if (cmd === 'prev-tab') activate(tabs[(i - 1 + tabs.length) % tabs.length]);
  else if (cmd.startsWith('tab-')) { const n = +cmd.slice(4); activate(n === 9 ? tabs[tabs.length - 1] : tabs[n - 1] || t); }
  else if (cmd === 'toggle-panel') $('#sidebar').classList.toggle('collapsed');
  else if (cmd === 'settings') openSettings();
  else if (cmd === 'history') document.dispatchEvent(new Event('history-toggle'));
  else if (cmd === 'download') $('#dl-btn').click();
  else if (cmd === 'devtools') t.wv.openDevTools();
});

// ---------- settings ----------
async function loadSettings() {
  settings = await window.vg.getSettings();
  $('#set-folder').textContent = settings.downloadDir;
  $('#set-folder').title = settings.downloadDir;
  $('#set-compat').value = settings.compatible ? '1' : '0';
  $('#set-audio').value = settings.audioFormat;
  $('#set-conn').value = String(settings.concurrentFragments);
  document.querySelectorAll('#theme-seg button').forEach((b) => b.classList.toggle('on', b.dataset.v === settings.theme));
  const eng = $('#set-engine');
  eng.className = 'set-desc';
  if (!settings.bins.ytdlp) eng.innerHTML = 'Not installed. Run <code>brew install yt-dlp</code>';
  else eng.textContent = `yt-dlp ${settings.version || ''}` + (settings.bins.ffmpeg ? ' · ffmpeg ready' : ' · ffmpeg missing (needed for HD)');
  $('#set-aria').innerHTML = settings.bins.aria2c ? 'Enabled: direct video files use 16 connections.'
    : 'Install <code>brew install aria2</code> to download direct video files over 16 connections.';
}
function openSettings() { loadSettings(); $('#settings').classList.remove('hidden'); }
function closeSettings() { $('#settings').classList.add('hidden'); }
$('#settings-btn').onclick = openSettings;
$('#settings-close').onclick = closeSettings;
$('#settings').addEventListener('mousedown', (e) => e.target === $('#settings') && closeSettings());
window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#settings').classList.contains('hidden')) closeSettings(); });
document.querySelectorAll('#theme-seg button').forEach((b) => (b.onclick = async () => {
  await window.vg.setSettings({ theme: b.dataset.v });
  settings.theme = b.dataset.v;
  document.querySelectorAll('#theme-seg button').forEach((x) => x.classList.toggle('on', x === b));
}));
$('#set-folder-btn').onclick = async () => { const d = await window.vg.chooseFolder(); settings.downloadDir = d; $('#set-folder').textContent = d; };
$('#set-compat').onchange = (e) => window.vg.setSettings({ compatible: e.target.value === '1' });
$('#set-audio').onchange = (e) => { settings.audioFormat = e.target.value; window.vg.setSettings({ audioFormat: e.target.value }); renderPage(); renderNav(); };
$('#set-conn').onchange = (e) => window.vg.setSettings({ concurrentFragments: +e.target.value });
$('#set-update').onclick = async () => {
  const b = $('#set-update'), eng = $('#set-engine');
  b.disabled = true; b.textContent = 'Checking…';
  const r = await window.vg.updateYtdlp();
  b.disabled = false; b.textContent = 'Check for updates';
  eng.className = 'set-desc' + (r.ok ? ' ok' : '');
  eng.textContent = r.ok ? `yt-dlp ${r.version} · ${r.message}` : r.message;
};

// ---------- boot ----------
loadSettings().then(() => { createTab(); renderJobsEmpty(); });
