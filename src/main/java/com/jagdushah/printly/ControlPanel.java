package com.jagdushah.printly;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Control Panel: a local web UI, and the API it runs on.
 *
 * <h2>Why this is HTTP and not the WebSocket the design document specified</h2>
 *
 * <p>The Phase 1 technical design put a WebSocket at the front of the service, with RSA-signed
 * calls and a trusted-cert store, because that is what QZ Tray does and QZ has to: a browser can
 * only open {@code wss://} from an HTTPS page, so QZ needs a certificate for {@code localhost} that
 * the OS trusts. That certificate is named in the design's own risk register as "the single most
 * awkward part of this whole build", and it is awkward per machine, for ever.
 *
 * <p>This service already avoids all of it. The mixed-content spec treats {@code http://127.0.0.1}
 * as a potentially trustworthy origin, so an HTTPS page may {@code fetch} it with no TLS, no
 * certificate, and no trust-store step; security comes from binding to loopback and checking the
 * {@code Origin} header. That is a straight win over the WebSocket plan and it is already in
 * production against the packing flow — so the panel is built on the transport that exists rather
 * than a second one beside it. Everything the design wanted from the socket is still here:
 *
 * <ul>
 *   <li><b>Two front-ends, one engine.</b> The panel prints through {@link PrintRouter}, the same
 *       object {@code POST /print} uses. A job from the panel and a job from the web app are the
 *       same job, composed by the same code, so they cannot disagree about what comes out.</li>
 *   <li><b>The panel is a client, not a special case.</b> Its endpoints add staging, strategies and
 *       batches; the printing itself has no panel-only path.</li>
 *   <li><b>Live state.</b> Polling, not pushed events. The panel is on the same machine and asks
 *       for a few kilobytes a second while a batch runs; a socket would be a nicer implementation
 *       of the same user-visible behaviour, and is not worth a second transport to maintain.</li>
 * </ul>
 *
 * <p>The one thing genuinely given up is signature verification, which gated <em>unknown web
 * pages</em> — not the panel, which the design trusts by default anyway. The origin allow-list in
 * {@code config.json} is what does that job here.
 */
public final class ControlPanel {

    /**
     * How much a single staged upload may be.
     *
     * <p>Deliberately larger than {@code maxBodyBytes}, which bounds what a <em>web page</em> may
     * post at the print endpoints. This is a person choosing a file in a picker on the same machine,
     * and a 40-page courier manifest is a normal thing to want to print; refusing it with the limit
     * that exists to keep a runaway web app from filling the heap would be the wrong lesson applied
     * to the wrong caller. The staging area's own total cap is what actually protects memory, and
     * uploads go to disk rather than into the heap.
     */
    private static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;

    /** Total size of the staging area. Generous: it is disk, and a bulk run is the point. */
    static final long MAX_SPOOL_BYTES = 1024L * 1024 * 1024;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Config config;
    private final PrintRouter router;
    private final Store store;
    private final Spool spool;
    private final BatchRunner batches;
    private final long startedAt = System.currentTimeMillis();

    public ControlPanel(Config config, PrintRouter router, Store store, Spool spool, BatchRunner batches) {
        this.config = config;
        this.router = router;
        this.store = store;
        this.spool = spool;
        this.batches = batches;
    }

    // ================================================================== the UI

    /**
     * Serve the panel's own files out of the jar.
     *
     * <p>Served from the jar rather than from disk so that an installed copy has no loose web assets
     * to go missing or get edited into a state nobody can reproduce. There is no build step and no
     * npm: the panel is plain HTML, CSS and JavaScript, which is the same reasoning that keeps this
     * project's Java build a bare {@code javac} with vendored jars.
     */
    public void handleUi(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new HttpApi.BadRequestException(405, "use GET for the Control Panel");
        }
        String path = exchange.getRequestURI().getPath();
        String name = path.equals("/") || path.equals("/ui") || path.equals("/ui/")
                ? "index.html"
                : path.startsWith("/ui/") ? path.substring("/ui/".length()) : path.substring(1);

        // Flat namespace, and anything else is refused outright. The panel has three files; the
        // moment this accepts a slash or a dot-dot it is a file server pointed at the jar.
        if (!name.matches("[A-Za-z0-9._-]{1,64}") || name.contains("..")) {
            HttpApi.respond(exchange, 404, HttpApi.error("no such Control Panel file"));
            return;
        }

        byte[] body;
        try (InputStream in = ControlPanel.class.getResourceAsStream("/ui/" + name)) {
            if (in == null) {
                HttpApi.respond(exchange, 404, HttpApi.error("no such Control Panel file: " + name));
                return;
            }
            body = in.readAllBytes();
        }

        exchange.getResponseHeaders().set("Content-Type", contentType(name));
        // no-store rather than a cache header with an ETag: the panel is a local page whose whole
        // job is to reflect live printer state, and a stale app.js after an upgrade is a support
        // call that starts with "it says the old version".
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    // ================================================================== the API

    /** Dispatch {@code /api/...}. Everything here answers JSON, including its failures. */
    public void handleApi(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        String rest = path.startsWith("/api/") ? path.substring("/api/".length()) : "";
        String[] parts = rest.split("/");
        String head = parts.length > 0 ? parts[0] : "";
        String id = parts.length > 1 ? parts[1] : null;
        String action = parts.length > 2 ? parts[2] : null;
        boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());

        switch (head) {
            case "state" -> HttpApi.respond(exchange, 200, state());
            case "printers" -> printers(exchange, id, post);
            case "presets" -> collection(exchange, Store.PRESETS, id, post, this::validatePreset);
            case "strategies" -> collection(exchange, Store.STRATEGIES, id, post, this::validateStrategy);
            case "settings" -> settings(exchange, post);
            case "files" -> files(exchange, id, post);
            case "plan" -> plan(exchange);
            case "print" -> print(exchange);
            case "preview" -> diagnostic(exchange, true);
            case "preflight" -> diagnostic(exchange, false);
            case "batches" -> batches(exchange, id, action, post);
            case "jobs" -> jobs(exchange, id, action, post);
            case "log" -> log(exchange);
            case "reconnect" -> {
                HttpApi.requirePost(exchange);
                router.reconnectAll();
                HttpApi.respond(exchange, 200, Map.of("ok", true, "reconnecting", true));
            }
            default -> HttpApi.respond(exchange, 404, HttpApi.error("no such panel endpoint: /api/" + rest));
        }
    }

    // ------------------------------------------------------------------ state

    /**
     * Everything the panel needs, in one call.
     *
     * <p>One request rather than eight, because the panel polls this while a batch runs and eight
     * round-trips a second against a server with a fixed thread pool is a pool the print endpoints
     * have to share. It is a few kilobytes.
     */
    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("service", HttpApi.SERVICE_ID);
        m.put("version", Main.VERSION);
        m.put("uptimeMs", System.currentTimeMillis() - startedAt);
        m.put("port", config.port);
        m.put("documentLane", router.hasDocumentLane());
        m.put("documentPrinters", router.documentPrinterCount());

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("config", String.valueOf(config.file));
        paths.put("log", String.valueOf(Log.file()));
        paths.put("data", String.valueOf(store.dir()));
        m.put("paths", paths);

        m.put("allowedOrigins", new ArrayList<>(config.allowedOrigins));
        m.put("printers", printerList());
        m.put("presets", store.list(Store.PRESETS));
        m.put("strategies", store.list(Store.STRATEGIES));
        m.put("settings", store.settings());

        List<Map<String, Object>> staged = new ArrayList<>();
        for (Spool.Entry entry : spool.list()) {
            staged.add(entry.toJson());
        }
        m.put("files", staged);
        m.put("stagedBytes", spool.totalBytes());
        m.put("stagedLimitBytes", MAX_SPOOL_BYTES);

        m.put("jobs", jobList(40, null));

        List<Map<String, Object>> runs = new ArrayList<>();
        for (BatchRunner.Batch batch : batches.recent(10)) {
            runs.add(batch.toJson(false));
        }
        m.put("batches", runs);
        return m;
    }

    /**
     * Every printer, with the panel's extra column: whether the panel owns its definition.
     *
     * <p>It matters because a printer defined in {@code config.json} cannot be edited from here —
     * that file is hand-written and often carries the reasoning for its values in comments — and a
     * UI that offered an edit button that silently did nothing would be worse than one that says
     * where the definition lives.
     */
    private List<Map<String, Object>> printerList() {
        List<String> owned = new ArrayList<>();
        for (Map<String, Object> item : store.list(Store.PRINTERS)) {
            owned.add(Json.str(item, "name", ""));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> printer : router.listPrinters()) {
            Map<String, Object> copy = new LinkedHashMap<>(printer);
            if ("label".equals(Json.str(printer, "lane", ""))) {
                boolean editable = owned.stream()
                        .anyMatch(name -> Store.sameName(name, Json.str(printer, "name", "")));
                copy.put("editable", editable);
                copy.put("source", editable ? "panel" : "config.json");
            } else {
                copy.put("editable", false);
                copy.put("source", "operating system");
            }
            out.add(copy);
        }
        return out;
    }

    private List<Map<String, Object>> jobList(int limit, String batchId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job job : router.jobs().recent(limit, batchId)) {
            out.add(job.toJson());
        }
        return out;
    }

    // ------------------------------------------------------------------ printers

    private void printers(HttpExchange exchange, String id, boolean post) throws IOException {
        if (!post) {
            HttpApi.respond(exchange, 200, printerList());
            return;
        }
        Map<String, Object> req = body(exchange);
        switch (id == null ? "save" : id) {
            case "save" -> savePrinter(exchange, req);
            case "delete" -> deletePrinter(exchange, req);
            case "probe" -> probePrinter(exchange, req);
            case "test-label" -> testLabel(exchange, req);
            default -> throw new HttpApi.BadRequestException(404,
                    "unknown printer action '" + id + "'");
        }
    }

    private void savePrinter(HttpExchange exchange, Map<String, Object> req) throws IOException {
        String name = Json.str(req, "name", "").trim();
        if (name.isEmpty()) {
            throw new HttpApi.BadRequestException(400, "a printer needs a name");
        }
        // The name is what a web app prints to, so it goes in URLs, logs and job records. Keeping
        // it to plain characters is what stops "pack 1 " and "pack-1" being two printers nobody can
        // tell apart in a list.
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,47}")) {
            throw new HttpApi.BadRequestException(400, "'" + name + "' is not a usable printer name — "
                    + "use letters, digits, spaces, dots, dashes or underscores");
        }
        for (PrinterTarget existing : config.printers) {
            if (Store.sameName(existing.name(), name) && !isPanelOwned(name)) {
                throw new HttpApi.BadRequestException(409, "'" + name + "' is defined in "
                        + config.file.getFileName() + " — edit it there, so its comments survive");
            }
        }

        PrinterTarget target;
        try {
            target = PrinterTarget.from(req);
        } catch (RuntimeException e) {
            throw new HttpApi.BadRequestException(400, e.getMessage());
        }
        if (target.port() < 1 || target.port() > 65535) {
            throw new HttpApi.BadRequestException(400, "port " + target.port() + " is not a port");
        }

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", findPrinterId(name));
        record.put("name", target.name());
        record.put("host", target.host());
        record.put("port", target.port());
        record.put("charset", target.charset().name());
        record.put("lineEnding", target.lineEnding());
        record.put("note", target.note());
        Map<String, Object> stored = store.upsert(Store.PRINTERS, record);

        // Live, without a restart. This is the whole reason the panel can be used to set a station
        // up: the alternative is edit a file, restart the service, and lose the warm sockets of
        // every other printer on the station mid-shift.
        router.addLabelPrinter(target);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("printer", stored);
        body.put("printers", printerList());
        HttpApi.respond(exchange, 200, body);
    }

    private String findPrinterId(String name) {
        for (Map<String, Object> item : store.list(Store.PRINTERS)) {
            if (Store.sameName(Json.str(item, "name", ""), name)) {
                return Json.str(item, "id", "");
            }
        }
        return "";
    }

    private boolean isPanelOwned(String name) {
        return !findPrinterId(name).isEmpty();
    }

    private void deletePrinter(HttpExchange exchange, Map<String, Object> req) throws IOException {
        String name = Json.str(req, "name", "").trim();
        String id = findPrinterId(name);
        if (id.isEmpty()) {
            throw new HttpApi.BadRequestException(404, "'" + name
                    + "' is not a printer this panel added, so it cannot remove it");
        }
        store.delete(Store.PRINTERS, id);
        router.removeLabelPrinter(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("removed", name);
        body.put("printers", printerList());
        HttpApi.respond(exchange, 200, body);
    }

    /**
     * Open a socket to a label printer and close it again.
     *
     * <p>The one question worth answering before saving a printer, and the panel's most useful
     * button: an IP typo and a printer that is switched off look identical in every other screen,
     * and both look like "the label did not print" once a shift has started.
     *
     * <p>This is a fresh connection, so it is only safe on a printer the service is not already
     * holding: most budget thermal hardware accepts exactly one connection on {@code :9100}. That is
     * why it probes the address rather than an existing printer's warm socket, and why a printer
     * already configured and online is reported from its own liveness check instead.
     */
    private void probePrinter(HttpExchange exchange, Map<String, Object> req) throws IOException {
        String host = Json.str(req, "host", "").trim();
        int port = (int) Json.num(req, "port", 9100);
        if (host.isEmpty()) {
            throw new HttpApi.BadRequestException(400, "a host or IP to probe is required");
        }
        PrinterConnection existing = router.label(Json.str(req, "name", ""));
        if (existing != null && existing.online()
                && existing.target().host().equalsIgnoreCase(host) && existing.target().port() == port) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("reachable", true);
            body.put("note", "already connected — this printer's warm socket is open, so nothing "
                    + "was probed. Most thermal printers accept only one connection at a time.");
            HttpApi.respond(exchange, 200, body);
            return;
        }

        long t0 = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), config.connectTimeoutMs);
            body.put("reachable", true);
            body.put("ms", Math.round((System.nanoTime() - t0) / 1_000_000.0));
        } catch (IOException e) {
            body.put("reachable", false);
            body.put("ms", Math.round((System.nanoTime() - t0) / 1_000_000.0));
            body.put("error", e.getClass().getSimpleName().replace("Exception", "")
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        HttpApi.respond(exchange, 200, body);
    }

    /**
     * Print a small TSPL label, to prove the whole path end to end.
     *
     * <p>A reachable socket is not a working printer: the address can be right and the label stock,
     * the gap sensor or the codepage wrong, and every one of those failures is invisible until
     * something real prints crooked. The geometry here is deliberately modest — 50x25mm with a 2mm
     * gap, which is the commonest small label — and it is expected to need adjusting per station
     * rather than to be right everywhere.
     */
    private void testLabel(HttpExchange exchange, Map<String, Object> req) throws IOException {
        String name = Json.str(req, "name", "").trim();
        PrinterConnection connection = router.label(name);
        if (connection == null) {
            throw new HttpApi.BadRequestException(404, "no label printer named '" + name + "'");
        }
        String tspl = """
                SIZE 50 mm,25 mm
                GAP 2 mm,0
                DIRECTION 0
                CLS
                TEXT 16,14,"3",0,1,1,"PRINTLY OK"
                TEXT 16,54,"2",0,1,1,"%s"
                TEXT 16,80,"2",0,1,1,"%s"
                TEXT 16,106,"2",0,1,1,"%s"
                PRINT 1,1
                """.formatted(name, connection.target().address(), STAMP.format(Instant.now()));

        Job job = router.submit(name, "tspl", connection.target().encodeText(tspl), 1,
                PrintOptions.NONE, "test label", null, "printer test");
        job.await(6000);
        Map<String, Object> body = new LinkedHashMap<>(job.toJson());
        body.put("ok", job.state() == Job.State.DONE);
        HttpApi.respond(exchange, job.state() == Job.State.FAILED ? 502 : 200, body);
    }

    // ------------------------------------------------------------------ presets & strategies

    private interface Validator {
        void check(Map<String, Object> item);
    }

    /** The shared CRUD shape for the two stored collections. */
    private void collection(HttpExchange exchange, String name, String id, boolean post,
            Validator validator) throws IOException {
        if (!post) {
            HttpApi.respond(exchange, 200, store.list(name));
            return;
        }
        Map<String, Object> req = body(exchange);
        if ("delete".equals(id)) {
            String target = Json.str(req, "id", "").trim();
            boolean removed = store.delete(name, target);
            if (!removed) {
                throw new HttpApi.BadRequestException(404, "nothing here with id '" + target + "'");
            }
            HttpApi.respond(exchange, 200, Map.of("ok", true, "items", store.list(name)));
            return;
        }
        if (id != null && !id.equals("save")) {
            throw new HttpApi.BadRequestException(404, "unknown action '" + id + "'");
        }
        try {
            validator.check(req);
        } catch (IllegalArgumentException e) {
            throw new HttpApi.BadRequestException(400, e.getMessage());
        }
        Map<String, Object> saved = store.upsert(name, req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("item", saved);
        body.put("items", store.list(name));
        HttpApi.respond(exchange, 200, body);
    }

    /**
     * Refuse a preset whose geometry does not parse.
     *
     * <p>The same parse the print path does, at the moment of saving. A preset is written once and
     * used a thousand times, and the values in it are hardware-calibrated: catching a unit mix-up
     * here costs a red message in a form, and catching it at print time costs a batch.
     */
    private void validatePreset(Map<String, Object> item) {
        String name = Json.str(item, "name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a preset needs a name");
        }
        int copies = (int) Json.num(item, "copies", 1);
        if (copies < 1 || copies > 1000) {
            throw new IllegalArgumentException("copies must be between 1 and 1000");
        }
        PrintOptions.from(item.get("options"));
    }

    /** Refuse a strategy whose rules cannot be resolved. Same reasoning as a preset. */
    private void validateStrategy(Map<String, Object> item) {
        String name = Json.str(item, "name", "").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a strategy needs a name");
        }
        List<Object> rules = Json.arr(item, "rules");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("a strategy needs at least one rule");
        }
        for (Object raw : rules) {
            Map<String, Object> rule = Json.obj(raw);
            String label = Json.str(rule, "label", "a rule");
            try {
                PageSelection.parse(Json.str(rule, "pages", "all"), Json.str(rule, "pageOrder", "normal"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(label + ": " + e.getMessage());
            }
            int copies = (int) Json.num(rule, "copies", 1);
            if (copies < 1 || copies > 1000) {
                throw new IllegalArgumentException(label + ": copies must be between 1 and 1000");
            }
        }
        // Resolved against a nominal ten-page document, which is the cheapest way to catch a rule
        // that can never match anything — "every:0", a range that ends before it starts — without
        // needing a real file in hand.
        Strategy.plan(item, 10, "probe", "", store.list(Store.PRESETS));
    }

    // ------------------------------------------------------------------ settings

    private void settings(HttpExchange exchange, boolean post) throws IOException {
        if (!post) {
            HttpApi.respond(exchange, 200, store.settings());
            return;
        }
        Map<String, Object> merged = store.updateSettings(body(exchange));
        HttpApi.respond(exchange, 200, Map.of("ok", true, "settings", merged));
    }

    // ------------------------------------------------------------------ staged files

    private void files(HttpExchange exchange, String id, boolean post) throws IOException {
        if (!post) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Spool.Entry entry : spool.list()) {
                out.add(entry.toJson());
            }
            HttpApi.respond(exchange, 200, out);
            return;
        }
        if ("delete".equals(id)) {
            Map<String, Object> req = body(exchange);
            if (Json.bool(req, "all", false)) {
                spool.clear();
                HttpApi.respond(exchange, 200, Map.of("ok", true, "files", List.of()));
                return;
            }
            spool.remove(Json.str(req, "fileId", ""));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Spool.Entry entry : spool.list()) {
                out.add(entry.toJson());
            }
            HttpApi.respond(exchange, 200, Map.of("ok", true, "files", out));
            return;
        }
        stageUpload(exchange);
    }

    /**
     * Take one uploaded PDF into the staging area.
     *
     * <p>Two shapes accepted, and raw bytes are the one the panel uses: {@code application/pdf}
     * with the file as the body and its name in the query string. Base64 in a JSON envelope is
     * accepted too, because that is the shape every other endpoint here speaks and a script driving
     * the panel's API will reach for it first — but it inflates a document by a third on a path
     * whose whole purpose is two hundred of them at once.
     */
    private void stageUpload(HttpExchange exchange) throws IOException {
        String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
        byte[] pdf;
        String name;
        if (contentType.toLowerCase(Locale.ROOT).contains("json")) {
            Map<String, Object> req = Json.parseObject(
                    new String(HttpApi.readBody(exchange, MAX_UPLOAD_BYTES), StandardCharsets.UTF_8));
            name = Json.str(req, "name", "document.pdf").trim();
            String data = Json.str(req, "data", "");
            int comma = data.startsWith("data:") ? data.indexOf(',') : -1;
            try {
                pdf = Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data);
            } catch (IllegalArgumentException e) {
                throw new HttpApi.BadRequestException(400, "\"data\" is not valid base64");
            }
        } else {
            name = HttpApi.query(exchange.getRequestURI()).getOrDefault("name", "document.pdf");
            pdf = HttpApi.readBody(exchange, MAX_UPLOAD_BYTES);
        }
        if (pdf.length == 0) {
            throw new HttpApi.BadRequestException(400, "the upload was empty");
        }
        // A file name reaches a log line, a job title and the panel's own table. Stripping the path
        // and anything exotic keeps all three readable and keeps a name from being mistaken for a
        // path anywhere downstream.
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "document.pdf";
        }
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }

        try {
            Spool.Entry entry = spool.stage(name, pdf);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("file", entry.toJson());
            HttpApi.respond(exchange, 200, body);
        } catch (IllegalArgumentException e) {
            throw new HttpApi.BadRequestException(400, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ plan

    /**
     * What a strategy would do, without doing it.
     *
     * <p>The screen this feeds is the answer to the question a routing strategy otherwise leaves
     * open: an operator has just said "odd pages left, even pages right" and has no way to know
     * whether the service agrees, short of pressing Print on a real order. This resolves the rules
     * against the real page count of the real files and lists every job it would create, before
     * anything is queued.
     */
    private void plan(HttpExchange exchange) throws IOException {
        HttpApi.requirePost(exchange);
        Map<String, Object> req = body(exchange);
        Map<String, Object> strategy = strategyFrom(req);
        String printer = Json.str(req, "printer", "").trim();
        String presetId = Json.str(req, "presetId", "").trim();
        List<Map<String, Object>> presets = store.list(Store.PRESETS);

        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> fileIds = Json.arr(req, "fileIds");
        if (fileIds.isEmpty() && req.get("fileId") != null) {
            fileIds = List.of(Json.str(req, "fileId", ""));
        }
        if (fileIds.isEmpty()) {
            // No file in hand: plan against a stated page count so a strategy can be checked while
            // it is being written, which is when it is worth checking.
            int pages = Math.max(1, (int) Json.num(req, "pages", 2));
            out.add(planOne("(a " + pages + "-page document)", pages, strategy, printer, presetId, presets));
        } else {
            for (Object raw : fileIds) {
                String fileId = String.valueOf(raw);
                Spool.Entry entry = spool.entry(fileId);
                if (entry == null) {
                    throw new HttpApi.BadRequestException(404, "file '" + fileId + "' is no longer staged");
                }
                out.add(planOne(entry.name(), entry.pages(), strategy, printer, presetId, presets));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", out.stream().allMatch(m -> Json.bool(m, "ok", false)));
        body.put("plans", out);
        HttpApi.respond(exchange, 200, body);
    }

    private Map<String, Object> planOne(String name, int pages, Map<String, Object> strategy,
            String printer, String presetId, List<Map<String, Object>> presets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("pages", pages);
        try {
            Strategy.Plan plan = Strategy.plan(strategy, pages, printer, presetId, presets);
            m.put("ok", true);
            m.putAll(plan.toJson());
        } catch (IllegalArgumentException e) {
            // A plan that cannot be made is reported per file rather than as one failed request:
            // "the third of these two hundred files has one page and your invoice rule needs two"
            // is the useful answer, and a single error code loses which file it was about.
            m.put("ok", false);
            m.put("error", e.getMessage());
        }
        return m;
    }

    /** The strategy named in a request, or null for "everything to the chosen printer". */
    private Map<String, Object> strategyFrom(Map<String, Object> req) {
        Object inline = req.get("strategy");
        if (inline instanceof Map) {
            return Json.obj(inline);
        }
        String id = Json.str(req, "strategyId", "").trim();
        if (id.isEmpty()) {
            return null;
        }
        Map<String, Object> found = store.find(Store.STRATEGIES, id);
        if (found == null) {
            throw new HttpApi.BadRequestException(404, "no strategy with id '" + id + "'");
        }
        return found;
    }

    // ------------------------------------------------------------------ printing

    /**
     * One print from the panel, document or raw.
     *
     * <p>Goes through {@link PrintRouter#submit} exactly as {@code POST /print} does. There is no
     * panel-only print path, on purpose: the moment there were two, a job from the panel and a job
     * from the packing page could come out differently, and the panel's preview would be a preview
     * of the wrong thing.
     */
    private void print(HttpExchange exchange) throws IOException {
        HttpApi.requirePost(exchange);
        Map<String, Object> req = body(exchange);
        String printer = Json.str(req, "printer", "").trim();
        if (printer.isEmpty()) {
            throw new HttpApi.BadRequestException(400, "choose a printer first");
        }
        String mode = Json.str(req, "mode", "document").trim().toLowerCase(Locale.ROOT);
        int copies = Math.max(1, (int) Json.num(req, "copies", 1));
        if (copies > 1000) {
            throw new HttpApi.BadRequestException(400, "copies must be between 1 and 1000");
        }
        String title = Json.str(req, "title", null);

        byte[] payload;
        String type;
        PrintOptions options;
        if (mode.equals("raw") || mode.equals("tspl")) {
            type = mode.equals("raw") ? "raw" : "tspl";
            String data = Json.str(req, "data", "");
            if (data.isBlank()) {
                throw new HttpApi.BadRequestException(400, "there are no commands to send");
            }
            String encoding = Json.str(req, "encoding", "utf8").toLowerCase(Locale.ROOT);
            payload = switch (encoding) {
                case "base64" -> decodeBase64(data);
                case "hex" -> decodeHex(data);
                default -> router.encodeText(printer, data);
            };
            options = PrintOptions.NONE;
            if (title == null) {
                title = "raw " + type;
            }
        } else {
            type = "pdf";
            Resolved resolved = resolveDocument(req);
            payload = resolved.pdf();
            if (title == null) {
                title = resolved.name();
            }
            options = optionsFor(req);
        }
        if (payload.length == 0) {
            throw new HttpApi.BadRequestException(400, "there is nothing to print");
        }

        Job job = router.submit(printer, type, payload, copies, options, title, null,
                Json.str(req, "strategy", null));
        long wait = Json.num(req, "wait", 0);
        if (wait > 0) {
            job.await(Math.min(wait, 60_000));
        }
        int status = switch (job.state()) {
            case DONE -> 200;
            case FAILED -> 502;
            default -> 202;
        };
        HttpApi.respond(exchange, status, job.toJson());
    }

    /** A document to print, from the staging area or straight off the request. */
    private record Resolved(String name, byte[] pdf) {
    }

    private Resolved resolveDocument(Map<String, Object> req) throws IOException {
        String fileId = Json.str(req, "fileId", "").trim();
        if (!fileId.isEmpty()) {
            Spool.Entry entry = spool.entry(fileId);
            if (entry == null) {
                throw new HttpApi.BadRequestException(404, "file '" + fileId
                        + "' is no longer staged — add it again");
            }
            return new Resolved(entry.name(), spool.read(fileId));
        }
        String data = Json.str(req, "data", "");
        if (data.isBlank()) {
            throw new HttpApi.BadRequestException(400, "choose a file first");
        }
        return new Resolved(Json.str(req, "name", "document.pdf"), decodeBase64(data));
    }

    /**
     * The geometry for a panel print: a preset, a literal options object, or both.
     *
     * <p>A literal {@code options} overrides the preset field by field rather than replacing it,
     * which is what makes the panel's "start from this preset and nudge the left margin" work
     * without saving a new preset for every experiment. The page selection is layered on last,
     * because it belongs to the action rather than to the geometry.
     */
    private PrintOptions optionsFor(Map<String, Object> req) {
        Map<String, Object> merged = new LinkedHashMap<>();
        String presetId = Json.str(req, "presetId", "").trim();
        if (!presetId.isEmpty()) {
            Map<String, Object> preset = store.find(Store.PRESETS, presetId);
            if (preset == null) {
                throw new HttpApi.BadRequestException(404, "no preset with id '" + presetId + "'");
            }
            merged.putAll(Json.obj(preset.get("options")));
        }
        Object literal = req.get("options");
        if (literal instanceof Map) {
            merged.putAll(Json.obj(literal));
        }
        String pages = Json.str(req, "pages", null);
        if (pages != null && !pages.isBlank()) {
            merged.put("pages", pages);
            merged.put("pageOrder", Json.str(req, "pageOrder", "normal"));
        }
        try {
            return PrintOptions.from(merged.isEmpty() ? null : merged);
        } catch (IllegalArgumentException e) {
            throw new HttpApi.BadRequestException(400, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ preview & preflight

    /**
     * Preview or preflight, with the panel's page selection applied first.
     *
     * <p>Applying the selection before rendering is the part that matters. A strategy's rule prints
     * page 3 of a six-page document with its own geometry; previewing page 3 of the whole document
     * would compose a different page against a different orientation and show something the printer
     * will not produce. The preview has to be of the document the rule actually creates.
     */
    private void diagnostic(HttpExchange exchange, boolean render) throws IOException {
        HttpApi.requirePost(exchange);
        Map<String, Object> req = body(exchange);
        String printer = Json.str(req, "printer", "").trim();
        if (printer.isEmpty()) {
            throw new HttpApi.BadRequestException(400, "choose a printer first");
        }
        Resolved resolved = resolveDocument(req);
        PrintOptions options = optionsFor(req);
        byte[] pdf = resolved.pdf();

        String pagesNote = null;
        PageSelection selection = options.pages();
        if (selection != null) {
            try {
                PdfSplitter.Applied applied = PdfSplitter.apply(pdf, selection);
                pagesNote = selection.describe(applied.sourcePages());
                pdf = applied.pdf();
                options = options.withoutPages();
            } catch (IllegalArgumentException e) {
                throw new HttpApi.BadRequestException(400, e.getMessage());
            }
        }

        int page = Math.max(1, (int) Json.num(req, "page", 1));
        try {
            Map<String, Object> result = render
                    ? router.preview(printer, pdf, options, page - 1,
                            Json.dbl(req, "dpi", 0), Json.bool(req, "overlay", true))
                    : router.preflight(printer, pdf, options);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("file", resolved.name());
            if (pagesNote != null) {
                body.put("pagesNote", pagesNote);
            }
            body.putAll(result);
            HttpApi.respond(exchange, 200, body);
        } catch (IllegalArgumentException e) {
            throw new HttpApi.BadRequestException(400, e.getMessage());
        } catch (IllegalStateException e) {
            // The lane is busy behind a real print. A retry is the right answer, so 503.
            throw new HttpApi.BadRequestException(503, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ batches

    private void batches(HttpExchange exchange, String id, String action, boolean post) throws IOException {
        if (!post) {
            if (id == null) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (BatchRunner.Batch batch : batches.recent(20)) {
                    out.add(batch.toJson(false));
                }
                HttpApi.respond(exchange, 200, out);
                return;
            }
            BatchRunner.Batch batch = batches.batch(id);
            if (batch == null) {
                throw new HttpApi.BadRequestException(404, "no batch '" + id + "'");
            }
            HttpApi.respond(exchange, 200, batch.toJson(true));
            return;
        }
        if (id != null && "cancel".equals(action)) {
            int pulled = batches.cancel(id);
            if (pulled < 0) {
                throw new HttpApi.BadRequestException(404, "no batch '" + id + "'");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("pulled", pulled);
            // Said plainly, because it is the one thing about cancel that surprises people: bytes
            // already handed to a driver cannot be recalled.
            body.put("note", pulled == 0
                    ? "nothing was still queued — anything already at a printer will finish"
                    : pulled + " queued job(s) pulled; anything already at a printer will finish");
            body.put("batch", batches.batch(id).toJson(true));
            HttpApi.respond(exchange, 200, body);
            return;
        }
        startBatch(exchange);
    }

    private void startBatch(HttpExchange exchange) throws IOException {
        Map<String, Object> req = body(exchange);
        List<String> fileIds = new ArrayList<>();
        for (Object raw : Json.arr(req, "fileIds")) {
            fileIds.add(String.valueOf(raw));
        }
        if (fileIds.isEmpty()) {
            // Nothing chosen means everything staged, which is what the operator means when they
            // drop a folder in and press the button.
            for (Spool.Entry entry : spool.list()) {
                fileIds.add(entry.id());
            }
        }
        if (fileIds.isEmpty()) {
            throw new HttpApi.BadRequestException(400, "add some files first");
        }

        Map<String, Object> strategy = strategyFrom(req);
        String strategyName = strategy == null
                ? "Everything to one printer"
                : Json.str(strategy, "name", "strategy");
        BatchRunner.Request request = new BatchRunner.Request(fileIds, strategy, strategyName,
                Json.str(req, "printer", "").trim(), Json.str(req, "presetId", "").trim(),
                (int) Json.num(req, "concurrency", 2), (int) Json.num(req, "copies", 1));

        try {
            BatchRunner.Batch batch = batches.start(request, store.list(Store.PRESETS));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("batchId", batch.id());
            body.put("batch", batch.toJson(true));
            HttpApi.respond(exchange, 200, body);
        } catch (IllegalArgumentException e) {
            // Refused before anything printed, which is the promise: a batch either runs as
            // described or does not start.
            throw new HttpApi.BadRequestException(400, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ jobs

    private void jobs(HttpExchange exchange, String id, String action, boolean post) throws IOException {
        if (!post) {
            Map<String, String> q = HttpApi.query(exchange.getRequestURI());
            int limit = (int) HttpApi.parseLong(q.get("limit"), 60);
            HttpApi.respond(exchange, 200, jobList(Math.max(1, Math.min(500, limit)), q.get("batchId")));
            return;
        }
        Job job = router.jobs().get(id);
        if (job == null) {
            throw new HttpApi.BadRequestException(404, "unknown or expired job '" + id + "'");
        }
        switch (action == null ? "" : action) {
            case "cancel" -> {
                boolean pulled = job.cancel();
                Map<String, Object> body = new LinkedHashMap<>(job.toJson());
                body.put("ok", true);
                body.put("cancelled", pulled);
                if (!pulled) {
                    body.put("note", "too late — this job had already left the queue. Once bytes are "
                            + "at the driver the service cannot recall them.");
                }
                HttpApi.respond(exchange, 200, body);
            }
            case "reprint" -> reprint(exchange, job);
            default -> throw new HttpApi.BadRequestException(404,
                    "use /api/jobs/{id}/cancel or /api/jobs/{id}/reprint");
        }
    }

    /**
     * Send a finished job to the printer again, exactly as it went the first time.
     *
     * <p>The job's own payload and options, which is why the page selection was already spent when
     * the job was created: a reprint re-applies nothing, so "odd pages" cannot be applied twice.
     * A different printer may be named, which is the realistic case — the first one jammed.
     */
    private void reprint(HttpExchange exchange, Job job) throws IOException {
        if (!job.reprintable()) {
            throw new HttpApi.BadRequestException(410, "this job's document is no longer held — "
                    + "the history keeps recent documents only, so print the file again instead");
        }
        Map<String, Object> req = body(exchange);
        String printer = Json.str(req, "printer", "").trim();
        if (printer.isEmpty()) {
            printer = job.printer();
        }
        int copies = (int) Json.num(req, "copies", job.copies());
        Job again = router.submit(printer, job.type(), job.payload(), Math.max(1, copies),
                job.options(), job.title() == null ? "reprint" : job.title(), job.batchId(), "reprint");
        long wait = Json.num(req, "wait", 0);
        if (wait > 0) {
            again.await(Math.min(wait, 60_000));
        }
        Map<String, Object> body = new LinkedHashMap<>(again.toJson());
        body.put("reprintOf", job.id());
        HttpApi.respond(exchange, again.state() == Job.State.FAILED ? 502 : 200, body);
    }

    // ------------------------------------------------------------------ log

    /**
     * The tail of the log file.
     *
     * <p>Worth an endpoint because of where this software runs. The packaged build is windowed, so
     * there is no console; the log is the only account of what a driver did, and getting at it
     * today means talking someone through opening {@code %APPDATA%} over the phone while a shift
     * waits. Showing it in the panel makes "send me what it said" a copy and a paste.
     */
    private void log(HttpExchange exchange) throws IOException {
        int lines = (int) HttpApi.parseLong(HttpApi.query(exchange.getRequestURI()).get("lines"), 200);
        lines = Math.max(10, Math.min(2000, lines));
        Path file = Log.file();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("file", String.valueOf(file));
        if (file == null || !Files.isRegularFile(file)) {
            body.put("lines", List.of("(no log file yet)"));
            HttpApi.respond(exchange, 200, body);
            return;
        }
        Deque<String> tail = new ArrayDeque<>(lines);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == lines) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            body.put("lines", List.of("could not read the log: " + e));
            HttpApi.respond(exchange, 200, body);
            return;
        }
        body.put("lines", new ArrayList<>(tail));
        HttpApi.respond(exchange, 200, body);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] raw = HttpApi.readBody(exchange, MAX_UPLOAD_BYTES);
        if (raw.length == 0) {
            return new LinkedHashMap<>();
        }
        return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    }

    private static byte[] decodeBase64(String data) {
        int comma = data.startsWith("data:") ? data.indexOf(',') : -1;
        try {
            return Base64.getDecoder().decode(comma >= 0 ? data.substring(comma + 1) : data);
        } catch (IllegalArgumentException e) {
            throw new HttpApi.BadRequestException(400, "that is not valid base64: " + e.getMessage());
        }
    }

    /**
     * Hex pairs to bytes, for a raw payload pasted out of a printer manual.
     *
     * <p>Manuals write control sequences in hex ({@code 1B 40} for an ESC/POS reset) and there is
     * no way to type those into a textarea. Whitespace, commas and {@code 0x} prefixes are all
     * tolerated because every manual punctuates them differently.
     */
    private static byte[] decodeHex(String data) {
        String cleaned = data.replaceAll("(?i)0x", "").replaceAll("[^0-9a-fA-F]", "");
        if (cleaned.length() % 2 != 0) {
            throw new HttpApi.BadRequestException(400,
                    "hex needs an even number of digits — found " + cleaned.length());
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
