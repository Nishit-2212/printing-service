package com.jagdushah.printly;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny append-only logger: console plus one size-capped rotating file.
 *
 * The packaged Windows build runs without a console window (jpackage defaults to windowed),
 * so the file is the only place an operator or a remote helper can see what happened.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Object LOCK = new Object();

    private static Path file;
    private static Writer writer;

    private Log() {
    }

    /** Start mirroring output to {@code path}. Failures here are non-fatal: console logging continues. */
    public static void toFile(Path path) {
        synchronized (LOCK) {
            closeWriter();
            file = path;
            openWriter();
        }
    }

    public static Path file() {
        return file;
    }

    public static void info(String msg) {
        write("INFO ", msg, null);
    }

    public static void warn(String msg) {
        write("WARN ", msg, null);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        String line = TS.format(LocalDateTime.now()) + " " + level + " " + msg;
        synchronized (LOCK) {
            PrintStream console = level.startsWith("ERROR") ? System.err : System.out;
            console.println(line);
            if (t != null) {
                t.printStackTrace(console);
            }
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.write(System.lineSeparator());
                if (t != null) {
                    writer.write("        ");
                    writer.write(stackTrace(t));
                    writer.write(System.lineSeparator());
                }
                writer.flush();
                rotateIfNeeded();
            } catch (IOException ignored) {
                // never let logging break printing
            }
        }
    }

    private static String stackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 8); i++) {
            sb.append(System.lineSeparator()).append("            at ").append(frames[i]);
        }
        if (t.getCause() != null && t.getCause() != t) {
            sb.append(System.lineSeparator()).append("        caused by ").append(t.getCause());
        }
        return sb.toString();
    }

    private static void rotateIfNeeded() throws IOException {
        if (file == null || Files.size(file) < MAX_BYTES) {
            return;
        }
        closeWriter();
        Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        openWriter();
    }

    private static void openWriter() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            writer = null;
            System.err.println("could not open log file " + file + ": " + e);
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // closing on the way out; nothing useful to do
            }
            writer = null;
        }
    }
}
