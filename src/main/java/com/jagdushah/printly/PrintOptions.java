package com.jagdushah.printly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paper geometry for a document job, as sent by the frontend on {@code POST /print}.
 *
 * <p>This mirrors the option object QZ Tray took via {@code qz.configs.create(...)}, because the
 * callers' values were calibrated against physical output under QZ and have to survive the move
 * unchanged. A "tidier" number here is a mis-placed barcode that fails at the courier's scanner
 * rather than on this machine.
 *
 * <p>Deliberately knows nothing about PDFBox or {@code java.awt.print}: this is the request model,
 * and turning it into a {@code PageFormat} is {@link DocumentLane}'s job. Everything is normalised
 * to points (1/72") on the way in, since that is the only unit Java's printing API speaks.
 *
 * <p>Unset is meaningful and is not the same as a default. The invoice profiles deliberately send
 * no {@code orientation} so the driver's own default applies, so every field here distinguishes
 * "absent" from "zero".
 */
public final class PrintOptions {

    /** What the caller may put in {@code orientation}; absent means "leave it to the driver". */
    public enum Orientation {
        PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE
    }

    /** What the caller may put in {@code colorType}. */
    public enum ColorType {
        COLOR, GRAYSCALE, BLACKWHITE
    }

    /** What the caller may put in {@code scale}. Mapped onto PDFBox's Scaling at print time. */
    public enum Scale {
        /** Print at the PDF's own size, no scaling. */
        ACTUAL,
        /** Shrink to fit if oversized, but never enlarge. */
        FIT,
        /** Scale in either direction to fill the page. */
        FIT_TO_PAGE
    }

    /** Nothing specified — the printer's own defaults apply end to end. */
    public static final PrintOptions NONE = new PrintOptions();

    private double widthPt;
    private double heightPt;
    private boolean hasSize;
    private boolean sizeIsSheet;

    private double marginTopPt;
    private double marginRightPt;
    private double marginBottomPt;
    private double marginLeftPt;
    private boolean hasMargins;

    private Orientation orientation;
    private ColorType colorType;
    private Scale scale;
    private double density;
    private String pageRange;

    /** The raw units string, kept only so {@link #toJson()} can echo what the caller sent. */
    private String units = "in";

    private PrintOptions() {
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Read an options object off a {@code /print} body.
     *
     * <p>Throws {@link IllegalArgumentException} on anything it cannot make sense of rather than
     * quietly falling back. A typo in a hardware-calibrated number must fail loudly at the API,
     * not silently print a label the scanner will reject.
     *
     * @param raw the {@code options} value from the request, or null when absent
     */
    public static PrintOptions from(Object raw) {
        if (raw == null) {
            return NONE;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("must be an object");
        }
        Map<String, Object> m = Json.obj(raw);
        if (m.isEmpty()) {
            return NONE;
        }
        PrintOptions o = new PrintOptions();

        // "units" sits inside "size" for the packing profiles, but beside it in the older label
        // dialogs (`size: {w,h}, units: "mm"`). Accept both — those dialogs migrate next.
        Map<String, Object> size = Json.obj(m.get("size"));
        String unitName = Json.str(size, "units", Json.str(m, "units", "in"));
        double perUnit = pointsPerUnit(unitName);
        o.units = unitName.trim().toLowerCase(Locale.ROOT);

        if (!size.isEmpty()) {
            double w = Json.dbl(size, "width", -1);
            double h = Json.dbl(size, "height", -1);
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException(
                        "\"size\" needs a positive \"width\" and \"height\" (got " + w + "x" + h + ")");
            }
            o.widthPt = w * perUnit;
            o.heightPt = h * perUnit;
            // 200in is well past any real media and catches a unit mix-up, e.g. 100 "in"
            // where 100 mm was meant.
            if (o.widthPt > 14400 || o.heightPt > 14400) {
                throw new IllegalArgumentException("\"size\" of " + w + "x" + h + " " + unitName
                        + " is larger than any real media — check \"units\"");
            }
            o.hasSize = true;
        }

        // How "size" is to be read. Default "printable" keeps the driver's own stock as the sheet
        // and treats size as a rectangle drawn on it; "sheet" replaces the sheet with size, which
        // is what QZ Tray did unconditionally (see ResolvedPage.resolve). Only a profile calibrated
        // against QZ's output should ask for it: a sheet larger than the loaded media makes a
        // thermal printer keep feeding, which is how one order came out over three labels.
        String sizeMeans = Json.str(m, "sizeMeans", null);
        if (sizeMeans != null && !sizeMeans.isBlank()) {
            o.sizeIsSheet = switch (sizeMeans.trim().toLowerCase(Locale.ROOT)) {
                case "sheet", "paper", "media" -> true;
                case "printable", "imageable", "area" -> false;
                default -> throw new IllegalArgumentException("unknown \"sizeMeans\" '" + sizeMeans
                        + "' (use printable or sheet)");
            };
            if (o.sizeIsSheet && !o.hasSize) {
                throw new IllegalArgumentException(
                        "\"sizeMeans\":\"sheet\" needs a \"size\" to make the sheet from");
            }
        }

        Map<String, Object> margins = Json.obj(m.get("margins"));
        if (!margins.isEmpty()) {
            o.marginTopPt = margin(margins, "top", perUnit);
            o.marginRightPt = margin(margins, "right", perUnit);
            o.marginBottomPt = margin(margins, "bottom", perUnit);
            o.marginLeftPt = margin(margins, "left", perUnit);
            o.hasMargins = true;
            // Only checkable when a size came along; otherwise the driver's paper decides.
            if (o.hasSize) {
                if (o.marginLeftPt + o.marginRightPt >= o.widthPt) {
                    throw new IllegalArgumentException("left+right margins leave no printable width");
                }
                if (o.marginTopPt + o.marginBottomPt >= o.heightPt) {
                    throw new IllegalArgumentException("top+bottom margins leave no printable height");
                }
            }
        }

        String orientation = Json.str(m, "orientation", null);
        if (orientation != null && !orientation.isBlank()) {
            o.orientation = switch (orientation.trim().toLowerCase(Locale.ROOT)) {
                case "portrait" -> Orientation.PORTRAIT;
                case "landscape" -> Orientation.LANDSCAPE;
                case "reverse-landscape", "reverse_landscape" -> Orientation.REVERSE_LANDSCAPE;
                default -> throw new IllegalArgumentException("unknown \"orientation\" '" + orientation
                        + "' (use portrait, landscape or reverse-landscape)");
            };
        }

        String color = Json.str(m, "colorType", null);
        if (color != null && !color.isBlank()) {
            o.colorType = switch (color.trim().toLowerCase(Locale.ROOT)) {
                case "color", "colour" -> ColorType.COLOR;
                case "grayscale", "greyscale", "gray", "grey" -> ColorType.GRAYSCALE;
                case "blackwhite", "black-white", "monochrome", "mono" -> ColorType.BLACKWHITE;
                default -> throw new IllegalArgumentException("unknown \"colorType\" '" + color
                        + "' (use color, grayscale or blackwhite)");
            };
        }

        String scale = Json.str(m, "scale", null);
        if (scale != null && !scale.isBlank()) {
            o.scale = switch (scale.trim().toLowerCase(Locale.ROOT)) {
                case "actual", "actual-size", "none" -> Scale.ACTUAL;
                case "fit", "shrink-to-fit" -> Scale.FIT;
                case "fit-to-page", "fit_to_page", "stretch-to-fit" -> Scale.FIT_TO_PAGE;
                default -> throw new IllegalArgumentException("unknown \"scale\" '" + scale
                        + "' (use actual, fit or fit-to-page)");
            };
        }

        double density = Json.dbl(m, "density", 0);
        if (density != 0) {
            // 0 is "unset". Anything outside this is a typo, not a printer.
            if (density < 25 || density > 2400) {
                throw new IllegalArgumentException("\"density\" of " + density
                        + " dpi is out of range (25-2400)");
            }
            o.density = density;
        }

        String pageRange = Json.str(m, "pageRange", null);
        if (pageRange != null && !pageRange.isBlank()) {
            String cleaned = pageRange.replace(" ", "");
            // One 1-based range, open upper bound allowed: "1", "2-4", "2-". Deliberately
            // not a comma-separated list — PrinterJob honours only the first range it is
            // given, so accepting "1,4-5" would quietly print page 1 alone.
            if (!cleaned.matches("\\d+(-\\d*)?")) {
                throw new IllegalArgumentException("\"pageRange\" '" + pageRange
                        + "' is not a single page range like '1', '2-4' or '2-'");
            }
            o.pageRange = cleaned;
        }

        return o;
    }

    private static double margin(Map<String, Object> m, String side, double perUnit) {
        double v = Json.dbl(m, side, 0);
        if (v < 0) {
            throw new IllegalArgumentException("margin \"" + side + "\" cannot be negative");
        }
        return v * perUnit;
    }

    private static double pointsPerUnit(String units) {
        return switch (units.trim().toLowerCase(Locale.ROOT)) {
            case "in", "inch", "inches" -> 72.0;
            case "mm" -> 72.0 / 25.4;
            case "cm" -> 72.0 / 2.54;
            case "pt", "point", "points" -> 1.0;
            default -> throw new IllegalArgumentException("unknown \"units\" '" + units
                    + "' (use in, mm, cm or pt)");
        };
    }

    // ------------------------------------------------------------------ accessors

    /** True when nothing at all was specified, so the lane can skip building a page format. */
    public boolean isEmpty() {
        return !hasSize && !hasMargins && orientation == null && colorType == null
                && scale == null && density == 0 && pageRange == null;
    }

    public boolean hasSize() {
        return hasSize;
    }

    /**
     * True when {@code size} is the sheet rather than a rectangle drawn on the driver's sheet.
     *
     * <p>QZ Tray's behaviour, opted into per profile. It stops the geometry being clamped to the
     * loaded media at all, which is the only way to reproduce a profile calibrated under QZ — and
     * also the way to make a thermal printer feed stock it does not have, so it is off by default
     * and stays off for anything that has not been checked against real output.
     */
    public boolean sizeIsSheet() {
        return sizeIsSheet;
    }

    public double widthPt() {
        return widthPt;
    }

    public double heightPt() {
        return heightPt;
    }

    public boolean hasMargins() {
        return hasMargins;
    }

    public double marginTopPt() {
        return marginTopPt;
    }

    public double marginRightPt() {
        return marginRightPt;
    }

    public double marginBottomPt() {
        return marginBottomPt;
    }

    public double marginLeftPt() {
        return marginLeftPt;
    }

    /** Null means the caller left it to the driver, which the invoice profiles do on purpose. */
    public Orientation orientation() {
        return orientation;
    }

    public ColorType colorType() {
        return colorType;
    }

    public Scale scale() {
        return scale;
    }

    /** Rasterisation dpi, or 0 when unset. */
    public double density() {
        return density;
    }

    /** A 1-based page list such as {@code "1"} or {@code "1,3-5"}, or null for every page. */
    public String pageRange() {
        return pageRange;
    }

    // ------------------------------------------------------------------ reporting

    /**
     * A compact echo for {@code GET /jobs/{id}}, holding only what was actually set.
     *
     * <p>Worth the few bytes: "the label came out wrong" is answered by seeing exactly which
     * geometry the bridge believed it was given.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (hasSize) {
            m.put("widthPt", round(widthPt));
            m.put("heightPt", round(heightPt));
            m.put("units", units);
            m.put("sizeMeans", sizeIsSheet ? "sheet" : "printable");
        }
        if (hasMargins) {
            m.put("marginsPt", List.of(round(marginTopPt), round(marginRightPt),
                    round(marginBottomPt), round(marginLeftPt)));
        }
        if (orientation != null) {
            m.put("orientation", wire(orientation.name()));
        }
        if (colorType != null) {
            m.put("colorType", wire(colorType.name()));
        }
        if (scale != null) {
            m.put("scale", wire(scale.name()));
        }
        if (density != 0) {
            m.put("density", round(density));
        }
        if (pageRange != null) {
            m.put("pageRange", pageRange);
        }
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Enum names back to the hyphenated spelling the caller sent, so the echo is recognisable. */
    private static String wire(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public String toString() {
        return isEmpty() ? "PrintOptions{}" : "PrintOptions" + Json.write(toJson());
    }
}
