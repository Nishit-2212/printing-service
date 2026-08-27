package com.jagdushah.printly;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.PrinterException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.PDFPrintable;

/**
 * The print path, rendered onto a raster instead of a print head.
 *
 * <p>Not a mock-up and deliberately not a second implementation. It takes the {@link ResolvedPage}
 * the lane composed and the same {@link PDFPrintable} the lane builds — same {@code Scaling}, same
 * {@code center=false}, same rasterisation dpi — and calls {@link PDFPrintable#print} with a
 * {@link Graphics2D} backed by a {@link BufferedImage}. Java2D does not know or care whether the
 * surface underneath it is a driver or an image, which is the whole reason this is honest: a
 * divergence between preview and paper would have to be a divergence inside PDFBox or the driver,
 * not between two copies of our own geometry.
 *
 * <h2>Why the image is the sheet, not the page</h2>
 *
 * <p>A {@code Printable} draws into <em>page</em> space, which on a landscape page is the media
 * turned on its side. Handing that straight back would show the operator a correct picture of the
 * wrong thing: they are holding a label that comes off the roll one way up, and the question they
 * are answering is whether the barcode is inside the printable area of <em>that</em>.
 *
 * <p>So the content is drawn through the inverse of the orientation transform, back onto the sheet
 * as it feeds. The mapping is derived from {@link PageFormat}'s own published contract rather than
 * guessed: for a landscape page {@code getImageableX()} is defined as
 * {@code paperHeight - (paperImageableY + paperImageableHeight)} and {@code getImageableY()} as
 * {@code paperImageableX}, which pins page space to sheet space exactly. See
 * {@link #mediaTransform}.
 *
 * <h2>What the overlay adds</h2>
 *
 * <p>Three things the paper itself cannot show: the edge of the sheet, the rectangle the geometry
 * resolved to, and — the one that cost an afternoon — the strip the head physically cannot reach.
 * On a TSC TE244 that is 1.25mm down one edge of the roll, and because the invoice profiles print
 * landscape it lands at the top of the rotated page, which is why the invoice came back with its
 * top millimetre shaved off and nothing in any log said so.
 */
public final class PagePreview {

    /** Screen-ish by default: legible in a dialog without making the response enormous. */
    public static final double DEFAULT_DPI = 96;

    /** Below this the label is unreadable; above it the base64 costs more than it tells anyone. */
    private static final double MIN_DPI = 24;
    private static final double MAX_DPI = 600;

    /** ~24MB as an int raster. A 4x6in label at 600dpi is 8.6M pixels, so this is the real cap. */
    private static final long MAX_PIXELS = 12_000_000L;

    private static final Color SHEET_EDGE = new Color(0x99, 0x9F, 0xA8);
    private static final Color IMAGEABLE_EDGE = new Color(0x25, 0x63, 0xEB);
    private static final Color UNREACHABLE_FILL = new Color(0xDC, 0x26, 0x26, 0x40);
    private static final Color UNREACHABLE_EDGE = new Color(0xDC, 0x26, 0x26, 0xC0);

    private PagePreview() {
    }

    /**
     * Render one page of a document exactly as it would be printed.
     *
     * @param page       the composed page, from {@link ResolvedPage#resolve}
     * @param doc        the PDF; the very bytes that would have been printed
     * @param o          the caller's options, read here only for scaling, density and colour
     * @param pageIndex  0-based
     * @param dpi        preview resolution, clamped to a sane band
     * @param overlay    whether to draw the sheet, imageable and unreachable guides on top
     * @return a JSON-ready block: base64 PNG plus the pixel geometry needed to map a click on the
     *         image back to a point on the sheet
     */
    public static Map<String, Object> render(ResolvedPage page, PDDocument doc, PrintOptions o,
            int pageIndex, double dpi, boolean overlay) throws PrinterException, IOException {
        if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
            throw new IllegalArgumentException("page " + (pageIndex + 1) + " is outside this "
                    + doc.getNumberOfPages() + "-page document");
        }
        double resolution = resolution(dpi, page);
        double scale = resolution / 72.0;
        int widthPx = (int) Math.max(1, Math.round(page.sheetWidthPt() * scale));
        int heightPx = (int) Math.max(1, Math.round(page.sheetHeightPt() * scale));

        BufferedImage sheet = drawContent(page, doc, o, pageIndex, widthPx, heightPx, scale);
        sheet = applyColorType(sheet, o);
        if (overlay) {
            drawGuides(sheet, page, scale);
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(sheet, "png", png);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mime", "image/png");
        m.put("encoding", "base64");
        m.put("widthPx", widthPx);
        m.put("heightPx", heightPx);
        m.put("dpi", Math.round(resolution * 100.0) / 100.0);
        m.put("pageIndex", pageIndex);
        m.put("pageCount", doc.getNumberOfPages());
        m.put("overlay", overlay);
        m.put("data", Base64.getEncoder().encodeToString(png.toByteArray()));
        return m;
    }

