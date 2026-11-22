#!/usr/bin/env node
// Copies every built installer into release/ with the stable names the download page links to.
import { mkdirSync, copyFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'release');
mkdirSync(out, { recursive: true });
const apk = 'android/app/build/outputs/apk/release';
const files = [
  ['dist.noindex/VideoGrabber-mac-arm64.dmg', 'VideoGrabber-mac-arm64.dmg'],
  ['dist.noindex/VideoGrabber-mac-x64.dmg', 'VideoGrabber-mac-x64.dmg'],
  ['dist.noindex/VideoGrabber-Setup-win-x64.exe', 'VideoGrabber-Setup-win-x64.exe'],
  [`${apk}/app-arm64-v8a-release.apk`, 'VideoGrabber-android-arm64.apk'],
  [`${apk}/app-armeabi-v7a-release.apk`, 'VideoGrabber-android-armv7.apk'],
  [`${apk}/app-x86_64-release.apk`, 'VideoGrabber-android-x86_64.apk'],
  [`${apk}/app-universal-release.apk`, 'VideoGrabber-android-universal.apk'],
];
for (const [src, name] of files) {
  const p = join(root, src);
  if (!existsSync(p)) { console.log(`  – missing ${src} (not built)`); continue; }
  copyFileSync(p, join(out, name));
  console.log(`  ✓ ${name}  ${(statSync(p).size / 1e6).toFixed(0)} MB`);
}
console.log(`\nrelease/: ${readdirSync(out).length} files — upload them all to a GitHub Release.`);
