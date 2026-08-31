package com.jagdushah.printly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Which pages of a document a rule applies to, and in what order they are sent.
 *
 * <p>This is what makes a routing strategy possible. {@link PrintOptions#pageRange()} already
 * carried one contiguous range, deliberately limited because it becomes a {@code PageRanges}
 * attribute and {@code PrinterJob} honours only the first range it is handed. That limit is fine
 * for "print page 1 of the FirstCry invoice" and useless for "odd pages to the left printer, even
 * pages to the right one", which is the whole point of a strategy.
 *
 * <p>So a selection is resolved to an explicit page list here and carried out by rewriting the
 * PDF ({@link PdfSplitter}) rather than by asking the driver. That is the same decision the pack
 * page made in its own {@code pages.js}: trimming the bytes cannot be ignored by anything
 * downstream, whereas an attribute alongside a {@code Printable} may or may not be honoured, and
 * finding out which costs a label and a courier bag.
 *
 * <p>Grammar &mdash; a comma-separated list of terms, unioned:
 *
 * <pre>
 *   all                every page (also the empty spec)
 *   odd / even         1,3,5... / 2,4,6...  - manual duplex on a single-sided printer
 *   first / last       the first / last page
 *   4                  one page
 *   2-5                an inclusive range
 *   3-                 page 3 to the end
 *   -3                 the start to page 3
 *   every:3            every third page from the first
 *   every:3+2          every third page starting at page 2
 * </pre>
 *
 * <p>A term naming a page past the end contributes nothing rather than failing: "5-" against a
 * three-page invoice is a rule that does not apply to this document, not a mistake in the rule. A
 * spec that resolves to nothing at all is reported as empty and the caller decides &mdash; a
 * strategy skips that rule, a direct {@code /print} refuses, because a print of nothing is a
 * caller error rather than a document that happens to be short.
 */
public final class PageSelection {

    /** Every page, in document order. What an absent spec means. */
    public static final PageSelection ALL = new PageSelection("all", false);

    private final String spec;
    private final boolean reverse;

    private PageSelection(String spec, boolean reverse) {
        this.spec = spec;
        this.reverse = reverse;
    }

    /**
     * Parse a spec, refusing anything it cannot make sense of.
     *
     * @param raw   the spec, or null/blank for every page
     * @param order {@code "reverse"} to send the selected pages back to front, else document order
     * @throws IllegalArgumentException on a term that is not in the grammar above
     */
    public static PageSelection parse(String raw, String order) {
        boolean reverse = order != null && order.trim().equalsIgnoreCase("reverse");
        String cleaned = raw == null ? "" : raw.replace(" ", "").toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty() || cleaned.equals("all") || cleaned.equals("*")) {
            return reverse ? new PageSelection("all", true) : ALL;
        }
        // Validate every term now rather than at print time. A strategy is edited once and run a
        // thousand times, so a typo that only surfaces on the four-hundredth order is the
        // expensive kind of mistake to allow through.
        PageSelection selection = new PageSelection(cleaned, reverse);
        for (String term : cleaned.split(",", -1)) {
            if (term.isEmpty()) {
                throw new IllegalArgumentException("empty term in page selection '" + raw + "'");
            }
            validate(term);
        }
        return selection;
    }

    private static void validate(String term) {
        if (term.equals("odd") || term.equals("even") || term.equals("first") || term.equals("last")) {
            return;
        }
        if (term.startsWith("every:")) {
            String rest = term.substring("every:".length());
            int plus = rest.indexOf('+');
            String step = plus < 0 ? rest : rest.substring(0, plus);
            String offset = plus < 0 ? "1" : rest.substring(plus + 1);
            if (!step.matches("\\d{1,4}") || !offset.matches("\\d{1,4}")
                    || Integer.parseInt(step) < 1 || Integer.parseInt(offset) < 1) {
                throw new IllegalArgumentException("'" + term
                        + "' is not a step like 'every:3' or 'every:3+2'");
            }
            return;
        }
        if (term.matches("\\d{1,7}")) {
            if (Integer.parseInt(term) < 1) {
                throw new IllegalArgumentException("pages are 1-based, so '" + term + "' is not a page");
            }
            return;
        }
        if (term.matches("\\d{0,7}-\\d{0,7}") && !term.equals("-")) {
            int dash = term.indexOf('-');
            String from = term.substring(0, dash);
            String to = term.substring(dash + 1);
            if (!from.isEmpty() && Integer.parseInt(from) < 1) {
                throw new IllegalArgumentException("pages are 1-based, so '" + term + "' is not a range");
            }
            if (!from.isEmpty() && !to.isEmpty() && Integer.parseInt(from) > Integer.parseInt(to)) {
                throw new IllegalArgumentException("range '" + term + "' ends before it starts");
            }
            return;
        }
        throw new IllegalArgumentException("'" + term + "' is not a page, a range like '2-5', "
                + "or one of odd, even, first, last, every:N");
    }

    /** True when this selects every page in document order, so a split can be skipped entirely. */
    public boolean isEverything() {
        return !reverse && spec.equals("all");
    }

    public boolean isReversed() {
        return reverse;
    }

    /** The spec as written, for echoing back into the UI and into a job record. */
    public String spec() {
        return spec;
    }

    /**
     * The 1-based pages this selects from a document of {@code pageCount} pages.
     *
     * <p>Ascending and duplicate-free, then reversed if the rule asked for it. Pages past the end
     * are dropped, so the list is always safe to hand to {@link PdfSplitter}.
     */
    public List<Integer> resolve(int pageCount) {
        LinkedHashSet<Integer> hit = new LinkedHashSet<>();
        if (spec.equals("all")) {
            for (int p = 1; p <= pageCount; p++) {
                hit.add(p);
            }
        } else {
            for (String term : spec.split(",", -1)) {
                add(term, pageCount, hit);
            }
        }
        List<Integer> pages = new ArrayList<>(hit);
        Collections.sort(pages);
        if (reverse) {
            Collections.reverse(pages);
        }
        return pages;
    }

    private static void add(String term, int pageCount, LinkedHashSet<Integer> into) {
        switch (term) {
            case "odd" -> {
                for (int p = 1; p <= pageCount; p += 2) {
                    into.add(p);
                }
                return;
            }
            case "even" -> {
                for (int p = 2; p <= pageCount; p += 2) {
                    into.add(p);
                }
                return;
            }
            case "first" -> {
                if (pageCount >= 1) {
                    into.add(1);
                }
                return;
            }
            case "last" -> {
                if (pageCount >= 1) {
                    into.add(pageCount);
                }
                return;
            }
            default -> {
                // a page, a range, or every:N — handled below
            }
        }
        if (term.startsWith("every:")) {
            String rest = term.substring("every:".length());
            int plus = rest.indexOf('+');
            int step = Integer.parseInt(plus < 0 ? rest : rest.substring(0, plus));
            int offset = plus < 0 ? 1 : Integer.parseInt(rest.substring(plus + 1));
            for (int p = offset; p <= pageCount; p += step) {
                into.add(p);
            }
            return;
        }
        if (term.indexOf('-') < 0) {
            int p = Integer.parseInt(term);
            if (p <= pageCount) {
                into.add(p);
            }
            return;
        }
        int dash = term.indexOf('-');
        String fromText = term.substring(0, dash);
        String toText = term.substring(dash + 1);
        int from = fromText.isEmpty() ? 1 : Integer.parseInt(fromText);
        int to = toText.isEmpty() ? pageCount : Math.min(Integer.parseInt(toText), pageCount);
        for (int p = Math.max(1, from); p <= to; p++) {
            into.add(p);
        }
    }

    /** A short human summary for a job record and the batch table, e.g. "odd (3 of 6 pages)". */
    public String describe(int pageCount) {
        int n = resolve(pageCount).size();
        String base = spec.equals("all") ? "all pages" : spec;
        String order = reverse ? ", reversed" : "";
        return base + " (" + n + " of " + pageCount + " page" + (pageCount == 1 ? "" : "s") + order + ")";
    }

    @Override
    public String toString() {
        return "PageSelection{" + spec + (reverse ? ",reverse" : "") + "}";
    }
}
