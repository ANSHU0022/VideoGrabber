// Builds build/icon.ico (16–256 px) from build/icon-win.png. Run: node scripts/make-ico.js
const fs = require('fs');
const path = require('path');
const pngToIco = require('png-to-ico');
const src = path.join(__dirname, '..', 'build', 'icon-win.png');
pngToIco(src).then((buf) => {
  fs.writeFileSync(path.join(__dirname, '..', 'build', 'icon.ico'), buf);
  console.log('build/icon.ico', buf.length, 'bytes');
});
