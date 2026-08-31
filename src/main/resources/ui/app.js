/*
 * Printly Control Panel.
 *
 * Plain JavaScript, no framework, no build step — the same reasoning that keeps the service's build
 * a bare `javac` over vendored jars. It talks to `/api/*` on the service that served it.
 *
 * Two ideas run through the whole file and are worth stating once:
 *
 * 1. THE PLAN IS SHOWN BEFORE ANYTHING PRINTS. Every screen that can start a print first asks the
 *    service what it would do — resolved against the real page count of the real file and the real
 *    printer's real media — and shows it. The failure this design is aimed at is not a crash, it is
 *    forty labels already on the floor before anyone notices the rule was wrong.
 *
 * 2. ACCEPTED IS NOT PRINTED. The service answers 202 when a job is still in flight when the wait
 *    expires. 202 is a 2xx, so a plain `response.ok` reads it as success, and that is exactly how
 *    orders get marked packed with nothing in the bag. Every place here that reports an outcome
 *    distinguishes confirmed-on-paper from still-in-flight, and never calls the second one done.
 */

'use strict';

/* ------------------------------------------------------------------ helpers */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/** Escape for interpolation into HTML. Printer names, file names and notes are all user data. */
function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

const fmt = {
  bytes(n) {
    if (n === undefined || n === null) return '—';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  },
  ms(n) {
    if (n === undefined || n === null) return '—';
    if (n < 1000) return `${Math.round(n)} ms`;
    if (n < 60000) return `${(n / 1000).toFixed(1)} s`;
    return `${Math.floor(n / 60000)}m ${Math.round((n % 60000) / 1000)}s`;
  },
  time(ts) {
    if (!ts) return '—';
    return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  },
  inches(pt) {
    return pt ? `${(pt / 72).toFixed(2)}in` : '—';
  },
};

/** One request. Failures arrive as thrown Errors carrying the service's own message. */
async function api(path, { method = 'GET', body, raw, query, signal } = {}) {
  const url = new URL(path, location.origin);
  if (query) Object.entries(query).forEach(([k, v]) => v != null && url.searchParams.set(k, v));

  const init = { method };
  if (signal) init.signal = signal;
  if (raw) {
    init.method = 'POST';
    init.headers = { 'Content-Type': 'application/pdf' };
    init.body = raw;
  } else if (body !== undefined) {
    init.method = method === 'GET' ? 'POST' : method;
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(url, init);
  } catch (e) {
    // An abort is a caller decision, not a failure, and must not be reported as the service being
    // down — the caller that aborted is about to issue a replacement request.
    if (e && e.name === 'AbortError') {
      const abort = new Error('superseded');
      abort.aborted = true;
      throw abort;
    }
    throw new Error('The Printly service is not answering. Is it still running in the system tray?');
  }
  const text = await response.text();
  let payload = {};
  try {
    payload = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`The service answered with something that is not JSON (${response.status}).`);
  }
  if (!response.ok) {
    const error = new Error(payload.error || `The service returned ${response.status}.`);
    error.status = response.status;
    error.payload = payload;
    throw error;
  }
  // 202 is deliberately not treated as an error here — the callers that care read `state` and
  // `ok` off the job and say "sent, not confirmed". Throwing would lose the job id.
  payload.httpStatus = response.status;
  return payload;
}

function toast(message, kind = 'ok', title) {
  const node = document.createElement('div');
  node.className = `toast ${kind}`;
  node.innerHTML = (title ? `<strong>${esc(title)}</strong>` : '') + esc(message);
  $('#toasts').appendChild(node);
  setTimeout(() => node.remove(), kind === 'bad' ? 9000 : 5000);
}

function show(node, html, kind) {
  node.hidden = false;
  node.className = `result${kind ? ` ${kind}` : ''}`;
  node.innerHTML = html;
}

function debounce(fn, ms) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
}

/* ------------------------------------------------------------------ paper */

/**
 * Common paper sizes, so the preview can say "A4" instead of only "8.27 × 11.69 in".
 *
 * Names matter more than they look: "your A4 page on a 4 × 6 label" is a sentence a packer can act
 * on, where two pairs of decimals is a sum they have to do first. Tolerance is 0.06in — driver
 * media is rarely exact (a 4 × 6 roll reports 4.10 × 6.00 on the TSC) and a near-miss should still
 * be recognised.
 */
const PAPERS = [
  ['A4', 8.27, 11.69], ['A5', 5.83, 8.27], ['A6', 4.13, 5.83], ['A3', 11.69, 16.54],
  ['Letter', 8.5, 11], ['Legal', 8.5, 14], ['Executive', 7.25, 10.5],
  ['4 × 6 label', 4, 6], ['4 × 6 label', 4.1, 6], ['3 × 2 label', 3, 2],
  ['4 × 4 label', 4, 4], ['2 × 1 label', 2, 1], ['80mm receipt', 3.15, 0],
];

/** A friendly name for a paper size in inches, or null when nothing matches. */
function paperName(win, hin) {
  if (!(win > 0) || !(hin > 0)) return null;
  for (const [name, w, h] of PAPERS) {
    if (h === 0) {
      // A receipt roll: the width is the identity, the length is however much is fed.
      if (Math.abs(win - w) < 0.06) return name;
      continue;
    }
    const hit = (Math.abs(win - w) < 0.06 && Math.abs(hin - h) < 0.06)
      || (Math.abs(win - h) < 0.06 && Math.abs(hin - w) < 0.06);
    if (hit) return name;
  }
  return null;
}

/** "4.10 × 6.00 in", and with its name when it has one: "A4 · 8.27 × 11.69 in". */
function paperLabel(win, hin, { named = true } = {}) {
  if (!(win > 0) || !(hin > 0)) return '—';
  const dims = `${win.toFixed(2)} × ${hin.toFixed(2)} in`;
  const name = named ? paperName(win, hin) : null;
  return name ? `${name} · ${dims}` : dims;
}

/* ------------------------------------------------------------------ state */

const S = {
  data: null,             // the last /api/state
  view: 'print',
  print: { file: null, batchId: null, page: 1, pageCount: 1, seq: 0, inFlight: null, drawn: null },
  bulk: { batchId: null, batch: null, uploading: false, plans: {} },
  editingPreset: null,    // preset id, '' for a new one, null when the form is untouched
  editingStrategy: null,
  rules: [],
  printerForm: { editing: null },
  jobsFilter: 'all',
  polling: null,
};

/** The printers of one lane, or all of them. */
function printers(lane) {
  const all = S.data?.printers || [];
  return lane ? all.filter((p) => p.lane === lane) : all;
}

function presets() {
  return (S.data?.presets || []).slice().sort((a, b) => (a.order || 99) - (b.order || 99)
    || String(a.name).localeCompare(String(b.name)));
}

function strategies() {
  return (S.data?.strategies || []).slice().sort((a, b) => String(a.name).localeCompare(String(b.name)));
}

function settings() {
  return S.data?.settings || {};
}

function waitMs() {
  const value = Number(settings().waitMs);
  return Number.isFinite(value) ? Math.max(0, Math.min(60000, value)) : 12000;
}

/**
 * Whether the preview redraws by itself.
 *
 * Default true, and the default matters: this screen is built around the picture answering the
 * question, so an absent setting has to mean on. It is a setting at all only because rendering runs
 * on the printer's own lane thread, so on a station whose printer is permanently mid-batch an
 * operator may want to ask for the picture explicitly instead.
 */
function autoPreview() {
  const value = settings().autoPreview;
  return value === undefined || value === null ? true : !!value;
}

/* ------------------------------------------------------------------ select filling */

/**
 * Fill a printer dropdown, keeping the current choice if it still exists.
 *
 * Grouped by lane and labelled with what each lane is for, because the difference is not cosmetic:
 * a PDF cannot go down the label lane and raw commands cannot go down the document lane, and a flat
 * list of names invites exactly that mistake.
 */
function fillPrinters(select, lane, chosen) {
  const want = chosen ?? select.value;
  const list = printers(lane);
  const groups = { label: [], document: [] };
  list.forEach((p) => groups[p.lane]?.push(p));

  let html = list.length ? '' : '<option value="">No printers found</option>';
  for (const [key, title] of [['label', 'Label printers — raw commands, no driver'],
    ['document', 'Document printers — PDFs, via the driver']]) {
    if (!groups[key].length) continue;
    html += `<optgroup label="${esc(title)}">`;
    groups[key].forEach((p) => {
      const state = p.online === false ? ' — offline' : '';
      html += `<option value="${esc(p.name)}">${esc(p.name)}${state}</option>`;
    });
    html += '</optgroup>';
  }
  select.innerHTML = html;
  if (want && list.some((p) => p.name === want)) select.value = want;
  else if (settings().defaultPrinter && list.some((p) => p.name === settings().defaultPrinter)) {
    select.value = settings().defaultPrinter;
  }
}

function fillPresets(select, chosen, emptyLabel = 'No preset — the driver\'s own defaults') {
  const want = chosen ?? select.value;
  let html = `<option value="">${esc(emptyLabel)}</option>`;
  presets().forEach((p) => {
    html += `<option value="${esc(p.id)}">${esc(p.name)}</option>`;
  });
  select.innerHTML = html;
  if (want) select.value = want;
  else if (settings().defaultPresetId) select.value = settings().defaultPresetId;
}

function fillStrategies(select, chosen, emptyLabel = 'No strategy — use the settings above') {
  const want = chosen ?? select.value;
  let html = `<option value="">${esc(emptyLabel)}</option>`;
  strategies().forEach((s) => {
    html += `<option value="${esc(s.id)}">${esc(s.name)}</option>`;
  });
  select.innerHTML = html;
  if (want) select.value = want;
}

/* ------------------------------------------------------------ the verdict
 *
 * Turning the resolver's structured notes into sentences a packer can act on.
 *
 * This is the whole point of the rework. The service already knew everything below — it reported it
 * as `clamped width 537.8 -> 237.5 pt (media)`, which is precise, complete, and useless to the
 * person holding the label. Every note carries a `code`, and a clamp carries the `bound` that
 * caused it, so the translation is a lookup rather than a guess.
 *
 * The rules it encodes, in order of how much they should worry someone:
 *
 *   content-clipped   artwork is genuinely lost. The only true "cut off".
 *   sheet-not-loaded  the one case where this preview cannot promise to match the paper.
 *   clamped (media)   the page was bigger than the paper.
 *   clamped (head)    the page reached where the printer cannot mark.
 *   clamped (margin)  your own margin held it back — working as asked, so only informational.
 *
 * A clamp is deliberately NOT described as content being cut off. Under scale-to-fit the geometry
 * is trimmed and the artwork is then shrunk into whatever survived, so nothing is lost — saying
 * "content will be clipped" there would be a new lie in place of the old jargon.
 */

