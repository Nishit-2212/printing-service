package com.jagdushah.printly;

import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
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
 */
public final class DocumentLane {

    /**
     * How long a printer enumeration is reused.
     *
     * <p>Enumerating printers on Windows costs 100-300ms and the print path would otherwise do it
     * twice per job — once to route, once to print. A newly attached printer takes up to this long
     * to appear, which is under the frontend's own poll interval.
     */
    private static final long LOOKUP_TTL_MS = 5000;

    private final ExecutorService pool;

    private volatile PrintService[] cached;
    private volatile long cachedAt;

    public DocumentLane(int threads) {
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "document-lane-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
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

    public void submit(Job job) {
        pool.execute(() -> {
            job.markPrinting();
            try {
                print(job);
                job.complete();
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.warn("document job " + job.id() + " failed: " + reason);
                job.fail(reason);
            }
        });
    }

    private void print(Job job) throws Exception {
        PrintService svc = find(job.printer());
        if (svc == null) {
            throw new PrintException("no OS printer named '" + job.printer() + "'");
        }

        long t0 = System.currentTimeMillis();
        try (PDDocument doc = PDDocument.load(job.payload())) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new PrintException("the PDF has no pages");
            }
            long renderMs = System.currentTimeMillis() - t0;

            PrintOptions options = job.options();
            PrinterJob pj = PrinterJob.getPrinterJob();
            pj.setPrintService(svc);
            pj.setJobName("printly " + job.id());
            pj.setPrintable(
                    new PDFPrintable(doc, scaling(options), false, (float) options.density()),
                    pageFormat(pj, options));

            long t1 = System.currentTimeMillis();
            pj.print(attributes(job, options, pages));
            job.timing(renderMs, System.currentTimeMillis() - t1);
        }
    }

    /**
     * Build the page geometry.
     *
     * <p>Starts from the driver's own default page so that a field the caller left out keeps the
     * driver's behaviour — the invoice profiles rely on that for orientation.
     *
     * <p>{@link PrinterJob#validatePage} is deliberately never called. It clamps a page to the
     * media the driver advertises, which would quietly turn a 4x6 label into Letter on any driver
     * whose stock list does not happen to include it.
     */
    private static PageFormat pageFormat(PrinterJob pj, PrintOptions o) {
        PageFormat pf = pj.defaultPage();
        if (!o.hasSize() && !o.hasMargins()) {
            applyOrientation(pf, o);
            return pf;
        }

        Paper paper = pf.getPaper();
        if (o.hasSize()) {
            paper.setSize(o.widthPt(), o.heightPt());
        }

        // Java gives every Paper a one-inch imageable margin on all four sides by default. Left
        // alone that crushes a 4x6 label into the middle of the media and puts the barcode
        // somewhere the courier's scanner will not find it, so the printable area is always set
        // explicitly — zero margins meaning full bleed, which is what a thermal label wants.
        double width = paper.getWidth();
        double height = paper.getHeight();
        double left = o.hasMargins() ? o.marginLeftPt() : 0;
        double top = o.hasMargins() ? o.marginTopPt() : 0;
        double right = o.hasMargins() ? o.marginRightPt() : 0;
        double bottom = o.hasMargins() ? o.marginBottomPt() : 0;

        // PrintOptions already checks margins against an explicit size. This catches the other
        // case: margins sent without a size, measured against whatever paper the driver reports.
        if (width - left - right <= 0 || height - top - bottom <= 0) {
            throw new IllegalArgumentException("margins leave no printable area on the "
                    + Math.round(width) + "x" + Math.round(height) + "pt page this printer reports");
        }

        paper.setImageableArea(left, top, width - left - right, height - top - bottom);
        pf.setPaper(paper);
        applyOrientation(pf, o);
        return pf;
    }

    private static void applyOrientation(PageFormat pf, PrintOptions o) {
        if (o.orientation() == null) {
            // Several invoice profiles send no orientation on purpose: the driver's own default
            // was what matched the printed output.
            return;
        }
        pf.setOrientation(switch (o.orientation()) {
            case PORTRAIT -> PageFormat.PORTRAIT;
            case LANDSCAPE -> PageFormat.LANDSCAPE;
            case REVERSE_LANDSCAPE -> PageFormat.REVERSE_LANDSCAPE;
        });
    }

    /**
     * Map the caller's scale onto PDFBox's.
     *
     * <p>UNVERIFIED AGAINST HARDWARE. This mirrors what QZ Tray's names meant, but the sizes the
     * packing flow sends were tuned by looking at real labels, so this mapping has to be confirmed
     * the same way — one physical print per platform and document type — before it is trusted.
     * {@code fit} is the interesting one: it is read here as "shrink if oversized, never enlarge",
     * and it is the only setting the Flipkart label profile uses.
     */
    private static Scaling scaling(PrintOptions o) {
        if (o.scale() == null) {
            return Scaling.SHRINK_TO_FIT; // PDFBox's own default
        }
        return switch (o.scale()) {
            case ACTUAL -> Scaling.ACTUAL_SIZE;
            case FIT -> Scaling.SHRINK_TO_FIT;
            case FIT_TO_PAGE -> Scaling.SCALE_TO_FIT;
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
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
