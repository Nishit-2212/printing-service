# Printly

A small always-running local service that accepts print jobs from your web app over
`http://127.0.0.1:9110` and writes them straight to a label printer's raw socket.

It replaces QZ Tray for the **label lane**. TSPL goes in, bytes go to the socket, nothing is
rendered — that is the entire speed story.

There are two ways in, and they are the same engine underneath:

- **From your web app**, over the HTTP API below. This is what the packing flow uses.
- **From the Control Panel**, a local web UI the service serves at `http://127.0.0.1:9110/`, for
  bulk printing and station setup without writing any code.

A job from either side is composed by the same code and comes out the same, so a preview in the
panel is a preview of what the web app will print.

Measured on the bundled mock printer: **200 labels submitted and acknowledged in 228 ms over a
single reused TCP connection**, 0 failures, 0 interleaving.

---

## Requirements

- **Build machine:** JDK 21+ (nothing else — no Maven, no Gradle, no downloads)
- **Pack PCs:** nothing, once you ship the `jpackage` installer with its bundled JRE

## Quick start

```bash
./build.sh                                   # -> build/dist/printly.jar
java -jar build/dist/printly.jar        # writes a starter config on first run
```

The first run tells you where the config went:

| OS | Config + log location |
|----|----------------------|
| Windows | `%APPDATA%\Printly\` |
| Linux | `~/.config/printly/` |
| macOS | `~/Library/Application Support/Printly/` |

Add your printers (see `config.example.json`), restart, and check `http://127.0.0.1:9110/health`.

### Try it without hardware

```bash
java tools/MockPrinter.java 9101                       # pretends to be a TSPL printer
java -jar build/dist/printly.jar --config my.json # point a printer at 127.0.0.1:9101
```

`MockPrinter` accepts one connection at a time, exactly like real TSPL hardware, and prints
whatever bytes arrive.

### Command line

```
--config, -c <path>   configuration file to use
--port,   -p <n>      override the listening port
--no-tray             run without a system tray icon
--no-panel            run without the Control Panel UI and its /api endpoints
--version, -v
--help,   -h
```

`--no-panel` is for a station whose only client is the web app. Nothing about `/health`,
`/printers`, `/print`, `/preview`, `/preflight` or `/jobs` changes either way — the panel adds
endpoints under `/api/` and never alters the ones above.

---

## HTTP API

### `GET /health`
```json
{ "ok": true, "service": "printly", "version": "1.0.0", "uptimeMs": 15661,
  "printers": { "pack-1": "online", "pack-2": "offline" },
  "documentLane": true, "documentPrinters": 3 }
```

`documentPrinters` is how a status badge tells "the bridge is down" apart from "the bridge is up
but sees no printers" — two very different things to put in front of an operator.

### `GET /printers` — optionally `?lane=label` or `?lane=document`
```json
[{ "name": "pack-1", "lane": "label", "host": "192.168.1.40", "port": 9100,
   "online": true, "queued": 0, "printed": 412, "failed": 0 },
 { "name": "ZDesigner GK420d", "lane": "document",
   "online": true, "default": false, "acceptsPdf": true, "acceptingJobs": true }]
```

### `POST /print`
```json
{ "printer": "pack-1", "type": "tspl", "data": "SIZE 50 mm,25 mm\nGAP 2 mm,0\n...", "copies": 1 }
```

| Field | Meaning |
|-------|---------|
| `printer` | name from `config.json`, or an OS printer name when `type` is `pdf` |
| `type` | `tspl` (default), `raw`, or `pdf` |
| `data` | the payload string |
| `encoding` | `utf8` (default for `tspl`) or `base64` (default for `raw`/`pdf`) |
| `copies` | 1–1000; the payload is repeated on the label lane |
| `options` | paper geometry, document lane only — see below |

#### `options` — paper geometry for `type: "pdf"`

Mirrors the object QZ Tray took via `qz.configs.create(...)`, so values calibrated against
physical output under QZ carry over unchanged. Every field is optional, and **absent is not the
same as a default** — omitting `orientation` leaves the driver's own default in force, which some
invoice profiles rely on.

