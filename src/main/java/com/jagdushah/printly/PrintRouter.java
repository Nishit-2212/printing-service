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

    /**
     * The label printers, by lower-cased name.
     *
     * <p>Concurrent rather than a plain map because the Control Panel can add and remove printers
     * while a shift is running, and the alternative — restart the service to pick up a new IP — is
     * exactly the friction the panel exists to remove. Display order is by name (see
     * {@link #labelPrinters()}) rather than insertion, which is also the order a person looks for
     * a printer in.
     */
    private final Map<String, PrinterConnection> byName = new java.util.concurrent.ConcurrentHashMap<>();
    private final Config config;
    private final DocumentLane documents;
    private final JobRegistry jobs;
    private volatile boolean started;

    public PrintRouter(Config config) {
        this.config = config;
        for (PrinterTarget target : config.printers) {
            byName.put(target.name().toLowerCase(Locale.ROOT), new PrinterConnection(target, config));
        }
        this.documents = config.documentLane ? new DocumentLane(config) : null;
        this.jobs = new JobRegistry(config.jobHistory);
    }

    public void start() {
        started = true;
        byName.values().forEach(PrinterConnection::start);
    }

    /**
     * Add or replace a label printer without a restart.
     *
     * <p>Replacing means the old connection is shut down first, which fails anything still queued
     * on it. That is the right trade: the printer being replaced is one whose address just changed,
     * so a job queued for the old address was never going to come out anyway, and leaving a worker
     * holding a socket to an IP nobody uses is how a station ends up with two things fighting over
     * one printer's single connection slot.
     */
    public synchronized void addLabelPrinter(PrinterTarget target) {
        String key = target.name().toLowerCase(Locale.ROOT);
        PrinterConnection previous = byName.remove(key);
        if (previous != null) {
            previous.shutdown();
        }
        PrinterConnection connection = new PrinterConnection(target, config);
        byName.put(key, connection);
        if (started) {
            connection.start();
        }
        Log.info("label printer '" + target.name() + "' -> " + target.address()
                + (previous == null ? " added" : " replaced"));
    }

    /** @return true when a printer of that name was there to remove */
    public synchronized boolean removeLabelPrinter(String name) {
        if (name == null) {
            return false;
        }
        PrinterConnection removed = byName.remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        removed.shutdown();
        Log.info("label printer '" + name + "' removed");
        return true;
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
        return submit(printerName, type, payload, copies, options, null, null, null);
    }

    /**
     * Submit with the labelling the Control Panel needs on top.
     *
     * <p>{@code title}, {@code batchId} and {@code strategy} are metadata only — nothing in the
     * print path reads them. They are what turns the recent-jobs list from a column of {@code j_*}
     * ids into something an operator can use to find the order that did not come out.
     */
    public Job submit(String printerName, String type, byte[] payload, int copies, PrintOptions options,
            String title, String batchId, String strategy) {
        // Route before registering. A rejected job would otherwise sit in the registry as QUEUED
        // for ever, since the caller gets an exception rather than the id needed to poll it.
        if (isDocumentType(type)) {
            if (documents == null) {
                throw new RejectedException(400, "the document lane is disabled in config.json");
            }
            if (!documents.has(printerName)) {
                throw new RejectedException(404, "no OS printer named '" + printerName + "'");
            }

            // A page selection is spent here, before the job exists, so that everything downstream
            // — the lane, the preview, a reprint — sees an ordinary document that happens to be
            // the pages that were asked for. Doing it any later would mean every one of those
            // paths having to know about selections, and a reprint re-applying one.
            String pagesNote = null;
            PageSelection selection = options.pages();
            if (selection != null) {
                try {
                    PdfSplitter.Applied applied = PdfSplitter.apply(payload, selection);
                    pagesNote = selection.describe(applied.sourcePages());
                    payload = applied.pdf();
                    options = options.withoutPages();
                } catch (IllegalArgumentException e) {
                    // The selection matched no page of this document. A 400: the caller asked for
                    // something specific and it is not there, and printing the whole document
                    // instead would be the wrong paper coming out of the wrong printer.
                    throw new RejectedException(400, e.getMessage());
                } catch (java.io.IOException e) {
                    throw new RejectedException(400, "could not read the PDF: " + e.getMessage());
                }
            }

            Job job = new Job(printerName, type, payload, copies, options);
            job.describe(title, pagesNote);
            job.attribute(batchId, strategy);
            jobs.put(job);
            if (!documents.submit(job)) {
                String reason = "document queue for '" + printerName
                        + "' is full — printer is not keeping up";
                // Settle it here, exactly as the label lane does: a job left QUEUED after the
                // caller got an exception is one nothing will ever pick up or time out.
                job.fail(reason);
                throw new RejectedException(503, reason);
            }
            return job;
        }
        PrinterConnection connection = label(printerName);
        if (connection == null) {
            throw new RejectedException(404, "no label printer named '" + printerName + "' in config.json");
        }
        Job job = new Job(printerName, type, payload, copies, options);
        job.describe(title, null);
        job.attribute(batchId, strategy);
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

    /**
     * Render what a document job would put on paper, without putting it there.
     *
     * <p>Document lane only, and deliberately so: a TSPL label carries its geometry in the command
     * stream rather than in {@code options}, so there is no page here to compose or to preview.
     * The honest answer for a label printer is that this endpoint does not apply to it.
     */
    public Map<String, Object> preview(String printerName, byte[] pdf, PrintOptions options,
            int pageIndex, double dpi, boolean overlay) {
        return documents(printerName).preview(printerName, pdf, options, pageIndex, dpi, overlay);
    }

    /** Resolve a profile against a printer's loaded media and report it. Prints nothing. */
    public Map<String, Object> preflight(String printerName, byte[] pdf, PrintOptions options) {
        return documents(printerName).preflight(printerName, pdf, options);
    }

    /** The document lane, or the same refusals {@link #submit} would have given for this printer. */
    private DocumentLane documents(String printerName) {
        if (documents == null) {
            throw new RejectedException(400, "the document lane is disabled in config.json");
        }
        if (!documents.has(printerName)) {
            throw new RejectedException(404, "no OS printer named '" + printerName + "'");
        }
        return documents;
    }

    public List<Map<String, Object>> listPrinters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrinterConnection c : labelPrinters()) {
            out.add(c.status());
        }
        if (documents != null) {
            out.addAll(documents.list());
        }
        return out;
    }

    public Map<String, Object> labelStatuses() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (PrinterConnection c : labelPrinters()) {
            m.put(c.target().name(), c.online() ? "online" : "offline");
        }
        return m;
    }

    /** Every label printer, by name, so both the tray and {@code /printers} read in one order. */
    public List<PrinterConnection> labelPrinters() {
        List<PrinterConnection> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> a.target().name().compareToIgnoreCase(b.target().name()));
        return out;
    }

    public void reconnectAll() {
        byName.values().forEach(PrinterConnection::reconnect);
        if (documents != null) {
            // The document lane has no socket to drop, but it does cache a print context per
            // printer. A driver reconfigured mid-shift would otherwise need a service restart.
            documents.reconnectAll();
        }
    }

    public void shutdown() {
        byName.values().forEach(PrinterConnection::shutdown);
        if (documents != null) {
            documents.shutdown();
        }
    }
}
