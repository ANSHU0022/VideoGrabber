const { app, BrowserWindow, ipcMain, session, dialog, shell, nativeTheme, Menu, clipboard } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn, execFile } = require('child_process');
const crypto = require('crypto');
const { pathToFileURL } = require('url');

const PARTITION = 'persist:browser';
const IS_WIN = process.platform === 'win32';
const IS_MAC = process.platform === 'darwin';
let win;

// ---------- settings ----------
const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
let settings = {
  downloadDir: path.join(os.homedir(), 'Downloads', 'VideoGrabber'),
  compatible: false, // true = prefer H.264/AAC MP4 (plays in QuickTime), usually caps at 1080p
  audioFormat: 'mp3',
  concurrentFragments: 8,
  theme: 'system', // system | light | dark
  homeEngine: 'youtube',
};
function loadSettings() {
  try { Object.assign(settings, JSON.parse(fs.readFileSync(settingsFile(), 'utf8'))); } catch {}
}
function saveSettings() {
  try { fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2)); } catch {}
}

// ---------- binaries ----------
function findBin(name) {
  const exe = IS_WIN ? `${name}.exe` : name;
  const candidates = [
    process.resourcesPath && path.join(process.resourcesPath, 'bin', exe),
    path.join(__dirname, '..', 'bin', exe),
    ...(IS_WIN ? [] : [`/opt/homebrew/bin/${exe}`, `/usr/local/bin/${exe}`, `/usr/bin/${exe}`]),
  ].filter(Boolean);
  for (const p of candidates) {
    try { fs.accessSync(p, fs.constants.X_OK); return p; } catch {}
  }
  return null;
}
const bins = {};
function resolveBins() {
  for (const n of ['yt-dlp', 'ffmpeg', 'deno', 'node', 'aria2c']) bins[n.replace('-', '')] = findBin(n);
}

// Packaged app: run yt-dlp from a copy in the user's app-data folder so it can update itself
// (writing inside the app bundle would break its signature). Sites like YouTube change often;
// an outdated yt-dlp is the #1 cause of "HTTP Error 403". Refreshed at most once a day.
function freshYtdlp() {
  if (!app.isPackaged) return;
  const exe = IS_WIN ? 'yt-dlp.exe' : 'yt-dlp';
  const bundled = path.join(process.resourcesPath, 'bin', exe);
  if (!fs.existsSync(bundled)) return;
  const dir = path.join(app.getPath('userData'), 'bin');
  const mine = path.join(dir, exe);
  try {
    if (!fs.existsSync(mine)) {
      fs.mkdirSync(dir, { recursive: true });
      fs.copyFileSync(bundled, mine);
      fs.chmodSync(mine, 0o755);
    }
    bins.ytdlp = mine;
  } catch { return; }
  if (Date.now() - (settings.lastYtdlpUpdate || 0) < 24 * 3600e3) return;
  const c = spawn(mine, ['-U'], { env: childEnv(), windowsHide: true });
  c.on('close', (code) => { if (code === 0) { settings.lastYtdlpUpdate = Date.now(); saveSettings(); } });
  c.on('error', () => {});
}
// GUI apps launched from Finder get a minimal PATH; make sure brew tools are reachable.
const childEnv = () => ({
  ...process.env,
  PATH: [process.env.PATH, '/opt/homebrew/bin', '/usr/local/bin'].filter(Boolean).join(path.delimiter),
});

let userAgent = '';
function commonArgs() {
  const a = ['--no-playlist', '--no-warnings', '--cookies', cookieFile()];
  if (bins.ffmpeg) a.push('--ffmpeg-location', path.dirname(bins.ffmpeg));
  if (bins.deno) a.push('--js-runtimes', `deno:${bins.deno}`);
  else if (bins.node) a.push('--js-runtimes', `node:${bins.node}`);
  a.push('--remote-components', 'ejs:github');
  if (userAgent) a.push('--user-agent', userAgent);
  return a;
}

