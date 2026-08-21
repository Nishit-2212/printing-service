package com.jagdushah.printly;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One warm socket and one worker thread per label printer.
 *
 * <p>Jobs for a printer are strictly serialized. Most budget thermal printers (TSC, Godex)
 * accept a single connection on :9100 at a time; a second concurrent writer is either refused
 * or interleaves into garbled labels. Parallelism therefore comes from having several printers,
 * never from several sockets to one printer.
 *
 * <p>The socket is kept open between jobs so a label costs one write, not a TCP handshake.
 */
public final class PrinterConnection {

    private static final int MAX_BATCH_JOBS = 64;
    private static final int MAX_BATCH_BYTES = 1 << 20;
    /** Idle liveness probe: long enough to distinguish a closed peer from a quiet one. */
    private static final int LIVENESS_READ_TIMEOUT_MS = 25;

    private final PrinterTarget target;
    private final int connectTimeoutMs;
    private final int idleCheckMs;
    private final BlockingQueue<Job> queue;
    private final Thread worker;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile InputStream in;

    private volatile boolean online;
    private volatile String lastError;
    private volatile boolean running = true;
    private volatile boolean reconnectRequested;

    private final AtomicLong printed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public PrinterConnection(PrinterTarget target, Config config) {
        this.target = target;
        this.connectTimeoutMs = Math.max(250, config.connectTimeoutMs);
        this.idleCheckMs = Math.max(1000, config.idleCheckMs);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity));
        this.worker = new Thread(this::run, "printer-" + target.name());
        this.worker.setDaemon(true);
    }

    public void start() {
        worker.start();
    }

    public PrinterTarget target() {
        return target;
    }

    public boolean online() {
        return online;
    }

    /** Hands the job to this printer's queue. Returns false when the queue is full. */
    public boolean submit(Job job) {
        return running && queue.offer(job);
    }

    /** Drops the warm socket; the worker reopens it on the next tick. Safe to call from the tray. */
    public void reconnect() {
        reconnectRequested = true;
        worker.interrupt();
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
        List<Job> pending = new ArrayList<>();
        queue.drainTo(pending);
        for (Job j : pending) {
            j.fail("bridge shutting down");
        }
        closeQuietly();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", target.name());
        m.put("lane", "label");
        m.put("host", target.host());
        m.put("port", target.port());
        m.put("online", online);
        m.put("queued", queue.size());
        m.put("printed", printed.get());
        m.put("failed", failed.get());
        if (!target.note().isEmpty()) {
            m.put("note", target.note());
        }
        if (lastError != null) {
            m.put("lastError", lastError);
        }
        return m;
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        Log.info("printer '" + target.name() + "' worker started for " + target.address());
        while (running) {
            if (reconnectRequested) {
                reconnectRequested = false;
                Log.info("printer '" + target.name() + "': reconnect requested");
                closeQuietly();
            }
            Job first;
            try {
                first = queue.poll(idleCheckMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                continue; // interrupted for a reconnect request
            }
            if (first == null) {
                idleTick();
                continue;
            }
            writeBatch(collectBatch(first));
        }
        closeQuietly();
        Log.info("printer '" + target.name() + "' worker stopped");
    }

    /**
     * Take everything already queued for this printer so it goes out in one write.
     * Same correctness (still serialized), far fewer round-trips on a bulk run.
     */
    private List<Job> collectBatch(Job first) {
        List<Job> batch = new ArrayList<>();
        batch.add(first);
        long bytes = (long) first.payload().length * first.copies();
        while (batch.size() < MAX_BATCH_JOBS && bytes < MAX_BATCH_BYTES) {
            Job next = queue.peek();
            if (next == null) {
                break;
            }
            long size = (long) next.payload().length * next.copies();
            if (bytes + size > MAX_BATCH_BYTES && !batch.isEmpty()) {
                break;
            }
            queue.poll();
            batch.add(next);
            bytes += size;
        }
        return batch;
    }

    private void writeBatch(List<Job> batch) {
        byte[] bytes;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (Job job : batch) {
                job.markPrinting();
                for (int i = 0; i < job.copies(); i++) {
                    buf.write(job.payload());
                }
            }
            bytes = buf.toByteArray();
        } catch (IOException impossible) {
            failBatch(batch, "could not assemble payload: " + impossible);
            return;
        }

        boolean reusedSocket;
        try {
            reusedSocket = ensureConnected();
        } catch (IOException e) {
            markOffline(e);
            failBatch(batch, "printer offline (" + target.address() + "): " + describe(e));
            return;
        }

        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            closeQuietly();
            // A write that fails on a *reused* socket almost always means the printer had
            // already closed it while idle and nothing reached the hardware, so one retry on a
            // fresh connection is safe. A fresh socket failing is a real error — don't reprint.
            if (reusedSocket && retryOnFreshSocket(bytes)) {
                completeBatch(batch);
                return;
            }
            markOffline(e);
            failBatch(batch, "write failed to " + target.address() + ": " + describe(e));
            return;
        }
        completeBatch(batch);
    }

    private boolean retryOnFreshSocket(byte[] bytes) {
        Log.warn("printer '" + target.name() + "': stale socket, retrying on a fresh connection");
        try {
            connect();
            out.write(bytes);
            out.flush();
            markOnline();
            return true;
        } catch (IOException retryFailed) {
            closeQuietly();
            markOffline(retryFailed);
            return false;
        }
    }

    private void completeBatch(List<Job> batch) {
        markOnline();
        for (Job job : batch) {
            job.complete();
        }
        printed.addAndGet(batch.size());
    }

    private void failBatch(List<Job> batch, String reason) {
        Log.warn("printer '" + target.name() + "': " + reason);
        for (Job job : batch) {
            job.fail(reason);
        }
        failed.addAndGet(batch.size());
    }

    // ------------------------------------------------------------------ socket lifecycle

    /** @return true when an existing warm socket was reused, false when a new one was opened */
    private boolean ensureConnected() throws IOException {
        Socket s = socket;
        if (s != null && s.isConnected() && !s.isClosed() && !s.isOutputShutdown()) {
            return true;
        }
        connect();
        return false;
    }

    private void connect() throws IOException {
        closeQuietly();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(target.host(), target.port()), connectTimeoutMs);
        // Nagle would otherwise hold tiny label payloads for tens of milliseconds.
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSoTimeout(0);
        socket = s;
        out = s.getOutputStream();
        in = s.getInputStream();
    }

    /**
     * Runs when no job has arrived for {@code idleCheckMs}. Keeps the socket warm and doubles as
     * the health probe behind {@code online} — we cannot probe with a second socket, because the
     * printer only accepts one connection and our own warm socket is holding it.
     */
    private void idleTick() {
        Socket s = socket;
        if (s == null || s.isClosed()) {
            try {
                connect();
                markOnline();
            } catch (IOException e) {
                closeQuietly();
                markOffline(e);
            }
            return;
        }
        // Non-destructive liveness check: a closed peer surfaces as EOF, a live one as a timeout.
        try {
            s.setSoTimeout(LIVENESS_READ_TIMEOUT_MS);
            int b = in.read();
            if (b < 0) {
                closeQuietly();
                markOffline(new IOException("printer closed the connection"));
                return;
            }
            drainUnsolicited();
            markOnline();
        } catch (SocketTimeoutException stillAlive) {
            markOnline();
        } catch (IOException e) {
            closeQuietly();
            markOffline(e);
        } finally {
            try {
                Socket cur = socket;
                if (cur != null && !cur.isClosed()) {
                    cur.setSoTimeout(0);
                }
            } catch (IOException ignored) {
                // best effort; the next write will surface any real problem
            }
        }
    }

    /** Some printers push unsolicited status bytes. We never read them otherwise, so discard. */
    private void drainUnsolicited() {
        try {
            InputStream stream = in;
            while (stream != null && stream.available() > 0) {
                if (stream.read() < 0) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // a real fault will show up on the next write
        }
    }

    private void closeQuietly() {
        Socket s = socket;
        socket = null;
        out = null;
        in = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already on the failure path
            }
        }
    }

    private void markOnline() {
        if (!online) {
            Log.info("printer '" + target.name() + "' (" + target.address() + ") is online");
        }
        online = true;
        lastError = null;
    }

    private void markOffline(IOException e) {
        String message = describe(e);
        if (online || lastError == null) {
            Log.warn("printer '" + target.name() + "' (" + target.address() + ") is offline: " + message);
        }
        online = false;
        lastError = message;
    }

    private static String describe(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return "timed out";
        }
        if (e instanceof ConnectException) {
            return "connection refused";
        }
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }
}
