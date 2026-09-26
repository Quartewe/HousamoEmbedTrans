package com.quarty.housamoembedtrans.logging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            appendZip(entries, zip, "");
        }
    }

    public static void appendZip(List<Entry> entries, ZipOutputStream zip, String prefix)
        throws IOException {
        for (Entry entry : entries) {
            checkInterrupted();
            zip.putNextEntry(new ZipEntry(prefix + entry.file.getName()));
            try (InputStream input = new FileInputStream(entry.file)) {
                copy(input, zip, entry.length);
            }
            zip.closeEntry();
        }
    }

    /** Sender owns the snapshot and validates dates/names before publication. */
    public static void writeSnapshot(List<Entry> entries, OutputStream output)
        throws IOException {
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(entries.size());
        for (Entry entry : entries) {
            checkInterrupted();
            data.writeUTF(entry.file.getName());
            data.writeLong(entry.length);
            try (InputStream input = new FileInputStream(entry.file)) {
                copy(input, data, entry.length);
            }
        }
        data.flush();
    }

    /** Consumes the trusted game endpoint's file stream without buffering whole files. */
    public static int appendSnapshotZip(InputStream input, ZipOutputStream zip, String prefix)
        throws IOException {
        DataInputStream data = new DataInputStream(input);
        int count = data.readInt();
        for (int index = 0; index < count; index++) {
            checkInterrupted();
            zip.putNextEntry(new ZipEntry(prefix + data.readUTF()));
            copy(data, zip, data.readLong());
            zip.closeEntry();
        }
        // Wait for the reliable pipe's terminal status, including remote failure.
        if (data.read() != -1) throw new IOException("Unexpected log stream contents");
        return count;
    }

    private static void copy(InputStream input, OutputStream output, long remaining)
        throws IOException {
        byte[] buffer = new byte[32 * 1024];
        while (remaining > 0) {
            checkInterrupted();
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Log stream ended before its snapshot boundary");
            output.write(buffer, 0, count);
            remaining -= count;
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
