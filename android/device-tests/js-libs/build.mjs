import {build} from 'esbuild';
import {mkdir, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const outdir=fileURLToPath(new URL('../../app/src/main/assets/tvbox-js/js/lib/',import.meta.url));
await mkdir(outdir,{recursive:true});
await build({entryPoints:{'cheerio.min':'cheerio.mjs','crypto-js':'crypto.mjs'},outdir,bundle:true,format:'esm',platform:'browser',minify:true,
  entryNames:'[name]',outExtension:{'.js':'.js'}});
await rm(outdir+'cheerio.js',{force:true});
await rm(outdir+'crypto.js',{force:true});
let notice='Bundled TVBox JavaScript helpers. Built by android/device-tests/js-libs.\n\n';
const directories=[];
for (const entry of await readdir('node_modules',{withFileTypes:true})) {
  if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
  if(entry.name.startsWith('@')) {
    for(const child of await readdir('node_modules/'+entry.name)) directories.push('node_modules/'+entry.name+'/'+child);
  } else directories.push('node_modules/'+entry.name);
}
for(const directory of directories) {
  const pkg=JSON.parse(await readFile(directory+'/package.json','utf8'));
  if (pkg.name==='esbuild') continue;
  const licenses=(await readdir(directory)).filter(name=>/^licen[cs]e(?:\.|$)/i.test(name));
  for (const license of licenses) notice+=`${pkg.name} ${pkg.version}\n${await readFile(directory+'/'+license,'utf8')}\n\n`;
}
await writeFile(outdir+'THIRD_PARTY_NOTICES.txt',notice);