// ---------- cookies: share the in-app browser login with yt-dlp ----------
const cookieFile = () => path.join(app.getPath('userData'), 'cookies.txt');
async function writeCookies() {
  const cookies = await session.fromPartition(PARTITION).cookies.get({});
  const lines = ['# Netscape HTTP Cookie File'];
  for (const c of cookies) {
    let domain = c.domain || '';
    if (!c.hostOnly && !domain.startsWith('.')) domain = '.' + domain;
    lines.push([
      (c.httpOnly ? '#HttpOnly_' : '') + domain,
      domain.startsWith('.') ? 'TRUE' : 'FALSE',
      c.path || '/',
      c.secure ? 'TRUE' : 'FALSE',
      Math.floor(c.expirationDate || 0),
      c.name,
      c.value,
    ].join('\t'));
  }
  fs.writeFileSync(cookieFile(), lines.join('\n') + '\n', { mode: 0o600 });
}

// ---------- window ----------
function createWindow() {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    ...(IS_MAC
      ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 14, y: 14 } }
      // Windows/Linux: our tab strip is the title bar; native min/max/close drawn on the right.
      : { titleBarStyle: 'hidden', titleBarOverlay: overlayColors(), autoHideMenuBar: true }),
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#18181b' : '#f4f4f5',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webviewTag: true,
    },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
}

function overlayColors() {
  const dark = nativeTheme.shouldUseDarkColors;
  return { color: dark ? '#161618' : '#ececee', symbolColor: dark ? '#f1f1f3' : '#17171a', height: 40 };
}
nativeTheme.on('updated', () => {
  if (!IS_MAC && win && !win.isDestroyed()) { try { win.setTitleBarOverlay(overlayColors()); } catch {} }
});

// Kill yt-dlp *and* the ffmpeg/deno processes it started.
function killTree(child) {
  if (!child || child.exitCode !== null) return;
  try {
    if (IS_WIN) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true });
    else process.kill(-child.pid, 'SIGTERM');
  } catch { try { child.kill(); } catch {} }
}

const send = (ch, data) => win && !win.isDestroyed() && win.webContents.send(ch, data);

function setupBrowserSession() {
  const ses = session.fromPartition(PARTITION);
  // Look like a normal Chrome browser (sites like Google block "Electron" user agents).
  userAgent = ses.getUserAgent().replace(/\s(Electron|videograbber|VideoGrabber)\/\S+/gi, '');
  ses.setUserAgent(userAgent);
  app.userAgentFallback = userAgent;

  // Sniff media requests so we can offer streams yt-dlp's site extractors don't know about.
  ses.webRequest.onResponseStarted({ urls: ['http://*/*', 'https://*/*'] }, (d) => {
    const h = {};
    for (const [k, v] of Object.entries(d.responseHeaders || {})) h[k.toLowerCase()] = String(v);
    const ct = (h['content-type'] || '').toLowerCase();
    const range = h['content-range'] || '';
    let len = parseInt(h['content-length'] || '0', 10);
    const total = range.match(/\/(\d+)$/);
    if (total) len = parseInt(total[1], 10);
    const u = d.url;
    if (/googlevideo\.com|\.ts(\?|$)|\.m4s(\?|$)|\.aac(\?|$)/i.test(u)) return; // segments / handled by extractor
    let kind = null;
    if (/\.m3u8(\?|$)/i.test(u) || ct.includes('mpegurl')) kind = 'HLS';
    else if (/\.mpd(\?|$)/i.test(u) || ct.includes('dash+xml')) kind = 'DASH';
    else if ((ct.startsWith('video/') || /\.(mp4|webm|mov|mkv)(\?|$)/i.test(u)) && (len === 0 || len > 1.5e6)
      && !/segment|[-_/]chunk|[-_/]frag|init\.(mp4|webm)|[?&](range|bytes|bytestart)=/i.test(u)) kind = 'MP4';
    if (!kind) return;
    send('media-detected', { url: u, kind, size: len, webContentsId: d.webContentsId, referer: d.referrer || '' });
  });
}

