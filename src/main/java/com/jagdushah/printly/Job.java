package com.jagdushah.printly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One print request: immutable payload plus a mutable outcome the API can report back. */
public final class Job {

    public enum State {
        QUEUED, PRINTING, DONE, FAILED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();

    private final String id;
    private final String printer;
    private final String type;
    private final byte[] payload;
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

    public Job(String printer, String type, byte[] payload, int copies, PrintOptions options) {
        this.id = "j_" + Long.toString(SEQ.incrementAndGet(), 36);
        this.printer = printer;
        this.type = type;
        this.payload = payload;
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

    /** The single-copy payload. Repetition for {@link #copies()} happens at the lane. */
    public byte[] payload() {
        return payload;
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

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public boolean settled() {
        return state == State.DONE || state == State.FAILED;
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

    void markPrinting() {
        if (state == State.QUEUED) {
            state = State.PRINTING;
            startedAt = System.currentTimeMillis();
        }
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
        m.put("copies", copies);
        m.put("bytes", payload.length);
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
