package com.jagdushah.printly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One print request: immutable payload plus a mutable outcome the API can report back. */
public final class Job {

    public enum State {
        QUEUED, PRINTING, DONE, FAILED,
        /**
         * Pulled out of its queue before the lane reached it.
         *
         * <p>Distinct from FAILED because nothing went wrong and nothing needs investigating — an
         * operator changed their mind about a batch. Both report {@code ok:false}, which is what
         * matters to a caller deciding whether the paper came out.
         */
        CANCELLED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();

    private final String id;
    private final String printer;
    private final String type;
    private volatile byte[] payload;
    private final int payloadBytes;
    private final int copies;
    private final PrintOptions options;
    private final long createdAt = System.currentTimeMillis();
    private final CountDownLatch settled = new CountDownLatch(1);

    private volatile State state = State.QUEUED;
    private volatile String error;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile long renderMs = -1;
    private volatile long setupMs = -1;
    private volatile long spoolMs = -1;
    private volatile ResolvedPage page;
    private volatile String title;
    private volatile String pagesNote;
    private volatile String batchId;
    private volatile String strategy;

    public Job(String printer, String type, byte[] payload, int copies, PrintOptions options) {
        this.id = "j_" + Long.toString(SEQ.incrementAndGet(), 36);
        this.printer = printer;
        this.type = type;
        this.payload = payload;
        this.payloadBytes = payload.length;
        this.copies = copies;
        this.options = options == null ? PrintOptions.NONE : options;
    }

    public String id() {
        return id;
    }

    public String printer() {
        return printer;
    }

    public String type() {
        return type;
    }

    /**
     * The single-copy payload. Repetition for {@link #copies()} happens at the lane.
     *
     * <p>Null once {@link #releasePayload()} has reclaimed it, which only happens to a settled job.
     * The print path always sees it non-null; only a reprint of an old job can find it gone, and
     * {@link #reprintable()} is how to ask first.
     */
    public byte[] payload() {
        return payload;
    }

    /** How big the payload was, which stays reportable after the bytes themselves are released. */
    public int payloadBytes() {
        return payloadBytes;
    }

    /** True while the bytes are still held, so this job can be sent to the printer again. */
    public boolean reprintable() {
        return payload != null;
    }

    /**
     * Drop the payload of a finished job.
     *
     * <p>The registry keeps the last few hundred jobs so the panel can reprint one, and each of
     * them was holding its document. Five hundred courier invoices is comfortably more than the
     * 128 MB heap the service is packaged with, and the failure mode is an OutOfMemoryError that
     * takes printing down mid-shift — caused by the history feature rather than by any printing.
     * So the bytes are reclaimed oldest-first past a budget ({@link JobRegistry}); recent jobs stay
     * reprintable, which is the only part of the history anyone reprints from.
     *
     * <p>Only ever called on a settled job: a queued one still has to print, and a printing one may
     * be retried on a fresh socket by the label lane.
     */
    void releasePayload() {
        if (settled()) {
            payload = null;
        }
    }

    public int copies() {
        return copies;
    }

    /**
     * Paper geometry for this job. Never null — {@link PrintOptions#NONE} when the caller sent
     * none, meaning the printer's own defaults apply. Only the document lane reads this; a TSPL
     * label carries its geometry in the command stream itself.
     */
    public PrintOptions options() {
        return options;
    }

    /**
     * The page the document lane actually composed for this job, once it has composed one.
     *
     * <p>Null for a label job, and for a document job that failed before the geometry was worked
     * out. Anything else and this is the answer to "what did the service think it was printing" —
     * a question that previously needed reflection into a private method to answer, and by then
     * the job was long gone.
     */
    public ResolvedPage resolvedPage() {
        return page;
    }

    void resolvedPage(ResolvedPage page) {
        this.page = page;
    }

    /**
     * What a person would call this job — a file name, or "raw label".
     *
     * <p>Nothing in the print path reads it. It exists because a recent-jobs list of
     * {@code j_1 … j_47} on one printer is unusable for the thing an operator actually does with
     * it, which is find the order that did not come out and reprint it.
     */
    public String title() {
        return title;
    }

    void describe(String title, String pagesNote) {
        this.title = title;
        this.pagesNote = pagesNote;
    }

    /** Which batch and which strategy rule produced this job, for grouping in the panel. */
    void attribute(String batchId, String strategy) {
        this.batchId = batchId;
        this.strategy = strategy;
    }