app.on('web-contents-created', (_e, contents) => {
  contents.on('will-attach-webview', (_ev, webPreferences) => {
    delete webPreferences.preload;
    webPreferences.nodeIntegration = false;
    webPreferences.contextIsolation = true;
  });
  if (contents.getType() !== 'webview') return;
  contents.setWindowOpenHandler(({ url, disposition }) => {
    // Real popups (OAuth/login) are allowed; target=_blank / cmd-click links open as tabs.
    if (disposition === 'new-window') return { action: 'allow' };
    send('open-url', { url, background: disposition === 'background-tab' });
    return { action: 'deny' };
  });
  contents.on('context-menu', (_ev, p) => {
    const items = [];
    if (p.linkURL) {
      items.push(
        { label: 'Open Link in New Tab', click: () => send('open-url', { url: p.linkURL, background: true }) },
        { label: 'Download Video from Link', click: () => send('download-link', { url: p.linkURL, title: p.linkText }) },
        { label: 'Copy Link', click: () => clipboard.writeText(p.linkURL) },
        { type: 'separator' },
      );
    }
    if (p.mediaType === 'video' && p.srcURL && !p.srcURL.startsWith('blob:')) {
      items.push({ label: 'Download This Video', click: () => send('download-link', { url: p.srcURL, direct: true }) }, { type: 'separator' });
    }
    if (p.selectionText) items.push({ role: 'copy' }, { type: 'separator' });
    if (p.isEditable) items.push({ role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { type: 'separator' });
    items.push(
      { label: 'Back', enabled: contents.navigationHistory.canGoBack(), click: () => contents.navigationHistory.goBack() },
      { label: 'Forward', enabled: contents.navigationHistory.canGoForward(), click: () => contents.navigationHistory.goForward() },
      { label: 'Reload', click: () => contents.reload() },
      { type: 'separator' },
      { label: 'Inspect Element', click: () => contents.inspectElement(p.x, p.y) },
    );
    Menu.buildFromTemplate(items).popup();
  });
});

// ---------- probe: what can be downloaded from this URL? (one in flight per tab) ----------
const probes = new Map();
ipcMain.handle('probe', async (_e, url, key) => {
  const prev = probes.get(key);
  if (prev) { try { prev.kill(); } catch {} probes.delete(key); }
  if (!bins.ytdlp) return { error: 'yt-dlp not found. Install with: brew install yt-dlp' };
  await writeCookies();
  return new Promise((resolve) => {
    const child = spawn(bins.ytdlp, [...commonArgs(), '-J', url], { env: childEnv(), windowsHide: true });
    probes.set(key, child);
    let out = '', err = '';
    const timer = setTimeout(() => child.kill(), 90000);
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('close', (code) => {
      clearTimeout(timer);
      if (probes.get(key) === child) probes.delete(key);
      if (code === null) return resolve({ cancelled: true });
      if (code !== 0 || !out) return resolve({ error: lastErrorLine(err) || 'No downloadable video found on this page.' });
      try { resolve(summarize(JSON.parse(out))); }
      catch { resolve({ error: 'Could not read video info.' }); }
    });
  });
});

function summarize(info) {
  if (info._type === 'playlist') {
    return { error: `This is a playlist or channel (${(info.entries || []).length} items). Open a single video to download it.`, playlist: true };
  }
  const formats = info.formats || [];
  const bestAudio = formats
    .filter((f) => f.vcodec === 'none' && f.acodec && f.acodec !== 'none')
    .sort((a, b) => (b.abr || b.tbr || 0) - (a.abr || a.tbr || 0))[0];
  const audioSize = bestAudio ? bestAudio.filesize || bestAudio.filesize_approx || 0 : 0;
  const byHeight = new Map();
  for (const f of formats) {
    if (!f.height || !f.vcodec || f.vcodec === 'none') continue;
    const vSize = f.filesize || f.filesize_approx || (f.tbr && info.duration ? (f.tbr * 1000 / 8) * info.duration : 0);
    const size = vSize ? vSize + (f.acodec === 'none' ? audioSize : 0) : 0;
    const prev = byHeight.get(f.height);
    if (!prev || (f.tbr || 0) > (prev.tbr || 0)) {
      byHeight.set(f.height, { height: f.height, fps: f.fps || null, size, tbr: f.tbr || 0, hdr: !!(f.dynamic_range && f.dynamic_range !== 'SDR') });
    }
  }
  const qualities = [...byHeight.values()].sort((a, b) => b.height - a.height);
  return {
    title: info.title || info.id,
    thumbnail: info.thumbnail,
    duration: info.duration,
    uploader: info.uploader || info.channel || '',
    site: info.extractor_key || info.extractor,
    isLive: !!info.is_live,
    url: info.webpage_url || info.original_url,
    qualities,
    audioSize,
    hasAudio: !!bestAudio || formats.some((f) => f.acodec && f.acodec !== 'none'),
  };
}

function lastErrorLine(s) {
  const lines = s.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('ERROR'));
  return lines.length ? lines[lines.length - 1].replace(/^ERROR:\s*(\[[^\]]+\]\s*)?/, '') : '';
}

