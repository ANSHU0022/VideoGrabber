#!/usr/bin/env node
// Downloads the standalone tools that get bundled into the desktop installers.
//   node scripts/fetch-binaries.mjs win-x64
// Output: bin/<target>/  (git-ignored). electron-builder copies it into the app's resources/bin.
import { mkdirSync, rmSync, existsSync, readdirSync, statSync, renameSync, chmodSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const GH = 'https://github.com';
const TARGETS = {
  'win-x64': [
    { url: `${GH}/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe`, out: 'yt-dlp.exe' },
    { url: `${GH}/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip`, zip: true, pick: ['deno.exe'] },
    // LGPL build: enough for merging/remuxing and MP3/M4A/Opus/FLAC audio.
    { url: `${GH}/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip`, zip: true, pick: ['ffmpeg.exe', 'ffprobe.exe'] },
  ],
  'mac-arm64': [
    { url: `${GH}/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos`, out: 'yt-dlp' }, // universal binary
    { url: `${GH}/denoland/deno/releases/latest/download/deno-aarch64-apple-darwin.zip`, zip: true, pick: ['deno'] },
    { url: 'https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffmpeg.zip', zip: true, pick: ['ffmpeg'] },
    { url: 'https://ffmpeg.martin-riedl.de/redirect/latest/macos/arm64/release/ffprobe.zip', zip: true, pick: ['ffprobe'] },
  ],
  'mac-x64': [
    { url: `${GH}/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos`, out: 'yt-dlp' },
    { url: `${GH}/denoland/deno/releases/latest/download/deno-x86_64-apple-darwin.zip`, zip: true, pick: ['deno'] },
    { url: 'https://ffmpeg.martin-riedl.de/redirect/latest/macos/amd64/release/ffmpeg.zip', zip: true, pick: ['ffmpeg'] },
    { url: 'https://ffmpeg.martin-riedl.de/redirect/latest/macos/amd64/release/ffprobe.zip', zip: true, pick: ['ffprobe'] },
  ],
};

const target = process.argv[2];
if (!TARGETS[target]) {
  console.error(`Usage: node scripts/fetch-binaries.mjs <${Object.keys(TARGETS).join('|')}>`);
  process.exit(1);
}
const outDir = join(root, 'bin', target);
const tmp = join(root, 'bin', `.tmp-${target}`);
rmSync(tmp, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
mkdirSync(tmp, { recursive: true });

function findFile(dir, name) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) { const r = findFile(p, name); if (r) return r; }
    else if (e.toLowerCase() === name.toLowerCase()) return p;
  }
  return null;
}

for (const item of TARGETS[target]) {
  const name = item.url.split('/').pop();
  process.stdout.write(`↓ ${name} … `);
  const res = await fetch(item.url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`${res.status} for ${item.url}`);
  const buf = Buffer.from(await res.arrayBuffer());
  if (!item.zip) {
    writeFileSync(join(outDir, item.out), buf);
    chmodSync(join(outDir, item.out), 0o755);
  } else {
    const zipPath = join(tmp, name);
    writeFileSync(zipPath, buf);
    const ex = join(tmp, name + '.d');
    mkdirSync(ex);
    execFileSync('tar', ['-xf', zipPath, '-C', ex]); // bsdtar (macOS/Windows 10+) reads zip
    for (const f of item.pick) {
      const found = findFile(ex, f);
      if (!found) throw new Error(`${f} not found in ${name}`);
      renameSync(found, join(outDir, f));
      chmodSync(join(outDir, f), 0o755);
    }
  }
  console.log(`${(buf.length / 1e6).toFixed(1)} MB`);
}
rmSync(tmp, { recursive: true, force: true });
console.log(`✓ ${target}: ${readdirSync(outDir).join(', ')}`);
if (!existsSync(join(outDir, target.startsWith('win') ? 'yt-dlp.exe' : 'yt-dlp'))) process.exit(1);
