package com.jagdushah.printly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bulk printing: many files, one strategy, with progress, cancel, and a per-file outcome.
 *
 * <p>The design decision that matters here is that <b>every file is planned before any file is
 * printed</b>. A batch is validated in full — each document inspected, each rule resolved against
 * its real page count, each preset's geometry parsed — and only then does anything reach a printer.
 * A batch that cannot be carried out is refused with the file and the reason, and nothing has come
 * out. The alternative, discovering a bad preset on file forty-one of two hundred, leaves forty
 * printed labels and a stack of half-processed orders, and that is not recoverable by pressing
 * anything.
 *
 * <p>The second decision is that this bounds how much is in flight rather than queueing everything.
 * The lanes would cope — they serialize per printer and the queues hold a thousand — but three
 * things break at scale if the runner just dumps: memory (every queued job holds its PDF), cancel
 * (a thousand queued jobs is a thousand cancels, and the operator wanted one), and honesty about
 * progress (a job that is queued has not printed, and a progress bar that counts it as sent is the
 * kind that reaches 100% while the printer is still working). So {@code concurrency} files are in
 * flight, and the next one is submitted as one finishes.
 *
 * <p><b>What "done" means.</b> An item is done when every job it created reported DONE — the paper
 * is out. It is failed when any job failed. There is no third state where the service shrugged: the
 * whole reason the packing flow moved off a fire-and-forget call is that "accepted" read as
 * "printed" and orders were marked packed with nothing in the bag.
 */
public final class BatchRunner {

    /** How long a poll of the in-flight items sleeps. Fast enough for a live progress bar. */
    private static final long POLL_MS = 60;

    private final PrintRouter router;
    private final Spool spool;
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean running = true;

    public BatchRunner(PrintRouter router, Spool spool) {
        this.router = router;
        this.spool = spool;
    }

    // ------------------------------------------------------------------ model

    /** What the caller asked for. Immutable; the plan is worked out from it in {@link #start}. */
    public record Request(List<String> fileIds, Map<String, Object> strategy, String strategyName,
            String printer, String presetId, int concurrency, int copiesMultiplier) {
    }

    /** One file in a batch, and how it went. */
    public static final class Item {
        private final String fileId;
        private final String name;
        private final int pages;
        private final Strategy.Plan plan;
        private final List<Job> jobs = Collections.synchronizedList(new ArrayList<>());

        private volatile String state = "pending";
        private volatile String error;
        private volatile long startedAt;
        private volatile long finishedAt;

        Item(String fileId, String name, int pages, Strategy.Plan plan) {
            this.fileId = fileId;
            this.name = name;
            this.pages = pages;
            this.plan = plan;
        }

        public String state() {
            return state;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", fileId);
            m.put("name", name);
            m.put("pages", pages);
            m.put("state", state);
            m.put("plan", plan.toJson());
            List<Map<String, Object>> jobJson = new ArrayList<>();
            synchronized (jobs) {
                for (Job job : jobs) {
                    Map<String, Object> j = new LinkedHashMap<>();
                    j.put("jobId", job.id());
                    j.put("printer", job.printer());
                    j.put("state", job.state().wire());
                    if (job.error() != null) {
                        j.put("error", job.error());
                    }
                    jobJson.add(j);
                }
            }
            m.put("jobs", jobJson);
            if (error != null) {
                m.put("error", error);
            }
            if (startedAt > 0) {
                m.put("startedAt", startedAt);
            }
            if (finishedAt > 0) {
                m.put("finishedAt", finishedAt);
                m.put("durationMs", finishedAt - startedAt);
            }
            return m;
        }
    }

    /** A running or finished batch. */
    public static final class Batch {
        private final String id;
        private final String strategyName;
        private final String printer;
        private final int concurrency;
        private final List<Item> items;
        private final long createdAt = System.currentTimeMillis();

        private volatile String state = "queued";
        private volatile boolean cancelRequested;
        private volatile long finishedAt;

        Batch(String id, String strategyName, String printer, int concurrency, List<Item> items) {
            this.id = id;
            this.strategyName = strategyName;
            this.printer = printer;
            this.concurrency = concurrency;
            this.items = items;
        }

        public String id() {
            return id;
        }