// Clean, unique file name for streams that have no proper title from an extractor.
function streamOutput(title) {
  let base = (title || 'video').replace(/[\/\\:*?"<>|\x00-\x1f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 150) || 'video';
  const exists = (b) => ['mp4', 'mkv', 'webm', 'mp3', 'm4a'].some((x) => fs.existsSync(path.join(settings.downloadDir, `${b}.${x}`)));
  let name = base, n = 2;
  while (exists(name)) name = `${base} (${n++})`;
  return name.replace(/%/g, '%%') + '.%(ext)s';
}

// ---------- downloads ----------
const jobs = new Map();
let nextId = 1;

ipcMain.handle('download', async (_e, opts) => {
  if (!bins.ytdlp) return { error: 'yt-dlp not found' };
  await writeCookies();
  fs.mkdirSync(settings.downloadDir, { recursive: true });
  const id = nextId++;
  const args = [
    ...commonArgs(),
    '--newline', '--progress', '--no-simulate', '--no-mtime',
    '--concurrent-fragments', String(settings.concurrentFragments),
    '-P', settings.downloadDir,
    // Per-download temp folder: lets the same video be fetched as video and audio at once.
    // Keyed by URL+options (not job id) so a restarted download still resumes its .part files.
    '-P', `temp:${tempDirFor(opts)}`,
    '-o', opts.stream ? streamOutput(opts.title) : '%(title).150B [%(id)s].%(ext)s',
    '--progress-template', 'download:VGPROG %(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s|%(info.format_id)s|%(progress.total_bytes,progress.total_bytes_estimate)s',
    '--print', 'after_move:VGFILE %(filepath)s',
    '--embed-metadata',
  ];
  if (opts.referer) args.push('--referer', opts.referer);
  if (opts.stream) {
    // Direct files from plain servers are often throttled per connection: split them.
    if (bins.aria2c) args.push('--downloader', `http:${bins.aria2c}`, '--downloader-args', 'aria2c:-x 16 -s 16 -k 1M --summary-interval=1');
    else args.push('--http-chunk-size', '10M');
  }

  if (opts.mode === 'audio') {
    args.push('-f', 'ba/b', '-x', '--audio-format', opts.audioFormat || settings.audioFormat, '--audio-quality', '0');
  } else {
    const h = opts.height;
    const sel = h ? `bv*[height<=${h}]+ba/b[height<=${h}]/bv*+ba/b` : 'bv*+ba/b';
    args.push('-f', sel);
    if (settings.compatible) {
      args.push('-S', 'vcodec:h264,res,proto,acodec:m4a', '--merge-output-format', 'mp4');
    } else {
      args.push('-S', 'res,fps,hdr:12,proto,vbr', '--merge-output-format', 'mp4/mkv');
    }
  }
  args.push('--retries', '10', '--fragment-retries', '10', '--', opts.url);

  const job = { id, child: null, file: null, stage: 0, lastFormat: null, err: '', opts };
  jobs.set(id, job);
  const sendJob = (patch) => send('job-update', { id, ...patch });
  launch(job, args, sendJob, 1);
  return { id };
});

const TEMP_ROOT = () => path.join(settings.downloadDir, '.videograbber-temp');
function tempDirFor(opts) {
  const key = crypto.createHash('sha1').update([opts.url, opts.mode, opts.height, opts.audioFormat, settings.compatible].join('|')).digest('hex').slice(0, 12);
  return path.join(TEMP_ROOT(), key);
}
function cleanTemp(args) {
  const i = args.findIndex((a) => typeof a === 'string' && a.startsWith('temp:'));
  if (i < 0) return;
  const dir = args[i].slice(5);
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch {}
  try { fs.rmdirSync(path.dirname(dir)); } catch {} // only removes the root when empty
}

const MAX_ATTEMPTS = 4;
const TRANSIENT = /403|Forbidden|timed out|Connection reset|HTTP Error 5\d\d|Remote end closed|IncompleteRead|fragment .* not found|part-Frag/i;

function launch(job, args, sendJob, attempt) {
  const child = spawn(bins.ytdlp, args, { env: childEnv(), detached: !IS_WIN, windowsHide: true });
  job.child = child;
  job.err = '';
  job.stage = 0;
  job.lastFormat = null;
  let buf = '';
  const onLine = (line) => {
    if (line.startsWith('VGPROG ')) {
      const [pct, speed, eta, fmt, total] = line.slice(7).split('|').map((s) => s.trim());
      if (fmt !== job.lastFormat) { job.lastFormat = fmt; job.stage++; }
      sendJob({ status: 'downloading', percent: parseFloat(pct) || 0, speed, eta, stage: job.stage, total: parseFloat(total) || 0 });
    } else if (line.startsWith('VGFILE ')) {
      job.file = line.slice(7).trim();
      sendJob({ file: job.file });
    } else if (/\[(Merger|ExtractAudio|FixupM3u8|VideoConvertor|Metadata)\]/.test(line)) {
      sendJob({ status: 'processing' });
    }
  };
  child.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) { onLine(buf.slice(0, i).trim()); buf = buf.slice(i + 1); }
  });
  child.stderr.on('data', (d) => { job.err += d; if (job.err.length > 20000) job.err = job.err.slice(-20000); });
  child.on('close', (code) => {
    if (buf.trim()) onLine(buf.trim());
    const errLine = lastErrorLine(job.err);
    if (job.cancelled) sendJob({ status: 'cancelled' });
    else if (code === 0) {
      let size = 0;
      try { size = fs.statSync(job.file).size; } catch {}
      cleanTemp(args);
      const entry = addHistory(job, size);
      sendJob({ status: 'done', percent: 100, file: job.file, size, historyId: entry && entry.id });
    } else if (attempt < MAX_ATTEMPTS && TRANSIENT.test(errLine || job.err)) {
      sendJob({ status: 'retrying', attempt: attempt + 1 });
      if (/Frag/i.test(job.err)) { // parallel fragment download tripped up: fall back to sequential
        const i = args.indexOf('--concurrent-fragments');
        if (i >= 0) args[i + 1] = '1';
      }
      // Re-run: yt-dlp re-extracts fresh stream URLs and resumes .part files.
      return setTimeout(() => {
        if (!job.cancelled) return launch(job, args, sendJob, attempt + 1);
        sendJob({ status: 'cancelled' });
        jobs.delete(job.id);
      }, 1500 * attempt);
    } else sendJob({ status: 'error', error: errLine || `yt-dlp exited with code ${code}` });
    jobs.delete(job.id);
  });
}