    public String batchId() {
        return batchId;
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public boolean settled() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    /**
     * Take this job out of its queue, if it has not started.
     *
     * <p>Best-effort by design, and the boundary is exact: a job still QUEUED is cancelled here and
     * its lane skips it when it gets there, which is a real cancel. A job already PRINTING has
     * bytes in the driver or on the wire and the service cannot recall them — the honest answer is
     * false, and the caller is told so rather than shown a cancel that did nothing.
     *
     * @return true when the job will not print
     */
    public synchronized boolean cancel() {
        if (state != State.QUEUED) {
            return false;
        }
        finishedAt = System.currentTimeMillis();
        state = State.CANCELLED;
        settled.countDown();
        return true;
    }

    /**
     * Record where a document job's time actually went.
     *
     * <p>Three phases, split because they fail differently and are fixed in different places:
     *
     * <ul>
     *   <li>{@code renderMs} — parsing the PDF. Slow means a bloated document upstream. Normally
     *       0-2ms, so anything larger is the interesting kind of surprise.</li>
     *   <li>{@code setupMs} — acquiring the print context and attaching the page. Slow means the
     *       driver, or a context that could not be reused; it is ours to fix.</li>
     *   <li>{@code spoolMs} — {@link java.awt.print.PrinterJob#print}. This is the driver and the
     *       print head, and on a thermal printer it does not return until the label is out, so it
     *       is mostly not ours to fix. Knowing that is the point of splitting it out.</li>
     * </ul>
     *
     * <p>Queue wait is not passed in; it falls out of {@code startedAt - createdAt} and is
     * reported as {@code queueMs}. It matters once callers stop printing one at a time: a
     * non-zero queue wait means the printer is saturated, which is the good kind of busy.
     */
    void timing(long renderMs, long setupMs, long spoolMs) {
        this.renderMs = renderMs;
        this.setupMs = setupMs;
        this.spoolMs = spoolMs;
    }

    /** One-line timing breakdown for the log; the same numbers {@link #toJson} reports. */
    public String timingLine() {
        StringBuilder sb = new StringBuilder(72);
        sb.append("queue=").append(Math.max(0, queueMs())).append("ms");
        if (renderMs >= 0) {
            sb.append(" render=").append(renderMs).append("ms");
        }
        if (setupMs >= 0) {
            sb.append(" setup=").append(setupMs).append("ms");
        }
        if (spoolMs >= 0) {
            sb.append(" spool=").append(spoolMs).append("ms");
        }
        if (finishedAt > 0) {
            sb.append(" total=").append(finishedAt - createdAt).append("ms");
        }
        return sb.toString();
    }

    private long queueMs() {
        return startedAt > 0 ? startedAt - createdAt : 0;
    }

    /**
     * Claim the job for a lane.
     *
     * <p>Synchronized against {@link #cancel()}, and the reason the two must be: the window
     * between a lane taking a job off its queue and writing its bytes is exactly where a cancel
     * arrives, and losing that race in the other direction prints a label the operator cancelled.
     *
     * @return false when the job was cancelled first and must be skipped
     */
    synchronized boolean markPrinting() {
        if (state != State.QUEUED) {
            return state == State.PRINTING;
        }
        state = State.PRINTING;
        startedAt = System.currentTimeMillis();
        return true;
    }

    void complete() {
        if (settled()) {
            return;
        }
        finishedAt = System.currentTimeMillis();
        state = State.DONE;
        settled.countDown();
    }

    void fail(String reason) {
        if (settled()) {
            return;
        }
        finishedAt = System.currentTimeMillis();
        error = reason;
        state = State.FAILED;
        settled.countDown();
    }

    /** Block until the job finishes, or the timeout expires. Returns true if it settled. */
    public boolean await(long millis) {
        if (millis <= 0) {
            return settled();
        }
        try {
            return settled.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return settled();
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", id);
        m.put("printer", printer);
        m.put("type", type);
        m.put("state", state.wire());
        m.put("ok", state == State.DONE);
        if (title != null) {
            m.put("title", title);
        }
        if (pagesNote != null) {
            m.put("pagesNote", pagesNote);
        }
        if (batchId != null) {
            m.put("batchId", batchId);
        }
        if (strategy != null) {
            m.put("strategy", strategy);
        }
        m.put("copies", copies);
        m.put("bytes", payloadBytes);
        m.put("reprintable", payload != null);
        m.put("createdAt", createdAt);
        if (startedAt > 0) {
            m.put("startedAt", startedAt);
        }
        if (finishedAt > 0) {
            m.put("finishedAt", finishedAt);
            m.put("durationMs", finishedAt - createdAt);
        }
        if (startedAt > 0) {
            m.put("queueMs", queueMs());
        }
        if (renderMs >= 0) {
            m.put("renderMs", renderMs);
        }
        if (setupMs >= 0) {
            m.put("setupMs", setupMs);
        }
        if (spoolMs >= 0) {
            m.put("spoolMs", spoolMs);
        }
        if (error != null) {
            m.put("error", error);
        }
        if (!options.isEmpty()) {
            m.put("options", options.toJson());
        }
        // What the options resolved to on the printer's real media, which is a different question
        // from what was asked for and the only one the output answers. Present on document jobs
        // that got as far as composing a page.
        ResolvedPage resolved = page;
        if (resolved != null) {
            m.put("page", resolved.toJson());
        }
        return m;
    }
}
