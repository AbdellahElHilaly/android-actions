package com.htmlshelf;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.htmlshelf.core.SafeZipExtractor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/** Copies imported content into a private folder; no broad storage permission needed. */
public final class ImportManager {
    private final Context context;
    private final PageRepository repository;

    public ImportManager(Context context, PageRepository repository) {
        this.context = context.getApplicationContext();
        this.repository = repository;
    }

    public void importDocument(Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = resolver.getType(uri);
        File sites = new File(context.getFilesDir(), "sites");
        if (!sites.isDirectory() && !sites.mkdirs()) throw new IOException("Cannot initialize storage");
        String identifier = UUID.randomUUID().toString();
        File stage = new File(sites, ".import-" + identifier);
        File target = new File(sites, "site-" + identifier);
        if (!stage.mkdirs()) throw new IOException("Cannot create staging folder");
        boolean committed = false;
        try (InputStream content = resolver.openInputStream(uri)) {
            if (content == null) throw new IOException("Cannot read selected file");
            try (BufferedInputStream input = new BufferedInputStream(content)) {
                input.mark(8);
                byte[] magic = new byte[4];
                int read = input.read(magic);
                input.reset();
                boolean zipMagic = read == 4 && magic[0] == 'P' && magic[1] == 'K' &&
                        (magic[2] == 3 || magic[2] == 5 || magic[2] == 7);
                boolean zip = lower.endsWith(".zip") || zipMagic ||
                        "application/zip".equals(mime) || "application/x-zip-compressed".equals(mime);
                String root;
                if (zip) {
                    root = SafeZipExtractor.extractAndLocateIndex(input, stage);
                } else {
                    if (!(lower.endsWith(".html") || lower.endsWith(".htm") ||
                            "text/html".equals(mime))) {
                        throw new IOException("Only HTML and ZIP files are supported");
                    }
                    File html = new File(stage, "index.html");
                    copyLimited(input, html, SafeZipExtractor.MAX_FILE_BYTES);
                    root = "";
                }
                if (!stage.renameTo(target)) throw new IOException("Cannot finish import");
                String title = name.replaceFirst("(?i)\\.(html?|zip)$", "").trim();
                if (title.isEmpty()) title = "Untitled";
                repository.add(title, target.getName(), root);
                committed = true;
            }
        } finally {
            SafeZipExtractor.deleteTree(stage);
            if (!committed) SafeZipExtractor.deleteTree(target);
        }
    }

    private static void copyLimited(InputStream input, File output, long max) throws IOException {
        long total = 0;
        byte[] data = new byte[16384];
        try (FileOutputStream out = new FileOutputStream(output)) {
            int count;
            while ((count = input.read(data)) >= 0) {
                total += count;
                if (total > max) throw new IOException("HTML file exceeds size limit");
                out.write(data, 0, count);
            }
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        String name = null;
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name == null ? "page.html" : name;
    }
}
