# Printly

A small always-running local service that accepts print jobs from your web app over
`http://127.0.0.1:9110` and writes them straight to a label printer's raw socket.

It replaces QZ Tray for the **label lane**. TSPL goes in, bytes go to the socket, nothing is
rendered — that is the entire speed story.

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
--version, -v
--help,   -h
```

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

### `POST /reconnect` — drop and reopen every printer socket

Errors are always `{ "ok": false, "error": "..." }` with a real status code: 400 bad request,
403 origin not allowed, 404 unknown printer/job, 413 body too large, 502 print failed,
503 queue full or the lane did not answer in time.

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
| Nothing in the console on Windows | The packaged build is windowed; read `%APPDATA%\Printly\printly.log` |

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
  TrayUi.java             system tray status, reconnect, quit
  Log.java                rotating file + console log
  Json.java               dependency-free JSON reader/writer
lib/                      vendored jars, committed — see below
tools/MockPrinter.java    fake TSPL printer for testing without hardware
tools/GeometryCheck.java  the calibrated profiles, asserted against measured
                          printer geometry — no hardware, exits non-zero on drift
```

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
