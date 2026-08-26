package com.jagdushah.printly;

import java.awt.geom.Rectangle2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
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
import java.util.concurrent.ConcurrentHashMap;
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
import org.apache.pdfbox.printing.Scaling;
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
        Lane lane = lanes.computeIfAbsent(
                job.printer().toLowerCase(Locale.ROOT), key -> new Lane(job.printer()));
        return lane.offer(job);
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
        private final BlockingQueue<Job> queue;
        private final Thread worker;

        /** Set from any thread by {@link #invalidate()}; cleared only by the worker. */
        private volatile boolean stale;

        // -- worker-confined state --
        private PrinterJob context;
        private PrintService boundTo;
        private PageFormat driverPage;
        private Rectangle2D printableArea;
        private boolean printableAreaResolved;

        Lane(String printer) {
            this.printer = printer;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.worker = new Thread(this::run, "document-" + printer);
            this.worker.setDaemon(true);
            this.worker.start();
            Log.info("document lane opened for '" + printer + "' ("
                    + laneCount.incrementAndGet() + " active)");
        }

        boolean offer(Job job) {
            return queue.offer(job);
        }

        void invalidate() {
            stale = true;
        }

        void stop() {
            worker.interrupt();
        }

        private void run() {
            while (running) {
                Job job;
                try {
                    job = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                job.markPrinting();
                try {
                    print(job);
                    job.complete();
                    Log.info("document job " + job.id() + " done: printer='" + printer + "' "
                            + kb(job) + " " + job.timingLine());
                } catch (Exception e) {
                    // A job that threw part-way through may have left the native print context in
                    // a state the next job would inherit. Cheaper to rebuild than to reason about.
                    context = null;
                    boundTo = null;
                    String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                    Log.warn("document job " + job.id() + " failed on '" + printer + "': " + reason);
                    job.fail(reason);
                }
            }
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
                // center=false pins the content to the top-left of the imageable area, which is
                // what QZ did: its PDFWrapper passes center=false explicitly. PDFBox's
                // four-argument constructor defaults it to true, and centring shifts the artwork
                // on any profile whose margins are asymmetric — the Flipkart label's left 0.3in
                // against right 0 is exactly that, and the shift lands on the barcode.
                pj.setPrintable(
                        new PDFPrintable(doc, scaling(options), false, (float) options.density(), false),
                        pageFormat(driverPage(), printableArea(), options, doc));
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

        private String kb(Job job) {
            return String.format(Locale.ROOT, "%.1fKB", job.payload().length / 1024.0);
        }
    }

    /**
     * Build the page geometry: the caller's rectangle, on the media the printer actually holds.
     *
     * <h2>The paper is the driver's, never the caller's</h2>
     *
     * <p>{@code size} sets the <em>printable rectangle</em>, not the sheet. An earlier version
     * read it as the sheet and called {@link Paper#setSize}, which is the one place this lane
     * diverged from QZ Tray badly enough to be visible on paper: the Flipkart invoice profile is
     * 4x10in, so a pack station with both printers pointed at its 4x6 label roll was asking the
     * TSC to feed ten inches, and one order came out over three labels instead of two.
     *
     * <p>QZ never overrode the sheet on the PDF path. It put the geometry into a
     * {@code MediaPrintableArea} and let {@link PrinterJob#getPageFormat} resolve it, which keeps
     * the driver's own stock as the paper — measured on a TSC TE244 configured for 4.10x6.00in:
     *
     * <pre>
     * getPageFormat(MediaPrintableArea 4x10in)  -> paper 4.10x6.00in, imageable 2.10x4.00in
     * getPageFormat(MediaPrintableArea 4x6in inset 0.3/0.1)
     *                                           -> paper 4.10x6.00in, imageable 3.70x5.80in
     * </pre>
     *
     * <p>The second line is the label profile, and it is exactly what this method computes, which
     * is why labels never diverged and only the invoices did.
     *
     * <h2>Oversized rectangles are clamped, not dropped</h2>
     *
     * <p>The first line above is the JDK dropping a rectangle that does not fit the media and
     * falling back to its own one-inch inset — it does that for anything without slack, an exact
     * 4x6 on 4.10x6.00 included. So QZ printed the invoice into a 2.10x4.00in box adrift in the
     * middle of the label. That is not worth reproducing: this lane clamps instead, so a 4x10in
     * invoice fills the 4x6 label rather than shrinking into the centre of it. Page count matches
     * QZ, legibility beats it.
     *
     * <h2>Clamped to what the head can actually mark</h2>
     *
     * <p>Not to the sheet — to the printable area the driver advertises for the loaded media,
     * which is usually smaller. The TSC TE244 reports {@code x=0.049in, y=0, 4.000x6.000in} on a
     * 4.098in-wide roll: a 1.25mm strip down the left edge its head cannot reach. Zero margins
     * clamped to the sheet put content into that strip, and because the invoice profiles print
     * landscape, the sheet's left edge is the top of the rotated page — so the invoice came out
     * with its top millimetre shaved off.
     *
     * <p>"Full bleed" therefore means as much of the sheet as the hardware can mark, which is the
     * only reading a printer can honour. A driver that advertises nothing falls back to the sheet.
     *
     * <p>{@link PrinterJob#validatePage} is still never called. Clamping to the advertised
     * printable area is exactly as much driver-conformance as is wanted; validatePage goes further
     * and snaps the whole page onto an advertised stock, which turns a 4x6 label into Letter on
     * any driver whose list does not happen to include it.
     *
     * @param driverPage the printer's own page, from {@link Lane#driverPage()}; its paper is the
     *                   loaded media and is what decides how far the printer feeds
     * @param printable  what the head can mark, in points on the sheet, or null if unadvertised
     * @param doc        needed because orientation can depend on it, see {@link #applyOrientation}
     */
    private static PageFormat pageFormat(PageFormat driverPage, Rectangle2D printable,
            PrintOptions o, PDDocument doc) {
        if (!o.hasSize() && !o.hasMargins()) {
            applyOrientation(driverPage, o, doc, false);
            return driverPage;
        }

        Paper paper = driverPage.getPaper();
        double pageWidth = paper.getWidth();
        double pageHeight = paper.getHeight();

        double left = o.hasMargins() ? o.marginLeftPt() : 0;
        double top = o.hasMargins() ? o.marginTopPt() : 0;
        double right = o.hasMargins() ? o.marginRightPt() : 0;
        double bottom = o.hasMargins() ? o.marginBottomPt() : 0;

        // Absent size means "the whole sheet", which is the only reading left once the sheet is
        // the driver's: margins alone then inset the media, as they always did.
        double width = (o.hasSize() ? o.widthPt() : pageWidth) - left - right;
        double height = (o.hasSize() ? o.heightPt() : pageHeight) - top - bottom;

        // PrintOptions already checks margins against an explicit size. This catches the other
        // case: margins sent without a size, measured against whatever paper the driver reports.
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("margins leave no printable area on the "
                    + Math.round(pageWidth) + "x" + Math.round(pageHeight)
                    + "pt page this printer reports");
        }

        // Java gives every Paper a one-inch imageable margin on all four sides by default. Left
        // alone that crushes a 4x6 label into the middle of the media and puts the barcode
        // somewhere the courier's scanner will not find it, so the printable area is always set
        // explicitly — zero margins meaning as much of the sheet as the head can mark.
        Paper sized = driverPage.getPaper();
        sized.setImageableArea(left, top, width, height);
        driverPage.setPaper(sized);
        clampImageable(driverPage, printable);

        applyOrientation(driverPage, o, doc, true);
        // Again after orientation: swapImageableArea transposes the rectangle, which can push a
        // landscape profile back off a sheet narrower than that profile is tall.
        clampImageable(driverPage, printable);
        return driverPage;
    }

    /**
     * Pull the printable rectangle back inside what the printer can mark.
     *
     * <p>Two jobs. Bounding it by the sheet is what makes a 4x10in invoice profile land on a 4x6
     * label instead of asking for ten inches of stock — hand a thermal printer a page taller than
     * its media and it simply keeps feeding. Bounding it by the advertised printable area is what
     * stops content being placed in a margin the head cannot reach, which is otherwise silently
     * shaved off the edge.
     *
     * @param printable the head's reach in points on the sheet, or null when unadvertised
     */
    private static void clampImageable(PageFormat pf, Rectangle2D printable) {
        Paper paper = pf.getPaper();
        double minX = 0;
        double minY = 0;
        double maxX = paper.getWidth();
        double maxY = paper.getHeight();
        if (printable != null) {
            // Intersect rather than replace: a driver that over-reports must not be able to grow
            // the area beyond the sheet the feed is measured against.
            minX = Math.max(minX, printable.getMinX());
            minY = Math.max(minY, printable.getMinY());
            maxX = Math.min(maxX, printable.getMaxX());
            maxY = Math.min(maxY, printable.getMaxY());
        }
        double x = clamp(paper.getImageableX(), minX, maxX);
        double y = clamp(paper.getImageableY(), minY, maxY);
        paper.setImageableArea(x, y,
                Math.min(paper.getImageableWidth(), maxX - x),
                Math.min(paper.getImageableHeight(), maxY - y));
        pf.setPaper(paper);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Set the page orientation the way QZ Tray did.
     *
     * <p>An explicit {@code orientation} always wins. When the caller sends none, QZ did
     * <em>not</em> fall through to the driver — it inspected the PDF and flipped the page to
     * landscape itself, in {@code PrintPDF.print}. An earlier version of this method read the
     * absent value as "leave it to the driver" and stated so in a comment, which is what silently
     * turned the Flipkart invoice upright: its page is 595x455.7pt, landscape, and QZ had been
     * rotating it onto the 4x10in strip all along.
     *
     * @param sized whether the caller's geometry was applied to the paper, which is the only case
     *              where the landscape swap below has a rectangle worth swapping
     */
    private static void applyOrientation(PageFormat pf, PrintOptions o, PDDocument doc, boolean sized) {
        if (o.orientation() == null) {
            autoLandscape(pf, doc);
            return;
        }
        pf.setOrientation(switch (o.orientation()) {
            case PORTRAIT -> PageFormat.PORTRAIT;
            case LANDSCAPE -> PageFormat.LANDSCAPE;
            case REVERSE_LANDSCAPE -> PageFormat.REVERSE_LANDSCAPE;
        });
        if (sized && o.orientation() != PrintOptions.Orientation.PORTRAIT) {
            swapImageableArea(pf);
        }
    }

    /**
     * QZ's auto-landscape detection, ported as-is:
     *
     * <pre>{@code
     * if ((page.getImageableHeight() > page.getImageableWidth()
     *         && bounds.getWidth() > bounds.getHeight())
     *         ^ (pd.getRotation() / 90) % 2 == 1) {
     *     page.setOrientation(LANDSCAPE);
     * }
     * }</pre>
     *
     * <p>The exclusive-or is what makes it read right: a landscape page on portrait paper needs
     * the flip, and so does a portrait page carrying {@code /Rotate 90}, but a landscape page that
     * is <em>also</em> quarter-turned is already upright and must be left alone. PDFBox normalises
     * {@code /Rotate} into [0,360), so the division cannot go negative here.
     *
     * <p>{@link PDPage#getBBox()} rather than the media box, again because that is what QZ read.
     *
     * <p>QZ evaluated this per page against one shared {@code PageFormat} and only ever latched
     * landscape on, never off. This lane binds a single format to the whole document through
     * {@link PrinterJob#setPrintable}, so it cannot vary per page at all; scanning until the first
     * page that asks for the flip is the same answer for every document the packing flow prints,
     * all of which are single-page by the time they arrive.
     */
    private static void autoLandscape(PageFormat pf, PDDocument doc) {
        boolean portraitPaper = pf.getImageableHeight() > pf.getImageableWidth();
        for (PDPage page : doc.getPages()) {
            PDRectangle bounds = page.getBBox();
            boolean landscapeSource = bounds.getWidth() > bounds.getHeight();
            boolean quarterTurned = (page.getRotation() / 90) % 2 == 1;
            if ((portraitPaper && landscapeSource) ^ quarterTurned) {
                pf.setOrientation(PageFormat.LANDSCAPE);
                return;
            }
        }
    }

    /**
     * Transpose the imageable rectangle, which QZ did for every non-portrait orientation on top of
     * {@code setOrientation}.
     *
     * <p>Java already reports a landscape page's imageable width and height swapped, so doing it
     * again on the paper looks redundant and is arguably a QZ bug — but the Meesho label
     * (6x5.7in) and the merged Flipkart/Meesho invoice (8.5x7.5in) were both calibrated on real
     * output with the swap in place, and undoing it silently transposes their printable area.
     *
     * <p>Not replicated: QZ applied this once per page of the document, so an even page count
     * swapped it back to where it started. Every profile that reaches here is single-page, which
     * is why nobody noticed; doing it once is the behaviour those labels were actually tuned
     * against.
     */
    private static void swapImageableArea(PageFormat pf) {
        Paper paper = pf.getPaper();
        paper.setImageableArea(paper.getImageableX(), paper.getImageableY(),
                paper.getImageableHeight(), paper.getImageableWidth());
        pf.setPaper(paper);
    }

    /**
     * Map the caller's scale onto PDFBox's.
     *
     * <p>QZ Tray had no {@code scale} option at all. It had a boolean {@code scaleContent},
     * defaulting to true, and consumed it in one line:
     *
     * <pre>{@code
     * Scaling scale = (pxlOpts.isScaleContent()? Scaling.SCALE_TO_FIT:Scaling.ACTUAL_SIZE);
     * }</pre>
     *
     * <p>So every {@code scale}, {@code fit-to-page} and {@code fitToPage} key the packing flow
     * ever sent QZ was discarded, and every PDF it printed was scaled to fill the page. The
     * calibrated sizes upstream were tuned against that, which is why the three names below
     * collapse onto two behaviours: anything that is not {@code actual} fills the page.
     *
     * <p>This costs the one thing QZ could not express either — a genuine shrink-only fit, which
     * would leave an undersized label at its own size instead of enlarging it. Reading {@code fit}
     * that way is what printed the Flipkart label small: its page is 215x360.3pt inside a
     * 266.4x417.6pt imageable area, so shrink-to-fit had nothing to shrink and QZ's ~1.16x
     * enlargement disappeared. Adding a fourth name for shrink-only is the way back, once
     * something actually wants it.
     */
    private static Scaling scaling(PrintOptions o) {
        if (o.scale() == null) {
            // QZ's default, not PDFBox's: scaleContent defaulted to true, so an options object
            // that says nothing about scale still filled the page.
            return Scaling.SCALE_TO_FIT;
        }
        return switch (o.scale()) {
            case ACTUAL -> Scaling.ACTUAL_SIZE;
            case FIT, FIT_TO_PAGE -> Scaling.SCALE_TO_FIT;
        };
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
