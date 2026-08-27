package com.jagdushah.printly;

import java.awt.geom.Rectangle2D;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * The calibrated geometry, checked against measured hardware numbers without the hardware.
 *
 * <p>Every case below is a real profile the packing flow sends, resolved against a driver page and
 * printable area measured off a real printer, with the imageable rectangle it must produce. These
 * are the numbers a wrong value in {@link ResolvedPage} moves, and moving one prints a barcode a
 * courier's scanner rejects — discovered a day later at the pickup desk, which is exactly the loop
 * this file exists to close.
 *
 * <p>In the package so it can reach {@link PrintOptions#from} and {@link ResolvedPage#resolve}
 * directly, and free of any test framework, because the build is a bare {@code javac} on purpose.
 *
 * <pre>
 * javac -cp "lib/pdfbox-2.0.37.jar;lib/fontbox-2.0.37.jar;lib/commons-logging-1.2.jar" \
 *       -d build/classes src/main/java/com/jagdushah/printly/*.java tools/GeometryCheck.java
 * java -cp "build/classes;lib/pdfbox-2.0.37.jar;lib/fontbox-2.0.37.jar;lib/commons-logging-1.2.jar" \
 *      com.jagdushah.printly.GeometryCheck
 * </pre>
 *
 * <p>Exits non-zero on the first mismatch.
 */
public final class GeometryCheck {

    /** A TSC TE244 loaded with a 4x6 roll: 4.098x6.000in sheet, head reaching 0.049in to 4.049in. */
    private static final double[] TSC_SHEET = { 4.098 * 72, 6.000 * 72 };
    private static final Rectangle2D TSC_HEAD =
            new Rectangle2D.Double(0.049 * 72, 0, 4.000 * 72, 6.000 * 72);

    private static int failures;

    public static void main(String[] args) throws Exception {
        // A landscape page, like the Flipkart and Meesho invoices, so the auto-detect has
        // something real to detect.
        PDDocument landscape = page(595, 455.7f);
        PDDocument portrait = page(215, 360.3f);

        // -- the calibrated split profiles, on the 4x6 roll every pack station runs --

        check("flipkart label", portrait,
                "{'size':{'width':4,'height':6,'units':'in'},"
                        + "'margins':{'top':0.1,'right':0,'bottom':0.1,'left':0.3},"
                        + "'orientation':'portrait'}",
                21.6, 7.2, 266.4, 417.6);

        // 4x10in of profile on a 6in roll: the height clamps to the media, and zero margins reach
        // 1.25mm past where the head can mark.
        check("flipkart invoice", landscape,
                "{'size':{'width':4,'height':10,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0}}",
                3.53, 0, 288, 432);

        // The regression this file was written for. Clamping before the orientation swap capped
        // the width at the sheet, the swap turned that cap into the height, and the second clamp
        // capped the width again — 255.5x255.5, a square, with two thirds of the label white.
        check("meesho label, as calibrated for a 6in roll", landscape,
                "{'size':{'width':6,'height':5.7,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0.5},"
                        + "'orientation':'landscape'}",
                36, 0, 255.55, 396);

        // Same profile with the left margin taken off, which is what a station on a 4in roll does
        // from the calibration screen. This is the whole printable area.
        check("meesho label, left margin cleared for a 4in roll", landscape,
                "{'size':{'width':6,'height':5.7,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0},"
                        + "'orientation':'landscape'}",
                3.53, 0, 288, 432);

        // The Meesho invoice, which used to carry zero margins and resolved to exactly the head's
        // own rectangle — x=3.5 y=0 288x432 against a head of x=3.5 y=0 288x432. Flush on every
        // edge, no room for the feed's registration tolerance, and it printed shaved along the
        // upper and left sides. The inset below is the head's 0.049in dead strip plus slack, and
        // the numbers here are what "there is room" looks like: an origin inside the head's start
        // and a rectangle that ends before the head's end.
        // The height matters as much as the origin here, and for a reason that is easy to miss:
        // this page has no orientation, so it is auto-detected landscape, and on a rotated page
        // PageFormat reports getImageableX() as paperHeight - (y + height). With height filling to
        // 428.4 that came to exactly 0 — the document's left-hand edge flush against the sheet's
        // bottom, which is what was still being shaved. 424.8 leaves 3.6pt there.
        check("meesho invoice, inset off the head's edge", landscape,
                "{'size':{'width':4,'height':10,'units':'in'},"
                        + "'margins':{'top':0.05,'right':0,'bottom':0.05,'left':0.1},"
                        + "'scale':'fit-to-page'}",
                7.2, 3.6, 280.8, 424.8);

        // The Meesho label as it actually ships: sizeMeans "sheet", which is how QZ read every
        // size. The sheet becomes 6x5.7in and the 4x6 roll is not consulted at all, so the
        // rectangle is 396x396 rather than the square the default reading clamps it to. This is
        // the number the develop branch prints with, and the reason this option exists.
        check("meesho label, QZ sheet semantics", landscape,
                "{'size':{'width':6,'height':5.7,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0.5},"
                        + "'orientation':'landscape','sizeMeans':'sheet'}",
                36, 0, 396, 396);

        // The guardrail on that option. A 4x10in invoice declared as a sheet is what makes a
        // thermal printer feed ten inches of a six-inch roll, so if anyone ever copies sizeMeans
        // onto an invoice profile this is the line that should make them stop and read.
        check("invoice as a sheet - ten inches of feed, never ship this", landscape,
                "{'size':{'width':4,'height':10,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0},"
                        + "'sizeMeans':'sheet'}",
                0, 0, 288, 720);

        // -- the wider rolls, where clamping once must agree with what clamping twice produced --

        // A 6.1x5.8in roll, the stock the Meesho profile was calibrated against. Still clamped,
        // because the 0.5in left margin pushes a 6in-wide profile past a 6.1in sheet — 36 + 410.4
        // is 446.4. Both the old double clamp and the single one land on 403.2, which is the point:
        // the reorder changed nothing here, only the case where the cap came from the other axis.
        Rectangle2D whole = new Rectangle2D.Double(0, 0, 6.1 * 72, 5.8 * 72);
        check("meesho label on the 6in roll it was calibrated for",
                landscape, pageFormat(6.1 * 72, 5.8 * 72), whole,
                "{'size':{'width':6,'height':5.7,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0.5},"
                        + "'orientation':'landscape'}",
                36, 0, 403.2, 396);

        // Genuine slack on every side, so nothing is clamped at all and the swap is the only thing
        // acting. This is the case that proves the reorder is a no-op on well-matched media.
        Rectangle2D roomy = new Rectangle2D.Double(0, 0, 8 * 72, 7 * 72);
        check("meesho label on a roll with room to spare",
                landscape, pageFormat(8 * 72, 7 * 72), roomy,
                "{'size':{'width':6,'height':5.7,'units':'in'},"
                        + "'margins':{'top':0,'right':0,'bottom':0,'left':0.5},"
                        + "'orientation':'landscape'}",
                36, 0, 410.4, 396);

        landscape.close();
        portrait.close();

        if (failures > 0) {
            System.err.println(failures + " geometry check(s) FAILED");
            System.exit(1);
        }
        System.out.println("all geometry checks passed");
    }

    private static void check(String name, PDDocument doc, String options,
            double x, double y, double w, double h) {
        check(name, doc, pageFormat(TSC_SHEET[0], TSC_SHEET[1]), TSC_HEAD, options, x, y, w, h);
    }

    private static void check(String name, PDDocument doc, PageFormat driverPage,
            Rectangle2D head, String options, double x, double y, double w, double h) {
        PrintOptions o = PrintOptions.from(Json.parse(options.replace('\'', '"')));
        ResolvedPage page = ResolvedPage.resolve(driverPage, head, o, doc);
        Rectangle2D got = page.imageableOnSheet();
        List<String> wrong = new ArrayList<>();
        if (!near(got.getX(), x)) {
            wrong.add(fmt("x", got.getX(), x));
        }
        if (!near(got.getY(), y)) {
            wrong.add(fmt("y", got.getY(), y));
        }
        if (!near(got.getWidth(), w)) {
            wrong.add(fmt("width", got.getWidth(), w));
        }
        if (!near(got.getHeight(), h)) {
            wrong.add(fmt("height", got.getHeight(), h));
        }
        if (wrong.isEmpty()) {
            System.out.printf(Locale.ROOT, "  ok    %-46s %.1f,%.1f %.1fx%.1f%n",
                    name, got.getX(), got.getY(), got.getWidth(), got.getHeight());
            return;
        }
        failures++;
        System.out.printf(Locale.ROOT, "  FAIL  %-46s %s%n", name, String.join("  ", wrong));
    }

    /** Half a point. Tighter than any real difference, looser than double rounding. */
    private static boolean near(double got, double want) {
        return Math.abs(got - want) < 0.5;
    }

    private static String fmt(String field, double got, double want) {
        return String.format(Locale.ROOT, "%s=%.2f want %.2f", field, got, want);
    }

    private static PageFormat pageFormat(double widthPt, double heightPt) {
        Paper paper = new Paper();
        paper.setSize(widthPt, heightPt);
        PageFormat pf = new PageFormat();
        pf.setPaper(paper);
        return pf;
    }

    private static PDDocument page(float w, float h) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(w, h)));
            doc.save(buf);
        }
        return PDDocument.load(buf.toByteArray());
    }

    private GeometryCheck() {
    }
}