```json
{
  "size":     { "width": 4, "height": 6, "units": "in" },
  "margins":  { "top": 0.1, "right": 0, "bottom": 0.1, "left": 0.3 },
  "orientation": "portrait",
  "colorType":   "grayscale",
  "density":     203,
  "scale":       "fit",
  "pageRange":   "1"
}
```

| Field | Accepted |
|-------|----------|
| `size` | the **printable rectangle**, not the sheet — see below. Positive `width`/`height`; `units` is `in` (default), `mm`, `cm` or `pt` |
| `margins` | `top`/`right`/`bottom`/`left`, same units as `size`, never negative |
| `orientation` | `portrait`, `landscape`, `reverse-landscape` |
| `colorType` | `color`, `grayscale`, `blackwhite` |
| `density` | rasterisation dpi, 25–2400 |
| `scale` | `actual`, `fit` (shrink only), `fit-to-page` (scale either way) |
| `pageRange` | one 1-based range: `1`, `2-4`, or `2-` for "page 2 to the end" |

`units` may also sit beside `size` rather than inside it, which is how the older label dialogs
write it. A value that cannot be made sense of is a **400**, not a silent fallback: these numbers
are hardware-calibrated, and quietly ignoring a typo prints a label the courier's scanner rejects.
Unrecognised keys (QZ-only ones like `scaleContent`) are ignored.

Only a single range is accepted for `pageRange`, on purpose: `PrinterJob` honours just the first
range it is given, so taking a list like `1,4-5` would quietly print page 1 alone.

##### `pages` — the capable page selector

`pageRange` above becomes a `PageRanges` attribute, which is why it is limited to one contiguous
range. `pages` is the other mechanism, and it can express what a routing strategy needs:

| Value | Selects |
|-------|---------|
| `all` (or absent) | every page, in order |
| `odd` / `even` | 1,3,5… / 2,4,6… — manual duplex on a single-sided printer |
| `first` / `last` | the first / the last page |
| `4` | one page |
| `2-5`, `3-`, `-3` | an inclusive range, open at either end |
| `every:3`, `every:3+2` | every third page, from the first / from the second |
| `1,4-6,last` | several terms, unioned |

Add `"pageOrder": "reverse"` to send the selected pages back to front, which is the order that makes
a face-up output tray come out collated on the second pass of a manual duplex.

The service carries this out by **rewriting the PDF down to the selected pages** before the job
reaches a lane, rather than by handing the driver an attribute. That is deliberate, and it is the
same decision the packing flow made in its own `pages.js`: a driver handed a `Printable` may or may
not honour the attributes alongside it, and finding out which costs a label and a courier bag.
Trimming the bytes cannot be ignored by anything downstream.

Consequences worth knowing:

- A selection that matches **no page** of the document is a **400**, not a whole-document print. The
  caller asked for something specific and it is not there.
- `all` in normal order is dropped rather than carried, so the common case never pays for a
  re-encode. Everything else costs one load-select-save — single-digit milliseconds on a warehouse
  PC, against seconds for the print head.
- The selection is **spent** when the job is created. `GET /jobs/{id}` echoes it as `pagesNote`
  ("odd (3 of 6 pages)"), and a reprint re-applies nothing — otherwise reprinting "odd pages" would
  print half of half.
- `pages` and `pageRange` can be used together: `pages` picks the pages, `pageRange` is still handed
  to the driver afterwards. Profiles already calibrated with `pageRange` are unaffected.

##### `size` sets the printable area, not the paper

The sheet is always the media the driver has loaded. `size` and `margins` together describe the
rectangle drawn **on** that sheet, and a rectangle bigger than the sheet is clamped to it.

This matters because the sheet is what decides how far a printer feeds. Reading `size` as the
paper instead — which an earlier build did — meant the 4x10in Flipkart invoice profile asked a
4x6 label roll for ten inches of stock, and one order came out over three labels instead of two.

It is also what QZ Tray did on its PDF path: it never overrode the sheet, it put the geometry into
a `MediaPrintableArea` and let `PrinterJob.getPageFormat` resolve it against the driver's stock.
The one QZ behaviour deliberately *not* copied is what the JDK does with a rectangle that does not
fit — it discards it and falls back to its own one-inch inset, for anything without slack, an exact
4x6 on a 4.10x6.00 media included. That printed the invoice as a 2.10x4.00in block adrift in the
middle of the label. Clamping keeps the page count identical and fills the label instead.