        public boolean finished() {
            return state.equals("done") || state.equals("failed") || state.equals("cancelled");
        }

        public Map<String, Object> toJson(boolean includeItems) {
            int done = 0;
            int failed = 0;
            int cancelled = 0;
            int running = 0;
            for (Item item : items) {
                switch (item.state) {
                    case "done" -> done++;
                    case "failed" -> failed++;
                    case "cancelled" -> cancelled++;
                    case "printing" -> running++;
                    default -> {
                        // still pending
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", id);
            m.put("state", state);
            m.put("strategy", strategyName);
            m.put("printer", printer);
            m.put("concurrency", concurrency);
            m.put("total", items.size());
            m.put("done", done);
            m.put("failed", failed);
            m.put("cancelled", cancelled);
            m.put("printing", running);
            m.put("pending", items.size() - done - failed - cancelled - running);
            m.put("createdAt", createdAt);
            if (finishedAt > 0) {
                m.put("finishedAt", finishedAt);
                m.put("durationMs", finishedAt - createdAt);
            }
            if (includeItems) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Item item : items) {
                    rows.add(item.toJson());
                }
                m.put("items", rows);
            }
            return m;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Plan every file, then start printing.
     *
     * @throws IllegalArgumentException when any file cannot be planned; nothing has been submitted
     */
    public Batch start(Request request, List<Map<String, Object>> presets) {
        if (request.fileIds().isEmpty()) {
            throw new IllegalArgumentException("no files to print");
        }
        int concurrency = Math.max(1, Math.min(8, request.concurrency()));

        List<Item> items = new ArrayList<>();
        for (String fileId : request.fileIds()) {
            Spool.Entry entry = spool.entry(fileId);
            if (entry == null) {
                throw new IllegalArgumentException("file '" + fileId
                        + "' is no longer staged — re-add it and try again");
            }
            Strategy.Plan plan;
            try {
                plan = Strategy.plan(request.strategy(), entry.pages(), request.printer(),
                        request.presetId(), presets);
            } catch (IllegalArgumentException e) {
                // Named, because the operator's next move is to fix that file or that rule, and
                // "a rule failed" does not tell them which of two hundred files provoked it.
                throw new IllegalArgumentException("'" + entry.name() + "': " + e.getMessage());
            }
            items.add(new Item(fileId, entry.name(), entry.pages(), plan));
        }

        String id = "b_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Long.toString(seq.incrementAndGet(), 36);
        Batch batch = new Batch(id, request.strategyName(), request.printer(), concurrency, items);
        batches.put(id, batch);

        int multiplier = Math.max(1, Math.min(100, request.copiesMultiplier()));
        Thread worker = new Thread(() -> run(batch, multiplier), "batch-" + id);
        worker.setDaemon(true);
        worker.start();
        return batch;
    }

    public Batch batch(String id) {
        return id == null ? null : batches.get(id);
    }

    /** Newest first, so the panel can show the last few runs. */
    public List<Batch> recent(int limit) {
        List<Batch> all = new ArrayList<>(batches.values());
        all.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /**
     * Stop a batch.
     *
     * <p>Best-effort in exactly one place, and it is worth being precise about which: nothing that
     * has not been submitted is submitted, and every job still queued is pulled from its lane. A
     * job already at the driver cannot be recalled — the bytes are gone — so a cancel during a
     * print stops the batch after the sheet that is already coming out.
     *
     * @return how many queued jobs were pulled
     */
    public int cancel(String id) {
        Batch batch = batches.get(id);
        if (batch == null) {
            return -1;
        }
        batch.cancelRequested = true;
        int pulled = 0;
        for (Item item : batch.items) {
            synchronized (item.jobs) {
                for (Job job : item.jobs) {
                    if (job.cancel()) {
                        pulled++;
                    }
                }
            }
        }
        return pulled;
    }

    public void shutdown() {
        running = false;
        for (Batch batch : batches.values()) {
            if (!batch.finished()) {
                cancel(batch.id);
            }
        }
    }

    // ------------------------------------------------------------------ the run

    private void run(Batch batch, int copiesMultiplier) {
        batch.state = "running";
        Log.info("batch " + batch.id + " started: " + batch.items.size() + " file(s), strategy='"
                + batch.strategyName + "', concurrency=" + batch.concurrency);

        List<Item> inFlight = new ArrayList<>();
        int index = 0;
        try {
            while (index < batch.items.size()) {
                if (batch.cancelRequested || !running) {
                    break;
                }
                while (inFlight.size() >= batch.concurrency) {
                    harvest(inFlight);
                    if (batch.cancelRequested || !running) {
                        break;
                    }
                }
                if (batch.cancelRequested || !running) {
                    break;
                }
                Item item = batch.items.get(index++);
                if (submit(batch, item, copiesMultiplier)) {
                    inFlight.add(item);
                }
            }
            // Whatever is still printing is waited out even after a cancel: those jobs are either
            // already on paper or already pulled, and reporting the batch finished while a job is
            // still open would leave the panel showing a state nothing will ever update.
            while (!inFlight.isEmpty()) {
                harvest(inFlight);
            }
        } catch (RuntimeException e) {
            Log.error("batch " + batch.id + " stopped unexpectedly", e);
        }

        for (Item item : batch.items) {
            if (item.state.equals("pending")) {
                item.state = "cancelled";
                item.error = "the batch was cancelled before this file was sent";
            }
        }

        boolean anyFailed = batch.items.stream().anyMatch(i -> i.state.equals("failed"));
        boolean anyCancelled = batch.items.stream().anyMatch(i -> i.state.equals("cancelled"));
        batch.finishedAt = System.currentTimeMillis();
        batch.state = anyCancelled && batch.cancelRequested ? "cancelled" : anyFailed ? "failed" : "done";
        Log.info("batch " + batch.id + " " + batch.state + " in "
                + (batch.finishedAt - batch.createdAt) + "ms");
    }

    /** Submit one item's jobs. Returns false when it failed before reaching a printer. */
    private boolean submit(Batch batch, Item item, int copiesMultiplier) {
        item.startedAt = System.currentTimeMillis();
        byte[] pdf;
        try {
            pdf = spool.read(item.fileId);
        } catch (IOException e) {
            item.state = "failed";
            item.error = "could not read the staged file: " + e.getMessage();
            item.finishedAt = System.currentTimeMillis();
            return false;
        }

        item.state = "printing";
        for (Strategy.Step step : item.plan.steps()) {
            try {
                PrintOptions options = PrintOptions.from(step.options());
                Job job = router.submit(step.printer(), "pdf", pdf, step.copies() * copiesMultiplier,
                        options, item.name, batch.id, step.label());
                item.jobs.add(job);
            } catch (PrintRouter.RejectedException | IllegalArgumentException e) {
                // One step of a multi-printer item failed to queue. The item is failed as a whole,
                // and any sibling job already queued is pulled: half of a label-plus-invoice split
                // is worse than neither, because the parcel goes out with a label and no paperwork.
                item.state = "failed";
                item.error = step.label() + " → " + step.printer() + ": " + e.getMessage();
                synchronized (item.jobs) {
                    for (Job queued : item.jobs) {
                        queued.cancel();
                    }
                }
                item.finishedAt = System.currentTimeMillis();
                return false;
            }
        }
        return true;
    }

    /** Move any finished item out of the in-flight list, recording how it went. */
    private void harvest(List<Item> inFlight) {
        boolean progressed = false;
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            Item item = inFlight.get(i);
            boolean allSettled = true;
            String failure = null;
            boolean cancelled = false;
            synchronized (item.jobs) {
                for (Job job : item.jobs) {
                    if (!job.settled()) {
                        allSettled = false;
                        break;
                    }
                    switch (job.state()) {
                        case FAILED -> {
                            if (failure == null) {
                                failure = job.printer() + ": " + job.error();
                            }
                        }
                        case CANCELLED -> cancelled = true;
                        default -> {
                            // done
                        }
                    }
                }
            }
            if (!allSettled) {
                continue;
            }
            item.finishedAt = System.currentTimeMillis();
            if (failure != null) {
                item.state = "failed";
                item.error = failure;
            } else if (cancelled) {
                item.state = "cancelled";
            } else {
                item.state = "done";
            }
            inFlight.remove(i);
            progressed = true;
        }
        if (!progressed) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
