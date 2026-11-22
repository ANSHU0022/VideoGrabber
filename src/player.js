/* global REVEAL */
// Built-in player for downloaded files. Formats Chromium can't decode fall back to the system player.
(() => {
  const $p = (s) => document.querySelector(s);
  const box = $p('#player'), video = $p('#player-video'), err = $p('#player-error');
  let current = null;

  async function play(file, title, poster) {
    current = file;
    $p('#player-title').textContent = title || file.split(/[\\/]/).pop();
    $p('#player-reveal').textContent = REVEAL;
    err.classList.add('hidden');
    video.classList.remove('hidden');
    video.poster = poster || '';
    video.src = await window.vg.fileUrl(file);
    box.classList.remove('hidden');
    video.play().catch(() => {});
  }
  function close() {
    video.pause();
    video.removeAttribute('src');
    video.load();
    box.classList.add('hidden');
    current = null;
  }
  const external = () => { if (current) window.vg.openFile(current); close(); };
  video.addEventListener('error', () => { if (!current) return; video.classList.add('hidden'); err.classList.remove('hidden'); });
  $p('#player-close').onclick = close;
  $p('#player-external').onclick = external;
  $p('#player-error-open').onclick = external;
  $p('#player-reveal').onclick = () => current && window.vg.reveal(current);
  box.addEventListener('mousedown', (e) => { if (e.target === box) close(); });
  window.addEventListener('keydown', (e) => {
    if (box.classList.contains('hidden')) return;
    if (e.key === 'Escape') { e.stopImmediatePropagation(); close(); }
    else if (e.key === ' ' && document.activeElement !== video) { e.preventDefault(); video.paused ? video.play() : video.pause(); }
  }, true);
  window.vgPlay = play;
})();