Java gives every page a one-inch inset by default, so zero margins here means genuine full bleed
rather than a label crushed into the middle of the media.

Returns **202** immediately with a `jobId`. Add `?wait=<ms>` to block until the label really
prints — then you get **200** on success or **502** with an `error`, which is what you want while
validating a new label against physical output.

> **A 202 is not a success.** It means the job was still in flight when the wait expired. Callers
> that need to know the label actually came out must check `ok === true`, not just the HTTP status.

```json
{ "jobId": "j_1", "printer": "pack-1", "state": "done", "ok": true, "durationMs": 1 }
```

### `POST /preview` — render what the printer *would* put on the label

Same body as `/print` minus `copies`, plus `page` (1-based, default 1), `dpi` (default 96) and
`overlay` (default true). Prints nothing; returns a base64 PNG of the sheet.

Not a mock-up, and that is the whole point. It composes the page with the same code the print path
uses, on the same driver query — the sheet the printer is really loaded with, the rectangle its
head can really reach — and hands the result to the same PDFBox renderer, aimed at an image instead
of a driver. The auto-landscape flip, the clamping, the scale-to-fit and the dead strip along one
edge all show up for the same reason they happen on paper. A preview drawn any other way would
agree with the printer right up to the moment it mattered.

The image is the **sheet**, not the page: a landscape job is drawn back through the inverse of the
orientation transform, so what you see is the label the way it comes off the roll rather than the
page turned on its side. With `overlay` on it also carries the sheet edge, the resolved printable
rectangle (dashed), and — the one that is otherwise invisible — the strip the head cannot reach.

```json
{ "ok": true, "printer": "TSC TE244",
  "page": { "...": "the block below" },
  "summary": ["paper        295.1 x 432.0 pt   4.10 x 6.00 in", "..."],
  "preview": { "mime": "image/png", "encoding": "base64", "widthPx": 615, "heightPx": 900,
               "dpi": 150, "pageIndex": 0, "pageCount": 1, "data": "iVBORw0K..." } }
```

Diagnostics queue on the printer's own lane rather than jumping it, because they read the very
driver state a print in flight is using. On an idle station that is free; behind a running picklist
it waits, and gives up after 20s with a 503 rather than holding an HTTP thread.

### `POST /preflight` — will this profile fit, before anything prints

`printer` and `options`; `data` optional. Resolves the geometry against the loaded media and
reports it. Burns nothing.

```
paper        295.1 x 432.0 pt   4.10 x 6.00 in
imageable    x=3.5 y=0.0   288.0 x 432.0 pt
orientation  landscape (auto-detected)
scaling      scale-to-fit  x0.632
clamped      x 0.0 -> 3.5 pt (head)
clamped      height 720.0 -> 432.0 pt (media)
```

That is a real answer from a real TSC TE244 for the 4x10in Flipkart invoice profile, and it is the
whole diagnosis: the strip is ten inches on a six-inch roll, and zero margins reach 1.25mm past
where the head can mark. `fits` is false whenever anything had to be cut down — which does not mean
the job will fail, it means it will print something other than what the profile describes, and that
is the failure that is expensive to notice.

Send `data` when the answer must be exact. Orientation is the one part of the composition that can
depend on the document — an absent `orientation` means the auto-landscape detection, which reads
the PDF's own page box — so a preflight without one resolves against a placeholder page and says so
in `documentSupplied` and `caveat`. Everything about size, margins and clamping is answered either
way.

### `GET /jobs/{id}` — also accepts `?wait=<ms>`

Carries a `page` block for any document job that got as far as composing one, in the same shape
`/preflight` returns: `paper`, `imageable`, `effective` (what PDFBox was actually handed, which is
the paper rectangle transposed on a rotated page), `head`, `orientation` with its `source`
(`requested`, `auto-detected` or `driver-default`), the scale factor, `fits`, and a `notes` list
naming every clamp with the bound that caused it — `media` for the sheet, `head` for the printable
area. Recorded before the print rather than after, so a job that fails at the driver still reports
the page it was failing to print.

