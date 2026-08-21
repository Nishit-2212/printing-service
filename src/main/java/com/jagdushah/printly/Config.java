package com.jagdushah.printly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The bridge's settings, loaded once at startup from a JSON file. */
public final class Config {

    public static final String DEFAULT_BIND = "127.0.0.1";
    public static final int DEFAULT_PORT = 9110;

    public final Path file;
    public final String bindAddress;
    public final int port;
    /** Empty means "accept any Origin" — convenient while bringing a station up, noisy on purpose. */
    public final Set<String> allowedOrigins;
    public final List<PrinterTarget> printers;
    public final int connectTimeoutMs;
    public final int idleCheckMs;
    public final int queueCapacity;
    public final int jobHistory;
    public final int maxBodyBytes;
    public final boolean documentLane;
    public final int documentThreads;

    private Config(Path file, Map<String, Object> root) {
        this.file = file;
        this.bindAddress = Json.str(root, "bindAddress", DEFAULT_BIND);
        this.port = (int) Json.num(root, "port", DEFAULT_PORT);
        this.connectTimeoutMs = (int) Json.num(root, "connectTimeoutMs", 3000);
        this.idleCheckMs = (int) Json.num(root, "idleCheckMs", 30000);
        this.queueCapacity = (int) Json.num(root, "queueCapacity", 1000);
        this.jobHistory = (int) Json.num(root, "jobHistory", 500);
        this.maxBodyBytes = (int) Json.num(root, "maxBodyBytes", 8 * 1024 * 1024);
        this.documentLane = Json.bool(root, "documentLane", true);
        // No longer used. The document lane runs one worker thread per printer, which is what
        // keeps a run of labels in the order they were queued; a shared pool could not promise
        // that. Different printers still print in parallel, which is the concurrency the packing
        // page actually needs. Still parsed so an existing config.json is not suddenly "wrong",
        // and still reported by /health for anyone diffing a station's settings.
        this.documentThreads = clamp((int) Json.num(root, "documentThreads", 4), 1, 16);

        Set<String> origins = new LinkedHashSet<>();
        for (Object o : Json.arr(root, "allowedOrigins")) {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
                origins.add(normalizeOrigin(s));
            }
        }
        this.allowedOrigins = Set.copyOf(origins);