ipcMain.handle('cancel', (_e, id) => {
  const job = jobs.get(id);
  if (!job) return;
  job.cancelled = true;
  killTree(job.child);
});

// ---------- download history ----------
const historyFile = () => path.join(app.getPath('userData'), 'history.json');
let history = null;
function loadHistory() {
  if (history) return history;
  try { history = JSON.parse(fs.readFileSync(historyFile(), 'utf8')); } catch { history = []; }
  if (!Array.isArray(history)) history = [];
  return history;
}
let saveTimer;
function saveHistory() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => { try { fs.writeFileSync(historyFile(), JSON.stringify(history)); } catch {} }, 200);
}
function addHistory(job, size) {
  if (!job.file) return null;
  const m = job.opts.meta || {};
  let site = m.site || '';
  if (!site) { try { site = new URL(m.pageUrl || job.opts.referer || job.opts.url).hostname.replace(/^www\./, ''); } catch {} }
  const entry = {
    id: `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`,
    title: m.title || path.basename(job.file).replace(/\.[^.]+$/, ''),
    file: job.file,
    ext: path.extname(job.file).slice(1).toLowerCase(),
    size,
    label: m.label || '',
    mode: job.opts.mode || 'video',
    thumb: m.thumb || '',
    duration: m.duration || 0,
    site,
    pageUrl: m.pageUrl || job.opts.url,
    date: Date.now(),
  };
  loadHistory().unshift(entry);
  if (history.length > 5000) history.length = 5000;
  saveHistory();
  if (!entry.thumb) queueThumb(entry);
  return entry;
}
// ---------- thumbnails from the downloaded file itself (for sites that give no image) ----------
const thumbDir = () => path.join(app.getPath('userData'), 'thumbs');
function makeThumb(file, id) {
  return new Promise((resolve) => {
    if (!bins.ffmpeg || !fs.existsSync(file)) return resolve(null);
    fs.mkdirSync(thumbDir(), { recursive: true });
    const out = path.join(thumbDir(), `${id}.jpg`);
    const grab = (ss) => new Promise((r) => {
      const c = spawn(bins.ffmpeg, ['-y', '-loglevel', 'error', '-ss', ss, '-i', file, '-frames:v', '1', '-vf', 'scale=480:-2', '-q:v', '4', out], { windowsHide: true });
      c.on('error', () => r(false));
      c.on('close', (code) => r(code === 0 && fs.existsSync(out) && fs.statSync(out).size > 0));
    });
    // Frame from 20% into the video: skips channel intros/logos so each card shows real content.
    const probe = bins.ffmpeg.replace(/ffmpeg(\.exe)?$/, (m, ext) => `ffprobe${ext || ''}`);
    execFile(probe, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file], { windowsHide: true, timeout: 15000 }, (e, outp) => {
      const d = parseFloat(outp) || 0;
      const at = d > 2 ? Math.min(Math.max(d * 0.2, 1), d - 1) : 0;
      grab(at.toFixed(2)).then((ok) => ok || grab('0')).then((ok) => resolve(ok ? `${pathToFileURL(out).href}?v=2` : null));
    });
  });
}
let thumbQueue = Promise.resolve();
function queueThumb(h) {
  const oldLocal = h.thumb && h.thumb.startsWith('file:') && !h.thumb.endsWith('?v=2'); // made from the intro: redo
  if (oldLocal) { h.thumb = ''; h.thumbTried = false; }
  if (h.thumb || h.thumbTried || h.mode === 'audio') return;
  h.thumbTried = true;
  thumbQueue = thumbQueue.then(async () => {
    const url = await makeThumb(h.file, h.id);
    if (url) { h.thumb = url; saveHistory(); send('history-updated'); }
  });
}

