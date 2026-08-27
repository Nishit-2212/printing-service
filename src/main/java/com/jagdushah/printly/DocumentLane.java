package com.jagdushah.printly;

import java.awt.geom.Rectangle2D;
import java.awt.print.PageFormat;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.PageRanges;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The cold lane: invoices and courier PDFs, rasterised with PDFBox and printed through
 * {@link PrinterJob}.
 *
 * <p>It does <em>not</em> hand the PDF to the OS spooler as a byte stream, which is what an
 * earlier version did via {@code DocFlavor.INPUT_STREAM.PDF}. Most Windows drivers advertise no
 * PDF flavor at all — least of all the thermal label printers this exists to drive — so that path
 * either refused the job or, worse, fell through to {@code AUTOSENSE} and sent raw PDF source to
 * the printer, which comes out as pages of ASCII garbage.
 *
 * <p>Rendering here instead sidesteps driver flavors completely: PDFBox draws the page onto an
 * ordinary Java2D surface and the driver receives a perfectly normal print job. It is the same
 * approach QZ Tray takes, which is deliberate — the geometry the packing flow sends was
 * calibrated against QZ's output, and matching its renderer is what lets those numbers carry over
 * unchanged.
 *
 * <h2>One worker per printer</h2>
 *
 * <p>Jobs for a given printer run on that printer's own thread, in the order they arrived. This
 * replaced a shared thread pool, for two reasons measured on a real pack station:
 *
 * <ul>
 *   <li><b>Order.</b> A pool lets two labels for the same printer enter {@link PrinterJob#print}
 *       at once and come out in whatever order the driver settles on. Nothing caught it while the
 *       frontend printed strictly one label at a time and waited for each — but the whole point of
 *       queueing ahead is to stop waiting, and a picklist whose labels are shuffled is worse than
 *       a slow one.</li>
 *   <li><b>Cost.</b> A thread that owns its printer can also own its {@link PrinterJob}, and
 *       reusing that costs 0ms against 35ms for building a fresh one. See {@link Lane#context}.</li>
 * </ul>
 *
 * <p>Different printers still run in parallel, which is the concurrency the packing flow actually
 * depends on: it fires a label and an invoice together and waits for both.
 */
public final class DocumentLane {

    /**
     * How long a printer enumeration is reused.
     *
     * <p>Mostly belt-and-braces: the JDK's own {@code Win32PrintServiceLookup} caches internally
     * and answers a repeat call in 0.2ms, so this saves little. It is kept because the first call
     * of a process really does cost ~40ms, and because a newly attached printer takes at most this
     * long to appear, which is under the frontend's own poll interval.
     */
    private static final long LOOKUP_TTL_MS = 5000;

    private final int queueCapacity;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final AtomicInteger laneCount = new AtomicInteger();

    private volatile PrintService[] cached;
    private volatile long cachedAt;
    private volatile boolean running = true;

    public DocumentLane(Config config) {
        this.queueCapacity = Math.max(1, config.queueCapacity);
        quietenPdfBox();
        warmUp();
    }

    // ------------------------------------------------------------------ discovery

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        PrintService def = PrintServiceLookup.lookupDefaultPrintService();
        for (PrintService svc : services()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", svc.getName());
            m.put("lane", "document");
            m.put("online", true);
            m.put("default", def != null && def.getName().equals(svc.getName()));
            // Always true now: this lane rasterises, so every OS printer can take a PDF. The
            // field is kept — and kept true — because the frontend filters its printer dropdown
            // on it. It used to mean "the driver advertises DocFlavor.INPUT_STREAM.PDF", which is
            // false for precisely the thermal printers the packing flow needs, so every one of
            // them disappeared from the dropdown.
            m.put("acceptsPdf", true);
            // Advisory only, and deliberately not folded into "online": a driver that briefly
            // reports not-accepting would otherwise make the printer vanish mid-shift, which is
            // the same failure acceptsPdf used to cause.
            m.put("acceptingJobs", acceptingJobs(svc));
            out.add(m);
        }
        return out;
    }

    public boolean has(String name) {
        return find(name) != null;
    }

    private PrintService[] services() {
        long now = System.currentTimeMillis();
        long stamp = cachedAt;
        PrintService[] snapshot = cached;
        if (snapshot != null && now - stamp < LOOKUP_TTL_MS) {
            return snapshot;
        }
        PrintService[] fresh = PrintServiceLookup.lookupPrintServices(null, null);
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private PrintService find(String name) {
        if (name == null) {
            return null;
        }
        for (PrintService svc : services()) {
            if (svc.getName().equalsIgnoreCase(name)) {
                return svc;
            }
        }
        return null;
    }

    /**
     * Ask a driver what it can actually mark on the media it has loaded.
     *
     * <p>Queried with the printer's own default {@code Media} attached, because the printable area
     * is per-stock: without it a driver answers for whatever it considers its default, which on a
     * label printer is rarely the roll that is loaded.
     *
     * <p>Returns the largest rectangle offered when a driver offers several. Some drivers answer
     * with one entry per stock and there is no better discriminator available here; the largest is
     * the one that does not needlessly shrink output.
     */
    private static Rectangle2D lookupPrintableArea(PrintService svc) {
        if (svc == null) {
            return null;
        }
        try {
            PrintRequestAttributeSet request = new HashPrintRequestAttributeSet();
            Media media = (Media) svc.getDefaultAttributeValue(Media.class);
            if (media != null) {
                request.add(media);
            }
            Object value = svc.getSupportedAttributeValues(MediaPrintableArea.class, null, request);
            MediaPrintableArea best = null;
            if (value instanceof MediaPrintableArea single) {
                best = single;
            } else if (value instanceof MediaPrintableArea[] all) {
                for (MediaPrintableArea candidate : all) {
                    if (best == null || area(candidate) > area(best)) {
                        best = candidate;
                    }
                }
            }
            if (best == null) {
                return null;
            }
            return new Rectangle2D.Double(
                    best.getX(MediaPrintableArea.INCH) * 72.0,
                    best.getY(MediaPrintableArea.INCH) * 72.0,
                    best.getWidth(MediaPrintableArea.INCH) * 72.0,
                    best.getHeight(MediaPrintableArea.INCH) * 72.0);
        } catch (RuntimeException e) {
            // Not every driver answers this, and a refusal is not a reason to fail the job: the
            // sheet is still a bound, it is just a more generous one.
            Log.warn("could not read the printable area of '" + svc.getName() + "': " + e);
            return null;
        }
    }

    private static float area(MediaPrintableArea m) {
        return m.getWidth(MediaPrintableArea.INCH) * m.getHeight(MediaPrintableArea.INCH);
    }

    private static boolean acceptingJobs(PrintService svc) {
        try {
            PrinterIsAcceptingJobs a = svc.getAttribute(PrinterIsAcceptingJobs.class);
            return a == null || a.equals(PrinterIsAcceptingJobs.ACCEPTING_JOBS);
        } catch (RuntimeException e) {
            // Not every driver answers this. Unknown reads as available rather than broken.
            return true;
        }
    }

    // ------------------------------------------------------------------ printing

    /**
     * Queue a job on its printer's lane.
     *
     * @return false when that lane's queue is full, which the HTTP layer turns into a 503. The
     *         caller must settle the job itself; nothing here will ever pick it up.
     */
    public boolean submit(Job job) {
        if (!running) {
            return false;
        }
        return lane(job.printer()).offer(new PrintTask(job));
    }

    private Lane lane(String printer) {
        return lanes.computeIfAbsent(printer.toLowerCase(Locale.ROOT), key -> new Lane(printer));
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * How long a diagnostic call waits for its turn on the lane.
     *
     * <p>Diagnostics queue behind prints rather than jumping them, because they read the very
     * driver state a print in flight is using and a second thread poking at a {@link PrinterJob}
     * is how this lane earns a heisenbug. That is nearly always free — the calibration screen is
     * used on an idle station — and this bound is what stops "nearly always" from becoming a
     * wedged HTTP thread when it is not.
     */
    private static final long DIAGNOSTIC_TIMEOUT_MS = 20_000;

    /**
     * Render what this printer would actually put on the label, without putting it there.
     *
     * <p>Runs on the printer's own lane thread, which is the point: the sheet and the printable
     * area come from the same cached driver query a print would use, so the preview cannot be
     * right about a printer the print path is wrong about. See {@link PagePreview}.
     *
     * @param pdf       the bytes that would have been printed
     * @param pageIndex 0-based page of that PDF
     * @param dpi       preview resolution; 0 for {@link PagePreview#DEFAULT_DPI}
     * @param overlay   draw the sheet, imageable and head-unreachable guides
     * @return {@code page} — the full {@link ResolvedPage} report — plus {@code preview}, a
     *         base64 PNG
     */
    public Map<String, Object> preview(String printer, byte[] pdf, PrintOptions options,
            int pageIndex, double dpi, boolean overlay) {
        return call(printer, lane -> {
            try (PDDocument doc = loadPdf(pdf)) {
                ResolvedPage page = lane.resolve(options, doc);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("printer", lane.printer);
                out.put("page", page.toJson());
                out.put("summary", page.toText());
                out.put("preview", PagePreview.render(page, doc, options, pageIndex, dpi, overlay));
                return out;
            }
        });
    }

    /**
     * Answer whether a profile fits this printer's loaded media, before anything prints.
     *
     * <p>The service already knows: it knows the media is 4.098x6.000in and that the head reaches
     * 0.049 to 4.049, and it has always known which of the caller's numbers it had to cut down to
     * get there. It just never said so until the label came out wrong. This resolves the geometry
     * and reports it, and burns nothing.
     *
     * <p>{@code pdf} is optional but not decorative. Orientation is the one part of the
     * composition that can depend on the document — an absent {@code orientation} means QZ's
     * auto-landscape detection, which reads the PDF's own page box — so a preflight without one
     * resolves against a placeholder page and says so in {@code documentSupplied}. Every question
     * about size, margins and clamping is answered either way.
     */
    public Map<String, Object> preflight(String printer, byte[] pdf, PrintOptions options) {
        return call(printer, lane -> {
            boolean supplied = pdf != null && pdf.length > 0;
            try (PDDocument doc = supplied ? loadPdf(pdf) : placeholder(options)) {
                ResolvedPage page = lane.resolve(options, doc);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("printer", lane.printer);
                out.put("fits", page.fits());
                out.put("documentSupplied", supplied);
                if (!supplied && options.orientation() == null) {
                    // Said plainly rather than left to be inferred from a boolean: this is the one
                    // field of the answer that is a guess, and a caller that reads it as settled
                    // would be as wrong as the bug this endpoint exists to catch.
                    out.put("caveat", "no PDF was supplied and no orientation was requested, so the "
                            + "auto-landscape detection ran against a placeholder page — send the "
                            + "PDF for the orientation this printer would really use");
                }
                out.put("page", page.toJson());
                out.put("summary", page.toText());
                return out;
            }
        });
    }

    /**
     * Parse a PDF a diagnostic endpoint was handed, as a caller error rather than a lane failure.
     *
     * <p>{@code PDDocument.load} throws {@link java.io.IOException} on a truncated or non-PDF
     * payload, and a checked exception coming off the lane thread reads to {@link #call} as "the
     * printer could not be reached" — so the caller got a 503 telling them to retry, for a
     * document no amount of retrying will fix. This is the translation that keeps the 400 and the
     * 503 meaning what they say.
     */
    private static PDDocument loadPdf(byte[] pdf) {
        PDDocument doc;
        try {
            doc = PDDocument.load(pdf);
        } catch (Exception e) {
            throw new IllegalArgumentException("\"data\" is not a readable PDF: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        if (doc.getNumberOfPages() == 0) {
            try {
                doc.close();
            } catch (Exception ignored) {
                // Nothing useful to do with a failure to close a document already being rejected.
            }
            throw new IllegalArgumentException("the PDF has no pages");
        }
        return doc;
    }

    /** A blank page the size the caller asked for, so a document-less preflight has a subject. */
    private static PDDocument placeholder(PrintOptions o) {
        PDDocument doc = new PDDocument();
        float w = (float) (o.hasSize() ? o.widthPt() : 288);
        float h = (float) (o.hasSize() ? o.heightPt() : 432);
        doc.addPage(new PDPage(new PDRectangle(w, h)));
        return doc;
    }

    /**
     * Run something on a printer's lane thread and wait for it.
     *
     * <p>Unwraps the worker's exception onto the calling thread so the HTTP layer sees the real
     * cause — a malformed PDF has to surface as a 400 naming the PDF, not as a 500 naming a
     * {@code CompletableFuture}.
     */
    private Map<String, Object> call(String printer, LaneCall work) {
        if (!running) {
            throw new IllegalStateException("the document lane is shutting down");
        }
        Lane lane = lane(printer);
        CallTask task = new CallTask(work);
        if (!lane.offer(task)) {
            throw new IllegalStateException("the queue for '" + printer
                    + "' is full — printer is not keeping up");
        }
        try {
            return task.result.get(DIAGNOSTIC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for '" + printer + "'");
        } catch (TimeoutException e) {
            throw new IllegalStateException("'" + printer + "' did not answer within "
                    + DIAGNOSTIC_TIMEOUT_MS + "ms — it may be busy printing");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause.getMessage() == null
                    ? String.valueOf(cause) : cause.getMessage());
        }
    }

    /** What {@link #call} runs, confined to one lane's worker thread. */
    private interface LaneCall {
        Map<String, Object> run(Lane lane) throws Exception;
    }

    /**
     * One unit of work on a lane's thread.
     *
     * <p>The queue used to hold {@link Job} directly. It holds this instead so a preview can share
     * the thread, the cached {@link PrinterJob} and the cached driver page with the prints it is
     * previewing — the alternative, querying the driver from the HTTP thread, would let the
     * preview answer for a different page than the one the next print composes.
     */
    private interface LaneTask {
        void run(Lane lane) throws Exception;

        /** Settle the task after {@link #run} threw. Never itself throws. */
        void fail(Exception e, Lane lane);
    }

    /** A real print. Failure poisons the lane's print context, because it may have poisoned it. */
    private final class PrintTask implements LaneTask {

        private final Job job;

        PrintTask(Job job) {
            this.job = job;
        }

        @Override
        public void run(Lane lane) throws Exception {
            job.markPrinting();
            lane.print(job);
            job.complete();
            Log.info("document job " + job.id() + " done: printer='" + job.printer() + "' "
                    + lane.kb(job) + " " + job.timingLine());
        }

        @Override
        public void fail(Exception e, Lane lane) {
            // A job that threw part-way through may have left the native print context in a state
            // the next job would inherit. Cheaper to rebuild than to reason about.
            lane.discardContext();
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.warn("document job " + job.id() + " failed on '" + lane.printer + "': " + reason);
            job.fail(reason);
        }
    }

    /**
     * A diagnostic call. Failure completes the caller's future and leaves the lane alone —
     * a malformed PDF handed to {@code /preview} is the caller's problem, not the printer's, and
     * dropping the print context over it would make the next real label pay 35ms for nothing.
     */
    private final class CallTask implements LaneTask {

        private final LaneCall work;
        private final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();

        CallTask(LaneCall work) {
            this.work = work;
        }

        @Override
        public void run(Lane lane) throws Exception {
            result.complete(work.run(lane));
        }

        @Override
        public void fail(Exception e, Lane ignored) {
            result.completeExceptionally(e);
        }
    }

    /**
     * Throw away every cached print context, so the next job rebuilds one.
     *
     * <p>The counterpart to caching a {@link PrinterJob} for the life of the process: without a
     * way to clear it, a driver reconfigured mid-shift — a changed default stock, a re-installed
     * printer — would keep printing against the context captured before the change, and the only
     * fix would be restarting the service. This is what the tray's Reconnect button hangs off.
     *
     * <p>Queued jobs are untouched; each lane drops its context on the next loop.
     */
    public void reconnectAll() {
        for (Lane lane : lanes.values()) {
            lane.invalidate();
        }
    }

    /**
     * One printer, one queue, one thread, one print context.
     *
     * <p>Everything in here except {@link #queue} and {@link #stale} is confined to
     * {@link #worker}. {@link PrinterJob} is not thread-safe and is never handed out.
     */
    private final class Lane {

        private final String printer;
        private final BlockingQueue<LaneTask> queue;
        private final Thread worker;

        /** Set from any thread by {@link #invalidate()}; cleared only by the worker. */
        private volatile boolean stale;

        // -- worker-confined state --
        private PrinterJob context;
        private PrintService boundTo;
        private PageFormat driverPage;
        private Rectangle2D printableArea;
        private boolean printableAreaResolved;

        /** The last clamp reported for this printer, so an identical one stays quiet. */
        private String lastClamp;

        Lane(String printer) {
            this.printer = printer;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.worker = new Thread(this::run, "document-" + printer);
            this.worker.setDaemon(true);
            this.worker.start();
            Log.info("document lane opened for '" + printer + "' ("
                    + laneCount.incrementAndGet() + " active)");
        }

        boolean offer(LaneTask task) {
            return queue.offer(task);
        }

        void invalidate() {
            stale = true;
        }

        void stop() {
            worker.interrupt();
        }

        /** Drop the cached print context so the next task rebuilds it. Worker thread only. */
        void discardContext() {
            context = null;
            boundTo = null;
        }

        private void run() {
            while (running) {
                LaneTask task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    task.run(this);
                } catch (Exception e) {
                    // How a failure is settled is the task's business — a print poisons the
                    // context, a preview does not — so the loop only routes it.
                    task.fail(e, this);
                }
            }
        }

        /**
         * Compose the page for a document, against this printer's real media.
         *
         * <p>The single place the geometry is worked out, shared by printing, previewing and
         * preflighting. A preview that resolved its own page would be a second implementation to
         * keep in step, and the first thing to drift would be the clamping that this whole exercise
         * exists to make visible.
         */
        private ResolvedPage resolve(PrintOptions options, PDDocument doc)
                throws PrintException, PrinterException {
            return ResolvedPage.resolve(driverPage(), printableArea(), options, doc);
        }

        private void print(Job job) throws Exception {
            long t0 = System.currentTimeMillis();
            try (PDDocument doc = PDDocument.load(job.payload())) {
                int pages = doc.getNumberOfPages();
                if (pages == 0) {
                    throw new PrintException("the PDF has no pages");
                }
                long renderMs = System.currentTimeMillis() - t0;

                long t1 = System.currentTimeMillis();
                PrintOptions options = job.options();
                PrinterJob pj = context();
                pj.setJobName("printly " + job.id());
                ResolvedPage page = resolve(options, doc);
                // Recorded before the print rather than after, so a job that fails at the driver
                // still reports the page it was failing to print. That is the case where knowing
                // the geometry matters most, and the case an after-the-fact record would miss.
                job.resolvedPage(page);
                noteClamping(job, page);
                // center=false pins the content to the top-left of the imageable area, which is
                // what QZ did: its PDFWrapper passes center=false explicitly. PDFBox's
                // four-argument constructor defaults it to true, and centring shifts the artwork
                // on any profile whose margins are asymmetric — the Flipkart label's left 0.3in
                // against right 0 is exactly that, and the shift lands on the barcode.
                pj.setPrintable(
                        new PDFPrintable(doc, ResolvedPage.scaling(options),
                                false, (float) options.density(), false),
                        page.pageFormat());
                long setupMs = System.currentTimeMillis() - t1;

                long t2 = System.currentTimeMillis();
                pj.print(attributes(job, options, pages));
                job.timing(renderMs, setupMs, System.currentTimeMillis() - t2);
            }
        }

        /**
         * The lane's {@link PrinterJob}, built once and kept.
         *
         * <p>{@code setPrintService} costs 35ms on the TSC driver and 25ms on the receipt printer
         * — every job, because a fresh {@code PrinterJob} has no service to compare against. Hand
         * it the same {@link PrintService} instance it already holds and it returns in 0ms. The
         * JDK hands back the same instances across lookups (a new array, the same elements), so
         * the identity check below hits on every job after the first.
         *
         * <p>Reuse also leaves {@code previousPaper} set inside the JDK's job, which is a small
         * bonus: a run of same-size labels stops re-pushing the paper size into the DEVMODE, and
         * a genuine size change still trips it.
         */
        private PrinterJob context() throws PrintException, PrinterException {
            if (stale) {
                stale = false;
                context = null;
                boundTo = null;
                forgetPageCache();
                Log.info("document lane '" + printer + "' rebuilding its print context");
            }
            PrintService svc = find(printer);
            if (svc == null) {
                context = null;
                boundTo = null;
                forgetPageCache();
                throw new PrintException("no OS printer named '" + printer + "'");
            }
            if (context != null && boundTo == svc) {
                return context;
            }
            PrinterJob pj = PrinterJob.getPrinterJob();
            pj.setPrintService(svc);
            context = pj;
            boundTo = svc;
            forgetPageCache();
            return pj;
        }

        private void forgetPageCache() {
            driverPage = null;
            printableArea = null;
            printableAreaResolved = false;
        }

        /**
         * What the head can physically mark on the loaded media, in points on the sheet.
         *
         * <p>Cached with the same lifetime as {@link #driverPage}: it is a driver query, and it
         * changes for the same reasons the default page does — a different stock, a reinstalled
         * printer — both of which drop the print context anyway.
         *
         * @return the printable rectangle, or null when the driver advertises none, in which case
         *         the sheet is the only bound available
         */
        private Rectangle2D printableArea() throws PrintException, PrinterException {
            context();
            if (printableAreaResolved) {
                return printableArea;
            }
            printableAreaResolved = true;
            printableArea = lookupPrintableArea(boundTo);
            if (printableArea == null) {
                Log.info("printer '" + printer + "' advertises no printable area; "
                        + "bounding output by the sheet alone");
            }
            return printableArea;
        }

        /**
         * The printer's own page — its paper is the media actually loaded.
         *
         * <p>Cached because {@link PrinterJob#defaultPage} asks the driver for its current DEVMODE
         * and costs 11-17ms every call, on a fresh job or a reused one alike. Every document job
         * needs it now that the caller's {@code size} sets the printable rectangle rather than the
         * sheet, so paying it per job would be 11-17ms added to each label of a picklist.
         *
         * <p>Dropped whenever the print context is, which is what the tray's Reconnect button
         * hangs off: a driver reconfigured mid-shift — a changed default stock, a re-installed
         * printer — must not keep being measured against the page captured before the change.
         *
         * <p>A copy is handed out every time. {@link PageFormat} is mutable and
         * {@link #pageFormat} writes all over the one it is given.
         */
        private PageFormat driverPage() throws PrintException, PrinterException {
            PrinterJob pj = context();
            if (driverPage == null) {
                driverPage = pj.defaultPage();
            }
            return (PageFormat) driverPage.clone();
        }

        /**
         * Say once, per printer, when a profile does not fit the media it is going onto.
         *
         * <p>Not a refusal. The label still prints, and a mid-shift job is not the place to start
         * rejecting geometry that has been printing all week — that judgement belongs to
         * {@code /preflight}, before the picklist starts.
         *
         * <p>Once, because a clamp is a property of the profile and the loaded roll, not of the
         * job: the Flipkart invoice is a 4x10in strip on a 4x6 label by design, so warning per
         * print would be fifty identical lines a picklist and would bury the one that changed.
         * Repeated when the message changes, which is what a re-loaded roll or an edited margin
         * looks like from here.
         */
        private void noteClamping(Job job, ResolvedPage page) {
            if (page.fits()) {
                lastClamp = null;
                return;
            }
            String summary = String.join("; ", page.toText());
            if (summary.equals(lastClamp)) {
                return;
            }
            lastClamp = summary;
            Log.warn("document job " + job.id() + " geometry was clamped on '" + printer
                    + "' (further identical clamps on this printer are not repeated): " + summary);
        }

        private String kb(Job job) {
            return String.format(Locale.ROOT, "%.1fKB", job.payload().length / 1024.0);
        }
    }

    private static PrintRequestAttributeSet attributes(Job job, PrintOptions o, int pages) {
        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        attrs.add(new JobName("printly " + job.id(), null));
        if (job.copies() > 1) {
            // Left to the driver, as it was before this lane rasterised, rather than printing the
            // page N times here. UNVERIFIED ON HARDWARE: a driver that ignores Copies alongside
            // setPrintable would produce one label instead of N. Worth one physical check —
            // but do not "fix" it by looping without confirming, or every driver that does
            // honour this attribute starts double-printing.
            attrs.add(new Copies(job.copies()));
        }
        if (o.colorType() != null) {
            attrs.add(o.colorType() == PrintOptions.ColorType.COLOR
                    ? Chromaticity.COLOR
                    : Chromaticity.MONOCHROME);
        }
        PageRanges range = pageRanges(o, pages);
        if (range != null) {
            attrs.add(range);
        }
        return attrs;
    }

    /** Resolve the caller's 1-based range against the document, including an open upper bound. */
    private static PageRanges pageRanges(PrintOptions o, int pages) {
        String spec = o.pageRange();
        if (spec == null) {
            return null;
        }
        int dash = spec.indexOf('-');
        int from;
        int to;
        if (dash < 0) {
            from = Integer.parseInt(spec);
            to = from;
        } else {
            from = Integer.parseInt(spec.substring(0, dash));
            String tail = spec.substring(dash + 1);
            to = tail.isEmpty() ? pages : Integer.parseInt(tail);
        }
        if (from < 1 || from > pages) {
            throw new IllegalArgumentException("pageRange '" + spec + "' starts outside this "
                    + pages + "-page document");
        }
        to = Math.min(to, pages);
        if (to < from) {
            throw new IllegalArgumentException("pageRange '" + spec + "' ends before it starts");
        }
        return new PageRanges(from, to);
    }

    // ------------------------------------------------------------------ startup

    /**
     * Render a throwaway page in the background so the first real job does not pay for it.
     *
     * <p>PDFBox scans every installed font the first time it renders and writes a disk cache. On a
     * Windows machine with a large font set that is routinely tens of seconds. Paid here it is
     * invisible; paid on the first label of the shift it overruns the caller's {@code ?wait=} and
     * returns a 202, which reads as success to a caller that only checks the HTTP status.
     *
     * <p>TODO: this warms PDFBox and nothing else. The print path has its own first-call cost —
     * ~212ms for the process's first {@link PrinterJob#getPrinterJob} and ~93ms for its first
     * {@code setPrintService} — which the first label of the shift still pays. Building a context
     * per configured printer here would move it off the critical path.
     */
    private void warmUp() {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (PDDocument doc = new PDDocument()) {
                    PDPage page = new PDPage(new PDRectangle(288, 432));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, 12);
                        cs.newLineAtOffset(10, 400);
                        cs.showText("warm");
                        cs.endText();
                    }
                    doc.save(buf);
                }
                try (PDDocument doc = PDDocument.load(buf.toByteArray())) {
                    new PDFRenderer(doc).renderImageWithDPI(0, 72);
                }
                Log.info("document lane warm (" + (System.currentTimeMillis() - t0) + "ms)");
            } catch (Exception e) {
                Log.warn("document lane warm-up failed; the first PDF may be slow: " + e);
            }
        }, "document-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * PDFBox logs its font-cache build and every substituted glyph at INFO through
     * commons-logging. Useful once, noise on every job, and it lands on a stderr the packaged
     * Windows build discards anyway. Warnings and errors still come through.
     */
    private static void quietenPdfBox() {
        Logger.getLogger("org.apache.pdfbox").setLevel(Level.WARNING);
    }

    public void shutdown() {
        running = false;
        for (Lane lane : lanes.values()) {
            lane.stop();
        }
    }
}