        List<PrinterTarget> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : Json.arr(root, "printers")) {
            PrinterTarget t = PrinterTarget.from(Json.obj(o));
            if (!seen.add(t.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate printer name '" + t.name() + "'");
            }
            found.add(t);
        }
        this.printers = List.copyOf(found);
    }

    /** Copy with a different listening port, for the {@code --port} command-line override. */
    private Config(Config base, int port) {
        this.file = base.file;
        this.bindAddress = base.bindAddress;
        this.port = port;
        this.allowedOrigins = base.allowedOrigins;
        this.printers = base.printers;
        this.connectTimeoutMs = base.connectTimeoutMs;
        this.idleCheckMs = base.idleCheckMs;
        this.queueCapacity = base.queueCapacity;
        this.jobHistory = base.jobHistory;
        this.maxBodyBytes = base.maxBodyBytes;
        this.documentLane = base.documentLane;
        this.documentThreads = base.documentThreads;
    }

    public Config withPort(int port) {
        return new Config(this, port);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Origins compare scheme+host+port only; a trailing slash or path in the config is forgiving. */
    static String normalizeOrigin(String raw) {
        String s = raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            if (slash >= 0) {
                s = s.substring(0, slash);
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public boolean originAllowed(String origin) {
        if (origin == null || origin.isBlank() || origin.equals("null")) {
            // Non-browser callers (curl, the tray's own self-check) send no Origin.
            return true;
        }
        if (allowedOrigins.isEmpty()) {
            return true;
        }
        return allowedOrigins.contains(normalizeOrigin(origin));
    }

    // ------------------------------------------------------------------ loading

    /**
     * Resolve the config file, creating a commented starter one if nothing exists yet.
     *
     * <p>Search order: explicit path argument, {@code -Dprintly.config},
     * {@code PRINTLY_CONFIG}, the per-user config dir, then {@code ./config.json}.
     */
    public static Config load(String explicitPath) throws IOException {
        Path path = resolvePath(explicitPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent() == null ? Paths.get(".") : path.getParent());
            Files.writeString(path, starterConfig(), StandardCharsets.UTF_8);
            Log.warn("no config found — wrote a starter one to " + path);
            Log.warn("edit it to add your printers, then restart the bridge");
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return new Config(path, Json.parseObject(text));
    }

    private static Path resolvePath(String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Paths.get(explicitPath).toAbsolutePath();
        }
        String prop = System.getProperty("printly.config");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop).toAbsolutePath();
        }
        String env = System.getenv("PRINTLY_CONFIG");
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath();
        }
        Path local = Paths.get("config.json").toAbsolutePath();
        if (Files.exists(local)) {
            return local;
        }
        return userDir().resolve("config.json");
    }

    /**
     * Per-user data directory: %APPDATA%\Printly, ~/Library/Application Support/Printly, or XDG.
     *
     * <p>Resolved once and cached, because the first call also performs the one-time move from
     * the pre-rename PrintBridge directory. A pack PC upgrading from Print Bridge would otherwise
     * come up with an empty config, write a starter one, and sit there with no printers until
     * someone re-typed every IP — which is exactly the kind of silent regression an upgrade must
     * not have.
     */
    public static Path userDir() {
        Path resolved = userDir;
        if (resolved != null) {
            return resolved;
        }
        synchronized (Config.class) {
            if (userDir == null) {
                Path dir = dataDir("Printly", "printly");
                adoptLegacyDir(dir);
                userDir = dir;
            }
            return userDir;
        }
    }

    private static volatile Path userDir;

    private static Path dataDir(String brandedName, String unixName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, brandedName);
            }
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", brandedName);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Paths.get(xdg, unixName);
        }
        return Paths.get(System.getProperty("user.home"), ".config", unixName);
    }

    /**
     * Move a Print Bridge data directory to its Printly name, once.
     *
     * <p>Only ever runs when the new directory does not exist yet, so a machine that has already
     * migrated — or a fresh install — is untouched. A whole-directory move is tried first; if the
     * OS refuses (an open log handle from a still-running old instance is the realistic case) the
     * config is copied instead, which is the file that actually matters. Failure is never fatal:
     * the worst outcome is the starter config, which is where we would have been anyway.
     */
    private static void adoptLegacyDir(Path target) {
        Path legacy = dataDir("PrintBridge", "print-bridge");
        if (legacy.equals(target) || Files.exists(target) || !Files.isDirectory(legacy)) {
            return;
        }
        try {
            Files.move(legacy, target);
            Files.deleteIfExists(target.resolve("print-bridge.log"));
            Log.info("adopted the existing Print Bridge configuration from " + legacy);
            return;
        } catch (IOException | RuntimeException e) {
            Log.warn("could not move " + legacy + " to " + target + " (" + e + ") — copying the config instead");
        }
        try {
            Path config = legacy.resolve("config.json");
            if (Files.isRegularFile(config)) {
                Files.createDirectories(target);
                Files.copy(config, target.resolve("config.json"));
                Log.info("copied the existing Print Bridge configuration from " + config);
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("could not carry the Print Bridge configuration over from " + legacy + ": " + e);
        }
    }

    public Path logFile() {
        return userDir().resolve("printly.log");
    }

    private static String starterConfig() {
        return """
                {
                  // Printly configuration.
                  // Restart the bridge after editing. Comments are allowed in this file.

                  "port": 9110,

                  // Origins your web app is served from. Leave the list empty to accept any
                  // origin (handy on day one, but tighten it once the app URL is settled).
                  "allowedOrigins": [
                    // "https://app.example.com"
                  ],

                  // Label printers, reached over a raw socket. "name" is what the frontend prints to.
                  "printers": [
                    // { "name": "pack-1", "host": "192.168.1.40", "port": 9100, "note": "MRP + carton" }
                  ],

                  // How long to wait for a printer to accept a TCP connection.
                  "connectTimeoutMs": 3000,

                  // How often an idle warm socket is checked for liveness. This is also what
                  // refreshes the online/offline status shown in the tray and /printers.
                  "idleCheckMs": 30000,

                  // Per-printer queue depth before /print starts replying 503.
                  "queueCapacity": 1000,

                  // Route type:"pdf" jobs to OS printers, rasterized with PDFBox.
                  "documentLane": true,

                  // Unused since the document lane went to one worker per printer. Kept so an
                  // upgraded station's config still reads cleanly. 1-16.
                  "documentThreads": 4
                }
                """;
    }
}
