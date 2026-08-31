/*
 * Does the Control Panel's print preview always settle?  node tools/PreviewStateCheck.js
 *
 * The companion to GeometryCheck and StrategyCheck, for the one part of the panel where a bug is
 * invisible rather than wrong: the preview's state machine. It shipped once with a busy indicator
 * that could be left showing over a finished picture, which reads as a hung application and is
 * impossible to tell apart from a wedged printer driver.
 *
 * The invariant under test is small and absolute: after every exit path of doPreview -- success,
 * superseded, aborted, timed out, refused by the service, and each of the three early returns --
 * the indicator ends up hidden, and no more than one render is ever on the wire. The second half
 * matters as much as the first: a browser allows about six connections per origin, so overlapping
 * renders starve the status poll and the panel appears to freeze while nothing is being requested.
 *
 * Runs against the real app.js with a DOM and fetch shim. No browser, no printer, no paper; exits
 * non-zero on drift.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const APP = path.join(__dirname, '..', 'src', 'main', 'resources', 'ui', 'app.js');

// ---------------------------------------------------------------- DOM shim

class El {
  constructor(id) {
    this.id = id;
    this.hidden = false;
    this.value = '';
    this.checked = false;
    this.textContent = '';
    this.innerHTML = '';
    this.className = '';
    this.style = {};
    this.tagName = 'DIV';
    this.disabled = false;
    this.open = false;
    this.dataset = {};
    this.files = [];
    this._on = {};
  }
  addEventListener(t, fn) { (this._on[t] = this._on[t] || []).push(fn); }
  removeEventListener() {}
  setAttribute() {}
  getAttribute() { return null; }
  closest() { return null; }
  scrollIntoView() {}
  click() {}
  fire(t, ev = {}) { (this._on[t] || []).forEach((fn) => fn(ev)); }
}

const els = new Map();
function el(id) {
  if (!els.has(id)) els.set(id, new El(id));
  return els.get(id);
}

global.document = {
  querySelector(sel) {
    if (sel.startsWith('#')) return el(sel.slice(1));
    if (sel === 'details.raw') return el('__raw');
    return el('__' + sel);
  },
  querySelectorAll() { return []; },
  createElement() { return new El('created'); },
  body: new El('body'),
  addEventListener() {},
};
global.location = { origin: 'http://127.0.0.1:9110' };
// navigator is read-only in modern Node; app.js only touches it in the log-copy button.
Object.defineProperty(global, 'navigator', { value: { clipboard: { writeText: async () => {} } }, configurable: true });
global.CSS = { escape: (s) => s };
global.confirm = () => true;
global.prompt = () => 'x';
global.URL = URL;
global.AbortController = AbortController;

// ---------------------------------------------------------------- fetch control

let fetchMode = 'ok';
let fetchDelay = 5;
let inFlightCount = 0;
let maxInFlight = 0;
let started = 0;
let aborted = 0;

const PREVIEW_OK = () => ({
  ok: true,
  printer: 'TSC TE244',
  preview: { mime: 'image/png', data: 'aGk=', widthPx: 615, heightPx: 900, dpi: 150, pageIndex: 0, pageCount: 3 },
  summary: ['paper 295.1 x 432.0 pt'],
  page: {
    paper: { widthPt: 295.1, heightPt: 432, widthIn: 4.098, heightIn: 6 },
    imageable: { xPt: 3.5, yPt: 0, widthPt: 288, heightPt: 432, widthIn: 4, heightIn: 6 },
    effective: { xPt: 3.5, yPt: 0, widthPt: 288, heightPt: 432, widthIn: 4, heightIn: 6 },
    source: { widthPt: 595, heightPt: 842, widthIn: 8.268, heightIn: 11.693 },
    orientation: { value: 'portrait', source: 'requested' },
    scaling: 'scale-to-fit', scaleFactor: 0.484,
    contentClipped: false, fits: false,
    notes: [{ level: 'warn', code: 'clamped', field: 'x', from: 0, to: 3.5, bound: 'head' }],
  },
});

global.fetch = (url, init) => {
  started += 1;
  inFlightCount += 1;
  maxInFlight = Math.max(maxInFlight, inFlightCount);
  return new Promise((resolve, reject) => {
    const done = (fn) => { inFlightCount -= 1; fn(); };
    const timer = setTimeout(() => {
      if (fetchMode === 'error') {
        return done(() => resolve({ ok: false, status: 503, text: async () => JSON.stringify({ error: 'busy printing' }) }));
      }
      if (fetchMode === 'hang') return; // never settles; only an abort ends it
      done(() => resolve({ ok: true, status: 200, text: async () => JSON.stringify(PREVIEW_OK()) }));
    }, fetchMode === 'hang' ? 1e6 : fetchDelay);
    if (init && init.signal) {
      init.signal.addEventListener('abort', () => {
        clearTimeout(timer);
        aborted += 1;
        const e = new Error('aborted');
        e.name = 'AbortError';
        done(() => reject(e));
      });
    }
  });
};

// ---------------------------------------------------------------- load app.js

let src = fs.readFileSync(APP, 'utf8');
src = src.replace(/boot\(\)\.catch\([\s\S]*$/, '');   // do not run the real boot
const api = new Function(`${src}\nreturn { doPreview, resetSheet, abortPreview, S, verdict, paperLabel };`)();
const { doPreview, resetSheet, abortPreview, S } = api;

// ---------------------------------------------------------------- harness

let checks = 0;
const failures = [];

function ok(label, cond, detail = '') {
  checks += 1;
  if (cond) console.log(`  ok    ${label}${detail ? '  ' + detail : ''}`);
  else { failures.push(label); console.log(`  FAIL  ${label}  ${detail}`); }
}

function ready({ file = true, printer = true, raw = false, auto = true } = {}) {
  els.clear();
  S.print.file = file ? { fileId: 'f_1', name: 'labels.pdf', pages: 3 } : null;
  S.print.page = 1;
  S.print.pageCount = 3;
  S.print.seq = 0;
  S.print.inFlight = null;
  S.print.drawn = null;
  S.data = { settings: auto ? {} : { autoPreview: false }, presets: [], printers: [], strategies: [] };
  el('print-printer').value = printer ? 'TSC TE244' : '';
  el('print-preset').value = '';
  el('print-strategy').value = '';
  el('print-pages').value = '';
  el('print-order').value = 'normal';
  el('sheet-guides').checked = true;
  el('__raw').open = raw;
  ['adj-width', 'adj-height', 'adj-mtop', 'adj-mright', 'adj-mbottom', 'adj-mleft', 'adj-density']
    .forEach((k) => { el(k).value = ''; });
  el('adj-units').value = 'in';
  el('adj-sizemeans').value = '';
  el('adj-orientation').value = '';
  el('adj-scale').value = '';
  el('sheet-busy').hidden = true;
}

const busy = () => el('sheet-busy').hidden === false;
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  console.log('-- the chip clears on every exit path');

  fetchMode = 'ok'; fetchDelay = 5;
  ready();
  await doPreview({ force: true });
  ok('a successful render clears it', !busy());
  ok('and paints the paper', el('sheet-paper').hidden === false);
  ok('and fills the caption', el('sheet-caption').innerHTML.includes('TSC TE244'));

  ready({ file: false });
  el('sheet-busy').hidden = false;                       // pretend one was in flight
  await doPreview({ force: true });
  ok('no file: cleared by the early return', !busy(), '(this is the bug that stuck)');

  ready({ printer: false });
  el('sheet-busy').hidden = false;
  await doPreview({ force: true });
  ok('no printer: cleared by the early return', !busy());

  ready({ raw: true });
  el('sheet-busy').hidden = false;
  await doPreview({ force: true });
  ok('raw mode: cleared by the early return', !busy());

  ready({ auto: false });
  el('sheet-busy').hidden = false;
  await doPreview();
  ok('auto-preview off: cleared, and says so', !busy()
    && el('sheet-empty').textContent.includes('Refresh preview'));

  fetchMode = 'error';
  ready();
  await doPreview({ force: true });
  ok('a service error clears it', !busy());
  ok('and reports the reason', el('sheet-verdict').innerHTML.includes('busy printing'));

  console.log('\n-- only one render is ever in flight');
  fetchMode = 'ok'; fetchDelay = 60;
  ready();
  started = 0; aborted = 0; maxInFlight = 0;
  const burst = [];
  for (let i = 0; i < 8; i += 1) {
    S.print.page = (i % 3) + 1;                          // vary it so the dedupe does not swallow them
    burst.push(doPreview({ force: true }));
    await wait(4);
  }
  await Promise.all(burst);
  ok('never more than one request on the wire', maxInFlight === 1, `peak ${maxInFlight} of ${started} issued`);
  ok('the superseded ones were aborted', aborted === started - 1, `${aborted} aborted`);
  ok('the chip is clear at the end', !busy());

  console.log('\n-- a hung printer does not hang the screen');
  fetchMode = 'hang';
  ready();
  // The real timeout is 24s; the test drives the same path by aborting the way the timer would.
  const hung = doPreview({ force: true });
  // The chip is deliberately delayed 180ms so a fast render does not flash it, so wait past that.
  await wait(300);
  ok('the chip appears once a render is slow enough to mention', busy());
  S.print.inFlight.abort();
  await hung;
  ok('an abort while current clears the chip', !busy());
  ok('and explains what happened', el('sheet-verdict').innerHTML.includes('did not answer in time'));
  ok('and keeps the last preview on screen', el('sheet-paper').hidden === false,
    '(an error must not blank what was already drawn)');

  console.log('\n-- identical settings do not re-render');
  fetchMode = 'ok'; fetchDelay = 5;
  ready();
  await doPreview({ force: true });
  started = 0;
  await doPreview();
  await doPreview();
  ok('an unchanged config issues no request', started === 0, `${started} issued`);
  el('print-pages').value = 'odd';
  await doPreview();
  ok('a changed config does', started === 1, `${started} issued`);

  console.log('');
  console.log(`${failures.length ? 'FAILED' : 'OK'} - ${checks - failures.length}/${checks} checks passed`);
  failures.forEach((f) => console.log(`  failed: ${f}`));
  process.exit(failures.length ? 1 : 0);
})();
