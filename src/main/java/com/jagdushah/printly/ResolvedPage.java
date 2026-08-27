package com.jagdushah.printly;

import java.awt.geom.Rectangle2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.Scaling;

/**
 * The page a {@link PrintOptions} actually resolved to on a given printer — and a record of how.
 *
 * <p>This is the composition step that used to live inside {@link DocumentLane} as a private
 * {@code pageFormat(...)} method. Nothing about the arithmetic changed in the move; what changed
 * is that the answer is now an object rather than a side effect, so three callers can share it:
 *
 * <ul>
 *   <li>{@link DocumentLane} builds one per print and hands its {@link #pageFormat()} to
 *       {@code PrinterJob.setPrintable} exactly as before;</li>
 *   <li>{@code POST /preview} renders through the same {@code PageFormat} into an image;</li>
 *   <li>{@code POST /preflight} builds one and prints nothing, which is how a profile can be
 *       checked against the loaded media without burning a label.</li>
 * </ul>
 *
 * <p>The notes are the other half of the point. Diagnosing a mis-placed invoice previously meant
 * reflecting into a private method to learn what page the service had built; the service knew the
 * sheet, the printable rectangle, the orientation after auto-detection and every clamp it applied,
 * and reported none of it. {@link #toJson()} is that knowledge, and it rides along on
 * {@code GET /jobs/{id}} so nobody reverse-engineers this again.
 *
 * <p>Instances are single-use and not thread-safe: {@link #pageFormat()} hands out the very
 * {@link PageFormat} that was composed, and {@link PageFormat} is mutable.
 */
public final class ResolvedPage {

    /** 1/72" to inches, for the human-readable half of every measurement. */
    private static final double PT_PER_IN = 72.0;

    /**
     * One thing the resolver did, in the order it did it.
     *
     * <p>{@code level} is {@code "info"} when the resolver merely recorded a decision and
     * {@code "warn"} when it changed the caller's numbers — a clamp is not an error (the label
     * still prints) but it does mean the geometry on paper is not the geometry that was asked
     * for, which is precisely the thing that costs an afternoon to work out from the output.
     *
     * @param bound which limit did the clamping: {@code "media"} for the sheet the printer feeds,
     *              {@code "head"} for the smaller rectangle the print head can actually mark
     */
    public record Note(String level, String code, String field, Double from, Double to,
            String bound, String message) {

        static Note info(String code, String message) {
            return new Note("info", code, null, null, null, null, message);
        }

        static Note clamp(String field, double from, double to, String bound) {
            return new Note("warn", "clamped", field, round(from), round(to), bound,
                    String.format(Locale.ROOT, "%s %.1f -> %.1f pt (%s)", field, from, to, bound));
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("code", code);
            if (field != null) {
                m.put("field", field);
            }
            if (from != null) {
                m.put("from", from);
            }
            if (to != null) {
                m.put("to", to);
            }
            if (bound != null) {
                m.put("bound", bound);
            }
            m.put("message", message);
            return m;
        }
    }

    private final PageFormat pageFormat;
    private final Paper paper;
    private final Rectangle2D head;
    private final String orientationSource;
    private final String scaling;
    private final Double scaleFactor;
    private final List<Note> notes;

    private ResolvedPage(PageFormat pageFormat, Rectangle2D head, String orientationSource,
            String scaling, Double scaleFactor, List<Note> notes) {
        this.pageFormat = pageFormat;
        this.paper = pageFormat.getPaper();
        this.head = head;
        this.orientationSource = orientationSource;
        this.scaling = scaling;
        this.scaleFactor = scaleFactor;
        this.notes = List.copyOf(notes);
    }

    // ------------------------------------------------------------------ resolving

