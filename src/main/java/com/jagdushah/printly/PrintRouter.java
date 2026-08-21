package com.jagdushah.printly;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sends a job down the right lane: TSPL/raw to a warm socket, PDF to the OS spooler. */
public final class PrintRouter {

    /** Refusal reasons the HTTP layer maps onto status codes. */
    public static final class RejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final int httpStatus;

        RejectedException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    private final Map<String, PrinterConnection> byName = new LinkedHashMap<>();
    private final DocumentLane documents;
    private final JobRegistry jobs;

    public PrintRouter(Config config) {
        for (PrinterTarget target : config.printers) {
            byName.put(target.name().toLowerCase(Locale.ROOT), new PrinterConnection(target, config));
        }
        this.documents = config.documentLane ? new DocumentLane(config.documentThreads) : null;
        this.jobs = new JobRegistry(config.jobHistory);
    }

    public void start() {
        byName.values().forEach(PrinterConnection::start);
    }

    public JobRegistry jobs() {
        return jobs;
    }

    public boolean hasDocumentLane() {
        return documents != null;
    }

    /** How many OS printers the document lane can see, so /health can tell "up but empty" apart. */
    public int documentPrinterCount() {
        return documents == null ? 0 : documents.list().size();
    }

    public PrinterConnection label(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Encodes text payloads with the target printer's charset and line-ending rule. */
    public byte[] encodeText(String printerName, String data) {
        PrinterConnection c = label(printerName);
        return c != null ? c.target().encodeText(data) : data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * @param options paper geometry for document jobs; pass {@link PrintOptions#NONE} for none.
     *                Label jobs ignore it — TSPL carries its own geometry in the command stream.
     */
    public Job submit(String printerName, String type, byte[] payload, int copies, PrintOptions options) {
        // Route before registering. A rejected job would otherwise sit in the registry as QUEUED
        // for ever, since the caller gets an exception rather than the id needed to poll it.
        if (isDocumentType(type)) {
            if (documents == null) {
                throw new RejectedException(400, "the document lane is disabled in config.json");
            }
            if (!documents.has(printerName)) {
                throw new RejectedException(404, "no OS printer named '" + printerName + "'");
            }
            Job job = new Job(printerName, type, payload, copies, options);
            jobs.put(job);
            documents.submit(job);
            return job;
        }
        PrinterConnection connection = label(printerName);
        if (connection == null) {
            throw new RejectedException(404, "no label printer named '" + printerName + "' in config.json");
        }
        Job job = new Job(printerName, type, payload, copies, options);
        jobs.put(job);
        if (!connection.submit(job)) {
            String reason = "queue for '" + printerName + "' is full — printer is not keeping up";
            // Settle it rather than leaving a QUEUED entry nothing will ever pick up.
            job.fail(reason);
            throw new RejectedException(503, reason);
        }
        return job;
    }

    public static boolean isDocumentType(String type) {
        return "pdf".equals(type) || "document".equals(type);
    }

    public List<Map<String, Object>> listPrinters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrinterConnection c : byName.values()) {
            out.add(c.status());
        }
        if (documents != null) {
            out.addAll(documents.list());
        }
        return out;
    }

    public Map<String, Object> labelStatuses() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (PrinterConnection c : byName.values()) {
            m.put(c.target().name(), c.online() ? "online" : "offline");
        }
        return m;
    }

    public List<PrinterConnection> labelPrinters() {
        return new ArrayList<>(byName.values());
    }

    public void reconnectAll() {
        byName.values().forEach(PrinterConnection::reconnect);
    }

    public void shutdown() {
        byName.values().forEach(PrinterConnection::shutdown);
        if (documents != null) {
            documents.shutdown();
        }
    }
}
