package com.jagdushah.printly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Files the Control Panel has been given but not yet printed, held on disk.
 *
 * <p>The reason this is not a map of byte arrays: the panel's whole point is bulk, and bulk means
 * someone drops two hundred courier PDFs on it at once. The service runs with {@code -Xmx128m} —
 * that is in the packaging, deliberately, because it sits on a warehouse PC that is also running a
 * browser — and two hundred invoices in a heap that size is an OutOfMemoryError that takes the
 * printing down mid-shift. On disk they cost nothing but disk, and the batch runner reads each one
 * back only while it is actually being submitted.
 *
 * <p>The spool is scratch, not storage. It is emptied at startup, because a file left behind is
 * from a run that is over, and the panel deletes its own entries as a batch finishes. Nothing here
 * needs to survive a restart: a file that has not printed yet is still in the operator's folder.
 */
public final class Spool {

    /** One staged file: what it is called, how many pages it has, and what it will print like. */
    public record Entry(String id, String name, int pages, long bytes, double widthPt, double heightPt,
            long stagedAt) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", id);
            m.put("name", name);
            m.put("pages", pages);
            m.put("bytes", bytes);
            m.put("widthPt", Math.round(widthPt * 10) / 10.0);
            m.put("heightPt", Math.round(heightPt * 10) / 10.0);
            // What the auto-landscape detection will read off this document, which is the one part
            // of the composition that depends on the file rather than the preset. Shown in the
            // panel because "why did this one come out sideways" is otherwise unanswerable.
            m.put("landscape", widthPt > heightPt);
            m.put("stagedAt", stagedAt);
            return m;
        }
    }

    private final Path dir;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final long maxTotalBytes;

    public Spool(Path dir, long maxTotalBytes) {
        this.dir = dir;
        this.maxTotalBytes = maxTotalBytes;
        clear();
    }

    /**
     * Stage a PDF and read what it is.
     *
     * <p>Inspecting it here rather than at print time is what lets the panel show a plan before
     * anything is queued: a page count is the input every routing rule is resolved against, so
     * without it "odd pages to the left printer" cannot be shown, only attempted.
     *
     * @throws IOException              on a disk failure
     * @throws IllegalArgumentException when the bytes are not a PDF this service can print, or the
     *                                  spool is already at its size limit
     */
    public Entry stage(String name, byte[] pdf) throws IOException {
        long staged = totalBytes();
        if (staged + pdf.length > maxTotalBytes) {
            throw new IllegalArgumentException("the staging area is full ("
                    + (staged / (1024 * 1024)) + " MB of " + (maxTotalBytes / (1024 * 1024))
                    + " MB) — print or clear the current files first");
        }
        PdfSplitter.Info info;
        try {
            info = PdfSplitter.inspect(pdf);
        } catch (IOException e) {
            // Rejected before it is written, and named, because the one thing an operator needs
            // from a bulk upload is which of the two hundred files was the bad one.
            throw new IllegalArgumentException("'" + name + "' is not a readable PDF: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }

        String id = "f_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Long.toString(seq.incrementAndGet(), 36);
        Files.createDirectories(dir);
        Files.write(path(id), pdf);
        Entry entry = new Entry(id, name, info.pageCount(), pdf.length,
                info.widthPt(), info.heightPt(), System.currentTimeMillis());
        entries.put(id, entry);
        return entry;
    }

    public Entry entry(String id) {
        return id == null ? null : entries.get(id);
    }

    /** The staged file's bytes. */
    public byte[] read(String id) throws IOException {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IOException("file '" + id + "' is no longer staged");
        }
        return Files.readAllBytes(path(id));
    }

    /** Everything staged, oldest first — the order they were dropped, which is the order to print. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort((a, b) -> Long.compare(a.stagedAt(), b.stagedAt()));
        return out;
    }

    public long totalBytes() {
        long sum = 0;
        for (Entry e : entries.values()) {
            sum += e.bytes();
        }
        return sum;
    }

    public boolean remove(String id) {
        Entry entry = entries.remove(id);
        if (entry == null) {
            return false;
        }
        try {
            Files.deleteIfExists(path(id));
        } catch (IOException e) {
            Log.warn("could not delete the staged file " + path(id) + ": " + e);
        }
        return true;
    }

    /** Empty the spool, on disk and in memory. */
    public void clear() {
        entries.clear();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    Log.warn("could not delete " + p + ": " + e);
                }
            }
        } catch (IOException e) {
            Log.warn("could not clear the spool directory " + dir + ": " + e);
        }
    }

    private Path path(String id) {
        // The id is generated here and is [a-z0-9_] only, so it cannot escape the directory.
        return dir.resolve(id + ".pdf");
    }
}