ipcMain.handle('history-list', () => {
  const list = loadHistory();
  list.forEach((h) => { if ((!h.thumb || h.thumb.startsWith('file:')) && h.mode !== 'audio' && fs.existsSync(h.file)) queueThumb(h); });
  return list.map((h) => ({ ...h, missing: !fs.existsSync(h.file) }));
});
ipcMain.handle('file-url', (_e, f) => pathToFileURL(f).href);
ipcMain.handle('history-remove', (_e, ids, deleteFiles) => {
  const set = new Set(ids);
  for (const h of loadHistory()) if (set.has(h.id) && deleteFiles) { try { shell.trashItem(h.file); } catch {} }
  history = loadHistory().filter((h) => !set.has(h.id));
  saveHistory();
  return true;
});
ipcMain.handle('history-clear', () => { history = []; saveHistory(); return true; });

ipcMain.handle('reveal', (_e, file) => file && shell.showItemInFolder(file));
ipcMain.handle('open-file', (_e, file) => file && shell.openPath(file));
ipcMain.handle('open-folder', () => { fs.mkdirSync(settings.downloadDir, { recursive: true }); shell.openPath(settings.downloadDir); });
ipcMain.handle('get-settings', async () => ({ ...settings, bins, version: await ytdlpVersion() }));
ipcMain.handle('set-settings', (_e, patch) => {
  Object.assign(settings, patch);
  if (patch.theme) nativeTheme.themeSource = settings.theme;
  saveSettings();
  return settings;
});
ipcMain.handle('choose-folder', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'], defaultPath: settings.downloadDir });
  if (!r.canceled && r.filePaths[0]) { settings.downloadDir = r.filePaths[0]; saveSettings(); }
  return settings.downloadDir;
});

function ytdlpVersion() {
  return new Promise((resolve) => {
    if (!bins.ytdlp) return resolve('');
    execFile(bins.ytdlp, ['--version'], { env: childEnv(), timeout: 15000, windowsHide: true }, (e, out) => resolve(e ? '' : out.trim()));
  });
}
ipcMain.handle('update-ytdlp', async () => {
  if (!bins.ytdlp) return { ok: false, message: 'yt-dlp not found' };
  const before = await ytdlpVersion();
  const viaBrew = /homebrew|\/usr\/local\/Cellar/.test(fs.realpathSync(bins.ytdlp));
  const brew = findBin('brew');
  const [cmd, args] = viaBrew && brew ? [brew, ['upgrade', 'yt-dlp']] : [bins.ytdlp, ['-U']];
  await new Promise((resolve) => spawn(cmd, args, { env: childEnv(), windowsHide: true }).on('close', resolve));
  const after = await ytdlpVersion();
  if (!after) return { ok: false, message: 'Update failed', version: before };
  return { ok: true, version: after, message: after === before ? 'Already up to date' : `Updated from ${before}` };
});

