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
    private volatile long spoolMs = -1;

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
     * <p>{@code renderMs} is parsing the PDF; {@code spoolMs} is rasterising it and handing it to
     * the driver. They are split because they fail differently — a slow parse means a bloated PDF
     * upstream, a slow spool means the printer or the raster density. Together they are what makes
     * a job that overran the caller's {@code ?wait=} explicable rather than just late.
     */
    void timing(long renderMs, long spoolMs) {
        this.renderMs = renderMs;
        this.spoolMs = spoolMs;
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
        if (renderMs >= 0) {
            m.put("renderMs", renderMs);
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
        return m;
    }
}
