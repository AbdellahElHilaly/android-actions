package com.htmlshelf.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ZIP extraction with explicit entry-count, decompressed-byte and path boundaries. */
public final class SafeZipExtractor {
    public static final int MAX_FILES = 6000;
    public static final long MAX_TOTAL_BYTES = 350L * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 150L * 1024 * 1024;

    private SafeZipExtractor() { }

    /** Returns the directory relative to destination which contains index.html. */
    public static String extractAndLocateIndex(InputStream input, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("Cannot create destination");
        }
        Path root = destination.getCanonicalFile().toPath();
        int count = 0;
        long total = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.indexOf('\0') >= 0 ||
                        name.matches("(?i)^[a-z]:.*")) {
                    throw new IOException("Unsafe ZIP entry");
                }
                for (String part : name.split("/")) {
                    if (part.equals("..")) throw new IOException("ZIP traversal blocked");
                }
                if (name.isEmpty()) { zis.closeEntry(); continue; }
                File target = new File(destination, name).getCanonicalFile();
                if (!target.toPath().startsWith(root) || target.toPath().equals(root)) {
                    throw new IOException("ZIP entry outside destination");
                }
                if (entry.isDirectory() || name.endsWith("/")) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create folder");
                } else {
                    if (++count > MAX_FILES) throw new IOException("Too many ZIP files");
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create folder");
                    long fileBytes = 0;
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            fileBytes += read;
                            total += read;
                            if (fileBytes > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                                throw new IOException("ZIP exceeds size limit");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        String directory = findIndexDirectory(destination);
        if (directory == null) throw new IOException("ZIP does not contain index.html");
        return directory;
    }

    public static String findIndexDirectory(File root) throws IOException {
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        Path base = root.getCanonicalFile().toPath();
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        while (!pending.isEmpty()) {
            File directory = pending.removeFirst();
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isDirectory()) pending.add(file);
                else if (file.getName().equalsIgnoreCase("index.html")) {
                    Path rel = base.relativize(directory.getCanonicalFile().toPath());
                    String candidate = rel.toString().replace(File.separatorChar, '/');
                    int depth = candidate.isEmpty() ? 0 : candidate.split("/").length;
                    if (depth < bestDepth || (depth == bestDepth &&
                            (best == null || candidate.compareTo(best) < 0))) {
                        best = candidate;
                        bestDepth = depth;
                    }
                }
            }
        }
        return best;
    }

    public static void deleteTree(File root) {
        if (root == null || !root.exists()) return;
        File[] children = root.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        root.delete();
    }
}
