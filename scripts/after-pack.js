// electron-builder afterPack hook.
// Windows: stamp our icon + version info into VideoGrabber.exe with pure-JS resedit
// (so building on macOS/Linux needs no Wine; signAndEditExecutable is off in package.json).
const fs = require('fs');
const path = require('path');

const { execFileSync } = require('child_process');

// macOS: ad-hoc sign (free, no Apple account). Without any signature, Apple Silicon Macs
// report downloaded apps as "damaged"; ad-hoc signed apps only need "Open Anyway" once.
function adHocSignMac(context) {
  const app = path.join(context.appOutDir, `${context.packager.appInfo.productFilename}.app`);
  const binDir = path.join(app, 'Contents', 'Resources', 'bin');
  if (fs.existsSync(binDir)) {
    for (const f of fs.readdirSync(binDir)) {
      execFileSync('codesign', ['--force', '--sign', '-', '--timestamp=none', path.join(binDir, f)]);
    }
  }
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', '--timestamp=none', app]);
  execFileSync('codesign', ['--verify', '--deep', '--strict', app]);
  console.log(`  • afterPack: ad-hoc signed ${path.basename(app)} (${context.arch === 1 ? 'x64' : 'arm64'})`);
}

exports.default = async function afterPack(context) {
  if (context.electronPlatformName === 'darwin') return adHocSignMac(context);
  if (context.electronPlatformName !== 'win32') return;
  const ResEdit = await import('resedit');
  const PE = await import('pe-library');
  const { productFilename, version } = context.packager.appInfo;
  const exePath = path.join(context.appOutDir, `${productFilename}.exe`);
  const exe = PE.NtExecutable.from(fs.readFileSync(exePath), { ignoreCert: true });
  const res = ResEdit.NtExecutableResource.from(exe);

  const ico = ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname, '..', 'build', 'icon.ico')));
  const groups = ResEdit.Resource.IconGroupEntry.fromEntries(res.entries);
  const lang = groups.length ? groups[0].lang : 1033;
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries, groups.length ? groups[0].id : 1, lang, ico.icons.map((i) => i.data));

  const [vi] = ResEdit.Resource.VersionInfo.fromEntries(res.entries);
  if (vi) {
    const [a = 0, b = 0, c = 0] = version.split('.').map((n) => parseInt(n, 10) || 0);
    vi.setFileVersion(a, b, c, 0, lang);
    vi.setProductVersion(a, b, c, 0, lang);
    vi.setStringValues({ lang, codepage: 1200 }, {
      ProductName: 'VideoGrabber', FileDescription: 'VideoGrabber', CompanyName: 'VideoGrabber',
      InternalName: 'VideoGrabber', OriginalFilename: 'VideoGrabber.exe', LegalCopyright: 'MIT License',
      FileVersion: version, ProductVersion: version,
    });
    vi.outputToResourceEntries(res.entries);
  }
  res.outputResource(exe);
  fs.writeFileSync(exePath, Buffer.from(exe.generate()));
  console.log(`  • afterPack: icon + version ${version} written to ${productFilename}.exe`);
};
