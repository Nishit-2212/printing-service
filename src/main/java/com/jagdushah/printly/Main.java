package com.jagdushah.printly;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Entry point: load config, open the loopback API, keep a warm socket per printer, sit in the tray. */
public final class Main {

    public static final String VERSION = "1.2.0";

    private static final CountDownLatch STOP = new CountDownLatch(1);

    public static void main(String[] args) {
        String configPath = null;
        boolean tray = true;
        boolean panel = true;
        Integer portOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> configPath = next(args, ++i, "--config");
                case "--port", "-p" -> portOverride = Integer.parseInt(next(args, ++i, "--port"));
                case "--no-tray" -> tray = false;
                case "--no-panel" -> panel = false;
                case "--version", "-v" -> {
                    System.out.println("Printly " + VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        Config config;
        try {
            Log.toFile(Config.userDir().resolve("printly.log"));
            config = Config.load(configPath);
            if (portOverride != null) {
                config = config.withPort(portOverride);
            }
        } catch (IOException | RuntimeException e) {
            Log.error("could not load configuration", e);
            System.exit(1);
            return;
        }

        Log.info("Printly " + VERSION + " starting");
        Log.info("config: " + config.file);
        Log.info("log:    " + Log.file());
        if (config.printers.isEmpty()) {
            Log.warn("no printers configured yet — add them to " + config.file + " and restart");
        }
        if (config.allowedOrigins.isEmpty()) {
            Log.warn("allowedOrigins is empty: any web page may submit print jobs. "
                    + "Add your app's origin to " + config.file + " once the URL is settled.");
        }

        // The Control Panel's own store, and the label printers it owns. Merged into the config
        // rather than written back into config.json, so that file keeps its comments — see
        // Config.withExtraPrinters.
        Store store = new Store(Config.userDir());
        if (panel) {
            store.seedIfEmpty();
            config = config.withExtraPrinters(store.labelPrinters());
        }

        PrintRouter router = new PrintRouter(config);

        ControlPanel controlPanel = null;
        BatchRunner batchRunner = null;
        if (panel) {
            Spool spool = new Spool(Config.userDir().resolve("spool"), ControlPanel.MAX_SPOOL_BYTES);
            batchRunner = new BatchRunner(router, spool);
            controlPanel = new ControlPanel(config, router, store, spool, batchRunner);
        }
        HttpApi api = new HttpApi(config, router, controlPanel);

        // Claim the port *before* opening any printer socket. A second accidental launch must
        // not connect to the printers at all: they accept one connection, and stealing it would
        // knock the already-running instance offline.
        try {
            api.start();
        } catch (BindException e) {
            if (alreadyRunning(config)) {
                Log.info("Printly is already running on port " + config.port + " — nothing to do");
                return;
            }
            Log.error("port " + config.port + " is in use by something else; change \"port\" in " + config.file, e);
            System.exit(1);
            return;
        } catch (IOException e) {
            Log.error("could not start the HTTP server", e);
            System.exit(1);
            return;
        }

        router.start();

        Log.info("listening on http://" + config.bindAddress + ":" + config.port
                + "  (endpoints: /health, /printers, /print, /jobs/{id})");
        if (panel) {
            Log.info("Control Panel: http://" + config.bindAddress + ":" + config.port + "/");
        }

        TrayUi trayUi = null;
        if (tray) {
            trayUi = new TrayUi(config, router, panel, Main::requestStop);
            if (!trayUi.install()) {
                trayUi = null;
            }
        }

        final TrayUi installedTray = trayUi;
        final BatchRunner installedBatches = batchRunner;
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> shutdown(api, router, installedTray, installedBatches), "shutdown"));

        try {
            STOP.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        shutdown(api, router, installedTray, installedBatches);
        System.exit(0);
    }

    private static void requestStop() {
        Log.info("quit requested");
        STOP.countDown();
    }

    private static boolean shuttingDown = false;

    private static synchronized void shutdown(HttpApi api, PrintRouter router, TrayUi tray,
            BatchRunner batches) {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        Log.info("shutting down");
        if (tray != null) {
            tray.remove();
        }
        api.stop();
        // Before the router, so a running batch stops submitting rather than racing the lanes as
        // they shut down and reporting every remaining file as a printer failure.
        if (batches != null) {
            batches.shutdown();
        }
        router.shutdown();
    }

    /**
     * A failed bind usually means our own second copy. Confirm by asking /health who is there,
     * so a genuine port clash with an unrelated service still reports as an error.
     */
    private static boolean alreadyRunning(Config config) {
        try {
            URI uri = URI.create("http://" + config.bindAddress + ":" + config.port + "/health");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            try (InputStream is = conn.getInputStream()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                // "print-bridge" is the pre-rename id. An old copy still holding the port is our
                // own process under its previous name, not an unrelated service on 9110.
                return body.contains("\"" + HttpApi.SERVICE_ID + "\"") || body.contains("\"print-bridge\"");
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    private static void printUsage() {
        System.out.println("""
                Printly — local TSPL print service

                  --config, -c <path>   configuration file to use
                  --port,   -p <n>      override the listening port
                  --no-tray             run without a system tray icon
                  --no-panel            run without the Control Panel UI and its endpoints
                  --version, -v         print the version and exit
                  --help,   -h          show this message
                """);
    }
}
