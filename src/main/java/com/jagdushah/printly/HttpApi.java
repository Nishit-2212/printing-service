package com.jagdushah.printly;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The local HTTP surface, bound to 127.0.0.1 only.
 *
 * <p>Plain HTTP is deliberate: the mixed-content spec treats {@code http://127.0.0.1} as a
 * potentially trustworthy origin, so an HTTPS page may call it without any TLS, certificate, or
 * OS trust-store step. Security comes from the loopback bind plus the Origin allow-list instead.
 */
public final class HttpApi {

    /** Marker in /health that lets a second launch recognise an already-running bridge. */
    public static final String SERVICE_ID = "printly";

    private final Config config;
    private final PrintRouter router;
    private final ControlPanel panel;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer server;
    private ExecutorService pool;

    /**
     * @param panel the Control Panel, or null to run headless as the pre-panel builds did.
     *              Null is not a degraded mode: a station whose only client is the web app needs
     *              nothing the panel adds, and {@code /health}, {@code /printers} and {@code /print}
     *              behave identically either way.
     */
    public HttpApi(Config config, PrintRouter router, ControlPanel panel) {
        this.config = config;
        this.router = router;
        this.panel = panel;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        // Without an explicit executor HttpServer runs handlers on the accept thread and
        // serialises every request, which would undo the per-printer parallelism.
        //
        // Sized for long polls, not for throughput. A caller that queues ahead holds one thread
        // per unfinished job for the length of its ?wait= — the bulk print page keeps two orders
        // in flight, which is four — plus its status poll. Eight was enough when every caller
        // printed one document at a time and waited; it is uncomfortably close now, and a pool
        // exhausted by waiters would stall the very /print that would free them.
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(24, r -> {
            Thread t = new Thread(r, "http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);

        server.createContext("/health", wrap(this::handleHealth));
        server.createContext("/printers", wrap(this::handlePrinters));
        server.createContext("/print", wrap(this::handlePrint));
        server.createContext("/preview", wrap(this::handlePreview));
        server.createContext("/preflight", wrap(this::handlePreflight));
        server.createContext("/jobs", wrap(this::handleJobs));
        server.createContext("/reconnect", wrap(this::handleReconnect));
        if (panel != null) {
            // The Control Panel's own endpoints. Kept under /api/ so they cannot collide with the
            // printing contract above — that contract has a published client (`printly-web`) whose
            // major version tracks it, and a panel feature must never be a reason to bump it.
            server.createContext("/api", wrap(panel::handleApi));
            server.createContext("/ui", wrap(panel::handleUi));
        }
        server.createContext("/", wrap(this::handleRoot));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private HttpHandler wrap(Handler handler) {
        return exchange -> {
            try {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                boolean allowed = config.originAllowed(origin);
                applyCors(exchange, origin, allowed);

                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(allowed ? 204 : 403, -1);
                    return;
                }
                if (!allowed) {
                    respond(exchange, 403, error("origin '" + origin + "' is not in allowedOrigins"));
                    return;
                }
                handler.handle(exchange);
            } catch (Json.SyntaxException e) {
                respond(exchange, 400, error("malformed JSON: " + e.getMessage()));
            } catch (PrintRouter.RejectedException e) {
                respond(exchange, e.httpStatus, error(e.getMessage()));
            } catch (BadRequestException e) {
                respond(exchange, e.status, error(e.getMessage()));
            } catch (Exception e) {
                Log.error("unhandled error on " + exchange.getRequestURI(), e);
                respond(exchange, 500, error(String.valueOf(e)));
            } finally {
                exchange.close();
            }
        };
    }

    private void applyCors(HttpExchange exchange, String origin, boolean allowed) {
        Headers h = exchange.getResponseHeaders();
        if (origin != null && allowed) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
        }
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Content-Type");
            h.set("Access-Control-Max-Age", "86400");
            // Chrome's Private Network Access check: a public HTTPS page reaching loopback is
            // preflighted and refused unless the response opts in explicitly.
            if (exchange.getRequestHeaders().getFirst("Access-Control-Request-Private-Network") != null) {
                h.set("Access-Control-Allow-Private-Network", "true");
            }
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    static final class BadRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int status;

        BadRequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ------------------------------------------------------------------ endpoints

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", SERVICE_ID);
        body.put("version", Main.VERSION);
        body.put("uptimeMs", System.currentTimeMillis() - startedAt);
        body.put("printers", router.labelStatuses());
        body.put("documentLane", router.hasDocumentLane());
        body.put("documentPrinters", router.documentPrinterCount());
        respond(exchange, 200, body);
    }

    private void handlePrinters(HttpExchange exchange) throws IOException {
        String lane = query(exchange.getRequestURI()).get("lane");
        List<Map<String, Object>> printers = router.listPrinters();
        if (lane != null && !lane.isBlank()) {
            printers = printers.stream().filter(p -> lane.equalsIgnoreCase(String.valueOf(p.get("lane")))).toList();
        }
        respond(exchange, 200, printers);
    }

    private void handleReconnect(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        router.reconnectAll();
        respond(exchange, 200, Map.of("ok", true, "reconnecting", true));
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String id = path.startsWith("/jobs/") ? path.substring("/jobs/".length()) : "";
        if (id.isBlank()) {
            throw new BadRequestException(400, "use GET /jobs/{jobId}");
        }
        Job job = router.jobs().get(id);
        if (job == null) {
            throw new BadRequestException(404, "unknown or expired job '" + id + "'");
        }
        long wait = parseLong(query(exchange.getRequestURI()).get("wait"), 0);
        if (wait > 0) {
            job.await(Math.min(wait, 60_000));
        }
        respond(exchange, 200, job.toJson());
    }

    private void handlePrint(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        byte[] raw = readBody(exchange, config.maxBodyBytes);
        Map<String, Object> req = Json.parseObject(new String(raw, StandardCharsets.UTF_8));

        String printer = Json.str(req, "printer", "").trim();
        if (printer.isEmpty()) {
            throw new BadRequestException(400, "\"printer\" is required");
        }
        String type = Json.str(req, "type", "tspl").trim().toLowerCase(Locale.ROOT);
        int copies = (int) Json.num(req, "copies", 1);
        if (copies < 1 || copies > 1000) {
            throw new BadRequestException(400, "\"copies\" must be between 1 and 1000");
        }
        Object data = req.get("data");
        if (!(data instanceof String text)) {
            throw new BadRequestException(400, "\"data\" must be a string");
        }
        byte[] payload = decode(printer, type, text, Json.str(req, "encoding", null));
        if (payload.length == 0) {
            throw new BadRequestException(400, "\"data\" decoded to zero bytes");
        }

        // Paper geometry is optional, but a malformed one is a 400 rather than a silent
        // fallback: these numbers are calibrated against physical output, and quietly
        // ignoring a typo prints a label the courier's scanner will reject.
        PrintOptions options;
        try {
            options = PrintOptions.from(req.get("options"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(400, "\"options\": " + e.getMessage());
        }

        Job job = router.submit(printer, type, payload, copies, options);

        long wait = parseLong(query(exchange.getRequestURI()).get("wait"), Json.num(req, "wait", 0));
        if (wait > 0) {
            job.await(Math.min(wait, 60_000));
        }
        // 202 while still in flight, 200 once printed, 502 when the printer refused it.
        int status = switch (job.state()) {
            case DONE -> 200;
            case FAILED -> 502;
            default -> 202;
        };
        Map<String, Object> body = new LinkedHashMap<>(job.toJson());
        body.put("queued", true);
        respond(exchange, status, body);
    }

    /**
     * Render a document exactly as this printer would print it, and return it as a PNG.
     *
     * <p>The same page composition and the same PDFBox renderer the print path uses, aimed at an
     * image instead of a driver. That is the whole value: a mock-up would agree with the printer
     * right up to the moment it mattered, whereas this cannot disagree without PDFBox or the
     * driver itself having changed underneath both.
     *
     * <p>Body is {@code /print}'s, minus {@code copies}: {@code printer}, {@code data} (base64
     * PDF), {@code options}. Plus {@code page} (1-based, default 1), {@code dpi} and
     * {@code overlay} (default true — the sheet edge, the resolved rectangle and the strip the
     * head cannot reach).
     */
    private void handlePreview(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        Map<String, Object> req = body(exchange);
        String printer = requirePrinter(req);
        byte[] pdf = requirePdf(req, printer);
        PrintOptions options = options(req);

        int page = (int) Json.num(req, "page", 1);
        if (page < 1) {
            throw new BadRequestException(400, "\"page\" is 1-based");
        }
        double dpi = Json.dbl(req, "dpi", 0);
        boolean overlay = Json.bool(req, "overlay", true);
        try {
            respond(exchange, 200, ok(router.preview(printer, pdf, options, page - 1, dpi, overlay)));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(400, e.getMessage());
        } catch (IllegalStateException e) {
            // The lane could not be reached — busy, shutting down, queue full. Not the caller's
            // mistake, and a retry is the right response, so it is a 503 rather than a 400.
            throw new BadRequestException(503, e.getMessage());
        }
    }

    /**
     * Answer whether a profile fits this printer's loaded media, before anything prints.
     *
     * <p>This is the guardrail under the calibration screen. The margins in those profiles are
     * numbers where a wrong value prints a barcode the courier's scanner rejects, so exposing them
     * to be dragged is only safe alongside something that can say "4x10in will be clamped to 4x6
     * on this printer" without burning a label to find out.
     *
     * <p>{@code data} is optional here — see {@code DocumentLane.preflight} for what that costs.
     */
    private void handlePreflight(HttpExchange exchange) throws IOException {
        requirePost(exchange);
        Map<String, Object> req = body(exchange);
        String printer = requirePrinter(req);
        PrintOptions options = options(req);
        byte[] pdf = req.get("data") == null ? null : requirePdf(req, printer);
        try {
            respond(exchange, 200, ok(router.preflight(printer, pdf, options)));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(400, e.getMessage());
        } catch (IllegalStateException e) {
            throw new BadRequestException(503, e.getMessage());
        }
    }

    private Map<String, Object> body(HttpExchange exchange) throws IOException {
        byte[] raw = readBody(exchange, config.maxBodyBytes);
        return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    }

    private static String requirePrinter(Map<String, Object> req) {
        String printer = Json.str(req, "printer", "").trim();
        if (printer.isEmpty()) {
            throw new BadRequestException(400, "\"printer\" is required");
        }
        return printer;
    }

    private byte[] requirePdf(Map<String, Object> req, String printer) {
        Object data = req.get("data");
        if (!(data instanceof String text)) {
            throw new BadRequestException(400, "\"data\" must be a base64 string");
        }
        byte[] payload = decode(printer, "pdf", text, Json.str(req, "encoding", null));
        if (payload.length == 0) {
            throw new BadRequestException(400, "\"data\" decoded to zero bytes");
        }
        return payload;
    }

    /** Same parse and the same refusal as {@code /print}, so a profile cannot preview differently. */
    private static PrintOptions options(Map<String, Object> req) {
        try {
            return PrintOptions.from(req.get("options"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(400, "\"options\": " + e.getMessage());
        }
    }

    private static Map<String, Object> ok(Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.putAll(body);
        return m;
    }

    /**
     * The catch-all: the Control Panel at the root, a JSON 404 everywhere else.
     *
     * <p>Only the exact root serves the panel. Anything else that reaches here is a caller with the
     * wrong path, and answering that with an HTML page instead of a JSON error would turn a typo in
     * a fetch URL into "the response was not JSON", which is a much longer way to the same fix.
     */
    private void handleRoot(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (panel != null && ("/".equals(path) || "/index.html".equals(path))) {
            panel.handleUi(exchange);
            return;
        }
        respond(exchange, 404, error("no such endpoint — try /health, /printers, /print, "
                + "/preview, /preflight, /jobs/{id}"
                + (panel == null ? "" : ", or / for the Control Panel")));
    }

    // ------------------------------------------------------------------ payload decoding

    /**
     * TSPL is text by default; raw and PDF are base64. Base64 is always available for TSPL too,
     * which matters as soon as a label embeds binary image data.
     */
    private byte[] decode(String printer, String type, String data, String encoding) {
        String enc = encoding == null || encoding.isBlank()
                ? (PrintRouter.isDocumentType(type) || "raw".equals(type) ? "base64" : "utf8")
                : encoding.trim().toLowerCase(Locale.ROOT);
        return switch (enc) {
            case "base64" -> {
                try {
                    yield Base64.getDecoder().decode(stripDataUri(data));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException(400, "\"data\" is not valid base64: " + e.getMessage());
                }
            }
            case "utf8", "utf-8", "text" -> {
                if (PrintRouter.isDocumentType(type)) {
                    throw new BadRequestException(400, "PDF payloads must be base64-encoded");
                }
                yield router.encodeText(printer, data);
            }
            default -> throw new BadRequestException(400, "unknown encoding '" + enc + "' (use utf8 or base64)");
        };
    }

    private static String stripDataUri(String s) {
        int comma = s.startsWith("data:") ? s.indexOf(',') : -1;
        return comma >= 0 ? s.substring(comma + 1) : s;
    }

    // ------------------------------------------------------------------ helpers

    static void requirePost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new BadRequestException(405, "use POST for " + exchange.getRequestURI().getPath());
        }
    }

    static byte[] readBody(HttpExchange exchange, int max) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > max) {
                    throw new BadRequestException(413, "request body exceeds maxBodyBytes (" + max + ")");
                }
            }
            return buf.toByteArray();
        }
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8), value);
        }
        return out;
    }

    static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