The service always knew all of this. It just never said so, and finding out meant reflecting into a
private method.

### `GET /jobs/{id}` extras for panel jobs

`title` (the file name), `pagesNote` (what a page selection resolved to), `batchId` and `strategy`
are present on jobs the Control Panel created, and `reprintable` says whether the service still
holds the document. All four are absent on a job from the web app, which sends none of them.

`state` gained one value: **`cancelled`**, for a job pulled out of its queue before a lane reached
it. Like `failed` it reports `ok: false` — the paper did not come out — but nothing went wrong and
nothing needs investigating.

### `POST /reconnect` — drop and reopen every printer socket

Errors are always `{ "ok": false, "error": "..." }` with a real status code: 400 bad request,
403 origin not allowed, 404 unknown printer/job, 413 body too large, 502 print failed,
503 queue full or the lane did not answer in time.

---

## The Control Panel

A local web UI the service serves at **`http://127.0.0.1:9110/`** — also on the tray menu, under
*Open Control Panel*. It exists for two people the HTTP API does not serve:

- the operator at the packing bench who needs to print two hundred courier PDFs and has no web app
  open, and
- whoever sets a station up, who currently does it by editing JSON and restarting.

It is plain HTML, CSS and JavaScript served out of the jar. No build step and no npm, for the same
reason the Java build is a bare `javac` over vendored jars: a warehouse build must not depend on the
network, and there is nothing here that a framework would earn.

### What it does

**Print** — one document. Drop a PDF, pick a printer and a preset, see the plan, preview it against
the printer's real media, print. Raw TSPL/ESC-POS/ZPL is here too, as plain text, hex pairs or
base64.

**Bulk** — many documents, one strategy, with progress and a per-file outcome. **Every file is
planned before any file prints**: a run that cannot be carried out is refused whole, naming the file
and the reason. The alternative — discovering a bad preset on file forty-one of two hundred — leaves
forty printed labels and a stack of half-processed orders, and no button recovers from that.

**Printers** — add a networked label printer, probe its socket, print a test label, and see queue
and failure counts per printer. A printer added here takes effect immediately, with no restart, so
the station's other printers keep their warm sockets.

**Presets** — the paper geometry from `options`, saved and named, with *Check fit* running a real
`/preflight` against a chosen printer. That check is what makes these fields safe to expose at all:
they are hardware-calibrated numbers, and it can say "4x10in will be clamped to 4x6 on this printer"
without burning a label to find out.

**Strategies** — see below.

**Jobs** — recent jobs with reprint and cancel, and the geometry each one resolved to.

**Settings** — the panel's defaults, the station's facts, and the tail of the log file. The log is
worth the screen: the packaged build is windowed, so there is no console, and getting at it today
means talking someone through `%APPDATA%` over the phone while a shift waits.

### Strategies: which pages go to which printer

The one genuinely new capability, as against a nicer front end for something the API already did. A
strategy is a list of rules, each pairing a page selection (`pages` above) with a printer, a preset
and a copy count. All of them are applied to every document.

That covers the things an operator cannot express by picking a printer and pressing Print:

| Strategy | Rules |
|----------|-------|
| **Manual duplex** on a printer with no duplexer | `odd` → printer A, then `even` reversed → printer A |
| **Label and invoice split**, one order per file | `first` → the 4x6 thermal roll, `2-` → the office printer |
| **A copy for the picklist** | `first` → thermal, 2 copies |
| **Fan-out** | the same pages to two printers, as two rules |

A rule that matches no page of a particular document is **skipped and reported**, not an error:
`2-` against a one-page Meesho label is a split strategy meeting a file that has no invoice, and
that is a fact about the file rather than a mistake in the rule. One strategy therefore covers a
one-page label and a three-page invoice without anyone maintaining two.

A rule that names no printer uses whichever printer is chosen when the strategy runs, which is what
makes the manual-duplex strategy reusable on any station.

### What it starts with

On first run the panel writes a starting set of presets and strategies — the per-platform profiles
the packing flow already prints with (Flipkart, Meesho, FirstCry), comments and all. An empty
Presets screen is a worse first impression than a populated one: the numbers here are not guessable,
they were calibrated against physical output, and this puts them in front of the person standing at
the printer instead of only in a web bundle.

