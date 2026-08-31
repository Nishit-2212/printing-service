package com.jagdushah.printly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the Control Panel remembers: presets, strategies, its own printers, its settings.
 *
 * <p>Flat JSON files in the user's data directory, one per collection, beside {@code config.json}.
 * The technical design offered SQLite or JSON and this takes JSON, for a reason that has nothing to
 * do with volumes: the service currently has exactly one third-party dependency and a build that is
 * a bare {@code javac} with no network access. Adding a JDBC driver to store four small lists would
 * cost that property, and these files are also the thing a warehouse admin ends up reading over
 * someone's shoulder on a phone call, which SQLite is not.
 *
 * <p><b>Why the panel's printers are not written into {@code config.json}.</b> That file is
 * hand-written, commented, and the record of how a station was set up — several of its comments are
 * the reason a number is what it is. A UI that rewrote it would drop every one of them the first
 * time an operator added a printer. So the panel owns {@code printers.json} and the two lists are
 * merged at startup, which also means "delete the file" is a complete undo of everything the UI
 * ever did to a station.
 *
 * <p>Writes are atomic: a temp file in the same directory, then a move. A half-written strategies
 * file that parses as nothing would silently reset an operator's rules, and they would find out by
 * printing a hundred labels on the wrong printer.
 */
public final class Store {

    public static final String PRESETS = "presets.json";
    public static final String STRATEGIES = "strategies.json";
    public static final String PRINTERS = "printers.json";
    private static final String SETTINGS = "panel.json";