    /**
     * Build the page geometry: the caller's rectangle, on the media the printer actually holds.
     *
     * <h2>The paper is the driver's, never the caller's — and this is a deliberate divergence</h2>
     *
     * <p>{@code size} sets the <em>printable rectangle</em>, not the sheet, unless
     * {@code sizeMeans: "sheet"} says otherwise. An earlier version read it as the sheet
     * unconditionally and called {@link Paper#setSize}, and the Flipkart invoice profile is 4x10in
     * — so a pack station with both printers pointed at its 4x6 label roll was asking the TSC to
     * feed ten inches, and one order came out over three labels instead of two.
     *
     * <p>An earlier version of this comment claimed QZ Tray never overrode the sheet, and that the
     * numbers below were what QZ resolved. <b>That was wrong.</b> Disassembling the installed
     * {@code qz-tray.jar} ({@code qz.printer.action.PrintPixel.applyDefaultSettings}) shows QZ
     * doing exactly what this lane refuses to do:
     *
     * <pre>{@code
     * if (size != null && size.getWidth() > 0 && size.getHeight() > 0) {
     *     w = size.getWidth(); h = size.getHeight();
     *     paper.setSize(w * convert, h * convert);          // the sheet becomes the caller's
     * }
     * if (margins != null) { x += left; y += top; w -= left + right; h -= top + bottom; }
     * if (w > 0 && h > 0) {
     *     attributes.add(new MediaPrintableArea(x, y, w, h, units));
     *     paper.setImageableArea(x * convert, y * convert, w * convert, h * convert);
     *     page.setPaper(paper);
     * }
     * }</pre>
     *
     * <p>So QZ never clamped to the loaded media at all: it declared the caller's size to be the
     * paper and left the driver to cope. That is why the 4x10in invoice fed three labels under QZ
     * too, and it is also why the Meesho label — a 6x5.7in profile on a 4x6 roll — looked right
     * under QZ and small here. QZ was not fitting it to the roll; it was telling the driver the
     * roll was 6x5.7in.
     *
     * <p>Keeping the driver's sheet is still the right default: it is what stops a thermal printer
     * feeding stock it does not have. But a profile calibrated against QZ's sheet-replacing
     * behaviour cannot be reproduced without it, so {@code sizeMeans: "sheet"} exists as an
     * explicit, per-profile opt back in. See {@link PrintOptions#sizeIsSheet()}.
     *
     * <p>For the record, measured on a TSC TE244 configured for 4.10x6.00in, this is what the
     * {@code MediaPrintableArea} QZ also attached resolves to on its own — the driver honours it
     * when it fits the media and silently drops it to a one-inch inset when it does not:
     *
     * <pre>
     * getPageFormat(MediaPrintableArea 4x10in)  -&gt; paper 4.10x6.00in, imageable 2.10x4.00in
     * getPageFormat(MediaPrintableArea 4x6in inset 0.3/0.1)
     *                                           -&gt; paper 4.10x6.00in, imageable 3.70x5.80in
     * </pre>
     *
     * <h2>Oversized rectangles are clamped, not dropped</h2>
     *
     * <p>The first line above is the JDK dropping a rectangle that does not fit the media and
     * falling back to its own one-inch inset — it does that for anything without slack, an exact
     * 4x6 on 4.10x6.00 included. So QZ printed the invoice into a 2.10x4.00in box adrift in the
     * middle of the label. That is not worth reproducing: this clamps instead, so a 4x10in
     * invoice fills the 4x6 label rather than shrinking into the centre of it. Page count matches
     * QZ, legibility beats it. Every clamp is recorded as a {@link Note}, which is what lets
     * {@code /preflight} answer "this profile will be cut down on this printer" before anything
     * prints.
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
     * <p>{@code PrinterJob.validatePage} is still never called. Clamping to the advertised
     * printable area is exactly as much driver-conformance as is wanted; validatePage goes further
     * and snaps the whole page onto an advertised stock, which turns a 4x6 label into Letter on
     * any driver whose list does not happen to include it.
     *
     * @param driverPage the printer's own page, freshly cloned; its paper is the loaded media and
     *                   is what decides how far the printer feeds. Mutated in place.
     * @param printable  what the head can mark, in points on the sheet, or null if unadvertised
     * @param doc        needed because orientation can depend on it, see {@link #applyOrientation}
     */
    public static ResolvedPage resolve(PageFormat driverPage, Rectangle2D printable,
            PrintOptions o, PDDocument doc) {
        List<Note> notes = new ArrayList<>();
        if (printable == null) {
            notes.add(Note.info("no-printable-area",
                    "the driver advertises no printable area; output is bounded by the sheet alone"));
        }

        String orientationSource;
        if (!o.hasSize() && !o.hasMargins()) {
            notes.add(Note.info("driver-geometry",
                    "no size or margins were sent, so the driver's own page is used unchanged"));
            orientationSource = applyOrientation(driverPage, o, doc, false, notes);
            return finish(driverPage, printable, orientationSource, o, doc, notes);
        }

        // QZ's sheet-replacing behaviour, opted into per profile. Done before anything is measured,
        // because from here down "the sheet" has to mean the caller's size for every calculation —
        // the margins inset it, the clamp bounds by it, and the preview draws it.
        if (o.sizeIsSheet() && o.hasSize()) {
            Paper sheet = driverPage.getPaper();
            double loadedWidth = sheet.getWidth();
            double loadedHeight = sheet.getHeight();
            sheet.setSize(o.widthPt(), o.heightPt());
            driverPage.setPaper(sheet);
            notes.add(Note.info("size-is-sheet", String.format(Locale.ROOT,
                    "\"sizeMeans\":\"sheet\" — the sheet was set to %.2fx%.2f in and the driver's own "
                            + "media ignored, as QZ Tray did. The printer will feed this length "
                            + "whether or not it is loaded.",
                    o.widthPt() / PT_PER_IN, o.heightPt() / PT_PER_IN)));

            // The one thing this whole class cannot answer, said out loud rather than left for a
            // preview to imply. Everything below composes a page on the declared sheet; the
            // printer then has to reconcile that page with the media actually loaded, and it does
            // that inside the driver — it may scale, it may clip, it may ignore the request
            // entirely. Nothing here observes which. A preview drawn from this page is therefore a
            // faithful picture of what was *sent* and only a guess at what comes *out*, and the
            // gap is exactly as wide as the difference below.
            if (Math.abs(loadedWidth - o.widthPt()) > 1 || Math.abs(loadedHeight - o.heightPt()) > 1) {
                notes.add(new Note("warn", "sheet-not-loaded", null, null, null, null,
                        String.format(Locale.ROOT,
                                "the declared sheet is %.2fx%.2f in but this printer is loaded with "
                                        + "%.2fx%.2f in — how the driver fits one onto the other is "
                                        + "not modelled here, so the preview shows the page as sent, "
                                        + "not as it will land on the label",
                                o.widthPt() / PT_PER_IN, o.heightPt() / PT_PER_IN,
                                loadedWidth / PT_PER_IN, loadedHeight / PT_PER_IN)));
            }
            // The head's reach was measured against the media this just replaced, so it no longer
            // describes anything. QZ did not clamp to it either; dropping it is what keeps the two
            // engines producing the same page rather than nearly the same one.
            printable = null;
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

        orientationSource = applyOrientation(driverPage, o, doc, true, notes);

        // What the trailing margins must keep clear of the media, not just of the caller's own
        // size. Subtracting them from `size` above is enough while the rectangle fits; the moment
        // it does not, the clamp below refills to the media edge and swallows them — which is how
        // a 4x10in invoice with a bottom margin ended up flush against the sheet again.
        //
        // Transposed when the rectangle was, because after swapImageableArea the caller's "bottom"
        // is the rectangle's right-hand side. Nothing swaps on the portrait and auto-landscape
        // paths, which is why the invoices read straight through.
        boolean swapped = o.orientation() != null
                && o.orientation() != PrintOptions.Orientation.PORTRAIT;
        double reserveRight = !o.hasMargins() ? 0
                : swapped ? o.marginBottomPt() : o.marginRightPt();
        double reserveBottom = !o.hasMargins() ? 0
                : swapped ? o.marginRightPt() : o.marginBottomPt();
        // Clamped once, and only after the orientation swap. It used to be clamped here and again
        // before the swap, which quietly capped every rotated profile to a square.
        //
        // Worked example, the Meesho label (6x5.7in, landscape) on a 4.10x6.00in roll. Clamping
        // first caps the width at the sheet's 288pt; swapImageableArea then transposes that cap
        // into the *height*, and the second clamp caps the new width at 288 again — so the result
        // is a 288x288 square whatever the profile asks for, and two thirds of the label is white.
        // No size and no margin can escape it, because the cap is the sheet width both times.
        //
        // Clamping after the swap instead lets the pre-swap height become the width and the
        // pre-swap width become the height, each bounded once: 288x432, the whole printable area.
        //
        // This is a no-op wherever the media already fits the profile, since nothing is clamped at
        // all then, and elsewhere it can only widen the rectangle — the remaining clamp still
        // bounds it by the sheet and the head. It is not a QZ behaviour either way: QZ handed the
        // geometry to getPageFormat and let the JDK drop it, which is the fallback this lane
        // exists to avoid.
        clampImageable(driverPage, printable, reserveRight, reserveBottom, notes);
        return finish(driverPage, printable, orientationSource, o, doc, notes);
    }

    private static ResolvedPage finish(PageFormat pf, Rectangle2D printable,
            String orientationSource, PrintOptions o, PDDocument doc, List<Note> notes) {
        Scaling s = scaling(o);
        Double factor = estimateScale(pf, doc, s);
        return new ResolvedPage(pf, printable, orientationSource, wire(s.name()), factor, notes);
    }

    /**
     * Pull the printable rectangle back inside what the printer can mark, recording every edge
     * it had to move.
     *
     * <p>Two jobs. Bounding it by the sheet is what makes a 4x10in invoice profile land on a 4x6
     * label instead of asking for ten inches of stock — hand a thermal printer a page taller than
     * its media and it simply keeps feeding. Bounding it by the advertised printable area is what
     * stops content being placed in a margin the head cannot reach, which is otherwise silently
     * shaved off the edge.
     *
     * @param printable the head's reach in points on the sheet, or null when unadvertised
     */
    private static void clampImageable(PageFormat pf, Rectangle2D printable, List<Note> notes) {
        clampImageable(pf, printable, 0, 0, notes);
    }

    /**
     * @param reserveRight  points to keep clear of the right-hand bound, so a trailing margin
     *                      survives a clamp instead of being refilled to the media edge
     * @param reserveBottom the same for the bottom bound
     */
    private static void clampImageable(PageFormat pf, Rectangle2D printable,
            double reserveRight, double reserveBottom, List<Note> notes) {
        Paper paper = pf.getPaper();
        double sheetW = paper.getWidth();
        double sheetH = paper.getHeight();
        double minX = 0;
        double minY = 0;
        double maxX = sheetW - Math.max(0, reserveRight);
        double maxY = sheetH - Math.max(0, reserveBottom);
        if (printable != null) {
            // Intersect rather than replace: a driver that over-reports must not be able to grow
            // the area beyond the sheet the feed is measured against.
            minX = Math.max(minX, printable.getMinX());
            minY = Math.max(minY, printable.getMinY());
            maxX = Math.min(maxX, printable.getMaxX());
            maxY = Math.min(maxY, printable.getMaxY());
        }
        double wasX = paper.getImageableX();
        double wasY = paper.getImageableY();
        double wasW = paper.getImageableWidth();
        double wasH = paper.getImageableHeight();

        double x = clamp(wasX, minX, maxX);
        double y = clamp(wasY, minY, maxY);
        double w = Math.min(wasW, maxX - x);
        double h = Math.min(wasH, maxY - y);
        paper.setImageableArea(x, y, w, h);
        pf.setPaper(paper);

        // Attribute each edge to whichever bound actually bit, because they have different fixes:
        // "media" means the profile asks for more stock than is loaded, "head" means it asks the
        // printer to mark where it physically cannot, and "margin" means the caller's own trailing
        // margin is what stopped it — which is the one case that is working as asked rather than
        // going wrong, and reads very differently in a report.
        double headMaxX = printable == null ? sheetW : Math.min(sheetW, printable.getMaxX());
        double headMaxY = printable == null ? sheetH : Math.min(sheetH, printable.getMaxY());
        note(notes, "x", wasX, x, printable != null && minX > 0 ? "head" : "media");
        note(notes, "y", wasY, y, printable != null && minY > 0 ? "head" : "media");
        note(notes, "width", wasW, w, trailingBound(reserveRight, maxX, headMaxX, sheetW));
        note(notes, "height", wasH, h, trailingBound(reserveBottom, maxY, headMaxY, sheetH));
    }

    /** Which of the three limits on a trailing edge is the one that actually stopped it. */
    private static String trailingBound(double reserve, double bound, double headBound,
            double sheetBound) {
        if (reserve > 0 && bound >= headBound - 0.05) {
            return "margin";
        }
        return headBound < sheetBound ? "head" : "media";
    }

    /** Sub-point moves are rounding, not clamping, and would only add noise to the report. */
    private static void note(List<Note> notes, String field, double from, double to, String bound) {
        if (Math.abs(from - to) > 0.05) {
            notes.add(Note.clamp(field, from, to, bound));
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Set the page orientation the way QZ Tray did.
     *
     * <p>An explicit {@code orientation} always wins. When the caller sends none, QZ did
     * <em>not</em> fall through to the driver — it inspected the PDF and flipped the page to
     * landscape itself, in {@code PrintPDF.print}. An earlier version read the absent value as
     * "leave it to the driver" and stated so in a comment, which is what silently turned the
     * Flipkart invoice upright: its page is 595x455.7pt, landscape, and QZ had been rotating it
     * onto the 4x10in strip all along.
     *
     * @param sized whether the caller's geometry was applied to the paper, which is the only case
     *              where the landscape swap below has a rectangle worth swapping
     * @return where the final orientation came from, for the report
     */
    private static String applyOrientation(PageFormat pf, PrintOptions o, PDDocument doc,
            boolean sized, List<Note> notes) {
        if (o.orientation() == null) {
            boolean flipped = autoLandscape(pf, doc);
            notes.add(Note.info("orientation", flipped
                    ? "no orientation was sent; the PDF's own page is landscape, so it was flipped"
                    : "no orientation was sent and the PDF needs no flip, so the driver's default stands"));
            return flipped ? "auto-detected" : "driver-default";
        }
        pf.setOrientation(switch (o.orientation()) {
            case PORTRAIT -> PageFormat.PORTRAIT;
            case LANDSCAPE -> PageFormat.LANDSCAPE;
            case REVERSE_LANDSCAPE -> PageFormat.REVERSE_LANDSCAPE;
        });
        if (sized && o.orientation() != PrintOptions.Orientation.PORTRAIT) {
            swapImageableArea(pf);
            notes.add(Note.info("swap", "the printable rectangle was transposed for "
                    + wire(o.orientation().name()) + ", as QZ Tray did"));
        }
        return "requested";
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
     * landscape on, never off. A single format is bound to the whole document through
     * {@code PrinterJob.setPrintable}, so it cannot vary per page at all; scanning until the first
     * page that asks for the flip is the same answer for every document the packing flow prints,
     * all of which are single-page by the time they arrive.
     *
     * @return whether the flip was applied, which is the only part the report cares about
     */
    private static boolean autoLandscape(PageFormat pf, PDDocument doc) {
        boolean portraitPaper = pf.getImageableHeight() > pf.getImageableWidth();
        for (PDPage page : doc.getPages()) {
            PDRectangle bounds = page.getBBox();
            boolean landscapeSource = bounds.getWidth() > bounds.getHeight();
            boolean quarterTurned = (page.getRotation() / 90) % 2 == 1;
            if ((portraitPaper && landscapeSource) ^ quarterTurned) {
                pf.setOrientation(PageFormat.LANDSCAPE);
                return true;
            }
        }
        return false;
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
    public static Scaling scaling(PrintOptions o) {
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

    /**
     * What PDFBox will multiply the artwork by, computed with PDFBox's own formula.
     *
     * <p>Derived rather than observed: {@code PDFPrintable} works this out privately inside
     * {@code print} and never exposes it, so this repeats the same two lines —
     * {@code min(imageableWidth / cropWidth, imageableHeight / cropHeight)} against the rotated
     * crop box — on page one. It is the number that answers "why did the label come out small",
     * and it is a report, not an input: nothing here feeds back into the print path, so a drift
     * between this and a future PDFBox shows up as a wrong diagnostic rather than a wrong label.
     *
     * <p>Page one only. Every document the packing flow prints is single-page by the time it
     * arrives, and one {@code PageFormat} is bound to all of them anyway.
     *
     * @return the factor, or null when there is no page to measure
     */
    private static Double estimateScale(PageFormat pf, PDDocument doc, Scaling scaling) {
        if (doc == null || doc.getNumberOfPages() == 0) {
            return null;
        }
        if (scaling == Scaling.ACTUAL_SIZE) {
            return 1.0;
        }
        PDRectangle crop = rotatedCropBox(doc.getPage(0));
        if (crop.getWidth() <= 0 || crop.getHeight() <= 0) {
            return null;
        }
        double sx = pf.getImageableWidth() / crop.getWidth();
        double sy = pf.getImageableHeight() / crop.getHeight();
        return round3(Math.min(sx, sy));
    }

    /** PDFBox's own {@code getRotatedCropBox}: a quarter-turned page measures transposed. */
    private static PDRectangle rotatedCropBox(PDPage page) {
        PDRectangle crop = page.getCropBox();
        int rotation = page.getRotation();
        if (rotation == 90 || rotation == 270) {
            return new PDRectangle(crop.getLowerLeftY(), crop.getLowerLeftX(),
                    crop.getHeight(), crop.getWidth());
        }
        return crop;
    }

    // ------------------------------------------------------------------ accessors

    /**
     * The composed page, ready for {@code PrinterJob.setPrintable}.
     *
     * <p>The live object, not a copy: this is what was measured, and handing back a clone would
     * make the report describe something other than what prints.
     */
    public PageFormat pageFormat() {
        return pageFormat;
    }

    /** The sheet the printer feeds, in points. Always the driver's media, never the caller's. */
    public double sheetWidthPt() {
        return paper.getWidth();
    }

    public double sheetHeightPt() {
        return paper.getHeight();
    }

    /** The printable rectangle in sheet coordinates, before the orientation transform. */
    public Rectangle2D imageableOnSheet() {
        return new Rectangle2D.Double(paper.getImageableX(), paper.getImageableY(),
                paper.getImageableWidth(), paper.getImageableHeight());
    }

    /** What the head can mark, in sheet coordinates, or null when the driver advertises none. */
    public Rectangle2D headArea() {
        return head;
    }

    public int orientation() {
        return pageFormat.getOrientation();
    }

    /** {@code requested}, {@code auto-detected} or {@code driver-default}. */
    public String orientationSource() {
        return orientationSource;
    }

    public List<Note> notes() {
        return notes;
    }

    /**
     * True when nothing had to be cut down — the geometry on paper is the geometry asked for.
     *
     * <p>This is what {@code /preflight} turns into a yes or no. A false does not mean the job
     * will fail; it means it will print something other than what the profile describes, which
     * is the failure that is expensive to notice.
     */
    public boolean fits() {
        return notes.stream().noneMatch(n -> !"info".equals(n.level()));
    }

    // ------------------------------------------------------------------ reporting

    /**
     * The whole resolution, for {@code GET /jobs/{id}} and the two diagnostic endpoints.
     *
     * <p>Both units on every measurement on purpose. Points are what Java speaks and what the
     * clamps are computed in; inches are what the profiles are written in and what a tape measure
     * against the actual label reads, and converting between them by hand at 3am is how a
     * calibration session goes wrong.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paper", rect(0, 0, sheetWidthPt(), sheetHeightPt()));
        m.put("imageable", rect(paper.getImageableX(), paper.getImageableY(),
                paper.getImageableWidth(), paper.getImageableHeight()));
        // What PDFBox is handed, which is the paper rectangle transposed on a rotated page. The
        // two differ for every landscape profile and only one of them explains the output.
        m.put("effective", rect(pageFormat.getImageableX(), pageFormat.getImageableY(),
                pageFormat.getImageableWidth(), pageFormat.getImageableHeight()));
        if (head != null) {
            m.put("head", rect(head.getX(), head.getY(), head.getWidth(), head.getHeight()));
        }
        Map<String, Object> orient = new LinkedHashMap<>();
        orient.put("value", orientationName(pageFormat.getOrientation()));
        orient.put("source", orientationSource);
        m.put("orientation", orient);
        m.put("scaling", scaling);
        if (scaleFactor != null) {
            m.put("scaleFactor", scaleFactor);
        }
        m.put("fits", fits());
        m.put("notes", notes.stream().map(Note::toJson).toList());
        return m;
    }

    private static Map<String, Object> rect(double x, double y, double w, double h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xPt", round(x));
        m.put("yPt", round(y));
        m.put("widthPt", round(w));
        m.put("heightPt", round(h));
        m.put("widthIn", round3(w / PT_PER_IN));
        m.put("heightIn", round3(h / PT_PER_IN));
        return m;
    }

    /**
     * The same numbers as a block a human reads without expanding JSON — the shape the bug report
     * that prompted all of this was written in.
     */
    public List<String> toText() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "paper        %.1f x %.1f pt   %.2f x %.2f in",
                sheetWidthPt(), sheetHeightPt(),
                sheetWidthPt() / PT_PER_IN, sheetHeightPt() / PT_PER_IN));
        lines.add(String.format(Locale.ROOT, "imageable    x=%.1f y=%.1f   %.1f x %.1f pt",
                paper.getImageableX(), paper.getImageableY(),
                paper.getImageableWidth(), paper.getImageableHeight()));
        lines.add("orientation  " + orientationName(pageFormat.getOrientation())
                + " (" + orientationSource + ")");
        lines.add("scaling      " + scaling + (scaleFactor == null ? ""
                : String.format(Locale.ROOT, "  x%.3f", scaleFactor)));
        for (Note n : notes) {
            if (!"info".equals(n.level())) {
                lines.add(n.code() + "      " + n.message());
            }
        }
        return lines;
    }

    private static String orientationName(int orientation) {
        return switch (orientation) {
            case PageFormat.LANDSCAPE -> "landscape";
            case PageFormat.REVERSE_LANDSCAPE -> "reverse-landscape";
            default -> "portrait";
        };
    }

    /** Enum names back to the hyphenated spelling the caller sends, so the echo is recognisable. */
    private static String wire(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    @Override
    public String toString() {
        return String.join(" | ", toText());
    }
}