Only ever written when the file is absent. Deleting every preset gets you an empty screen, not your
deletions undone on the next restart.

### Where it keeps things

Flat JSON beside `config.json`, in the same per-user directory:

| File | Holds |
|------|-------|
| `presets.json` | saved paper geometry |
| `strategies.json` | the routing rules |
| `printers.json` | label printers the panel added |
| `panel.json` | the panel's own defaults |
| `spool/` | staged files, emptied at startup |

**The panel never writes `config.json`.** That file is hand-edited, and several of its comments are
the reason a value is what it is; a UI that rewrote it would drop every one of them the first time
someone added a printer. So the two printer lists are merged at startup, `config.json` winning a
name clash, and the panel refuses to edit or delete anything defined there — it says where the
definition lives instead. Deleting the four files above is a complete undo of everything the UI ever
did to a station.

### Its endpoints

All under `/api/`, so they cannot collide with the printing contract above — that contract has a
published client whose major version tracks it, and a panel feature must never be a reason to bump
it.

| Endpoint | Does |
|----------|------|
| `GET /api/state` | everything the panel needs, in one call |
| `GET`/`POST` `/api/printers`, `/api/printers/delete` | list, add or remove a label printer |
| `POST /api/printers/probe` | open a socket to an address and close it again |
| `POST /api/printers/test-label` | print a small TSPL label, to prove the path end to end |
| `GET`/`POST` `/api/presets`, `/api/presets/delete` | preset CRUD, refusing geometry that does not parse |
| `GET`/`POST` `/api/strategies`, `/api/strategies/delete` | strategy CRUD, refusing rules that cannot resolve |
| `POST /api/files` | stage one PDF (`application/pdf` body, `?name=`), returning its page count |
| `GET /api/files`, `POST /api/files/delete` | the staging area |
| `POST /api/plan` | what a strategy would do to these files. Prints nothing |
| `POST /api/print` | one print, document or raw, through the same router as `POST /print` |
| `POST /api/preview`, `/api/preflight` | as above, with a page selection applied first |
| `POST /api/batches`, `GET /api/batches/{id}`, `POST /api/batches/{id}/cancel` | bulk runs |
| `GET /api/jobs`, `POST /api/jobs/{id}/reprint`, `POST /api/jobs/{id}/cancel` | history |
| `GET`/`POST` `/api/settings` | the panel's defaults |
| `GET /api/log?lines=` | the tail of the log file |

Two things about them worth knowing:

- The panel's own origin (`http://127.0.0.1:<port>`) is trusted unconditionally, whatever
  `allowedOrigins` says. A station that tightened the list for its web app would otherwise lock the
  operator out of the panel on the same machine, with a 403 that looks like a bug in the panel.
- `POST /api/files` accepts a larger body than `maxBodyBytes`, which bounds what a *web page* may
  post. This is a person choosing a file in a picker on the same machine, and staged files go to
  disk rather than into the heap.

### Why no WebSocket

The Phase 1 technical design put a WebSocket at the front of the service, with RSA-signed calls and
a trusted-certificate store, because that is what QZ Tray does — and QZ has to, since a browser can
only open `wss://` from an HTTPS page. That certificate is named in the design's own risk register
as *"the single most awkward part of this whole build"*, and it is awkward per machine, for ever.

This service already avoids all of it (see *Why no TLS* below), so the panel is built on the
transport that exists rather than a second one beside it. Everything the design wanted from the
socket is still here: two front-ends on one engine, the panel as an ordinary client rather than a
special case, and live state — by polling, which for a page on the same machine is a nicer
implementation question rather than a user-visible one. The only thing genuinely given up is
signature verification, which gated *unknown web pages* rather than the panel, and which
`allowedOrigins` does here.

---

## Frontend

The browser client is a separate npm package, **`printly-web`**, with its own repository. Web
apps install it rather than copying a file — a copied client drifts, and the copies drift apart
in ways that only show up as a label that never printed.

```bash
npm install printly-web
```

