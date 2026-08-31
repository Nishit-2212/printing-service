package com.jagdushah.printly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A routing strategy: which pages of a document go to which printer, with which geometry.
 *
 * <p>This is the feature the Control Panel exists for. Everything else in the panel is a nicer
 * front for something the HTTP API already did; a strategy is genuinely new, because it is the
 * thing an operator cannot express by picking a printer and pressing Print:
 *
 * <ul>
 *   <li><b>Split by document part.</b> Page 1 is the courier label and belongs on the 4x6 thermal
 *       roll; the rest is the invoice and belongs on the office printer. One file, two printers,
 *       two sets of calibrated geometry, one action.</li>
 *   <li><b>Manual duplex.</b> Odd pages, then even pages reversed, on a printer with no duplexer —
 *       which is most thermal and most cheap office hardware.</li>
 *   <li><b>Fan-out.</b> The same pages to two printers, because the picklist needs a copy.</li>
 * </ul>
 *
 * <p>A strategy is stored as data ({@link Store#STRATEGIES}) and resolved here against one real
 * document. Resolution is deliberately total: it either produces a plan naming every job that will
 * be created, or it fails with the reason. Nothing is submitted while a rule is still ambiguous,
 * because a strategy runs over a whole batch and a half-understood rule is a hundred sheets of
 * wrong paper rather than one.
 */
public final class Strategy {

    /** One job a plan will create: a printer, a page selection, and the geometry to use. */
    public record Step(String label, String printer, PageSelection selection, int copies,
            Map<String, Object> options, String presetName, int pageCount) {

        /** What the panel shows in the plan preview, e.g. "Label → TSC TE244 · first (1 of 3 pages)". */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(label.isBlank() ? "Rule" : label).append(" → ").append(printer);
            sb.append(" · ").append(selection.describe(pageCount));
            if (copies > 1) {
                sb.append(" · ").append(copies).append(" copies");
            }
            if (presetName != null && !presetName.isBlank()) {
                sb.append(" · ").append(presetName);
            }
            return sb.toString();
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("printer", printer);
            m.put("pages", selection.spec());
            if (selection.isReversed()) {
                m.put("pageOrder", "reverse");
            }
            m.put("selected", selection.resolve(pageCount));
            m.put("copies", copies);
            if (presetName != null) {
                m.put("preset", presetName);
            }
            m.put("summary", describe());
            return m;
        }
    }

    /**
     * What a strategy does to one document.
     *
     * @param steps  the jobs to create, in order
     * @param skipped rules that matched no page of this document, with the reason, so the panel can
     *                say why a two-printer strategy produced one job for a one-page file
     */
    public record Plan(List<Step> steps, List<String> skipped) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (Step step : steps) {
                jobs.add(step.toJson());
            }
            m.put("steps", jobs);
            m.put("skipped", skipped);
            return m;
        }
    }

    private Strategy() {
    }

    /**
     * Resolve a strategy against one document.
     *
     * @param strategy      the stored strategy, or null for "everything to the fallback printer"
     * @param pageCount     how many pages the document has
     * @param fallbackPrinter the printer chosen in the UI, used by any rule that names none
     * @param fallbackPresetId the preset chosen in the UI, used by any rule that names none
     * @param presets       every stored preset, for looking up a rule's geometry
     * @throws IllegalArgumentException when a rule cannot be carried out — an unparseable page
     *         selection, a missing printer, a preset that no longer exists
     */
    public static Plan plan(Map<String, Object> strategy, int pageCount, String fallbackPrinter,
            String fallbackPresetId, List<Map<String, Object>> presets) {
        List<Map<String, Object>> rules = rulesOf(strategy);
        List<Step> steps = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        int index = 0;
        for (Map<String, Object> rule : rules) {
            index++;
            if (!Json.bool(rule, "enabled", true)) {
                continue;
            }
            String label = Json.str(rule, "label", "Rule " + index).trim();

            PageSelection selection;
            try {
                selection = PageSelection.parse(Json.str(rule, "pages", "all"),
                        Json.str(rule, "pageOrder", "normal"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rule '" + label + "': " + e.getMessage());
            }

            // A rule that matches nothing in *this* document is not an error — "pages 2-" against
            // a one-page Meesho label is a split strategy meeting a file that has no invoice. It is
            // reported so the panel can say so, because the operator's next question is always
            // "why did only one thing print".
            if (selection.resolve(pageCount).isEmpty()) {
                skipped.add(label + ": " + selection.spec() + " matches no page of a "
                        + pageCount + "-page document");
                continue;
            }

            String printer = Json.str(rule, "printer", "").trim();
            if (printer.isEmpty()) {
                printer = fallbackPrinter == null ? "" : fallbackPrinter.trim();
            }
            if (printer.isEmpty()) {
                throw new IllegalArgumentException("rule '" + label
                        + "' names no printer, and no printer was chosen to fall back to");
            }

            String presetId = Json.str(rule, "presetId", "").trim();
            if (presetId.isEmpty()) {
                presetId = fallbackPresetId == null ? "" : fallbackPresetId.trim();
            }
            Map<String, Object> preset = presetId.isEmpty() ? null : findPreset(presets, presetId);
            if (!presetId.isEmpty() && preset == null) {
                throw new IllegalArgumentException("rule '" + label + "' uses preset '" + presetId
                        + "', which no longer exists");
            }

            Map<String, Object> options = preset == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(Json.obj(preset.get("options")));
            // The selection travels inside the options because that is where the print path reads
            // it (PrintOptions.pages, spent by PrintRouter.submit). A preset must not be able to
            // carry its own selection past this point, or a rule's pages would silently lose to
            // whatever the preset happened to say.
            options.put("pages", selection.spec());
            options.put("pageOrder", selection.isReversed() ? "reverse" : "normal");

            int copies = (int) Json.num(rule, "copies", 0);
            if (copies < 1) {
                copies = preset == null ? 1 : Math.max(1, (int) Json.num(preset, "copies", 1));
            }
            if (copies > 1000) {
                throw new IllegalArgumentException("rule '" + label + "' asks for " + copies
                        + " copies; the limit is 1000");
            }

            // Parse the geometry now, against this document, rather than at submit time. A typo in
            // a preset then surfaces in the plan — before anything is queued — instead of as one
            // failed job in the middle of a batch that already printed forty.
            try {
                PrintOptions.from(options);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rule '" + label + "' has invalid geometry: "
                        + e.getMessage());
            }

            steps.add(new Step(label, printer, selection, copies, options,
                    preset == null ? null : Json.str(preset, "name", null), pageCount));
        }

        if (steps.isEmpty()) {
            String why = skipped.isEmpty()
                    ? "this strategy has no enabled rules"
                    : "no rule of this strategy matches a " + pageCount + "-page document";
            throw new IllegalArgumentException(why);
        }
        return new Plan(steps, skipped);
    }

    /** A strategy's rules, defaulting to "everything, once" when there is no strategy at all. */
    private static List<Map<String, Object>> rulesOf(Map<String, Object> strategy) {
        if (strategy == null) {
            Map<String, Object> everything = new LinkedHashMap<>();
            everything.put("label", "All pages");
            everything.put("pages", "all");
            return List.of(everything);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object raw : Json.arr(strategy, "rules")) {
            Map<String, Object> rule = Json.obj(raw);
            if (!rule.isEmpty()) {
                out.add(rule);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("strategy '" + Json.str(strategy, "name", "?")
                    + "' has no rules");
        }
        return out;
    }

    private static Map<String, Object> findPreset(List<Map<String, Object>> presets, String id) {
        for (Map<String, Object> preset : presets) {
            if (id.equals(Json.str(preset, "id", null))) {
                return preset;
            }
        }
        return null;
    }
}