// ---------- app menu (shortcuts must work even while a web page has focus) ----------
function buildMenu() {
  const cmd = (c) => () => send('command', c);
  const item = (label, accelerator, c, extra = {}) => ({ label, accelerator, click: cmd(c), ...extra });
  const tabNums = Array.from({ length: 9 }, (_, i) => item(i === 8 ? 'Last Tab' : `Tab ${i + 1}`, `CmdOrCtrl+${i + 1}`, `tab-${i + 1}`, { visible: false, acceleratorWorksWhenHidden: true }));
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    ...(process.platform === 'darwin' ? [{ label: app.name, submenu: [
      { role: 'about' }, { type: 'separator' },
      item('Settings…', 'CmdOrCtrl+,', 'settings'), { type: 'separator' },
      { role: 'hide' }, { role: 'hideOthers' }, { role: 'unhide' }, { type: 'separator' }, { role: 'quit' },
    ] }] : []),
    { label: 'File', submenu: [
      item('New Tab', 'CmdOrCtrl+T', 'new-tab'),
      item('Open Location…', 'CmdOrCtrl+L', 'focus-address'),
      { type: 'separator' },
      item('Close Tab', 'CmdOrCtrl+W', 'close-tab'),
      ...(process.platform === 'darwin' ? [] : [item('Settings…', 'CmdOrCtrl+,', 'settings'), { role: 'quit' }]),
    ] },
    { role: 'editMenu' },
    { label: 'View', submenu: [
      item('Reload Page', 'CmdOrCtrl+R', 'reload'),
      item('Download Video', 'CmdOrCtrl+D', 'download'),
      item('Toggle Downloads Panel', 'CmdOrCtrl+Shift+D', 'toggle-panel'),
      item('All Downloads', 'CmdOrCtrl+Shift+H', 'history'),
      { type: 'separator' },
      { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' },
      { role: 'togglefullscreen' },
      item('Developer Tools for Page', 'CmdOrCtrl+Alt+I', 'devtools'),
    ] },
    { label: 'History', submenu: [
      item('Back', 'CmdOrCtrl+[', 'back'),
      item('Forward', 'CmdOrCtrl+]', 'forward'),
    ] },
    { label: 'Window', submenu: [
      { role: 'minimize' }, { role: 'zoom' }, { type: 'separator' },
      item('Show Next Tab', 'Ctrl+Tab', 'next-tab'),
      item('Show Previous Tab', 'Ctrl+Shift+Tab', 'prev-tab'),
      item('Next Tab', 'CmdOrCtrl+Shift+]', 'next-tab', { visible: false, acceleratorWorksWhenHidden: true }),
      item('Previous Tab', 'CmdOrCtrl+Shift+[', 'prev-tab', { visible: false, acceleratorWorksWhenHidden: true }),
      ...tabNums,
    ] },
  ]));
}

// ---------- lifecycle ----------
app.whenReady().then(() => {
  loadSettings();
  nativeTheme.themeSource = settings.theme;
  resolveBins();
  freshYtdlp();
  setupBrowserSession();
  buildMenu();
  createWindow();
  app.on('activate', () => BrowserWindow.getAllWindows().length === 0 && createWindow());
});
function stopAllJobs() {
  for (const j of jobs.values()) {
    j.cancelled = true;
    killTree(j.child);
  }
}
app.on('before-quit', (e) => {
  const running = [...jobs.values()].length;
  if (running && win && !win.isDestroyed() && !app.confirmedQuit) {
    const r = dialog.showMessageBoxSync(win, {
      type: 'question', buttons: ['Quit', 'Cancel'], defaultId: 1, cancelId: 1,
      message: `${running} download${running > 1 ? 's are' : ' is'} still running`,
      detail: 'Quitting will stop them. Partly downloaded files are kept and resume next time you download the same video.',
    });
    if (r === 1) return e.preventDefault();
  }
  app.confirmedQuit = true;
  stopAllJobs();
});
app.on('will-quit', () => { if (history) try { fs.writeFileSync(historyFile(), JSON.stringify(history)); } catch {} });
app.on('window-all-closed', () => {
  stopAllJobs();
  app.quit();
});