```js
import { usePrintly } from 'printly-web/react';

const { ready, printerNames, printLabel, printLabelBatch } = usePrintly({ lane: 'label' });

// one label, waiting for the real outcome (use this while validating a new template)
await printLabel('pack-1', tspl, { waitMs: 5000 });

// bulk, with the bounded pool that replaces the serialized `for + await` loop
const { sent, failed } = await printLabelBatch(jobs, { concurrency: 5, onProgress: (n, total) => … });
```

The client turns a **202 into an error whenever the caller passed `waitMs`** — see the warning
under `POST /print` above. That check is the reason this lives in one package instead of in each
app: it is easy to leave out, and leaving it out silently marks orders as printed.

There is no certificate promise, no signature promise, and no WebSocket — see *Why no TLS* below.

The package's major version tracks this service's HTTP contract: a `1.x` client talks to a `1.x`
service.

---

## How it works

```
Browser (HTTPS)  ──fetch──▶  127.0.0.1:9110  ──▶  queue per printer  ──▶  warm socket  ──▶  :9100
```

**One warm socket and one worker thread per printer; jobs strictly serialized per printer.**
Most budget thermal printers (TSC, Godex) accept a single connection on `:9100`. A second
concurrent writer is either refused or interleaves into garbled labels, so parallelism comes from
having several printers, never several sockets to one printer. The single-worker queue *is* the
arbitration: two operators hitting `pack-1` at once simply queue.

Other properties worth knowing:

- **Warm sockets.** The socket stays open between jobs, so a label costs one write, not a TCP
  handshake. `TCP_NODELAY` is set — Nagle would otherwise hold tiny label payloads for tens of ms.
- **Batching.** Jobs already queued for a printer are concatenated into one write. In the 200-label
  test that collapsed 200 jobs into 138 socket writes, still perfectly serialized.
- **Liveness without a second socket.** We cannot probe with a throwaway connection, because our
  own warm socket holds the printer's only slot. Instead an idle tick does a non-destructive short
  read: a closed peer surfaces as EOF, a live one as a timeout. That is what drives the
  online/offline status, and it recovers automatically when a printer comes back.
- **Stale-socket retry, carefully scoped.** A write that fails on a *reused* socket almost always
  means the printer closed it while idle and nothing reached the hardware, so it is retried once on
  a fresh connection. A write failing on a *fresh* socket is a real error and is never retried —
  a duplicate label is not an acceptable way to avoid a missing one.
- **Failures are surfaced, not swallowed.** You no longer have the OS spooler's retry behaviour, so
  every failure comes back through the API with the printer address and the reason.

### Two lanes

| Lane | Path | Used for |
|------|------|----------|
| **Label** (hot) | TSPL → raw socket, zero rendering | MRP, carton, Flipkart QC labels |
| **Document** (cold) | `type:"pdf"` → PDFBox raster → `PrinterJob` | invoices, courier PDFs |

The document lane rasterizes with **PDFBox** and prints through `PrinterJob`. It does not hand the
PDF to the spooler as a byte stream: most Windows drivers advertise no PDF doc flavor at all —
least of all the thermal label printers this exists to drive — so that path either refused the job
or fell through to `AUTOSENSE` and sent raw PDF source to the printer, which comes out as pages of
ASCII garbage. Rendering here sidesteps driver flavors entirely; the driver receives an ordinary
Java2D page. It is the same approach QZ Tray takes, deliberately, so that geometry calibrated
against QZ's output carries over unchanged.

Consequences worth knowing:

- `GET /printers?lane=document` reports `acceptsPdf: true` for every printer, because now every
  printer really can take one. The field is kept because the frontend filters its dropdown on it.
  `acceptingJobs` is reported alongside as **advisory only** — deliberately not folded into
  `online`, so a driver that briefly reports not-accepting cannot make a printer vanish mid-shift.
- Jobs report `renderMs` (parsing the PDF) and `spoolMs` (rasterizing and handing it to the
  driver) so a job that overran your `?wait=` is explicable rather than just late.
- The first render of a JVM's life builds PDFBox's font cache, which on a Windows machine with a
  large font set is routinely tens of seconds. The lane warms itself on a background thread at
  startup so that cost is never paid by a real job.