/**
 * Which bound being hit is actually worth worrying about.
 *
 * This mapping is the difference between a screen people read and one they learn to ignore, and it
 * does not follow `fits`. `fits` is false whenever the resolved geometry differs from the geometry
 * asked for, which is the right question for a calibration report and the wrong one for an
 * operator, because the three ways it can differ mean completely different things:
 *
 *   head    the printer physically cannot mark that strip, so content was pulled inside it. That
 *           is the printer protecting the job -- the original bug was content being left in that
 *           strip and silently shaved off. A TSC TE244 reports a 1.25mm strip, so this fires on
 *           EVERY zero-margin label profile. Warning on the normal state of the station is how you
 *           train someone to ignore warnings, so it is informational, with the measurement.
 *   margin  the operator's own margin held the page back. Working exactly as asked.
 *   media   the page was bigger than the paper. Genuinely actionable, and the failure that once
 *           put one order across three labels. This one warns.
 */
const CLAMP_LEVEL = { head: 'info', margin: 'info', media: 'warn' };

function verdict(page) {
  if (!page) return null;
  const notes = page.notes || [];
  const has = (code) => notes.some((n) => n.code === code);
  const clamps = notes.filter((n) => n.code === 'clamped');
  const mm = (n) => Math.abs((n.from ?? 0) - (n.to ?? 0)) / 72 * 25.4;
  const worst = (bound) => Math.max(0, ...clamps.filter((n) => n.bound === bound).map(mm));
  const bounds = new Set(clamps.map((n) => n.bound));
  const lines = [];
  let level = 'ok';

  const raise = (to) => {
    const rank = { ok: 0, info: 0, warn: 1, bad: 2 };
    if (rank[to] > rank[level]) level = to;
  };

  // What was scaled onto what. The single most useful sentence on the screen.
  const src = page.source;
  const factor = page.scaleFactor;
  if (src && factor != null) {
    const from = paperLabel(src.widthIn, src.heightIn);
    if (factor < 0.995) {
      lines.push(`Your ${from} page is being shrunk to ${Math.round(factor * 100)}% to fit the printable area.`);
    } else if (factor > 1.005) {
      lines.push(`Your ${from} page is being enlarged to ${Math.round(factor * 100)}% to fill the printable area.`);
    } else {
      lines.push(`Your ${from} page is printing at its own size.`);
    }
  }

  if (page.contentClipped) {
    raise('bad');
    lines.push('Scaling is set to <b>Actual size</b>, so anything past the printable area is cut '
      + 'off — you can see where in the preview. Switch scaling to <b>Fit the printable area</b> to '
      + 'keep all of it.');
  }

  if (has('sheet-not-loaded')) {
    raise('bad');
    lines.push('<b>The paper size you set is not the paper this printer has loaded.</b> The printer '
      + 'decides how to fit one onto the other, and nothing here can see how — so this preview shows '
      + 'what Printly sends, not exactly what lands on the label. This is the one case where the '
      + 'preview cannot promise to match.');
  }

  if (bounds.has('media')) {
    raise(CLAMP_LEVEL.media);
    lines.push(`The page was ${worst('media').toFixed(0)}mm larger than the paper, so it was brought `
      + 'in to the paper\u2019s edge. Without that a roll printer keeps feeding and one job comes out '
      + 'across several labels.');
  }
  if (bounds.has('head')) {
    raise(CLAMP_LEVEL.head);
    lines.push(`Brought in ${worst('head').toFixed(1)}mm from the edge — that is as close to the paper `
      + 'as this printer\u2019s head can reach, and it is shaded red in the preview. Nothing is lost; '
      + 'without it that much would have been shaved off.');
  }
  if (bounds.has('margin')) {
    raise(CLAMP_LEVEL.margin);
    lines.push('Your own margin is holding the page back from the edge — that is it working as asked.');
  }
  if (has('no-printable-area')) {
    lines.push('This printer does not report how far its head can reach, so only the paper edge is '
      + 'known. The preview may be slightly optimistic at the very edges.');
  }

  const orient = page.orientation || {};
  if (orient.source === 'auto-detected') {
    lines.push('Turned sideways automatically, because the page is wider than it is tall.');
  } else if (orient.source === 'requested') {
    lines.push(`Orientation set to <b>${esc(orient.value)}</b> by the preset.`);
  }

  const headline = level === 'bad'
    ? (page.contentClipped ? 'Some content will be cut off' : 'This may not come out as shown')
    : level === 'warn'
      ? 'It will print, but not exactly as the preset describes'
      : 'This is what will print';

  return { level, headline, lines };
}

/* ================================================================== boot */

async function boot() {
  wireNav();
  wirePrint();
  wireBulk();
  wirePrinters();
  wirePresets();
  wireStrategies();
  wireJobs();
  wireSettings();

  $('#reconnect').addEventListener('click', async () => {
    try {
      await api('/api/reconnect', { body: {} });
      toast('Every label printer socket has been dropped and will reopen.', 'ok', 'Reconnecting');
      await refresh();
    } catch (e) {
      toast(e.message, 'bad', 'Could not reconnect');
    }
  });

  await refresh();
  applyDefaults();
  schedulePoll();
}

/** The poll rate follows what is happening: fast while a batch runs, calm otherwise. */
function schedulePoll() {
  clearTimeout(S.polling);
  const busy = S.bulk.batch && !['done', 'failed', 'cancelled'].includes(S.bulk.batch.state);
  S.polling = setTimeout(async () => {
    try {
      await refresh();
      if (S.bulk.batchId) await refreshBatch();
    } catch {
      // A single failed poll is not worth a message: the service restarting mid-shift is normal
      // and the next tick reconnects. A persistent failure shows in the header status instead.
    }
    schedulePoll();
  }, busy ? 700 : 2500);
}

async function refresh() {
  const previous = S.data;
  try {
    S.data = await api('/api/state');
  } catch (e) {
    $('#lane-status').className = 'pill bad';
    $('#lane-status').textContent = 'service not answering';
    if (previous) throw e;
    return;
  }
  renderHeader();
  renderActiveView({ poll: true });
}

function renderHeader() {
  const d = S.data;
  $('#version').textContent = `v${d.version}`;
  const label = $('#lane-status');
  const labelPrinters = printers('label');
  const offline = labelPrinters.filter((p) => p.online === false).length;
  if (!d.documentLane && !labelPrinters.length) {
    label.className = 'pill warn';
    label.textContent = 'no printers configured';
  } else if (offline) {
    label.className = 'pill bad';
    label.textContent = `${offline} label printer${offline > 1 ? 's' : ''} offline`;
  } else {
    label.className = 'pill ok';
    label.textContent = `${d.documentPrinters} document · ${labelPrinters.length} label`;
  }
}

/** Defaults from the settings file, applied once after the first load. */
function applyDefaults() {
  const s = settings();
  if (s.defaultStrategyId) {
    $('#print-strategy').value = s.defaultStrategyId;
    $('#bulk-strategy').value = s.defaultStrategyId;
  } else {
    const fallback = strategies().find((x) => x.isDefault);
    if (fallback) $('#bulk-strategy').value = fallback.id;
  }
  if (s.concurrency) {
    $('#bulk-concurrency').value = s.concurrency;
    $('#set-concurrency').value = s.concurrency;
  }
  $('#set-wait').value = waitMs();
  $('#set-autopreview').checked = autoPreview();
  updatePrintPlan();
}

/* ------------------------------------------------------------------ nav */

function wireNav() {
  $('#nav').addEventListener('click', (e) => {
    const button = e.target.closest('.navitem');
    if (!button) return;
    S.view = button.dataset.view;
    $$('.navitem').forEach((b) => b.classList.toggle('active', b === button));
    $$('.view').forEach((v) => { v.hidden = v.id !== `view-${S.view}`; });
    renderActiveView();
    // The two editors are only built on a user action, and arriving at the screen is one.
    if (S.view === 'strategies' && !S.rules.length) loadStrategy(null);
  });
}

/**
 * Redraw the visible screen.
 *
 * The `poll` flag is what keeps a 2.5-second refresh from fighting the person using the page. A
 * poll may redraw anything derived purely from service state — printer status, job history, batch
 * progress — but must not rebuild a form someone is typing into: doing so takes their focus and
 * their caret every few seconds, which makes the rule editor unusable in a way that is very hard to
 * describe as a bug report. So the editors redraw on user actions only.
 */
function renderActiveView({ poll = false } = {}) {
  switch (S.view) {
    case 'print': renderPrint(poll); break;
    case 'bulk': renderBulk(); break;
    case 'printers': renderPrinters(); break;
    case 'presets': renderPresets(); break;
    case 'strategies': renderStrategies(poll); break;
    case 'jobs': renderJobs(); break;
    case 'settings': renderSettings(poll); break;
  }
  const staged = (S.data?.files || []).length;
  const badge = $('#badge-bulk');
  badge.hidden = staged === 0;
  badge.textContent = staged;
}

/* ================================================================== PRINT */

function rawMode() {
  return $('details.raw').open;
}

function wirePrint() {
  const drop = $('#print-drop');
  const input = $('#print-file');
  $('#print-browse').addEventListener('click', () => input.click());
  input.addEventListener('change', () => input.files[0] && choosePrintFile(input.files[0]));
  wireDropZone(drop, (files) => files[0] && choosePrintFile(files[0]));

  $('details.raw').addEventListener('toggle', () => {
    fillPrinters($('#print-printer'), rawMode() ? 'label' : 'document');
    configChanged();
  });

  // Anything that can change what comes out of the printer redraws the preview. That is the
  // requirement the old screen missed: the preview was a button you pressed, so it was always a
  // picture of a configuration you had since changed.
  ['#print-printer', '#print-preset', '#print-strategy', '#print-copies', '#print-pages',
    '#print-order', '#sheet-guides'].forEach((sel) => $(sel).addEventListener('change', configChanged));
  ADJUST_FIELDS.forEach((sel) => {
    const el = $(sel);
    el.addEventListener('change', configChanged);
    if (el.tagName === 'INPUT') el.addEventListener('input', debounce(configChanged, 400));
  });
  $('#print-preset').addEventListener('change', () => { clearAdjust(); configChanged(); });
  $('#print-pages').addEventListener('input', debounce(configChanged, 350));
  $('#raw-data').addEventListener('input', debounce(configChanged, 350));

  $('#adjust-reset').addEventListener('click', () => { clearAdjust(); configChanged(); });
  $('#adjust-save').addEventListener('click', saveAdjustAsPreset);

  $('#page-prev').addEventListener('click', () => stepPage(-1));
  $('#page-next').addEventListener('click', () => stepPage(1));

  $('#print-go').addEventListener('click', doPrint);
  $('#print-preview-btn').addEventListener('click', () => doPreview({ force: true }));
  $('#print-preflight-btn').addEventListener('click', doPreflight);
}

