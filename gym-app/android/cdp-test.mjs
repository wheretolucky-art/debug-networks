// Drives PPL Coach inside the emulator's Android WebView over the DevTools protocol:
// loads made-up sample data, logs a set (rest timer and keep-awake go through the native bridge),
// finishes the workout, opens the weekly report and shares it (Android share sheet), presses the
// Android back button, and records screenshots. Sample data only: this repository is public.
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const OUT = process.argv[2];
const log = (...a) => fs.appendFileSync(`${OUT}/smoke.log`, a.join(' ') + '\n');
const sh = (c) => execSync(c, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shot = (name) => { fs.writeFileSync(`${OUT}/${name}.png`, execSync('adb exec-out screencap -p', { maxBuffer: 64 * 1024 * 1024 })); log('screenshot', name); };
const back = async () => { sh('adb shell input keyevent KEYCODE_BACK'); await sleep(1500); };
const top = () => {
  try { const m = sh('adb shell dumpsys activity activities').match(/ResumedActivity[^\n]*/i); return m ? m[0].trim() : '?'; }
  catch { return '?'; }
};

let sock = '';
for (let i = 0; i < 30 && !sock; i++) {
  const m = sh('adb shell cat /proc/net/unix').match(/@(webview_devtools_remote_\d+)/);
  if (m) sock = m[1]; else await sleep(1000);
}
if (!sock) { log('no WebView debug socket'); process.exit(0); }
sh(`adb forward tcp:9222 localabstract:${sock}`);
let target;
for (let i = 0; i < 20 && !target; i++) {
  try { const list = await (await fetch('http://127.0.0.1:9222/json')).json(); target = list.find((t) => (t.url || '').includes('appassets')); } catch {}
  if (!target) await sleep(1000);
}
if (!target) { log('no page to drive'); process.exit(0); }
log('driving', target.url);

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
const errors = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  if (m.method === 'Runtime.exceptionThrown') errors.push((m.params.exceptionDetails.exception || {}).description || m.params.exceptionDetails.text);
  if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errors.push('console: ' + m.params.args.map((a) => a.value).join(' '));
};
const send = (method, params = {}) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); ws.send(JSON.stringify({ id: i, method, params })); setTimeout(() => { if (pending.has(i)) { pending.delete(i); resolve({ timeout: true }); } }, 10000); });
const js = async (expression, gesture = false) => { const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: gesture }); return r.result && r.result.result ? r.result.result.value : undefined; };
const click = async (selector, wait = 1200) => { const ok = await js(`(function(){ var e = document.querySelector(${JSON.stringify(selector)}); if (e) e.click(); return !!e; })()`, true); log('click', selector, ok ? 'ok' : 'NOT FOUND'); await sleep(wait); return ok; };
const fill = async (selector, value) => { const ok = await js(`(function(){ var e = document.querySelector(${JSON.stringify(selector)}); if (!e) return false; e.value = ${JSON.stringify(value)}; e.dispatchEvent(new Event('input', { bubbles: true })); return true; })()`); log('fill', selector, ok ? 'ok' : 'NOT FOUND'); };
await send('Runtime.enable');

// Made-up sample data: two weeks of weigh-ins and a few workouts built from the app's own default plan.
await js(`(function(){
  var p = function (x) { return (x < 10 ? '0' : '') + x; };
  var mk = function (n) { var d = new Date(); d.setHours(12); d.setDate(d.getDate() - n); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); };
  localStorage.removeItem('ppl-coach-v1');
  return true;
})()`);
await js('location.reload(); true');
await sleep(5000);
await send('Runtime.enable');
await fill('#qW', '63.2'); await fill('#qS', '7.5');
await click('input[name=qG][value=no]', 300);
await click('[data-act=quickCheckin]', 800);
await js(`(function(){
  var p = function (x) { return (x < 10 ? '0' : '') + x; };
  var mk = function (n) { var d = new Date(); d.setHours(12); d.setDate(d.getDate() - n); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); };
  var S = JSON.parse(localStorage.getItem('ppl-coach-v1'));
  var order = ['pushA', 'pullA', 'legsA', 'pushB', 'pullB'];
  for (var i = 12; i >= 1; i--) S.body[mk(i)] = { weight: 62.6 + (12 - i) * 0.05, sleep: 7, gas: i % 5 === 0 };
  order.forEach(function (sid, k) {
    var s = S.program.sessions[sid], n = 6 - k;
    S.workouts.push({ id: 'sample' + k, date: mk(n), sessionId: sid, name: s.name, focus: s.focus, kind: s.kind, started: Date.now() - n * 864e5, finished: Date.now() - n * 864e5 + 3600e3, notes: '',
      ex: s.exercises.map(function (e) { return { id: e.id, name: e.name, equip: e.equip, note: e.note || '', noKg: !!e.noKg, muscles: e.muscles, cue: e.cue, rir: '1', target: { sets: e.sets, min: e.min, max: e.max, rir: e.rir, rest: e.rest },
        sets: Array.apply(null, Array(e.sets)).map(function (_, j) { return { kg: e.noKg ? '' : (e.equip === 'bw' ? 0 : 20), reps: e.max - j, done: true }; }) }; }) });
  });
  localStorage.setItem('ppl-coach-v1', JSON.stringify(S));
  location.reload();
  return true;
})()`);
await sleep(5000);
await send('Runtime.enable');
log('native bridge present:', await js('!!window.GymAndroid'), '| version:', await js('window.GymAndroid && window.GymAndroid.appVersion()'));
log('hero:', await js(`(document.querySelector('.hero .big') || {}).textContent`));
shot('screen-1-today');

await click('.hero [data-act=start]', 1500);
log('title after start:', await js(`document.getElementById('title').textContent`), '| exercises:', await js(`document.querySelectorAll('.ex').length`));
await fill('#ex-0 [data-f=reps]', '7');
await click('#ex-0 [data-act=tick][data-set="0"]', 1200);
log('rest bar showing:', await js(`!document.getElementById('restbar').hidden`), '| set done:', await js(`!!document.querySelector('#ex-0 .setrow.done')`));
shot('screen-2-workout-rest');
await click('[data-act=restAdj][data-d="-15"]', 400);
await click('[data-act=restSkip]', 600);
await click('[data-act=finish]', 1500);
log('summary sheet open:', await js(`document.getElementById('sheet').open`), '| title:', await js(`document.getElementById('sheetTitle').textContent`));
shot('screen-3-finished');
await back();
log('sheet open after back:', await js(`document.getElementById('sheet').open`), '| workouts saved:', await js(`JSON.parse(localStorage.getItem('ppl-coach-v1')).workouts.length`));

await click('#nav [data-tab=diet]', 800);
await click('[data-act=meal][data-i="0"]', 600);
shot('screen-4-diet');
await click('#nav [data-tab=body]', 800);
shot('screen-5-body');
await click('#nav [data-tab=progress]', 1000);
shot('screen-6-progress');

await click('header [data-act=report]', 1200);
log('report starts:', await js(`(document.getElementById('repText') || {}).textContent.slice(0, 60)`));
shot('screen-7-report');
await click('[data-act=shareReport]', 4000);
log('after share, top activity:', top());
shot('screen-8-share-sheet');
await back();
await sleep(1000);
await back();
log('sheet open after back:', await js(`document.getElementById('sheet').open`), '| title:', await js(`document.getElementById('title').textContent`));
await back();
log('title after back from progress:', await js(`document.getElementById('title').textContent`), '| top:', top());
shot('screen-9-back-home');

log('page errors:', errors.length ? errors.join(' | ') : 'none');
ws.close();
process.exit(0);
