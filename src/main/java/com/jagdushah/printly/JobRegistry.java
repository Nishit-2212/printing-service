package com.jagdushah.printly;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recent jobs, so the frontend can poll GET /jobs/{id} after a 202. Oldest entries fall off. */
public final class JobRegistry {

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
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized int size() {
        return jobs.size();
    }
}