    /**
     * The one call that has to match the print path, and does so by being the same call.
     *
     * <p>The clip is not decoration: {@code RasterPrinterJob} clips the {@code Graphics} it hands
     * a {@code Printable} to the imageable rectangle, so anything drawn outside is dropped on
     * paper. Leaving it off here would show artwork that never prints — which is exactly the
     * class of lie a preview exists to stop telling.
     */
    private static BufferedImage drawContent(ResolvedPage page, PDDocument doc, PrintOptions o,
            int pageIndex, int widthPx, int heightPx, double scale) throws PrinterException {
        BufferedImage image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, widthPx, heightPx);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Points on the sheet from here down, so every number below is one a tape measure
            // against the actual label would read.
            g.scale(scale, scale);

            PageFormat pf = page.pageFormat();
            Graphics2D content = (Graphics2D) g.create();
            try {
                content.transform(mediaTransform(pf));
                content.clip(new Rectangle2D.Double(pf.getImageableX(), pf.getImageableY(),
                        pf.getImageableWidth(), pf.getImageableHeight()));
                new PDFPrintable(doc, ResolvedPage.scaling(o), false, (float) o.density(), false)
                        .print(content, pf, pageIndex);
            } finally {
                content.dispose();
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Page space back onto the sheet.
     *
     * <p>Read straight off {@link PageFormat}'s definition of {@code getImageableX/Y} per
     * orientation, which is the only description of this mapping that the driver also honours.
     * For landscape a page point {@code (ux, uy)} is sheet point {@code (uy, paperHeight - ux)};
     * for reverse landscape it is {@code (paperWidth - uy, ux)}. Portrait is the identity, which
     * is why this was invisible until an invoice printed sideways.
     */
    private static AffineTransform mediaTransform(PageFormat pf) {
        Paper paper = pf.getPaper();
        return switch (pf.getOrientation()) {
            case PageFormat.LANDSCAPE ->
                    new AffineTransform(0, -1, 1, 0, 0, paper.getHeight());
            case PageFormat.REVERSE_LANDSCAPE ->
                    new AffineTransform(0, 1, -1, 0, paper.getWidth(), 0);
            default -> new AffineTransform();
        };
    }

    /**
     * Approximate what {@code Chromaticity} does at the driver.
     *
     * <p>Approximate, and said so in the response: {@code MONOCHROME} on a real driver is that
     * driver's own halftoning, which nothing here can reproduce. What this does reproduce is the
     * part that actually catches people out — a colour that carries information on screen and
     * comes out as an indistinguishable grey on a thermal head.
     */
    private static BufferedImage applyColorType(BufferedImage source, PrintOptions o) {
        if (o.colorType() == null || o.colorType() == PrintOptions.ColorType.COLOR) {
            return source;
        }
        int type = o.colorType() == PrintOptions.ColorType.BLACKWHITE
                ? BufferedImage.TYPE_BYTE_BINARY
                : BufferedImage.TYPE_BYTE_GRAY;
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        // Back to RGB so the guides below can be drawn in colour on top of a monochrome page.
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = rgb.createGraphics();
        try {
            g2.drawImage(out, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return rgb;
    }

    /**
     * The three lines the paper cannot draw on itself.
     *
     * <p>Drawn in sheet points and stroked at a width that survives the scale, so the guides stay
     * hairline-thin at 600dpi instead of swelling into the label.
     */
    private static void drawGuides(BufferedImage image, ResolvedPage page, double scale) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(scale, scale);
            float hairline = (float) (1.0 / scale);

            Rectangle2D sheet = new Rectangle2D.Double(0, 0,
                    page.sheetWidthPt(), page.sheetHeightPt());

            // Everything the head cannot mark, as an area rather than four rectangles, so a
            // driver that insets on only one edge does not produce three empty bands.
            Rectangle2D head = page.headArea();
            if (head != null) {
                Area dead = new Area(sheet);
                dead.subtract(new Area(head));
                if (!dead.isEmpty()) {
                    g.setColor(UNREACHABLE_FILL);
                    g.fill(dead);
                    g.setColor(UNREACHABLE_EDGE);
                    g.setStroke(new BasicStroke(hairline));
                    g.draw(dead);
                }
            }

            g.setColor(IMAGEABLE_EDGE);
            g.setStroke(new BasicStroke(hairline * 1.5f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, new float[] { hairline * 6f, hairline * 4f }, 0f));
            g.draw(page.imageableOnSheet());

            g.setColor(SHEET_EDGE);
            g.setStroke(new BasicStroke(hairline));
            g.draw(new Rectangle2D.Double(hairline / 2, hairline / 2,
                    page.sheetWidthPt() - hairline, page.sheetHeightPt() - hairline));
        } finally {
            g.dispose();
        }
    }

    /**
     * Clamp the requested dpi, then clamp it again against the sheet.
     *
     * <p>The second clamp is what stops a 10in profile at 600dpi from allocating a raster the
     * bridge has no business allocating on a warehouse PC. Silently returning a smaller image is
     * fine here in a way it never is on the print path: the response reports the dpi it actually
     * used, and a preview at 300dpi instead of 600 answers the same question.
     */
    private static double resolution(double requested, ResolvedPage page) {
        double dpi = requested <= 0 ? DEFAULT_DPI : Math.max(MIN_DPI, Math.min(MAX_DPI, requested));
        double inches = (page.sheetWidthPt() / 72.0) * (page.sheetHeightPt() / 72.0);
        if (inches <= 0) {
            return dpi;
        }
        double maxDpi = Math.sqrt(MAX_PIXELS / inches);
        return Math.max(MIN_DPI, Math.min(dpi, maxDpi));
    }
}