/** The override controls, in the order the geometry reads them. */
const ADJUST_FIELDS = ['#adj-width', '#adj-height', '#adj-units', '#adj-sizemeans',
  '#adj-mtop', '#adj-mright', '#adj-mbottom', '#adj-mleft',
  '#adj-orientation', '#adj-scale', '#adj-density'];

function clearAdjust() {
  ADJUST_FIELDS.forEach((sel) => {
    const el = $(sel);
    if (el.tagName === 'SELECT') el.value = sel === '#adj-units' ? 'in' : '';
    else el.value = '';
  });
}

/**
 * The overrides as an options object, or null when nothing is overridden.
 *
 * Only fields the operator actually filled in are sent, because the service treats absent and
 * default as different things — an invoice preset depends on orientation staying absent so the
 * auto-landscape detection reads the PDF instead. A blank box here therefore means "leave the
 * preset alone", and "Let the document decide" is how you explicitly ask for absent.
 */
function adjustOverrides() {
  const num = (sel) => {
    const v = Number($(sel).value);
    return $(sel).value.trim() !== '' && Number.isFinite(v) ? v : null;
  };
  const out = {};
  const w = num('#adj-width');
  const h = num('#adj-height');
  if (w > 0 && h > 0) out.size = { width: w, height: h, units: $('#adj-units').value };
  if ($('#adj-sizemeans').value) out.sizeMeans = $('#adj-sizemeans').value;

  const m = { top: num('#adj-mtop'), right: num('#adj-mright'),
    bottom: num('#adj-mbottom'), left: num('#adj-mleft') };
  if (Object.values(m).some((v) => v !== null)) {
    out.margins = {
      top: m.top ?? 0, right: m.right ?? 0, bottom: m.bottom ?? 0, left: m.left ?? 0,
    };
  }

  const orientation = $('#adj-orientation').value;
  // "auto" is the explicit way to ask for absent, which an empty <select> value cannot express
  // because empty already means "don't override".
  if (orientation && orientation !== 'auto') out.orientation = orientation;
  if ($('#adj-scale').value) out.scale = $('#adj-scale').value;
  const density = num('#adj-density');
  if (density > 0) out.density = density;

  if (orientation === 'auto') out.__clearOrientation = true;
  return Object.keys(out).length ? out : null;
}

/**
 * The geometry for the current screen: the preset, with the overrides on top.
 *
 * Resolved in the browser rather than server-side so that "Let the document decide" can genuinely
 * remove a preset's orientation. The service merges `options` over a preset field by field and has
 * no way to spell "delete this key", so the deletion happens here where both halves are in hand.
 */
function effectiveOptions() {
  const overrides = adjustOverrides();
  if (!overrides) return null;
  const preset = presets().find((x) => x.id === $('#print-preset').value);
  const merged = { ...(preset?.options || {}), ...overrides };
  if (merged.__clearOrientation) {
    delete merged.orientation;
    delete merged.__clearOrientation;
  }
  return merged;
}

async function saveAdjustAsPreset() {
  const options = effectiveOptions();
  if (!options) {
    toast('Nothing is overridden yet — change a size or a margin first.', 'warn');
    return;
  }
  const base = presets().find((x) => x.id === $('#print-preset').value);
  const name = prompt('Name for this preset', base ? `${base.name} (adjusted)` : 'My preset');
  if (!name) return;
  try {
    const result = await api('/api/presets', {
      body: { name: name.trim(), note: 'Saved from the Print screen.', mode: 'document', copies: 1, options },
    });
    await refresh();
    clearAdjust();
    $('#print-preset').value = result.item.id;
    configChanged();
    toast(`“${result.item.name}” saved and selected.`, 'ok', 'Preset saved');
  } catch (e) {
    toast(e.message, 'bad', 'Not saved');
  }
}

function stepPage(by) {
  const next = Math.min(Math.max(1, S.print.page + by), S.print.pageCount);
  if (next === S.print.page) return;
  S.print.page = next;
  $('#page-label').textContent = `${S.print.page} / ${S.print.pageCount}`;
  doPreview({ force: true });
}

/** One change, one redraw: the plan and the picture both follow the same controls. */
function configChanged() {
  updatePrintPlan();
  schedulePreview();
}

function wireDropZone(zone, onFiles) {
  ['dragenter', 'dragover'].forEach((type) => zone.addEventListener(type, (e) => {
    e.preventDefault();
    zone.classList.add('over');
  }));
  ['dragleave', 'drop'].forEach((type) => zone.addEventListener(type, (e) => {
    e.preventDefault();
    if (type === 'dragleave' && zone.contains(e.relatedTarget)) return;
    zone.classList.remove('over');
  }));
  zone.addEventListener('drop', (e) => {
    const files = Array.from(e.dataTransfer?.files || []).filter((f) => /\.pdf$/i.test(f.name)
      || f.type === 'application/pdf');
    if (!files.length) {
      toast('Only PDF files can be printed through the document lane.', 'warn', 'Not a PDF');
      return;
    }
    onFiles(files);
  });
}

/**
 * Stage the chosen file and read what it is.
 *
 * The file goes to the service's staging area rather than being kept in the page. That is what lets
 * the preview, the plan and the print all work off the same bytes the printer will get, and it is
 * what keeps a 40 MB manifest out of the browser's memory on a machine that is also running the
 * packing app.
 */
async function choosePrintFile(file) {
  const previous = S.print.file;
  try {
    const result = await api(`/api/files?name=${encodeURIComponent(file.name)}`, { raw: file });
    S.print.file = result.file;
    S.print.page = 1;
    S.print.pageCount = result.file.pages || 1;
    if (previous) await api('/api/files/delete', { body: { fileId: previous.fileId } });
    renderPrintFile();
    await refresh();
    configChanged();
  } catch (e) {
    toast(e.message, 'bad', `Could not read ${file.name}`);
  }
}

function renderPrintFile() {
  const box = $('#print-fileinfo');
  const f = S.print.file;
  if (!f) {
    box.hidden = true;
    return;
  }
  box.hidden = false;
  box.innerHTML = `
    <strong>${esc(f.name)}</strong>
    <span class="muted">${f.pages} page${f.pages === 1 ? '' : 's'}</span>
    <span class="muted">${fmt.bytes(f.bytes)}</span>
    <span class="pill plain">${f.landscape ? 'landscape' : 'portrait'} ${fmt.inches(f.widthPt)} × ${fmt.inches(f.heightPt)}</span>
    <button class="link" id="print-file-clear">Remove</button>`;
  $('#print-file-clear').addEventListener('click', async () => {
    await api('/api/files/delete', { body: { fileId: f.fileId } });
    S.print.file = null;
    $('#print-file').value = '';
    renderPrintFile();
    resetSheet('Choose a printer and a file.');
    configChanged();
    refresh();
  });
}

function renderPrint(poll = false) {
  fillPrinters($('#print-printer'), rawMode() ? 'label' : 'document');
  fillPresets($('#print-preset'));
  fillStrategies($('#print-strategy'));
  renderPrintFile();
  // Neither the plan nor the preview is re-asked for on a timer: both answer the same question
  // they already answered, and a redraw would also throw away the operator's scroll position on
  // the image.
  if (!poll) configChanged();
}

/** The strategy to use, or an inline one built from the page/copies fields. */
function printStrategy() {
  const id = $('#print-strategy').value;
  if (id) return { strategyId: id };
  return {
    strategy: {
      name: 'Direct',
      rules: [{
        label: 'Pages',
        pages: $('#print-pages').value.trim() || 'all',
        pageOrder: $('#print-order').value,
        copies: Number($('#print-copies').value) || 1,
      }],
    },
  };
}

const updatePrintPlan = debounce(async () => {
  const box = $('#print-plan');
  const printer = $('#print-printer').value;
  const usingStrategy = !!$('#print-strategy').value;

  // A strategy owns the page selection. Leaving these enabled would let an operator set both and
  // then have to guess which won.
  $('#print-pages').disabled = usingStrategy;
  $('#print-order').disabled = usingStrategy;

  if (rawMode()) {
    const bytes = $('#raw-data').value.trim().length;
    $('#print-go').disabled = !printer || !bytes;
    $('#print-preview-btn').disabled = true;
    $('#print-preflight-btn').disabled = true;
    box.innerHTML = !printer ? '<p class="muted">Choose a label printer.</p>'
      : !bytes ? '<p class="muted">Paste the commands to send.</p>'
        : `<div class="planstep"><span class="idx">1</span><div class="body">
             <div class="t">${esc(printer)} — raw, straight to the socket</div>
             <div class="d">${bytes} characters, ${esc($('#print-copies').value || 1)} copy(ies).
               Nothing is rendered and nothing checks it: the commands are yours to get right.</div>
           </div></div>`;
    return;
  }

  const file = S.print.file;
  $('#print-go').disabled = !printer || !file;
  $('#print-preview-btn').disabled = !printer || !file;
  $('#print-preflight-btn').disabled = !printer || !file;

  if (!file) {
    box.innerHTML = '<p class="muted">Choose a PDF to see what would print.</p>';
    return;
  }
  if (!printer) {
    box.innerHTML = '<p class="muted">Choose a printer to see the plan.</p>';
    return;
  }

  try {
    const result = await api('/api/plan', {
      body: {
        fileId: file.fileId,
        printer,
        presetId: $('#print-preset').value,
        ...printStrategy(),
      },
    });
    box.innerHTML = renderPlans(result.plans);
  } catch (e) {
    box.innerHTML = `<div class="planskip">${esc(e.message)}</div>`;
  }
}, 200);