    private final Path dir;
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis());
    private final Object lock = new Object();

    public Store(Path dir) {
        this.dir = dir;
    }

    /** Where these files live, so the panel can show it and the tray can open it. */
    public Path dir() {
        return dir;
    }

    // ------------------------------------------------------------------ collections

    /**
     * One collection, as a mutable copy.
     *
     * <p>Read from disk on every call rather than cached. These are a few kilobytes read at human
     * speed, and a cache would mean the file and the panel disagreeing after someone edited the
     * file by hand — which is a supported thing to do, and the whole reason it is JSON.
     */
    public List<Map<String, Object>> list(String collection) {
        synchronized (lock) {
            Path path = dir.resolve(collection);
            if (!Files.isRegularFile(path)) {
                return new ArrayList<>();
            }
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    return new ArrayList<>();
                }
                Object parsed = Json.parse(text);
                List<Map<String, Object>> out = new ArrayList<>();
                if (parsed instanceof List<?> items) {
                    for (Object item : items) {
                        Map<String, Object> m = Json.obj(item);
                        if (!m.isEmpty()) {
                            out.add(new LinkedHashMap<>(m));
                        }
                    }
                }
                return out;
            } catch (IOException | RuntimeException e) {
                // A corrupt file must not take the service down, and must not look like an empty
                // one either — that reads as "my presets are gone" with no explanation. Say so in
                // the log and keep the bad file where someone can look at it.
                Log.warn("could not read " + path + " (" + e + ") — treating it as empty");
                return new ArrayList<>();
            }
        }
    }

    /** Replace a whole collection. Callers that change one item read, edit, and write back. */
    public void save(String collection, List<Map<String, Object>> items) throws IOException {
        synchronized (lock) {
            write(dir.resolve(collection), Json.write(items));
        }
    }

    /** One item by id, or null. */
    public Map<String, Object> find(String collection, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Map<String, Object> item : list(collection)) {
            if (id.equals(Json.str(item, "id", null))) {
                return item;
            }
        }
        return null;
    }

    /**
     * Insert or replace an item, keyed by its {@code id}.
     *
     * <p>An item arriving without an id is new and gets one. An {@code isDefault} item clears the
     * flag on every sibling, because "two defaults" is a state the UI cannot render and the print
     * path would have to break a tie in silently.
     *
     * @return the stored item, including its id
     */
    public Map<String, Object> upsert(String collection, Map<String, Object> item) throws IOException {
        synchronized (lock) {
            List<Map<String, Object>> items = list(collection);
            Map<String, Object> stored = new LinkedHashMap<>(item);
            String id = Json.str(stored, "id", "").trim();
            if (id.isEmpty()) {
                id = nextId(collection);
                stored.put("id", id);
            }
            stored.put("updatedAt", System.currentTimeMillis());

            boolean isDefault = Json.bool(stored, "isDefault", false);
            int at = -1;
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> existing = items.get(i);
                if (id.equals(Json.str(existing, "id", null))) {
                    at = i;
                    // Carry the creation time forward: an edit is not a new preset, and the panel
                    // sorts on it.
                    stored.putIfAbsent("createdAt", existing.get("createdAt"));
                } else if (isDefault) {
                    existing.put("isDefault", false);
                }
            }
            stored.putIfAbsent("createdAt", System.currentTimeMillis());
            if (at >= 0) {
                items.set(at, stored);
            } else {
                items.add(stored);
            }
            write(dir.resolve(collection), Json.write(items));
            return stored;
        }
    }

    /** @return true when something was removed */
    public boolean delete(String collection, String id) throws IOException {
        synchronized (lock) {
            List<Map<String, Object>> items = list(collection);
            boolean removed = items.removeIf(item -> id.equals(Json.str(item, "id", null)));
            if (removed) {
                write(dir.resolve(collection), Json.write(items));
            }
            return removed;
        }
    }

    private String nextId(String collection) {
        String prefix = switch (collection) {
            case PRESETS -> "p";
            case STRATEGIES -> "s";
            case PRINTERS -> "lp";
            default -> "x";
        };
        return prefix + "_" + Long.toString(seq.incrementAndGet(), 36);
    }

    // ------------------------------------------------------------------ settings

    /** The panel's own settings. Never null; missing keys are the caller's business. */
    public Map<String, Object> settings() {
        synchronized (lock) {
            Path path = dir.resolve(SETTINGS);
            if (!Files.isRegularFile(path)) {
                return new LinkedHashMap<>();
            }
            try {
                return new LinkedHashMap<>(Json.parseObject(Files.readString(path, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                Log.warn("could not read " + path + " (" + e + ") — using defaults");
                return new LinkedHashMap<>();
            }
        }
    }

    /** Merge changes into the settings and write them back. */
    public Map<String, Object> updateSettings(Map<String, Object> changes) throws IOException {
        synchronized (lock) {
            Map<String, Object> merged = settings();
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                if (e.getValue() == null) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            write(dir.resolve(SETTINGS), Json.write(merged));
            return merged;
        }
    }

    // ------------------------------------------------------------------ writing

    private void write(Path path, String json) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some Windows filesystems and every network share refuse an atomic replace. A plain
            // replace is still far better than writing in place, which can leave a truncated file.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ first run

    /**
     * Write the starting presets and strategies, once, if the files do not exist yet.
     *
     * <p>An empty Presets screen is a worse first impression than a wrong one: the operator has no
     * idea what a preset is for, and the numbers that matter here are not guessable — they were
     * calibrated against physical output, per platform, and a tidier value is a barcode the
     * courier's scanner rejects. So the profiles the packing flow already prints with ship as the
     * starting set, which makes the panel useful on a station's first day and makes the calibrated
     * numbers visible to the person standing at the printer instead of living only in a web bundle.
     *
     * <p>Only ever writes a file that is absent. Someone who deletes every preset gets an empty
     * screen, not their deletions undone on the next restart.
     */
    public void seedIfEmpty() {
        try {
            if (!Files.exists(dir.resolve(PRESETS))) {
                save(PRESETS, defaultPresets());
                Log.info("wrote the starting presets to " + dir.resolve(PRESETS));
            }
            if (!Files.exists(dir.resolve(STRATEGIES))) {
                save(STRATEGIES, defaultStrategies());
                Log.info("wrote the starting strategies to " + dir.resolve(STRATEGIES));
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("could not write the starting presets/strategies: " + e);
        }
    }

    private List<Map<String, Object>> defaultPresets() {
        List<Map<String, Object>> out = new ArrayList<>();
        int order = 0;

        out.add(preset(++order, "p_seed_label_4x6", "Label 4x6 (generic)",
                "Any courier label on a 4x6 roll. The safe starting point for a new platform.",
                size(4, 6), margins(0, 0, 0, 0), "portrait", "grayscale", 203, "fit-to-page", null, null));

        out.add(preset(++order, "p_seed_label_flipkart", "Flipkart label 4x6",
                "The 0.3in left margin is not a typo and not symmetry: the Flipkart label sits "
                        + "off-centre in its own page and that margin is what pulls the barcode back "
                        + "inside the printable area.",
                size(4, 6), margins(0.1, 0, 0.1, 0.3), "portrait", "grayscale", 203, "fit", null, null));

        out.add(preset(++order, "p_seed_label_meesho", "Meesho label 6x5.7",
                "Reads size as the sheet rather than a rectangle on the roll, which is what QZ Tray "
                        + "did unconditionally and what this profile's output was calibrated against. "
                        + "Do not copy sizeMeans:sheet to an invoice preset — a 4x10in sheet on a 4x6 "
                        + "roll makes a thermal printer feed ten inches of stock it does not have.",
                size(6, 5.7), margins(0, 0, 0, 0.5), "landscape", "grayscale", 203, "fit-to-page",
                null, "sheet"));

        out.add(preset(++order, "p_seed_invoice_4x6", "Invoice 4x6 (generic)",
                "Single-page invoice on the same 4x6 roll as the label.",
                size(4, 6), margins(0, 0, 0, 0), "portrait", "grayscale", 300, "fit-to-page", null, null));

        out.add(preset(++order, "p_seed_invoice_flipkart", "Flipkart invoice 4x10",
                "No orientation on purpose. The PDF is a landscape page that has to be rotated onto "
                        + "a portrait strip, and the service detects that from the PDF's own page box. "
                        + "Setting orientation:portrait here is what silently prints it upright.",
                size(4, 10), margins(0, 0, 0, 0), null, "grayscale", 200, "fit-to-page", null, null));

        out.add(preset(++order, "p_seed_invoice_meesho", "Meesho invoice 4x10",
                "The two non-zero margins are derived, not picked: left 0.1in covers the 0.049in "
                        + "strip the TSC head cannot reach plus slack for feed registration, and "
                        + "bottom 0.05in is the document's own left edge, because on an auto-detected "
                        + "landscape page the sheet's bottom is where the document's left lands. "
                        + "Confirm with the test pattern before trusting them on new hardware.",
                size(4, 10), margins(0.05, 0, 0.05, 0.1), null, "grayscale", 300, "fit-to-page", null, null));

        out.add(preset(++order, "p_seed_invoice_firstcry", "FirstCry invoice 4x6 (page 1 only)",
                "FirstCry generates this invoice at exactly 4x6in and lets its registered-office "
                        + "footer overflow onto a second page carrying that one line. Printing every "
                        + "page fed a whole extra sticker per order, so this one is page 1 only.",
                size(4, 6), margins(0, 0, 0, 0), "portrait", "grayscale", 300, "fit-to-page", "1", null));

        out.add(preset(++order, "p_seed_a4", "A4 document",
                "An ordinary office document on A4, in colour if the printer has it.",
                size(8.27, 11.69), margins(0.4, 0.4, 0.4, 0.4), "portrait", "color", 300, "fit", null, null));

        return out;
    }

    private static Map<String, Object> preset(int order, String id, String name, String note,
            Map<String, Object> size, Map<String, Object> margins, String orientation,
            String colorType, double density, String scale, String pageRange, String sizeMeans) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("size", size);
        options.put("margins", margins);
        // Absent is meaningful and is not the same as a default, which is why these are only put
        // when set rather than written as nulls: an invoice profile depends on orientation staying
        // absent so the auto-landscape detection reads the PDF instead.
        if (orientation != null) {
            options.put("orientation", orientation);
        }
        options.put("colorType", colorType);
        options.put("density", density);
        options.put("scale", scale);
        if (pageRange != null) {
            options.put("pageRange", pageRange);
        }
        if (sizeMeans != null) {
            options.put("sizeMeans", sizeMeans);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("note", note);
        m.put("mode", "document");
        m.put("copies", 1);
        m.put("options", options);
        m.put("isDefault", false);
        m.put("seeded", true);
        m.put("order", order);
        m.put("createdAt", System.currentTimeMillis());
        return m;
    }

    private static Map<String, Object> size(double w, double h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", w);
        m.put("height", h);
        m.put("units", "in");
        return m;
    }

    private static Map<String, Object> margins(double top, double right, double bottom, double left) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("top", top);
        m.put("right", right);
        m.put("bottom", bottom);
        m.put("left", left);
        return m;
    }

    private List<Map<String, Object>> defaultStrategies() {
        List<Map<String, Object>> out = new ArrayList<>();

        out.add(strategy("s_seed_single", "Everything to one printer",
                "The plain case, and the default. Every page of every file goes to the chosen "
                        + "printer with the chosen preset.",
                List.of(rule("All pages", "all", "normal", null, null, 1)), true));

        out.add(strategy("s_seed_duplex", "Manual duplex (odd, then even reversed)",
                "Two passes on a single-sided printer. The first pass prints the odd pages; the "
                        + "operator turns the stack over and the second prints the even pages in "
                        + "reverse, which is the order that makes a face-up output tray come out "
                        + "collated. Point both rules at the same printer.",
                List.of(rule("Front sides", "odd", "normal", null, null, 1),
                        rule("Back sides", "even", "reverse", null, null, 1)), false));

        out.add(strategy("s_seed_split", "Label to one printer, invoice to another",
                "The split the packing flow does per order: page 1 is the courier label and goes "
                        + "to the label printer, everything after it is the invoice and goes to the "
                        + "document printer. Set a printer and a preset on each rule.",
                List.of(rule("Label", "first", "normal", null, null, 1),
                        rule("Invoice", "2-", "normal", null, null, 1)), false));

        out.add(strategy("s_seed_label_copies", "Label, two copies",
                "One label per parcel plus one for the picklist, from a single file.",
                List.of(rule("Label", "first", "normal", null, null, 2)), false));

        return out;
    }

    private static Map<String, Object> strategy(String id, String name, String description,
            List<Map<String, Object>> rules, boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("rules", new ArrayList<>(rules));
        m.put("isDefault", isDefault);
        m.put("seeded", true);
        m.put("createdAt", System.currentTimeMillis());
        return m;
    }

    private static Map<String, Object> rule(String label, String pages, String order,
            String printer, String presetId, int copies) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("pages", pages);
        m.put("pageOrder", order);
        // Left unset in a seeded strategy on purpose: a rule pointing at a printer that does not
        // exist on this station is worse than one that visibly needs choosing, because it fails at
        // print time instead of in the editor.
        m.put("printer", printer == null ? "" : printer);
        m.put("presetId", presetId == null ? "" : presetId);
        m.put("copies", copies);
        m.put("enabled", true);
        return m;
    }

    // ------------------------------------------------------------------ label printers

    /**
     * The label printers the panel added, as targets to merge with the ones in {@code config.json}.
     *
     * <p>A bad entry is skipped rather than fatal: a typo in one printer's host must not stop the
     * service starting, because the station's other printers are how the shift keeps running.
     */
    public List<PrinterTarget> labelPrinters() {
        List<PrinterTarget> out = new ArrayList<>();
        for (Map<String, Object> item : list(PRINTERS)) {
            try {
                out.add(PrinterTarget.from(item));
            } catch (RuntimeException e) {
                Log.warn("skipping a printer in " + dir.resolve(PRINTERS) + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Case-insensitive name match, so the panel cannot create a second "pack-1". */
    public static boolean sameName(String a, String b) {
        return a != null && b != null
                && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }
}
