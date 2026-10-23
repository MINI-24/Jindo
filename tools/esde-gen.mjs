// Builds a compact preset table from ES-DE's Android es_systems.xml + es_find_rules.xml (MIT, Northwestern Software AB).
import { readFileSync, writeFileSync } from 'fs';
const dec = s => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
const sys = readFileSync('es_systems.xml', 'utf8'), rules = readFileSync('es_find_rules.xml', 'utf8');
const RA = '%EMULATOR_RETROARCH% %EXTRA_CONFIGFILE%=%EXTERNALDATA%/Android/data/%ANDROIDPACKAGE%/files/retroarch.cfg %EXTRA_LIBRETRO%=%INTERNALDATA%/%ANDROIDPACKAGE%/cores/';
const RA_END = ' %EXTRA_ROM%=%ROM%';
const systems = [];
for (const m of sys.matchAll(/<system>([\s\S]*?)<\/system>/g)) {
  const b = m[1];
  const tag = t => { const r = b.match(new RegExp('<' + t + '>([\\s\\S]*?)</' + t + '>')); return r ? r[1] : ''; };
  if (!tag('name') || !tag('fullname')) continue;
  const exts = [...new Set(dec(tag('extension')).split(/\s+/).filter(Boolean).map(e => e.replace(/^\./, '').toLowerCase()))].join(' ');
  const cmds = [...b.matchAll(/<command label="([^"]*)">([^<]*)<\/command>/g)].map(([, l, c]) => {
    c = dec(c).trim();
    if (c.startsWith(RA) && c.endsWith(RA_END)) c = '@' + c.slice(RA.length, -RA_END.length);
    return [dec(l), c];
  });
  systems.push([tag('name'), dec(tag('fullname')), exts, cmds]);
}
const emulators = {};
for (const m of rules.matchAll(/<emulator name="([^"]+)">([\s\S]*?)<\/emulator>/g)) {
  const r = m[2].match(/<rule type="androidpackage">([\s\S]*?)<\/rule>/);
  if (r) emulators[m[1]] = [...r[1].matchAll(/<entry>([^<]+)<\/entry>/g)].map(e => dec(e[1]).trim());
}
const out = JSON.stringify({ RA, RA_END, systems, emulators });
writeFileSync('esde-presets.json', out);
console.log(systems.length, 'systems', Object.keys(emulators).length, 'emulators', out.length, 'bytes');