function renderPlans(plans, { showFileNames = false } = {}) {
  if (!plans?.length) return '<p class="muted">Nothing to plan.</p>';
  let html = '';
  plans.forEach((plan) => {
    if (showFileNames) html += `<div class="planfile">${esc(plan.name)}</div>`;
    if (!plan.ok) {
      html += `<div class="planskip"><strong>${esc(plan.name)}</strong> — ${esc(plan.error)}</div>`;
      return;
    }
    plan.steps.forEach((step, i) => {
      const selected = step.selected || [];
      const pages = selected.length > 8
        ? `${selected.slice(0, 8).join(', ')} … (${selected.length} pages)`
        : selected.join(', ');
      html += `<div class="planstep">
        <span class="idx">${i + 1}</span>
        <div class="body">
          <div class="t">${esc(step.label || 'Rule')} → ${esc(step.printer)}</div>
          <div class="d">pages ${esc(pages)}${step.pageOrder === 'reverse' ? ' <em>(reversed)</em>' : ''}
            · ${step.copies} cop${step.copies === 1 ? 'y' : 'ies'}
            ${step.preset ? `· ${esc(step.preset)}` : '· no preset, driver defaults'}</div>
        </div></div>`;
    });
    (plan.skipped || []).forEach((note) => {
      html += `<div class="planskip">Skipped — ${esc(note)}</div>`;
    });
  });
  return html;
}

async function doPrint() {
  const button = $('#print-go');
  const box = $('#print-result');
  button.disabled = true;
  box.hidden = true;
  try {
    if (rawMode()) {
      const job = await api('/api/print', {
        body: {
          mode: 'raw',
          printer: $('#print-printer').value,
          data: $('#raw-data').value,
          encoding: $('#raw-encoding').value,
          copies: Number($('#print-copies').value) || 1,
          wait: waitMs(),
        },
      });
      showJobOutcome(box, [job]);
    } else if ($('#print-strategy').value) {
      // A strategy makes several jobs from one file, which is exactly what a batch of one is.
      // Reusing the batch path rather than looping here means the plan that was shown and the
      // work that runs are produced by the same code.
      const result = await api('/api/batches', {
        body: {
          fileIds: [S.print.file.fileId],
          strategyId: $('#print-strategy').value,
          printer: $('#print-printer').value,
          presetId: $('#print-preset').value,
          copies: Number($('#print-copies').value) || 1,
          concurrency: 1,
        },
      });
      S.print.batchId = result.batchId;
      show(box, '<strong>Sent</strong>Watching the jobs…', 'warn');
      await watchPrintBatch(result.batchId, box);
    } else {
      // previewBody() is deliberately the source of the geometry here too: the picture the
      // operator just approved and the job that is about to print are built from one function, so
      // they cannot describe different pages.
      const job = await api('/api/print', {
        body: {
          ...previewBody(),
          mode: 'document',
          copies: Number($('#print-copies').value) || 1,
          wait: waitMs(),
        },
      });
      showJobOutcome(box, [job]);
    }
    await refresh();
  } catch (e) {
    show(box, `<strong>Nothing was printed</strong>${esc(e.message)}`, 'bad');
  } finally {
    updatePrintPlan();
  }
}

