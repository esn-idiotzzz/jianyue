package com.joe.jmlite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Encoded covers survive bitmap eviction without keeping an unbounded heap. */
final class CoverDiskCache {
    private final File directory;
    private final long maxBytes;

    CoverDiskCache(File directory, long maxBytes) { this.directory = directory; this.maxBytes = maxBytes; }

    private File file(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return new File(directory, name + ".cover");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    synchronized byte[] read(String key) {
        File file = file(key);
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) return null;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            file.setLastModified(System.currentTimeMillis());
            return data;
        } catch (IOException ignored) { return null; }
    }

    synchronized void remove(String key) { file(key).delete(); }

    synchronized void write(String key, byte[] data) {
        if (data.length == 0 || data.length > Math.min(maxBytes, 8 * 1024 * 1024)) return;
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File target = file(key), staged = new File(directory, target.getName() + ".part");
        try {
            Files.write(staged.toPath(), data);
            if (!staged.renameTo(target)) return;
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".cover"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long size = 0;
            for (File entry : files) size += entry.length();
            for (File entry : files) {
                if (size <= maxBytes) break;
                long length = entry.length();
                if (!entry.equals(target) && entry.delete()) size -= length;
            }
        } catch (IOException ignored) {
            // Cache storage is optional; the downloaded cover can still be displayed.
        } finally { staged.delete(); }
    }
}
