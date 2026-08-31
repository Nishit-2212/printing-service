package com.jagdushah.printly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recent jobs, so the frontend can poll GET /jobs/{id} after a 202. Oldest entries fall off. */
public final class JobRegistry {

    /**
     * How much document data the history may hold onto, across all retained jobs.
     *
     * <p>The count of jobs is the wrong bound on its own. Five hundred TSPL labels are a few
     * hundred kilobytes; five hundred courier invoices are far more than the 128 MB heap the
     * service ships with, and reaching that limit kills printing for a reason that has nothing to
     * do with printing. Past this budget the oldest settled jobs give up their bytes and stop being
     * reprintable, while staying in the list with their outcome intact — which is the part of the
     * history anyone reads, as against the part anyone reprints from.
     */
    private static final long PAYLOAD_BUDGET_BYTES = 24L * 1024 * 1024;

    private final Map<String, Job> jobs;

    public JobRegistry(int capacity) {
        int max = Math.max(16, capacity);
        this.jobs = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Job> eldest) {
                return size() > max;
            }
        };
    }

    public synchronized void put(Job job) {
        jobs.put(job.id(), job);
        trimPayloads();
    }

    /**
     * Release payloads oldest-first until the retained total is back inside the budget.
     *
     * <p>Runs on submit rather than on a timer, because submit is the only thing that grows the
     * total and doing it here means there is no window in which the heap is over budget. Iteration
     * is oldest-first, which is insertion order; an unsettled job is skipped rather than stopping
     * the sweep, since a long-running print at the front must not pin every payload behind it.
     */
    private void trimPayloads() {
        long retained = 0;
        for (Job job : jobs.values()) {
            if (job.reprintable()) {
                retained += job.payloadBytes();
            }
        }
        if (retained <= PAYLOAD_BUDGET_BYTES) {
            return;
        }
        for (Job job : jobs.values()) {
            if (retained <= PAYLOAD_BUDGET_BYTES) {
                return;
            }
            if (job.reprintable() && job.settled()) {
                job.releasePayload();
                retained -= job.payloadBytes();
            }
        }
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized int size() {
        return jobs.size();
    }

    /**
     * The most recent jobs, newest first.
     *
     * <p>Insertion order is submission order, so newest-first is a reverse of the values. The
     * snapshot is taken under the lock and the list handed out is a copy: the panel polls this
     * every couple of seconds while lanes are mutating job state, and iterating the live map
     * would be a concurrent modification waiting for a busy shift.
     *
     * @param limit maximum entries to return; the registry's own capacity still caps it
     * @param batchId when set, only jobs from that batch
     */
    public synchronized List<Job> recent(int limit, String batchId) {
        List<Job> all = new ArrayList<>(jobs.values());
        Collections.reverse(all);
        List<Job> out = new ArrayList<>(Math.min(limit, all.size()));
        for (Job job : all) {
            if (batchId != null && !batchId.equals(job.batchId())) {
                continue;
            }
            out.add(job);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