- `documentThreads` (default 4) sizes the render pool. It matches the packing page's bulk-print
  concurrency on purpose: a narrower pool makes the tail of a batch queue behind rendering and
  overrun the caller's `?wait=`.

### Why no TLS

The mixed-content spec treats `http://127.0.0.1` as a *potentially trustworthy* origin, so an
HTTPS page can `fetch` it without any TLS, self-signed certificate, or OS trust-store install —
the whole class of QZ deployment pain simply disappears. Security instead comes from binding to
loopback only and checking the `Origin` header against `allowedOrigins`.

Two browser details this handles for you:

- **CORS.** Preflights are answered on every endpoint; without that the fetch fails even though
  mixed content is allowed.
- **Private Network Access.** Chrome preflights a public HTTPS page reaching loopback and refuses
  unless the response opts in, so `Access-Control-Allow-Private-Network: true` is returned when
  asked. Watch for this one — it is the most likely reason a working `curl` and a failing browser
  disagree.

---

## Packaging for the pack PCs

```bat
package-windows.bat          :: -> build\installer\Printly-1.0.0.msi (bundled JRE, no Java needed)
autostart-install.bat        :: run once after installing, to launch at login
```

The installer bundles only `java.base`, `java.desktop`, and `jdk.httpserver`, and the app runs
with `-Xmx128m`. There is no certificate or trust-store step.

Only one copy can run: the bridge claims the port before it opens any printer socket, and a second
launch recognises the first through `/health` and exits cleanly rather than stealing the printers'
single connection slot.

---

## Migration

Do not cut QZ over in one shot — every step below is reversible, and no station is ever unable to
print.

1. Install the bridge next to QZ on **one** pack PC.
2. Confirm the browser can reach it: `GET /health` from your actual HTTPS app, in the actual
   pack-station browser. This validates the mixed-content and Private Network Access story.
3. Convert **`mrpLabel.js`** to TSPL and route only that label through the bridge, with
   `waitMs` set so you see real failures. Validate against physical output.
4. Migrate carton, then Flipkart QC labels, one at a time, validating each on real hardware.
5. Replace the serialized `for + await` loop in `PrintDetail.jsx` with `printBulk`.
6. Retire QZ only once every label type is on the bridge and the document lane is settled.

**The remaining work is TSPL correctness, not Java.** PDFs "just worked" because they rasterized a
designed layout; hand-written TSPL means you now own `SIZE` / `GAP` / `DENSITY` / `DIRECTION` /
`REFERENCE`, barcode symbology, and text placement. A mis-positioned or over-dense barcode fails
silently at the *courier's* scanner, which is worse than a slow label. Test every label type
against physical output on every printer model.

## Troubleshooting

| Symptom | Cause |
|---------|-------|
| `curl` works, browser does not | CORS or Private Network Access — check the preflight, and that your origin is in `allowedOrigins` |
| Printer shows `offline`, ping works | Something else holds `:9100` — often a still-running QZ or a second bridge copy |
| Printer ignores the label | Try `"lineEnding": "crlf"` for that printer |
| Non-ASCII text prints wrong | Set the printer's `charset` to its codepage |
| Labels garbled under two operators | Should be impossible — per-printer serialization prevents it; check for a second bridge instance |
| First print after a long idle fails | Lower `idleCheckMs` |
| Nothing in the console on Windows | The packaged build is windowed; read `%APPDATA%\Printly\printly.log`, or the Log panel under Settings |
| The Control Panel will not load | Check `/health` answers. If the page loads but every action 403s, the service is older than the panel — the panel's own origin is trusted from this version on |
| A printer cannot be edited in the panel | It is defined in `config.json`, which the panel never rewrites so its comments survive. Edit it there and restart |
| "Test the connection" says already connected | The service is holding that printer's one socket. That answer *is* the liveness check — a fresh probe would have to steal the connection to run |
| A bulk run refused to start | One of the files could not be planned; the message names it. Nothing printed — that is the design, not a failure |
| Only some rules of a strategy printed | A rule that matches no page of that document is skipped and reported. A `2-` invoice rule against a one-page label is the usual case |
| A reprint says the document is no longer held | The history keeps recent documents within a byte budget so a shift of invoices cannot exhaust the 128 MB heap. Print the file again |