/** Follow a one-file batch to its end, so the Print screen can report a real outcome. */
async function watchPrintBatch(batchId, box) {
  for (let i = 0; i < 400; i += 1) {
    const batch = await api(`/api/batches/${encodeURIComponent(batchId)}`);
    if (['done', 'failed', 'cancelled'].includes(batch.state)) {
      const jobs = (batch.items || []).flatMap((item) => item.jobs || []);
      const failures = (batch.items || []).filter((item) => item.error).map((item) => item.error);
      if (batch.state === 'done') {
        show(box, `<strong>Printed</strong>${jobs.length} job${jobs.length === 1 ? '' : 's'}, confirmed on paper.`, 'ok');
      } else {
        show(box, `<strong>${batch.state === 'cancelled' ? 'Cancelled' : 'Failed'}</strong>`
          + `<ul>${failures.map((f) => `<li>${esc(f)}</li>`).join('')}</ul>`, 'bad');
      }
      return;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  show(box, '<strong>Still running</strong>This is taking longer than expected — see the Jobs screen.', 'warn');
}

/**
 * Report an outcome without ever calling an unconfirmed job printed.
 *
 * The three states are genuinely different and the middle one is the dangerous one: `done` means
 * the driver finished, `failed` means it refused, and anything else means the job is still in
 * flight and nobody yet knows. Collapsing the third into the first is the bug that marks orders
 * packed with an empty bag.
 */
function showJobOutcome(box, jobs) {
  const failed = jobs.filter((j) => j.state === 'failed');
  const open = jobs.filter((j) => !['done', 'failed', 'cancelled'].includes(j.state));
  if (failed.length) {
    show(box, `<strong>The printer refused it</strong>`
      + `<ul>${failed.map((j) => `<li>${esc(j.printer)}: ${esc(j.error || 'no reason given')}</li>`).join('')}</ul>`, 'bad');
  } else if (open.length) {
    show(box, '<strong>Sent, not yet confirmed</strong>'
      + 'The job is still in flight, so nothing here can say the paper came out. '
      + 'The Jobs screen will show how it ends.', 'warn');
  } else {
    const cancelled = jobs.filter((j) => j.state === 'cancelled').length;
    show(box, cancelled ? '<strong>Cancelled</strong>Pulled out of the queue before it printed.'
      : `<strong>Printed</strong>Confirmed by the printer in ${fmt.ms(jobs[0].durationMs)}.`,
      cancelled ? 'warn' : 'ok');
  }
}

/* ---------------------------------------------------------- the sheet view */

/** The body every diagnostic call shares, so the preview cannot be of different settings. */
function previewBody(extra = {}) {
  const options = effectiveOptions();
  return {
    printer: $('#print-printer').value,
    fileId: S.print.file?.fileId,
    presetId: $('#print-preset').value,
    ...(options ? { options } : {}),
    pages: $('#print-strategy').value ? '' : $('#print-pages').value.trim(),
    pageOrder: $('#print-order').value,
    ...extra,
  };
}

function resetSheet(message) {
  $('#sheet-paper').hidden = true;
  $('#sheet-empty').hidden = false;
  $('#sheet-empty').textContent = message;
  $('#sheet-verdict').hidden = true;
  $('#sheet-tech').hidden = true;
  $('#sheet-pager').hidden = true;
  // The busy chip has to go with it. Leaving it behind on an early return is what left the screen
  // saying "drawing..." over a finished preview with nothing on its way.
  $('#sheet-busy').hidden = true;
  $('#sheet-caption').textContent = message;
  S.print.drawn = null;
}

/**
 * Redraw the preview, coalescing bursts of changes.
 *
 * Debounced because a dragged margin fires an event per keystroke and each render is a real PDFBox
 * rasterisation on the printer's own lane thread, which queues behind any print in flight.
 */
const schedulePreview = debounce(() => doPreview(), 260);

/**
 * How long to wait for a render before giving up on it.
 *
 * The service gives a diagnostic 20s on the lane before answering 503, so this is a little longer:
 * the aim is to surface the service's own explanation when it has one, and only to invent a message
 * when nothing came back at all.
 */
const PREVIEW_TIMEOUT_MS = 24000;

/**
 * Draw the preview, one render at a time.
 *
 * <b>Strictly single-flight, and that is not an optimisation.</b> The first version fired a request
 * per change and let them overlap, guarding only the display with a sequence number. Two things
 * then go wrong at once, and together they look exactly like a hang:
 *
 *   - A browser allows about six concurrent connections per origin. Six slow renders in flight and
 *     everything after them waits in the browser, including the status poll and the render the
 *     operator is actually waiting for — which is why the overlay could sit on "drawing..." while
 *     nothing was on the wire at all.
 *   - Each render occupies one of the service's 24 HTTP threads for as long as the printer's lane
 *     takes to answer, so a driver that has gone slow turns a burst of typing into an exhausted
 *     pool.
 *
 * So a new render aborts the one before it. The abort frees the browser connection immediately, and
 * the request that mattered — the last one — is the one that runs.
 */
async function doPreview({ force = false } = {}) {
  const printer = $('#print-printer').value;
  const file = S.print.file;

  if (rawMode()) {
    abortPreview();
    resetSheet('Raw commands go straight to the printer, so there is nothing to render — '
      + 'the printer itself decides what they draw.');
    return;
  }
  if (!file || !printer) {
    abortPreview();
    resetSheet(!file ? 'Choose a PDF to see what will come out.' : 'Choose a printer to see what will come out.');
    return;
  }
  if (!force && !autoPreview()) {
    abortPreview();
    resetSheet('Automatic preview is off for this station — press “Refresh preview” to draw it.');
    return;
  }

  const body = previewBody({ page: S.print.page, overlay: $('#sheet-guides').checked, dpi: 150 });
  const signature = JSON.stringify(body);
  // Nothing that affects the picture has changed, so the picture on screen is already the answer.
  // Worth checking because several controls fire on both `input` and `change`.
  if (!force && signature === S.print.drawn) {
    return;
  }

  abortPreview();
  const controller = new AbortController();
  S.print.inFlight = controller;
  const seq = ++S.print.seq;
  const timer = setTimeout(() => controller.abort(), PREVIEW_TIMEOUT_MS);
  // Shown only if the render is slow enough to be worth mentioning; a fast one would otherwise
  // flash the chip on and off on every keystroke.
  const chip = setTimeout(() => { if (seq === S.print.seq) $('#sheet-busy').hidden = false; }, 180);

  try {
    const result = await api('/api/preview', { body, signal: controller.signal });
    if (seq !== S.print.seq) return;
    S.print.drawn = signature;
    paintSheet(result);
  } catch (e) {
    if (e.aborted) {
      // Superseded by a newer render, or timed out. A timeout is the only one worth reporting, and
      // it is told apart by whether this is still the current render.
      if (seq === S.print.seq) {
        sheetError('The printer did not answer in time',
          'Its driver may be busy with a print, or held by another program — QZ Tray holds a '
          + 'printer\u2019s connection while it runs. The last preview is still shown above.');
      }
      return;
    }
    if (seq !== S.print.seq) return;
    S.print.drawn = null;
    sheetError('Could not draw the preview', e.message);
  } finally {
    clearTimeout(timer);
    clearTimeout(chip);
    if (seq === S.print.seq) {
      $('#sheet-busy').hidden = true;
      S.print.inFlight = null;
    }
  }
}

/** Drop any render still in flight, so it stops holding a connection and a service thread. */
function abortPreview() {
  if (S.print.inFlight) {
    S.print.inFlight.abort();
    S.print.inFlight = null;
  }
  $('#sheet-busy').hidden = true;
}

/**
 * Report a failed render without throwing away the last good picture.
 *
 * Deliberately not resetSheet: the previous preview is still the best information on the screen,
 * and blanking it to show an error leaves the operator with less than they had.
 */
function sheetError(headline, detail) {
  const box = $('#sheet-verdict');
  box.hidden = false;
  box.className = 'verdict bad';
  box.innerHTML = `<p class="v-head">${esc(headline)}</p><ul><li>${esc(detail)}</li></ul>`;
}

/**
 * Put a rendered sheet on screen.
 *
 * The image is already the paper at its true proportions — PagePreview rasterises the whole sheet,
 * not the page — so the only sizing needed here is a cap on how much of the screen it may take.
 * Nothing is stretched: an aspect ratio that disagreed with the label would undo the entire point.
 */
function paintSheet(result) {
  const page = result.page || {};
  const paper = page.paper || {};
  const preview = result.preview || {};

  S.print.pageCount = preview.pageCount || 1;
  S.print.page = (preview.pageIndex ?? 0) + 1;

  $('#sheet-empty').hidden = true;
  const figure = $('#sheet-paper');
  figure.hidden = false;
  const img = $('#preview-img');
  img.src = `data:${preview.mime};base64,${preview.data}`;
  // The cap is on the long edge, so a 4 × 6 label and an A4 page both land at a sensible size and
  // a 4 × 10 strip does not run off the card.
  const tall = (paper.heightIn || 1) >= (paper.widthIn || 1);
  img.style.maxHeight = tall ? '58vh' : 'none';
  img.style.maxWidth = tall ? 'none' : '100%';

  $('#paper-dims').textContent = paperLabel(paper.widthIn, paper.heightIn);
  $('#sheet-caption').innerHTML = [
    `Printer <b>${esc(result.printer)}</b>`,
    `paper <b>${esc(paperLabel(paper.widthIn, paper.heightIn))}</b>`,
    result.pagesNote ? `pages ${esc(result.pagesNote)}` : null,
  ].filter(Boolean).join(' · ');

  const pager = $('#sheet-pager');
  pager.hidden = S.print.pageCount <= 1;
  $('#page-label').textContent = `${S.print.page} / ${S.print.pageCount}`;
  $('#page-prev').disabled = S.print.page <= 1;
  $('#page-next').disabled = S.print.page >= S.print.pageCount;

  const v = verdict(page);
  const box = $('#sheet-verdict');
  if (v) {
    box.hidden = false;
    box.className = `verdict ${v.level}`;
    box.innerHTML = `<p class="v-head">${esc(v.headline)}</p>`
      + (v.lines.length ? `<ul>${v.lines.map((l) => `<li>${l}</li>`).join('')}</ul>` : '');
  } else {
    box.hidden = true;
  }

  paintTech(result);
}

/** The numbers, kept and labelled, but behind a disclosure. */
function paintTech(result) {
  const page = result.page || {};
  const tech = $('#sheet-tech');
  tech.hidden = false;
  const rect = (r) => (r ? `${r.widthPt} × ${r.heightPt} pt  (${r.widthIn} × ${r.heightIn} in)` : '—');
  const rows = [
    ['Paper', rect(page.paper)],
    ['Printable area', page.imageable
      ? `${rect(page.imageable)} at x=${page.imageable.xPt} y=${page.imageable.yPt}` : '—'],
    ['Handed to PDFBox', rect(page.effective)],
    ['Head can reach', page.head ? rect(page.head) : 'not advertised by this driver'],
    ['Document page', rect(page.source)],
    ['Orientation', `${page.orientation?.value || '—'} (${page.orientation?.source || '—'})`],
    ['Scaling', `${page.scaling || '—'}${page.scaleFactor != null ? ` ×${page.scaleFactor}` : ''}`],
    ['Content clipped', page.contentClipped ? 'yes' : 'no'],
    ['Geometry as asked', page.fits ? 'yes' : 'no'],
    ['Rendered at', `${result.preview?.dpi} dpi, ${result.preview?.widthPx}×${result.preview?.heightPx} px`],
  ];
  $('#tech-facts').innerHTML = rows
    .map(([k, val]) => `<dt>${esc(k)}</dt><dd>${esc(val)}</dd>`).join('');
  $('#preview-summary').textContent = (result.summary || []).join('\n');
}

/**
 * Check fit without rendering.
 *
 * Kept, and still useful, for the two cases the picture cannot cover: no file staged, and a printer
 * mid-batch where a render would queue behind a real print. It writes into the same verdict block,
 * so there is one place on this screen that says whether things are all right.
 */
async function doPreflight() {
  const box = $('#sheet-verdict');
  // Shares the printer's lane with the render, so the render goes first — otherwise the two queue
  // behind each other and the slower one decides how long the screen waits.
  abortPreview();
  try {
    const result = await api('/api/preflight', { body: previewBody() });
    const v = verdict(result.page);
    box.hidden = false;
    box.className = `verdict ${v ? v.level : 'ok'}`;
    box.innerHTML = `<p class="v-head">${esc(v ? v.headline : 'Checked')}</p>`
      + (v && v.lines.length ? `<ul>${v.lines.map((l) => `<li>${l}</li>`).join('')}</ul>` : '')
      + (result.documentSupplied === false
        ? '<p class="v-foot">No document was staged, so orientation was resolved against a '
          + 'placeholder page. Drop the real file for an exact answer.</p>' : '');
    paintTech(result);
  } catch (e) {
    box.hidden = false;
    box.className = 'verdict bad';
    box.innerHTML = `<p class="v-head">Could not check</p><ul><li>${esc(e.message)}</li></ul>`;
  }
}

/* ================================================================== BULK */

function wireBulk() {
  $('#bulk-browse').addEventListener('click', () => $('#bulk-files').click());
  $('#bulk-files').addEventListener('change', (e) => stageMany(Array.from(e.target.files || [])));
  wireDropZone($('#bulk-drop'), stageMany);

  $('#bulk-clear').addEventListener('click', async () => {
    S.bulk.plans = {};
    await api('/api/files/delete', { body: { all: true } });
    S.print.file = null;
    renderPrintFile();
    await refresh();
  });
  $('#bulk-plan').addEventListener('click', bulkPlan);
  $('#bulk-start').addEventListener('click', bulkStart);
  $('#bulk-cancel').addEventListener('click', bulkCancel);
  $('#bulk-retry').addEventListener('click', bulkRetry);

  ['#bulk-strategy', '#bulk-printer', '#bulk-preset'].forEach((sel) => {
    $(sel).addEventListener('change', () => {
      // The plans on screen were resolved against the old choice, so they are now answers to a
      // question nobody asked. Dropping them beats showing a stale one next to a Start button.
      S.bulk.plans = {};
      $('#bulk-plan-result').hidden = true;
      renderBulk();
    });
  });

  $('#bulk-table').addEventListener('click', async (e) => {
    const button = e.target.closest('button[data-remove]');
    if (!button) return;
    delete S.bulk.plans[button.dataset.remove];
    await api('/api/files/delete', { body: { fileId: button.dataset.remove } });
    await refresh();
  });
}

/**
 * Upload files one at a time, reporting progress.
 *
 * Sequential on purpose. Two hundred parallel uploads to a loopback server is two hundred threads
 * contending for a pool the print endpoints share, and the bottleneck is never the transfer — it is
 * reading each PDF to count its pages. One at a time also means a bad file is named as it is
 * reached, rather than as one of a handful of simultaneous failures.
 */
async function stageMany(files) {
  if (!files.length) return;
  const progress = $('#bulk-upload-progress');
  progress.hidden = false;
  S.bulk.uploading = true;
  const failures = [];
  for (let i = 0; i < files.length; i += 1) {
    const file = files[i];
    progress.textContent = `Reading ${i + 1} of ${files.length} — ${file.name}`;
    try {
      await api(`/api/files?name=${encodeURIComponent(file.name)}`, { raw: file });
    } catch (e) {
      failures.push(`${file.name}: ${e.message}`);
    }
  }
  S.bulk.uploading = false;
  progress.hidden = true;
  $('#bulk-files').value = '';
  await refresh();
  if (failures.length) {
    toast(`${failures.length} of ${files.length} could not be read. First: ${failures[0]}`,
      'bad', 'Some files were not added');
  } else {
    toast(`${files.length} file${files.length === 1 ? '' : 's'} ready.`, 'ok', 'Added');
  }
}

function renderBulk() {
  fillPrinters($('#bulk-printer'), 'document');
  fillPresets($('#bulk-preset'));
  fillStrategies($('#bulk-strategy'));

  const files = S.data?.files || [];
  $('#bulk-count').textContent = files.length;
  $('#bulk-staged-note').textContent = files.length
    ? `${files.length} file${files.length === 1 ? '' : 's'} staged · ${fmt.bytes(S.data.stagedBytes)} `
      + `of ${fmt.bytes(S.data.stagedLimitBytes)} · held on disk, not in memory`
    : 'Files wait here on disk, not in memory.';

  const body = $('#bulk-table tbody');
  if (!files.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">Nothing staged yet.</td></tr>';
  } else {
    body.innerHTML = files.map((f) => `
      <tr>
        <td class="primaryCell">${esc(f.name)}</td>
        <td class="num">${f.pages}</td>
        <td class="num">${fmt.bytes(f.bytes)}</td>
        <td>${f.landscape ? 'landscape' : 'portrait'}<span class="sub">${fmt.inches(f.widthPt)} × ${fmt.inches(f.heightPt)}</span></td>
        <td data-plan="${esc(f.fileId)}" class="${S.bulk.plans[f.fileId] ? '' : 'muted'}">${
          S.bulk.plans[f.fileId] || 'press “Check the plan”'}</td>
        <td><div class="rowactions"><button class="ghost" data-remove="${esc(f.fileId)}">Remove</button></div></td>
      </tr>`).join('');
  }
  $('#bulk-start').disabled = !files.length;
  renderBatchProgress();
}

function bulkRequestBody() {
  const strategyId = $('#bulk-strategy').value;
  return {
    ...(strategyId ? { strategyId } : {}),
    printer: $('#bulk-printer').value,
    presetId: $('#bulk-preset').value,
    concurrency: Number($('#bulk-concurrency').value) || 2,
    copies: Number($('#bulk-copies').value) || 1,
  };
}

async function bulkPlan() {
  const box = $('#bulk-plan-result');
  const files = S.data?.files || [];
  if (!files.length) {
    show(box, '<strong>Nothing staged</strong>Add some files first.', 'warn');
    return;
  }
  try {
    const result = await api('/api/plan', {
      body: { ...bulkRequestBody(), fileIds: files.map((f) => f.fileId) },
    });
    // Written back into the table row by row, because "what will print" belongs beside the file
    // it is about rather than in a wall of text underneath.
    result.plans.forEach((plan, i) => {
      const html = plan.ok
        ? plan.steps.map((step) => esc(step.summary)).join('<span class="sub"></span>')
        : `<span class="pill bad">${esc(plan.error)}</span>`;
      // Cached as well as written, so the next poll's redraw does not throw it away — the whole
      // point of the column is that it stays there while the operator reads down the list.
      S.bulk.plans[files[i].fileId] = html;
      const cell = $(`[data-plan="${CSS.escape(files[i].fileId)}"]`);
      if (!cell) return;
      cell.classList.remove('muted');
      cell.innerHTML = html;
    });
    const bad = result.plans.filter((p) => !p.ok);
    if (bad.length) {
      show(box, `<strong>${bad.length} of ${result.plans.length} file(s) cannot be printed as set up</strong>`
        + 'Fix the rule or remove those files. A run does not start unless every file can be carried out.', 'bad');
    } else {
      const jobs = result.plans.reduce((n, p) => n + p.steps.length, 0);
      show(box, `<strong>Ready</strong>${result.plans.length} file(s) → ${jobs} job(s).`, 'ok');
    }
  } catch (e) {
    show(box, `<strong>Could not plan</strong>${esc(e.message)}`, 'bad');
  }
}

async function bulkStart() {
  const box = $('#bulk-plan-result');
  const files = S.data?.files || [];
  $('#bulk-start').disabled = true;
  try {
    const result = await api('/api/batches', {
      body: { ...bulkRequestBody(), fileIds: files.map((f) => f.fileId) },
    });
    S.bulk.batchId = result.batchId;
    S.bulk.batch = result.batch;
    box.hidden = true;
    renderBatchProgress();
    schedulePoll();
  } catch (e) {
    // Refused whole. Worth saying so plainly: the alternative design prints half a batch and
    // leaves the operator to work out which half.
    show(box, `<strong>The run did not start — nothing has printed</strong>${esc(e.message)}`, 'bad');
  } finally {
    $('#bulk-start').disabled = false;
  }
}

async function refreshBatch() {
  if (!S.bulk.batchId) return;
  try {
    S.bulk.batch = await api(`/api/batches/${encodeURIComponent(S.bulk.batchId)}`);
    renderBatchProgress();
  } catch {
    S.bulk.batchId = null;
  }
}

function renderBatchProgress() {
  const batch = S.bulk.batch;
  const card = $('#bulk-progress-card');
  if (!batch) {
    card.hidden = true;
    return;
  }
  card.hidden = false;

  const settled = batch.done + batch.failed + batch.cancelled;
  const pct = batch.total ? Math.round((settled / batch.total) * 100) : 0;
  const fill = $('#bulk-bar');
  fill.style.width = `${pct}%`;
  fill.className = `bar-fill${batch.failed ? ' bad' : batch.state === 'done' ? ' done' : ''}`;

  const pill = $('#bulk-state');
  const running = !['done', 'failed', 'cancelled'].includes(batch.state);
  pill.className = `pill ${running ? 'busy' : batch.failed ? 'bad' : batch.state === 'cancelled' ? 'warn' : 'ok'}`;
  pill.textContent = running ? 'printing' : batch.state;

  $('#bulk-counts').textContent = [
    `${settled} of ${batch.total} finished`,
    batch.done ? `${batch.done} printed` : null,
    batch.failed ? `${batch.failed} failed` : null,
    batch.cancelled ? `${batch.cancelled} cancelled` : null,
    batch.printing ? `${batch.printing} in flight` : null,
    batch.durationMs ? `in ${fmt.ms(batch.durationMs)}` : null,
  ].filter(Boolean).join(' · ');

  $('#bulk-cancel').hidden = !running;
  $('#bulk-retry').hidden = running || !batch.failed;

  const body = $('#bulk-progress-table tbody');
  body.innerHTML = (batch.items || []).map((item) => {
    const kind = { done: 'ok', failed: 'bad', cancelled: 'warn', printing: 'busy' }[item.state] || '';
    const jobs = (item.jobs || []).map((j) => `${esc(j.printer)} <span class="muted">${esc(j.state)}</span>`)
      .join('<span class="sub"></span>');
    return `<tr>
      <td class="primaryCell">${esc(item.name)}<span class="sub">${item.pages} page${item.pages === 1 ? '' : 's'}</span></td>
      <td><span class="pill ${kind}">${esc(item.state)}</span></td>
      <td>${jobs || '<span class="muted">—</span>'}</td>
      <td>${item.error ? `<span style="color:var(--bad)">${esc(item.error)}</span>`
        : item.durationMs ? `<span class="muted">${fmt.ms(item.durationMs)}</span>` : ''}</td>
    </tr>`;
  }).join('');
}

async function bulkCancel() {
  try {
    const result = await api(`/api/batches/${encodeURIComponent(S.bulk.batchId)}/cancel`, { body: {} });
    S.bulk.batch = result.batch;
    renderBatchProgress();
    toast(result.note, 'warn', 'Cancelling');
  } catch (e) {
    toast(e.message, 'bad', 'Could not cancel');
  }
}

async function bulkRetry() {
  const failed = (S.bulk.batch?.items || []).filter((i) => i.state === 'failed');
  if (!failed.length) return;
  try {
    const result = await api('/api/batches', {
      body: { ...bulkRequestBody(), fileIds: failed.map((i) => i.fileId) },
    });
    S.bulk.batchId = result.batchId;
    S.bulk.batch = result.batch;
    renderBatchProgress();
    schedulePoll();
    toast(`Retrying ${failed.length} file(s).`, 'ok', 'Started');
  } catch (e) {
    toast(e.message, 'bad', 'Could not retry');
  }
}

/* ================================================================== PRINTERS */

function wirePrinters() {
  $('#printers-refresh').addEventListener('click', refresh);
  $('#pf-probe').addEventListener('click', probePrinter);
  $('#pf-save').addEventListener('click', savePrinter);
  $('#pf-reset').addEventListener('click', () => resetPrinterForm());

  $('#printers-table').addEventListener('click', async (e) => {
    const edit = e.target.closest('button[data-edit]');
    const remove = e.target.closest('button[data-delete]');
    const test = e.target.closest('button[data-test]');
    if (edit) editPrinter(edit.dataset.edit);
    if (test) testLabel(test.dataset.test);
    if (remove) {
      if (!confirm(`Remove “${remove.dataset.delete}”? Anything queued for it will fail.`)) return;
      try {
        await api('/api/printers/delete', { body: { name: remove.dataset.delete } });
        toast(`${remove.dataset.delete} removed.`, 'ok');
        await refresh();
      } catch (err) {
        toast(err.message, 'bad', 'Could not remove it');
      }
    }
  });
}

function renderPrinters() {
  const body = $('#printers-table tbody');
  const list = S.data?.printers || [];
  if (!list.length) {
    body.innerHTML = '<tr><td colspan="9" class="empty">No printers found. '
      + 'Add a label printer below, or install a printer driver for the document lane.</td></tr>';
    return;
  }
  body.innerHTML = list.map((p) => {
    const online = p.online !== false;
    const advisory = p.lane === 'document' && p.acceptingJobs === false;
    return `<tr>
      <td class="primaryCell">${esc(p.name)}${p.default ? ' <span class="pill plain">system default</span>' : ''}
        ${p.note ? `<span class="sub">${esc(p.note)}</span>` : ''}</td>
      <td>${p.lane === 'label' ? 'Label' : 'Document'}</td>
      <td><span class="pill ${online ? 'ok' : 'bad'}">${online ? 'online' : 'offline'}</span>
        ${advisory ? '<span class="sub">driver says not accepting — advisory only</span>' : ''}
        ${p.lastError ? `<span class="sub">${esc(p.lastError)}</span>` : ''}</td>
      <td>${p.host ? `<code>${esc(p.host)}:${p.port}</code>` : '<span class="muted">via the driver</span>'}</td>
      <td class="num">${p.queued ?? '—'}</td>
      <td class="num">${p.printed ?? '—'}</td>
      <td class="num">${p.failed ?? '—'}</td>
      <td class="muted">${esc(p.source || '')}</td>
      <td><div class="rowactions">
        ${p.lane === 'label' ? `<button class="ghost" data-test="${esc(p.name)}">Test label</button>` : ''}
        ${p.editable ? `<button class="ghost" data-edit="${esc(p.name)}">Edit</button>
                        <button class="ghost danger" data-delete="${esc(p.name)}">Remove</button>` : ''}
      </div></td>
    </tr>`;
  }).join('');
}

function editPrinter(name) {
  const p = (S.data?.printers || []).find((x) => x.name === name);
  if (!p) return;
  S.printerForm.editing = name;
  $('#printer-form-title').textContent = `Edit “${name}”`;
  $('#pf-name').value = p.name;
  $('#pf-host').value = p.host || '';
  $('#pf-port').value = p.port || 9100;
  $('#pf-note').value = p.note || '';
  $('#pf-reset').hidden = false;
  $('#pf-result').hidden = true;
  $('#pf-name').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function resetPrinterForm() {
  S.printerForm.editing = null;
  $('#printer-form-title').textContent = 'Add a label printer';
  ['#pf-name', '#pf-host', '#pf-note'].forEach((sel) => { $(sel).value = ''; });
  $('#pf-port').value = 9100;
  $('#pf-charset').value = 'UTF-8';
  $('#pf-lineending').value = 'as-is';
  $('#pf-reset').hidden = true;
  $('#pf-result').hidden = true;
}

function printerFormBody() {
  return {
    name: $('#pf-name').value.trim(),
    host: $('#pf-host').value.trim(),
    port: Number($('#pf-port').value) || 9100,
    charset: $('#pf-charset').value,
    lineEnding: $('#pf-lineending').value,
    note: $('#pf-note').value.trim(),
  };
}

async function probePrinter() {
  const box = $('#pf-result');
  const form = printerFormBody();
  if (!form.host) {
    show(box, '<strong>No address</strong>Type the printer\'s IP or hostname first.', 'warn');
    return;
  }
  show(box, 'Opening a socket…');
  try {
    const result = await api('/api/printers/probe', { body: form });
    if (result.reachable) {
      show(box, `<strong>Reachable</strong>${result.note
        || `Answered in ${result.ms} ms. That proves the address, not the label stock — print a test label next.`}`, 'ok');
    } else {
      show(box, `<strong>Nothing answered on ${esc(form.host)}:${form.port}</strong>`
        + `${esc(result.error || '')}<ul>`
        + '<li>Check the IP and that the printer is on the same network.</li>'
        + '<li>Most thermal printers accept one connection at a time — a still-running QZ Tray or a '
        + 'second copy of Printly holds it.</li></ul>', 'bad');
    }
  } catch (e) {
    show(box, `<strong>Could not probe</strong>${esc(e.message)}`, 'bad');
  }
}

async function savePrinter() {
  const box = $('#pf-result');
  try {
    const result = await api('/api/printers', { body: printerFormBody() });
    toast(`${result.printer.name} saved and connected.`, 'ok', 'Printer ready');
    resetPrinterForm();
    await refresh();
  } catch (e) {
    show(box, `<strong>Not saved</strong>${esc(e.message)}`, 'bad');
  }
}

async function testLabel(name) {
  try {
    const job = await api('/api/printers/test-label', { body: { name } });
    if (job.state === 'done') toast(`A test label was printed on ${name}.`, 'ok', 'It works');
    else toast(job.error || `The job ended as ${job.state}.`, 'bad', `${name} did not print`);
    await refresh();
  } catch (e) {
    toast(e.message, 'bad', `${name} did not print`);
  }
}

/* ================================================================== PRESETS */

function wirePresets() {
  $('#preset-new').addEventListener('click', () => loadPreset(null));
  $('#ps-save').addEventListener('click', savePreset);
  $('#ps-delete').addEventListener('click', deletePreset);
  $('#ps-preflight').addEventListener('click', presetPreflight);
  $('#preset-list').addEventListener('click', (e) => {
    const button = e.target.closest('button[data-preset]');
    if (button) loadPreset(button.dataset.preset);
  });
}

function renderPresets() {
  const list = $('#preset-list');
  list.innerHTML = presets().map((p) => `
    <li><button data-preset="${esc(p.id)}" class="${p.id === S.editingPreset ? 'active' : ''}">
      <span class="n">${esc(p.name)}</span>
      <span class="d">${esc(describePreset(p))}</span>
      ${p.seeded ? '<span class="tag">shipped with Printly</span>' : ''}
    </button></li>`).join('') || '<li class="muted">No presets yet.</li>';
  fillPrinters($('#ps-preflight-printer'), 'document');
}

function describePreset(preset) {
  const o = preset.options || {};
  const bits = [];
  if (o.size) bits.push(`${o.size.width}×${o.size.height}${o.size.units || 'in'}`);
  if (o.orientation) bits.push(o.orientation);
  else bits.push('auto orientation');
  if (o.density) bits.push(`${o.density}dpi`);
  if (o.scale) bits.push(o.scale);
  if (o.pageRange) bits.push(`pages ${o.pageRange}`);
  if (o.sizeMeans === 'sheet') bits.push('size = the paper');
  return bits.join(' · ');
}

function loadPreset(id) {
  const preset = id ? presets().find((p) => p.id === id) : null;
  S.editingPreset = id;
  const o = preset?.options || {};
  const m = o.margins || {};
  $('#preset-form-title').textContent = preset ? `Edit “${preset.name}”` : 'New preset';
  $('#ps-name').value = preset?.name || '';
  $('#ps-copies').value = preset?.copies || 1;
  $('#ps-note').value = preset?.note || '';
  $('#ps-width').value = o.size?.width ?? '';
  $('#ps-height').value = o.size?.height ?? '';
  $('#ps-units').value = o.size?.units || 'in';
  $('#ps-sizemeans').value = o.sizeMeans || '';
  $('#ps-mtop').value = m.top ?? 0;
  $('#ps-mright').value = m.right ?? 0;
  $('#ps-mbottom').value = m.bottom ?? 0;
  $('#ps-mleft').value = m.left ?? 0;
  $('#ps-orientation').value = o.orientation || '';
  $('#ps-color').value = o.colorType || '';
  $('#ps-scale').value = o.scale || '';
  $('#ps-density').value = o.density ?? '';
  $('#ps-pagerange').value = o.pageRange || '';
  $('#ps-delete').hidden = !preset;
  $('#ps-result').hidden = true;
  renderPresets();
}

function presetFormBody() {
  const options = {};
  const width = Number($('#ps-width').value);
  const height = Number($('#ps-height').value);
  if (width > 0 && height > 0) {
    options.size = { width, height, units: $('#ps-units').value };
  }
  if ($('#ps-sizemeans').value) options.sizeMeans = $('#ps-sizemeans').value;
  options.margins = {
    top: Number($('#ps-mtop').value) || 0,
    right: Number($('#ps-mright').value) || 0,
    bottom: Number($('#ps-mbottom').value) || 0,
    left: Number($('#ps-mleft').value) || 0,
  };
  // Only written when set. Absent is meaningful and is not the same as a default: the invoice
  // profiles depend on orientation staying absent so the service reads it off the PDF instead.
  if ($('#ps-orientation').value) options.orientation = $('#ps-orientation').value;
  if ($('#ps-color').value) options.colorType = $('#ps-color').value;
  if ($('#ps-scale').value) options.scale = $('#ps-scale').value;
  const density = Number($('#ps-density').value);
  if (density > 0) options.density = density;
  if ($('#ps-pagerange').value.trim()) options.pageRange = $('#ps-pagerange').value.trim();

  return {
    ...(S.editingPreset ? { id: S.editingPreset } : {}),
    name: $('#ps-name').value.trim(),
    note: $('#ps-note').value.trim(),
    mode: 'document',
    copies: Number($('#ps-copies').value) || 1,
    options,
  };
}

async function savePreset() {
  const box = $('#ps-result');
  try {
    const result = await api('/api/presets', { body: presetFormBody() });
    S.editingPreset = result.item.id;
    await refresh();
    show(box, `<strong>Saved</strong>“${esc(result.item.name)}” is ready to use.`, 'ok');
  } catch (e) {
    show(box, `<strong>Not saved</strong>${esc(e.message)}`, 'bad');
  }
}

async function deletePreset() {
  if (!S.editingPreset) return;
  if (!confirm('Delete this preset? Strategy rules pointing at it will stop working.')) return;
  try {
    await api('/api/presets/delete', { body: { id: S.editingPreset } });
    loadPreset(null);
    await refresh();
    toast('Preset deleted.', 'ok');
  } catch (e) {
    toast(e.message, 'bad', 'Could not delete it');
  }
}

/**
 * Resolve this geometry against a real printer's real media, before it is trusted.
 *
 * The reason the margins in this form are safe to expose at all. These are numbers where a wrong
 * value prints a barcode a courier's scanner rejects, so an operator nudging them needs something
 * that can say "4×10in will be clamped to 4×6 on this printer" without burning a label to find out.
 */
async function presetPreflight() {
  const box = $('#ps-result');
  const printer = $('#ps-preflight-printer').value;
  if (!printer) {
    show(box, '<strong>Choose a printer</strong>The answer depends on the media it has loaded.', 'warn');
    return;
  }
  const staged = (S.data?.files || [])[0];
  try {
    const result = await api('/api/preflight', {
      body: {
        printer,
        options: presetFormBody().options,
        ...(staged ? { fileId: staged.fileId } : {}),
      },
    });
    const fits = result.page?.fits !== false;
    const caveat = result.documentSupplied === false
      ? '<p class="muted">No document was supplied, so orientation was resolved against a '
        + 'placeholder page. Stage a real file on the Bulk screen for an exact answer.</p>'
      : '';
    show(box, `<strong>${fits ? `It fits on ${esc(printer)}` : 'It does not fit — this would print something else'}</strong>`
      + caveat + `<pre class="summary">${esc((result.summary || []).join('\n'))}</pre>`,
      fits ? 'ok' : 'warn');
  } catch (e) {
    show(box, `<strong>Could not check</strong>${esc(e.message)}`, 'bad');
  }
}

/* ================================================================== STRATEGIES */

function wireStrategies() {
  $('#strategy-new').addEventListener('click', () => loadStrategy(null));
  $('#st-add-rule').addEventListener('click', () => {
    S.rules.push({ label: `Rule ${S.rules.length + 1}`, pages: 'all', pageOrder: 'normal', printer: '', presetId: '', copies: 1, enabled: true });
    renderRules();
  });
  $('#st-save').addEventListener('click', saveStrategy);
  $('#st-delete').addEventListener('click', deleteStrategy);
  $('#st-try').addEventListener('click', tryStrategy);
  $('#strategy-list').addEventListener('click', (e) => {
    const button = e.target.closest('button[data-strategy]');
    if (button) loadStrategy(button.dataset.strategy);
  });

  const table = $('#st-rules');
  table.addEventListener('input', (e) => {
    const field = e.target.dataset.field;
    const row = Number(e.target.dataset.row);
    if (!field || Number.isNaN(row)) return;
    S.rules[row][field] = e.target.type === 'checkbox' ? e.target.checked
      : e.target.type === 'number' ? Number(e.target.value) : e.target.value;
  });
  table.addEventListener('change', (e) => {
    if (e.target.dataset.field) $('#st-try-result').innerHTML = '';
  });
  table.addEventListener('click', (e) => {
    const remove = e.target.closest('button[data-removerule]');
    const up = e.target.closest('button[data-up]');
    if (remove) {
      S.rules.splice(Number(remove.dataset.removerule), 1);
      renderRules();
    } else if (up) {
      const i = Number(up.dataset.up);
      if (i > 0) {
        [S.rules[i - 1], S.rules[i]] = [S.rules[i], S.rules[i - 1]];
        renderRules();
      }
    }
  });
}

function renderStrategies(poll = false) {
  $('#strategy-list').innerHTML = strategies().map((s) => `
    <li><button data-strategy="${esc(s.id)}" class="${s.id === S.editingStrategy ? 'active' : ''}">
      <span class="n">${esc(s.name)}</span>
      <span class="d">${(s.rules || []).length} rule${(s.rules || []).length === 1 ? '' : 's'} — ${esc(
        (s.rules || []).map((r) => r.pages || 'all').join(', '))}</span>
      ${s.seeded ? '<span class="tag">shipped with Printly</span>' : ''}
    </button></li>`).join('') || '<li class="muted">No strategies yet.</li>';
  // Not on a poll: this table is made of inputs, and rebuilding it would take the caret out of
  // whichever one is being typed into.
  if (!poll) renderRules();
}

function loadStrategy(id) {
  const strategy = id ? strategies().find((s) => s.id === id) : null;
  S.editingStrategy = id;
  S.rules = strategy
    ? JSON.parse(JSON.stringify(strategy.rules || []))
    : [{ label: 'All pages', pages: 'all', pageOrder: 'normal', printer: '', presetId: '', copies: 1, enabled: true }];
  $('#strategy-form-title').textContent = strategy ? `Edit “${strategy.name}”` : 'New strategy';
  $('#st-name').value = strategy?.name || '';
  $('#st-description').value = strategy?.description || '';
  $('#st-delete').hidden = !strategy;
  $('#st-result').hidden = true;
  $('#st-try-result').innerHTML = '';
  renderStrategies();
}

function renderRules() {
  const body = $('#st-rules tbody');
  const printerOptions = (chosen) => ['<option value="">Whatever is chosen when it runs</option>']
    .concat(printers().map((p) => `<option value="${esc(p.name)}"${p.name === chosen ? ' selected' : ''}>`
      + `${esc(p.name)} (${p.lane})</option>`)).join('');
  const presetOptions = (chosen) => ['<option value="">Whatever is chosen when it runs</option>']
    .concat(presets().map((p) => `<option value="${esc(p.id)}"${p.id === chosen ? ' selected' : ''}>`
      + `${esc(p.name)}</option>`)).join('');

  body.innerHTML = S.rules.map((rule, i) => `
    <tr>
      <td><input type="checkbox" data-row="${i}" data-field="enabled" ${rule.enabled !== false ? 'checked' : ''}></td>
      <td><input type="text" data-row="${i}" data-field="label" value="${esc(rule.label || '')}" placeholder="Label"></td>
      <td><input type="text" data-row="${i}" data-field="pages" value="${esc(rule.pages || 'all')}" spellcheck="false" style="min-width:96px"></td>
      <td><select data-row="${i}" data-field="pageOrder">
        <option value="normal"${rule.pageOrder !== 'reverse' ? ' selected' : ''}>Normal</option>
        <option value="reverse"${rule.pageOrder === 'reverse' ? ' selected' : ''}>Reversed</option>
      </select></td>
      <td><select data-row="${i}" data-field="printer">${printerOptions(rule.printer)}</select></td>
      <td><select data-row="${i}" data-field="presetId">${presetOptions(rule.presetId)}</select></td>
      <td class="num"><input type="number" data-row="${i}" data-field="copies" min="1" max="1000"
        value="${rule.copies || 1}" style="min-width:66px"></td>
      <td><div class="rowactions">
        ${i > 0 ? `<button class="ghost" data-up="${i}" title="Move up">↑</button>` : ''}
        <button class="ghost danger" data-removerule="${i}" title="Remove">×</button>
      </div></td>
    </tr>`).join('') || '<tr><td colspan="8" class="empty">No rules — add one.</td></tr>';
}

function strategyFormBody() {
  return {
    ...(S.editingStrategy ? { id: S.editingStrategy } : {}),
    name: $('#st-name').value.trim(),
    description: $('#st-description').value.trim(),
    rules: S.rules,
  };
}

async function saveStrategy() {
  const box = $('#st-result');
  try {
    const result = await api('/api/strategies', { body: strategyFormBody() });
    S.editingStrategy = result.item.id;
    await refresh();
    show(box, `<strong>Saved</strong>“${esc(result.item.name)}” is available on the Print and Bulk screens.`, 'ok');
  } catch (e) {
    show(box, `<strong>Not saved</strong>${esc(e.message)}`, 'bad');
  }
}

async function deleteStrategy() {
  if (!S.editingStrategy) return;
  if (!confirm('Delete this strategy?')) return;
  try {
    await api('/api/strategies/delete', { body: { id: S.editingStrategy } });
    loadStrategy(null);
    await refresh();
    toast('Strategy deleted.', 'ok');
  } catch (e) {
    toast(e.message, 'bad', 'Could not delete it');
  }
}

/** Resolve the rules being edited against a stated page count, without saving or printing. */
async function tryStrategy() {
  const box = $('#st-try-result');
  const pages = Number($('#st-try-pages').value) || 1;
  try {
    const result = await api('/api/plan', {
      body: {
        strategy: strategyFormBody(),
        pages,
        printer: printers()[0]?.name || '',
        presetId: '',
      },
    });
    box.innerHTML = renderPlans(result.plans);
  } catch (e) {
    box.innerHTML = `<div class="planskip">${esc(e.message)}</div>`;
  }
}

/* ================================================================== JOBS */

function wireJobs() {
  $('#jobs-refresh').addEventListener('click', refresh);
  $('#jobs-filter').addEventListener('change', (e) => {
    S.jobsFilter = e.target.value;
    renderJobs();
  });
  $('#jobs-table').addEventListener('click', async (e) => {
    const reprint = e.target.closest('button[data-reprint]');
    const cancel = e.target.closest('button[data-canceljob]');
    if (reprint) {
      try {
        const job = await api(`/api/jobs/${encodeURIComponent(reprint.dataset.reprint)}/reprint`,
          { body: { wait: waitMs() } });
        if (job.state === 'done') toast(`Reprinted on ${job.printer}.`, 'ok', 'Printed');
        else if (job.state === 'failed') toast(job.error || 'The printer refused it.', 'bad', 'Not printed');
        else toast('Sent — still in flight, so not yet confirmed on paper.', 'warn', 'Sent');
        await refresh();
      } catch (err) {
        toast(err.message, 'bad', 'Could not reprint');
      }
    }
    if (cancel) {
      try {
        const result = await api(`/api/jobs/${encodeURIComponent(cancel.dataset.canceljob)}/cancel`, { body: {} });
        toast(result.cancelled ? 'Pulled out of the queue.' : result.note, result.cancelled ? 'ok' : 'warn');
        await refresh();
      } catch (err) {
        toast(err.message, 'bad', 'Could not cancel');
      }
    }
  });
}

function renderJobs() {
  const all = S.data?.jobs || [];
  const list = all.filter((j) => (
    S.jobsFilter === 'failed' ? j.state === 'failed'
      : S.jobsFilter === 'open' ? !['done', 'failed', 'cancelled'].includes(j.state)
        : true));
  const body = $('#jobs-table tbody');
  if (!list.length) {
    body.innerHTML = `<tr><td colspan="8" class="empty">${all.length
      ? 'Nothing matches that filter.' : 'Nothing has printed yet.'}</td></tr>`;
    return;
  }
  body.innerHTML = list.map((j) => {
    const open = !['done', 'failed', 'cancelled'].includes(j.state);
    const kind = { done: 'ok', failed: 'bad', cancelled: 'warn' }[j.state] || 'busy';
    const detail = j.error ? `<span style="color:var(--bad)">${esc(j.error)}</span>`
      : j.page?.fits === false ? '<span class="pill warn">geometry was cut down to fit</span>'
        : j.spoolMs != null ? `<span class="muted">render ${j.renderMs ?? 0}ms · spool ${j.spoolMs}ms</span>`
          : '';
    return `<tr>
      <td class="muted">${fmt.time(j.createdAt)}</td>
      <td class="primaryCell">${esc(j.title || (j.type === 'pdf' ? 'document' : `raw ${j.type}`))}
        ${j.strategy ? `<span class="sub">${esc(j.strategy)}</span>` : ''}</td>
      <td>${esc(j.printer)}</td>
      <td class="muted">${esc(j.pagesNote || '—')}</td>
      <td><span class="pill ${kind}">${esc(j.state)}</span></td>
      <td class="num">${j.durationMs != null ? fmt.ms(j.durationMs) : '—'}</td>
      <td>${detail}</td>
      <td><div class="rowactions">
        ${open ? `<button class="ghost" data-canceljob="${esc(j.jobId)}">Cancel</button>` : ''}
        ${!open && j.reprintable ? `<button class="ghost" data-reprint="${esc(j.jobId)}">Reprint</button>` : ''}
      </div></td>
    </tr>`;
  }).join('');
}

/* ================================================================== SETTINGS */

function wireSettings() {
  $('#set-save').addEventListener('click', saveSettings);
  $('#log-refresh').addEventListener('click', loadLog);
  $('#log-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText($('#log-body').textContent);
      toast('The log is on the clipboard.', 'ok');
    } catch {
      toast('The browser would not let the page copy. Select the text instead.', 'warn');
    }
  });
}

function renderSettings(poll = false) {
  const s = settings();
  // On a poll the saved value must not be pushed back into the dropdowns: someone half-way through
  // changing a default would watch it snap back every couple of seconds.
  if (poll) {
    fillPrinters($('#set-printer'));
    fillPresets($('#set-preset'), undefined, 'No preset');
    fillStrategies($('#set-strategy'), undefined, 'No strategy');
  } else {
    fillPrinters($('#set-printer'), null, s.defaultPrinter || '');
    fillPresets($('#set-preset'), s.defaultPresetId || '', 'No preset');
    fillStrategies($('#set-strategy'), s.defaultStrategyId || '', 'No strategy');
  }

  const d = S.data || {};
  $('#station-facts').innerHTML = `
    <dt>Version</dt><dd>${esc(d.version)}</dd>
    <dt>Listening on</dt><dd>http://127.0.0.1:${d.port}</dd>
    <dt>Up for</dt><dd>${fmt.ms(d.uptimeMs)}</dd>
    <dt>Document lane</dt><dd>${d.documentLane ? `on — ${d.documentPrinters} printer(s)` : 'off'}</dd>
    <dt>Allowed origins</dt><dd>${d.allowedOrigins?.length
      ? d.allowedOrigins.map(esc).join('<br>')
      : 'any origin — tighten this in config.json once your app URL is settled'}</dd>
    <dt>Config file</dt><dd>${esc(d.paths?.config)}</dd>
    <dt>Panel data</dt><dd>${esc(d.paths?.data)}</dd>
    <dt>Log file</dt><dd>${esc(d.paths?.log)}</dd>`;

  if ($('#log-body').textContent === '…') loadLog();
}

async function saveSettings() {
  const box = $('#set-result');
  try {
    await api('/api/settings', {
      body: {
        defaultPrinter: $('#set-printer').value,
        defaultPresetId: $('#set-preset').value,
        defaultStrategyId: $('#set-strategy').value,
        concurrency: Number($('#set-concurrency').value) || 2,
        waitMs: Number($('#set-wait').value) || 0,
        autoPreview: $('#set-autopreview').checked,
      },
    });
    await refresh();
    show(box, '<strong>Saved</strong>These are what the panel starts with from now on.', 'ok');
  } catch (e) {
    show(box, `<strong>Not saved</strong>${esc(e.message)}`, 'bad');
  }
}

async function loadLog() {
  try {
    const result = await api('/api/log', { query: { lines: 300 } });
    const body = $('#log-body');
    body.textContent = (result.lines || []).join('\n');
    body.scrollTop = body.scrollHeight;
  } catch (e) {
    $('#log-body').textContent = e.message;
  }
}

/* ------------------------------------------------------------------ go */

boot().catch((e) => {
  document.body.innerHTML = `<div style="padding:40px;font:15px system-ui;max-width:60ch">
    <h1 style="font-size:20px">The Control Panel could not start</h1>
    <p>${esc(e.message)}</p>
    <p>Check that the Printly service is running, then reload this page.</p></div>`;
});
