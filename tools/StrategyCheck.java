package com.jagdushah.printly;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asserts the routing engine against known-good answers. No hardware, no printers, no paper.
 *
 * <p>The companion to {@code GeometryCheck}, and the same idea: the parts of this service where a
 * silent wrong answer is expensive get a check that can run on any machine and exits non-zero when
 * it drifts. Geometry decides where ink lands; a strategy decides <em>which printer it lands on and
 * which pages go there</em>, and getting that wrong across a two-hundred-file run is the more
 * expensive of the two to discover late.
 *
 * <p>Run it after touching {@link PageSelection}, {@link PdfSplitter} or {@link Strategy}:
 *
 * <pre>
 *   javac -d build/check -cp "build/classes;lib/*" tools/StrategyCheck.java
 *   java  -cp "build/check;build/classes;lib/*" com.jagdushah.printly.StrategyCheck
 * </pre>
 */
public final class StrategyCheck {

    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        selections();
        splitting();
        strategies();
        options();

        System.out.println();
        if (failures == 0) {
            System.out.println("OK - " + checks + " checks passed");
            return;
        }
        System.out.println("FAILED - " + failures + " of " + checks + " checks");
        System.exit(1);
    }

    // ------------------------------------------------------------------ page selections

    private static void selections() {
        section("page selections against a 6-page document");

        pages("all", "all pages", 6, "1,2,3,4,5,6");
        pages("odd", "manual duplex, front sides", 6, "1,3,5");
        pages("even", "manual duplex, back sides", 6, "2,4,6");
        pages("first", "the courier label", 6, "1");
        pages("last", "the last page", 6, "6");
        pages("2-", "everything after the label", 6, "2,3,4,5,6");
        pages("-3", "the start to page 3", 6, "1,2,3");
        pages("2-4", "a middle range", 6, "2,3,4");
        pages("1,4-5", "a page and a range, unioned", 6, "1,4,5");
        pages("every:3", "every third page, from the first", 6, "1,4");
        pages("every:3+2", "every third page, from the second", 6, "2,5");
        pages("odd,last", "two terms overlapping", 6, "1,3,5,6");
        pages("4-99", "an end past the document is clamped", 6, "4,5,6");

        section("selections that match nothing rather than failing");
        // A rule that does not apply to a short document is a fact about the document, not an
        // error in the rule. This is what lets one strategy cover a one-page label and a
        // three-page invoice without the operator maintaining two.
        pages("2-", "a split rule meeting a 1-page file", 1, "");
        pages("even", "even pages of a 1-page file", 1, "");
        pages("9", "a page that is not there", 6, "");

        section("reversed order");
        expect("even reversed is the order a face-up tray needs",
                PageSelection.parse("even", "reverse").resolve(6).toString(), "[6, 4, 2]");
        expect("all reversed",
                PageSelection.parse("all", "reverse").resolve(3).toString(), "[3, 2, 1]");
        expect("reversed all is not 'everything', so it still rewrites the PDF",
                String.valueOf(PageSelection.parse("all", "reverse").isEverything()), "false");
        expect("plain all is 'everything', so the re-encode is skipped",
                String.valueOf(PageSelection.parse("all", null).isEverything()), "true");
        expect("an absent spec means everything",
                String.valueOf(PageSelection.parse(null, null).isEverything()), "true");

        section("specs that must be refused at the editor, not at the printer");
        rejects("every:0", "a step of zero would loop forever");
        rejects("9-3", "a range that ends before it starts");
        rejects("0", "pages are 1-based");
        rejects("0-4", "a range starting at zero");
        rejects("tuesday", "a word that is not a keyword");
        rejects("1,,3", "an empty term");
        rejects("-", "a bare dash");

        section("the summary an operator reads");
        expect("describe names the count it resolved to",
                PageSelection.parse("odd", null).describe(6), "odd (3 of 6 pages)");
        expect("describe says when it is reversed",
                PageSelection.parse("even", "reverse").describe(6), "even (3 of 6 pages, reversed)");
    }

    private static void pages(String spec, String why, int pageCount, String expected) {
        List<Integer> resolved = PageSelection.parse(spec, null).resolve(pageCount);
        StringBuilder sb = new StringBuilder();
        for (int p : resolved) {
            sb.append(sb.length() == 0 ? "" : ",").append(p);
        }
        expect("'" + spec + "' - " + why, sb.toString(), expected);
    }

    private static void rejects(String spec, String why) {
        checks++;
        try {
            PageSelection.parse(spec, null);
            failures++;
            System.out.println("  FAIL  '" + spec + "' was accepted; " + why);
        } catch (IllegalArgumentException e) {
            System.out.println("  ok    '" + spec + "' refused - " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ splitting

    private static void splitting() throws Exception {
        section("rewriting a PDF down to the selected pages");
        byte[] six = pdf(6);

        PdfSplitter.Info info = PdfSplitter.inspect(six);
        expect("inspect reads the page count", String.valueOf(info.pageCount()), "6");
        expect("inspect reads the page size", info.widthPt() + "x" + info.heightPt(), "288.0x432.0");
        expect("a taller-than-wide page is not landscape", String.valueOf(info.landscape()), "false");

        PdfSplitter.Applied odd = PdfSplitter.apply(six, PageSelection.parse("odd", null));
        expect("odd pages produce a 3-page document", String.valueOf(count(odd.pdf())), "3");
        expect("odd pages report what they came from", String.valueOf(odd.sourcePages()), "6");
        expect("odd pages were rewritten", String.valueOf(odd.rewritten()), "true");
        expect("the pages are the odd ones", odd.pages().toString(), "[1, 3, 5]");
        expect("page 1 of the result is page 1 of the source", textOf(odd.pdf(), 0), "PAGE 1");
        expect("page 2 of the result is page 3 of the source", textOf(odd.pdf(), 1), "PAGE 3");

        PdfSplitter.Applied reversed = PdfSplitter.apply(six, PageSelection.parse("even", "reverse"));
        expect("even reversed starts at page 6", textOf(reversed.pdf(), 0), "PAGE 6");
        expect("even reversed ends at page 2", textOf(reversed.pdf(), 2), "PAGE 2");

        PdfSplitter.Applied whole = PdfSplitter.apply(six, PageSelection.ALL);
        // The identity check matters: it is what keeps the hot path free of a re-encode, and the
        // packing flow's every-page invoices take it on every order.
        expect("selecting everything hands back the very same array",
                String.valueOf(whole.pdf() == six), "true");
        expect("selecting everything reports that nothing was rewritten",
                String.valueOf(whole.rewritten()), "false");

        checks++;
        try {
            PdfSplitter.apply(pdf(1), PageSelection.parse("2-", null));
            failures++;
            System.out.println("  FAIL  a selection matching no page should be refused");
        } catch (IllegalArgumentException e) {
            System.out.println("  ok    a selection matching no page is refused - " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ strategies

    private static void strategies() {
        section("resolving a strategy into jobs");
        List<Map<String, Object>> presets = List.of(
                preset("p_label", "Label 4x6", 4, 6),
                preset("p_invoice", "Invoice 4x10", 4, 10));

        Map<String, Object> split = strategy("Label left, invoice right",
                rule("Label", "first", "normal", "thermal-1", "p_label", 1),
                rule("Invoice", "2-", "normal", "office-1", "p_invoice", 1));

        Strategy.Plan threePages = Strategy.plan(split, 3, null, null, presets);
        expect("a 3-page order makes two jobs", String.valueOf(threePages.steps().size()), "2");
        expect("the label goes to the thermal printer", threePages.steps().get(0).printer(), "thermal-1");
        expect("the invoice goes to the office printer", threePages.steps().get(1).printer(), "office-1");
        expect("the invoice is pages 2 and 3",
                threePages.steps().get(1).selection().resolve(3).toString(), "[2, 3]");
        expect("each step carries its own preset's geometry",
                String.valueOf(Json.obj(threePages.steps().get(1).options().get("size")).get("height")),
                "10.0");
        expect("the step's summary is what the panel shows",
                threePages.steps().get(0).describe(),
                "Label → thermal-1 · first (1 of 3 pages) · Label 4x6");

        Strategy.Plan onePage = Strategy.plan(split, 1, null, null, presets);
        expect("a 1-page order makes one job", String.valueOf(onePage.steps().size()), "1");
        expect("and says why the second rule did not run",
                String.valueOf(onePage.skipped().size()), "1");

        section("the fallback printer");
        Map<String, Object> unassigned = strategy("Manual duplex",
                rule("Front", "odd", "normal", "", "", 1),
                rule("Back", "even", "reverse", "", "", 1));
        Strategy.Plan fallback = Strategy.plan(unassigned, 4, "chosen-at-run-time", "p_label", presets);
        expect("a rule naming no printer uses the one chosen when it runs",
                fallback.steps().get(0).printer(), "chosen-at-run-time");
        expect("both passes go to the same printer, which is what manual duplex needs",
                fallback.steps().get(1).printer(), "chosen-at-run-time");
        expect("the back pass is reversed",
                fallback.steps().get(1).selection().resolve(4).toString(), "[4, 2]");
        expect("the fallback preset is used too",
                fallback.steps().get(0).presetName(), "Label 4x6");

        section("strategies that must be refused before anything prints");
        refuses("a rule with no printer and nothing to fall back to",
                () -> Strategy.plan(unassigned, 4, null, null, presets));
        refuses("a rule pointing at a preset that was deleted",
                () -> Strategy.plan(strategy("Broken", rule("R", "all", "normal", "p1", "p_gone", 1)),
                        2, null, null, presets));
        refuses("a strategy with no rules at all",
                () -> Strategy.plan(strategy("Empty"), 2, "p1", null, presets));
        refuses("a document no rule matches",
                () -> Strategy.plan(strategy("Late pages", rule("R", "5-", "normal", "p1", "", 1)),
                        2, null, null, presets));
        refuses("a rule asking for more copies than the limit",
                () -> Strategy.plan(strategy("Too many", rule("R", "all", "normal", "p1", "", 5000)),
                        2, null, null, presets));

        section("a disabled rule is skipped without complaint");
        Map<String, Object> half = strategy("Half off",
                rule("On", "odd", "normal", "p1", "", 1),
                rule("Off", "even", "normal", "p1", "", 1));
        Json.obj(Json.arr(half, "rules").get(1)).put("enabled", false);
        expect("only the enabled rule produces a job",
                String.valueOf(Strategy.plan(half, 6, null, null, presets).steps().size()), "1");
    }

    // ------------------------------------------------------------------ options plumbing

    private static void options() {
        section("the selection reaching the print path");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("pages", "odd");
        raw.put("pageOrder", "reverse");
        PrintOptions options = PrintOptions.from(raw);
        expect("a selection makes the options non-empty", String.valueOf(options.isEmpty()), "false");
        expect("the selection survives the parse", options.pages().spec(), "odd");
        expect("so does the order", String.valueOf(options.pages().isReversed()), "true");

        // The reason withoutPages exists: once the router has rewritten the document, a reprint
        // must not apply "odd" a second time and print half of half.
        expect("spending the selection clears it",
                String.valueOf(options.withoutPages().pages()), "null");
        expect("and leaves nothing else behind to print",
                String.valueOf(options.withoutPages().isEmpty()), "true");

        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("size", Map.of("width", 4, "height", 6, "units", "in"));
        geometry.put("density", 203);
        geometry.put("pages", "even");
        PrintOptions kept = PrintOptions.from(geometry).withoutPages();
        expect("spending the selection keeps the geometry", String.valueOf(kept.widthPt()), "288.0");
        expect("and keeps the density", String.valueOf(kept.density()), "203.0");

        Map<String, Object> everything = new LinkedHashMap<>();
        everything.put("pages", "all");
        expect("'all' is dropped rather than carried, so nothing is re-encoded for it",
                String.valueOf(PrintOptions.from(everything).pages()), "null");

        checks++;
        try {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("pageOrder", "sideways");
            PrintOptions.from(bad);
            failures++;
            System.out.println("  FAIL  an unknown pageOrder should be refused");
        } catch (IllegalArgumentException e) {
            System.out.println("  ok    an unknown pageOrder is refused - " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void section(String title) {
        System.out.println();
        System.out.println("-- " + title);
    }

    private static void expect(String what, String actual, String expected) {
        checks++;
        if (expected.equals(actual)) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what);
            System.out.println("          expected: " + expected);
            System.out.println("          actual:   " + actual);
        }
    }

    private static void refuses(String what, Runnable work) {
        checks++;
        try {
            work.run();
            failures++;
            System.out.println("  FAIL  " + what + " was accepted");
        } catch (IllegalArgumentException e) {
            System.out.println("  ok    " + what + " - " + e.getMessage());
        }
    }

    private static Map<String, Object> preset(String id, String name, double w, double h) {
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("width", w);
        size.put("height", h);
        size.put("units", "in");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("size", size);
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", id);
        preset.put("name", name);
        preset.put("options", options);
        return preset;
    }

    @SafeVarargs
    private static Map<String, Object> strategy(String name, Map<String, Object>... rules) {
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("name", name);
        strategy.put("rules", new ArrayList<Object>(List.of(rules)));
        return strategy;
    }

    private static Map<String, Object> rule(String label, String pages, String order,
            String printer, String presetId, int copies) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("label", label);
        rule.put("pages", pages);
        rule.put("pageOrder", order);
        rule.put("printer", printer);
        rule.put("presetId", presetId);
        rule.put("copies", copies);
        rule.put("enabled", true);
        return rule;
    }

    /** A PDF whose every page says which page it is, so a reordering shows up in the text. */
    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(new PDRectangle(288, 432));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 36);
                    cs.newLineAtOffset(40, 340);
                    cs.showText("PAGE " + i);
                    cs.endText();
                }
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            doc.save(buf);
            return buf.toByteArray();
        }
    }

    private static int count(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * The text on one page of a PDF, as the reordering check needs it.
     *
     * <p>Extracted rather than compared byte-for-byte: a rewritten PDF is not byte-identical to its
     * source even for an unchanged page, and the question being asked is "is page 6 first", which is
     * about content rather than encoding.
     */
    private static String textOf(byte[] pdf, int pageIndex) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(doc).trim();
        }
    }
}
