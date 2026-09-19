package com.quarty.housamoembedtrans.logging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Append/snapshot run on the log writer; ZIP copying runs on the export worker. */
public final class DailyLogStore {
    public static final int DEFAULT_EXPORT_DAYS = 14;
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx");
    private final File directory;

    public DailyLogStore(File directory) {
        this.directory = directory;
    }

    public void append(long time, String level, String tag, String message)
        throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create log directory");
        }
        java.time.ZonedDateTime timestamp = Instant.ofEpochMilli(time)
            .atZone(ZoneId.systemDefault());
        File file = new File(directory, timestamp.toLocalDate() + ".log");
        byte[] record = (TIMESTAMP.format(timestamp) + " " + level + "/"
            + tag + ": " + message + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(record);
        }
    }

    /** Captures complete-record byte boundaries; later appends are excluded. */
    public List<Entry> snapshot(LocalDate today, int days) throws IOException {
        if (days < 1) {
            throw new IllegalArgumentException("days must be positive");
        }
        List<Entry> entries = new ArrayList<>();
        if (!directory.exists()) {
            return entries;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException("Could not list log directory");
        }
        LocalDate first = today.minusDays(days - 1L);
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.matches("\\d{4}-\\d{2}-\\d{2}\\.log")) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(name.substring(0, 10));
            } catch (java.time.format.DateTimeParseException ignored) {
                continue;
            }
            if (!date.isBefore(first) && !date.isAfter(today)) {
                entries.add(new Entry(file, file.length()));
            }
        }
        entries.sort(Comparator.comparing(entry -> entry.file.getName()));
        return entries;
    }

    /** Takes ownership of output. Memory use is independent of archive size. */
    public static void writeZip(List<Entry> entries, OutputStream output)
        throws IOException {
        byte[] buffer = new byte[32 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                checkInterrupted();
                zip.putNextEntry(new ZipEntry(entry.file.getName()));
                try (FileInputStream input = new FileInputStream(entry.file)) {
                    long remaining = entry.length;
                    while (remaining > 0) {
                        checkInterrupted();
                        int count = input.read(buffer, 0,
                            (int) Math.min(buffer.length, remaining));
                        if (count < 0) {
                            throw new IOException("Log changed during export: "
                                + entry.file.getName());
                        }
                        zip.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Log export canceled");
        }
    }

    public static final class Entry {
        private final File file;
        private final long length;

        private Entry(File file, long length) {
            this.file = file;
            this.length = length;
        }
    }
}