## Layout

```
src/main/java/com/jagdushah/printly/
  Main.java               entry point, single-instance, lifecycle
  Config.java             config file discovery, parsing, defaults
  PrinterTarget.java      one printer: host, port, charset, line endings
  HttpApi.java            the loopback HTTP surface, CORS, payload decoding
  PrinterConnection.java  warm socket + worker + queue  ← the heart of it
  PrintRouter.java        routes a job to the label lane or the document lane
  DocumentLane.java       PDFs rasterized with PDFBox, printed via PrinterJob
  PrintOptions.java       paper geometry off /print, normalised to points
  ResolvedPage.java       what those options resolved to on the real media, and every clamp
  PagePreview.java        the same composition and renderer, aimed at a PNG instead of a driver
  Job.java / JobRegistry.java   job state and recent-job lookup
  PageSelection.java      which pages a rule applies to: odd, even, 2-5, every:3
  PdfSplitter.java        rewriting a PDF down to those pages, and reading its page count
  Strategy.java           a routing strategy resolved into per-printer steps
  BatchRunner.java        bulk runs: plan every file, then print with progress and cancel
  Store.java              the panel's presets, strategies, printers and settings, as JSON
  Spool.java              staged files, on disk so a 200-file batch is not a heap problem
  ControlPanel.java       the /api endpoints and the served web UI
  TrayUi.java             system tray status, reconnect, quit
  Log.java                rotating file + console log
  Json.java               dependency-free JSON reader/writer
src/main/resources/ui/    the Control Panel page — index.html, styles.css, app.js
lib/                      vendored jars, committed — see below
tools/MockPrinter.java    fake TSPL printer for testing without hardware
tools/GeometryCheck.java  the calibrated profiles, asserted against measured
                          printer geometry — no hardware, exits non-zero on drift
tools/StrategyCheck.java  page selections, PDF splitting and strategy resolution,
                          asserted against known-good answers — no hardware either
```

The panel's three web files are copied into the jar by the build, so an installed copy has no loose
web assets to go missing or be edited into a state nobody can reproduce.

Run the routing checks after touching `PageSelection`, `PdfSplitter` or `Strategy`:

```bat
javac -encoding UTF-8 -d build\check -cp "build\classes;lib\pdfbox-2.0.37.jar;lib\fontbox-2.0.37.jar;lib\commons-logging-1.2.jar" tools\StrategyCheck.java
java -cp "build\check;build\classes;lib\pdfbox-2.0.37.jar;lib\fontbox-2.0.37.jar;lib\commons-logging-1.2.jar" com.jagdushah.printly.StrategyCheck
```

71 assertions, no printers, no paper, exits non-zero on drift. Geometry decides where ink lands; a
strategy decides which printer it lands on and which pages go there, and across a two-hundred-file
run that is the more expensive of the two to discover late.

One third-party dependency, and only for the document lane: **Apache PDFBox 2.0.x**
(`pdfbox`, `fontbox`, `commons-logging`, ~4.4 MB). Everything else is
`com.sun.net.httpserver`, `javax.print` and AWT from the JDK.

The jars are **vendored in `lib/` and committed**, not fetched. There is no Maven or Gradle here
on purpose — the build is a bare `javac`, and a warehouse build must not depend on the network.
Both `build.sh` and `build.bat` derive the compile classpath *and* the jar's `Class-Path` manifest
entry from whatever is in `lib/`, so upgrading a jar means dropping in the new file, with no build
script to edit. That manifest entry is what lets both `java -jar` and the `jpackage` launcher find
PDFBox; without it the service starts fine and then dies on the first PDF.

PDFBox 2.0.x rather than 3.x deliberately: QZ Tray renders with 2.0.x, so the geometry the packing
flow was calibrated against carries over instead of needing to be re-tuned against real labels.
(3.x also swaps `commons-logging` for `log4j-api`, so the jar set differs.)

After changing `lib/`, regenerate the `--add-modules` list in `package-windows.bat`:

```
jdeps --multi-release 21 --print-module-deps --ignore-missing-deps \
      build/dist/printly.jar lib/*.jar
```
