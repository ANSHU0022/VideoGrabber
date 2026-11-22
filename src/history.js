/* global REVEAL, el, iconEl, icon, fmtSize, fmtDur, createTab */
// Full download history: list / details / cards views. Loaded after renderer.js (shares its helpers).
(() => {
  const $h = (s) => document.querySelector(s);
  const store = {
    get(k, d) { try { return localStorage.getItem(k) || d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch {} },
  };
  const state = { items: [], view: store.get('historyView', 'rows'), type: 'all', sort: 'new', q: '', open: false };

  const fmtDate = (ts) => new Date(ts).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const fmtTime = (ts) => new Date(ts).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  function dayLabel(ts) {
    const d = new Date(ts); d.setHours(0, 0, 0, 0);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const diff = Math.round((today - d) / 864e5);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Yesterday';
    return d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', ...(d.getFullYear() !== today.getFullYear() ? { year: 'numeric' } : {}) });
  }

  async function load() {
    state.items = await window.vg.historyList();
    updateLink();
    if (state.open) render();
  }
  function updateLink() {
    const n = state.items.length;
    const link = $h('#history-link');
    link.textContent = `View all downloads (${n})`;
    link.classList.toggle('hidden', !n);
  }

  function filtered() {
    const q = state.q.trim().toLowerCase();
    let list = state.items.filter((h) =>
      (state.type === 'all' || h.mode === state.type) &&
      (!q || (h.title + ' ' + h.site + ' ' + h.ext + ' ' + h.label).toLowerCase().includes(q)));
    const by = {
      new: (a, b) => b.date - a.date,
      old: (a, b) => a.date - b.date,
      big: (a, b) => (b.size || 0) - (a.size || 0),
      name: (a, b) => a.title.localeCompare(b.title),
    }[state.sort];
    return list.sort(by);
  }

  // ---------- shared pieces ----------
  function thumbEl(h, cls) {
    const box = el('div', cls);
    if (h.thumb) {
      const img = el('img'); img.src = h.thumb; img.loading = 'lazy';
      img.onerror = () => { img.remove(); box.append(iconEl(h.mode === 'audio' ? 'music' : 'video', 20)); };
      box.append(img);
    } else box.append(iconEl(h.mode === 'audio' ? 'music' : 'video', 20));
    return box;
  }
  function tags(h) {
    const t = [];
    if (h.label) t.push(el('span', 'tag' + (/4K|8K|2160|4320/.test(h.label) ? ' accent' : ''), h.label));
    if (h.ext && h.ext.toUpperCase() !== (h.label || '').toUpperCase()) t.push(el('span', 'tag', h.ext.toUpperCase()));
    if (h.missing) t.push(el('span', 'tag danger', 'File missing'));
    return t;
  }
  function act(ic, tip, fn, disabled) {
    const b = el('button', 'icon-btn xs');
    b.innerHTML = icon(ic); b.title = tip; b.disabled = !!disabled;
    b.onclick = (e) => { e.stopPropagation(); fn(); };
    return b;
  }
  function actions(h) {
    const box = el('div', 'h-actions');
    box.append(
      act('play', 'Play', () => window.vgPlay(h.file, h.title, h.thumb), h.missing),
      act('folder', REVEAL, () => window.vg.reveal(h.file), h.missing),
      act('external', 'Open source page in new tab', () => { close(); createTab(h.pageUrl); }, !h.pageUrl),
      act('trash', 'Remove from history', () => remove(h)),
    );
    return box;
  }
  const openItem = (h) => !h.missing && window.vgPlay(h.file, h.title, h.thumb);

  async function remove(h) {
    const alsoFile = !h.missing && window.confirm(`Remove “${h.title}” from history?\n\nPress OK to also move the file to the Trash, or Cancel to keep the file.`);
    await window.vg.historyRemove([h.id], alsoFile);
    state.items = state.items.filter((x) => x.id !== h.id);
    updateLink(); render();
  }

  // ---------- views ----------
  function listView(items) {
    const wrap = el('div', 'hv-list');
    const head = el('div', 'hl-row hl-head');
    for (const c of ['', 'Name', 'Quality', 'Format', 'Size', 'Downloaded', '']) head.append(el('div', null, c));
    wrap.append(head);
    for (const h of items) {
      const r = el('div', 'hl-row' + (h.missing ? ' missing' : ''));
      const name = el('div', 'hl-name');
      name.append(el('div', 'hl-title', h.title), el('div', 'hl-site', h.site));
      r.append(
        thumbEl(h, 'hl-thumb'), name,
        el('div', 'hl-cell', h.label || '—'),
        el('div', 'hl-cell', (h.ext || '').toUpperCase()),
        el('div', 'hl-cell num', fmtSize(h.size) || '—'),
        el('div', 'hl-cell', fmtDate(h.date)),
        actions(h),
      );
      r.title = h.file;
      r.ondblclick = () => openItem(h);
      wrap.append(r);
    }
    return wrap;
  }

  function rowsView(items) {
    const wrap = el('div', 'hv-rows');
    let day = '';
    for (const h of items) {
      if (state.sort === 'new' || state.sort === 'old') {
        const d = dayLabel(h.date);
        if (d !== day) { day = d; wrap.append(el('div', 'h-day', d)); }
      }
      const r = el('div', 'hr-item' + (h.missing ? ' missing' : ''));
      const th = thumbEl(h, 'hr-thumb');
      if (h.duration) th.append(el('span', 'dur', fmtDur(h.duration)));
      th.onclick = () => openItem(h);
      const info = el('div', 'hr-info');
      const title = el('div', 'hr-title', h.title);
      title.onclick = () => openItem(h);
      const meta = el('div', 'hr-meta');
      meta.append(...tags(h));
      if (h.size) meta.append(el('span', 'hr-size', fmtSize(h.size)));
      const sub = el('div', 'hr-sub', [h.site, `${dayLabel(h.date)}, ${fmtTime(h.date)}`].filter(Boolean).join(' · '));
      const file = el('div', 'hr-file mono', h.file);
      file.title = h.file;
      info.append(title, meta, sub, file);
      r.append(th, info, actions(h));
      wrap.append(r);
    }
    return wrap;
  }

  function gridView(items) {
    const wrap = el('div', 'hv-grid');
    for (const h of items) {
      const c = el('div', 'hg-card' + (h.missing ? ' missing' : ''));
      const th = thumbEl(h, 'hg-thumb');
      if (h.label) th.append(el('span', 'hg-badge', h.label));
      if (h.duration) th.append(el('span', 'dur', fmtDur(h.duration)));
      if (!h.missing) { const p = el('span', 'hg-play'); p.innerHTML = icon('play', 20); th.append(p); }
      th.onclick = () => openItem(h);
      const body = el('div', 'hg-body');
      const title = el('div', 'hg-title', h.title);
      title.title = h.title;
      const meta = el('div', 'hg-meta');
      meta.append(el('span', null, [fmtSize(h.size), (h.ext || '').toUpperCase()].filter(Boolean).join(' · ')));
      if (h.missing) meta.append(el('span', 'tag danger', 'Missing'));
      const foot = el('div', 'hg-foot');
      foot.append(el('span', 'hg-date', [h.site, dayLabel(h.date)].filter(Boolean).join(' · ')), actions(h));
      body.append(title, meta, foot);
      c.append(th, body);
      wrap.append(c);
    }
    return wrap;
  }

  function render() {
    const items = filtered();
    const total = state.items.reduce((s, h) => s + (h.size || 0), 0);
    $h('#h-stats').textContent = state.items.length
      ? `${state.items.length} download${state.items.length > 1 ? 's' : ''} · ${fmtSize(total)}` : '';
    document.querySelectorAll('#h-view button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.view));
    document.querySelectorAll('#h-type button').forEach((b) => b.classList.toggle('on', b.dataset.v === state.type));
    $h('#h-clear').disabled = !state.items.length;
    const body = $h('#h-body');
    body.replaceChildren();
    if (!items.length) {
      const e = el('div', 'h-empty');
      e.append(iconEl(state.items.length ? 'search' : 'download', 28),
        el('b', null, state.items.length ? 'No matching downloads' : 'No downloads yet'),
        el('span', null, state.items.length ? 'Try a different search or filter.' : 'Everything you download will be listed here.'));
      body.append(e);
      return;
    }
    body.append(state.view === 'list' ? listView(items) : state.view === 'grid' ? gridView(items) : rowsView(items));
  }

  function open() { state.open = true; document.body.classList.add('history-open'); $h('#history').classList.remove('hidden'); load(); }
  function close() { state.open = false; document.body.classList.remove('history-open'); $h('#history').classList.add('hidden'); }

  // ---------- wiring ----------
  $h('#history-open').onclick = open;
  $h('#history-link').onclick = open;
  $h('#h-close').onclick = close;
  document.addEventListener('history-toggle', () => (state.open ? close() : open()));
  document.addEventListener('history-changed', load);
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && state.open && $h('#settings').classList.contains('hidden') && $h('#player').classList.contains('hidden')) close();
  });
  document.querySelectorAll('#h-view button').forEach((b) => (b.onclick = () => { state.view = b.dataset.v; store.set('historyView', state.view); render(); }));
  document.querySelectorAll('#h-type button').forEach((b) => (b.onclick = () => { state.type = b.dataset.v; render(); }));
  $h('#h-sort').onchange = (e) => { state.sort = e.target.value; render(); };
  $h('#h-q').addEventListener('input', (e) => { state.q = e.target.value; render(); });
  $h('#h-clear').onclick = async () => {
    if (!window.confirm('Clear the whole download history?\n\nYour downloaded files are not deleted.')) return;
    await window.vg.historyClear();
    state.items = []; updateLink(); render();
  };
  window.vgHistory = { open, close, isOpen: () => state.open };
  window.vg.on('history-updated', () => load());
  load();
})();
