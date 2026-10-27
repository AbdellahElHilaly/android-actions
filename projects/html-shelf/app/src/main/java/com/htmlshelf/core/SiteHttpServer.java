package com.htmlshelf.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A per-site, loopback-only HTTP server with a secret initial URL and a session cookie. */
public final class SiteHttpServer implements AutoCloseable {
    private final File siteRoot;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final String secret;
    private final String cookieName;
    private volatile boolean active = true;

    public SiteHttpServer(File siteRoot) throws IOException {
        this.siteRoot = siteRoot.getCanonicalFile();
        if (!this.siteRoot.isDirectory() || !new File(siteRoot, "index.html").isFile()) {
            // Case-insensitive index from ZIPs is also supported in route resolution.
            if (!containsIndex(this.siteRoot)) throw new IOException("Missing index.html");
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        StringBuilder b = new StringBuilder();
        for (byte v : random) b.append(String.format(Locale.ROOT, "%02x", v & 255));
        secret = b.toString();
        cookieName = "hs_" + secret.substring(0, 16);
        listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        Thread acceptThread = new Thread(this::acceptLoop, "HTMLShelf-HTTP");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private static boolean containsIndex(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase("index.html")) return true;
        return false;
    }

    public String launchUrl() {
        return "http://127.0.0.1:" + listener.getLocalPort() + "/?access=" + secret;
    }

    private void acceptLoop() {
        while (active) {
            try {
                Socket socket = listener.accept();
                try { workers.execute(() -> handle(socket)); }
                catch (RuntimeException stopped) { socket.close(); }
            } catch (IOException e) { if (active) e.printStackTrace(); }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             InputStream input = new BufferedInputStream(s.getInputStream());
             OutputStream output = new BufferedOutputStream(s.getOutputStream())) {
            s.setSoTimeout(12000);
            String first = readLine(input);
            if (first == null) return;
            String[] parts = first.split(" ");
            if (parts.length != 3 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
                respond(output, 405, "Method Not Allowed", "Only GET and HEAD", false, null); return;
            }
            String cookie = "";
            String rangeHeader = null;
            int headerBytes = 0;
            for (int i = 0; i < 100; i++) {
                String header = readLine(input);
                if (header == null) return;
                headerBytes += header.length();
                if (headerBytes > 16384) return;
                if (header.isEmpty()) break;
                if (header.regionMatches(true, 0, "Cookie:", 0, 7)) cookie = header.substring(7).trim();
                if (header.regionMatches(true, 0, "Range:", 0, 6)) rangeHeader = header.substring(6).trim();
            }
            String uri = parts[1];
            int question = uri.indexOf('?');
            String rawPath = question >= 0 ? uri.substring(0, question) : uri;
            String query = question >= 0 ? uri.substring(question + 1) : "";
            boolean firstAccess = hasAccessQuery(query);
            if (!firstAccess && !hasSession(cookie)) {
                respond(output, 403, "Forbidden", "Session required", false, null); return;
            }
            String setCookie = firstAccess ? cookieName + "=" + secret + "; Path=/; HttpOnly; SameSite=Lax" : null;
            String decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
            if (!decoded.startsWith("/") || decoded.indexOf('\0') >= 0) {
                respond(output, 400, "Bad Request", "Bad URL", false, setCookie); return;
            }
            File file = resolve(decoded);
            if (file == null || !file.isFile()) {
                // Support SPA history navigation while not hiding missing asset errors.
                if (!decoded.substring(decoded.lastIndexOf('/') + 1).contains(".")) file = indexFile(siteRoot);
            }
            if (file == null || !file.isFile()) {
                respond(output, 404, "Not Found", "Not found", parts[0].equals("HEAD"), setCookie); return;
            }
            long length = file.length();
            long start = 0;
            long end = length - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String requested = rangeHeader.substring(6).trim();
                    if (requested.contains(",")) throw new NumberFormatException();
                    String[] sides = requested.split("-", -1);
                    if (sides.length != 2 || length == 0) throw new NumberFormatException();
                    if (sides[0].isEmpty()) {
                        long suffix = Long.parseLong(sides[1]);
                        if (suffix <= 0) throw new NumberFormatException();
                        start = Math.max(0, length - suffix);
                    } else {
                        start = Long.parseLong(sides[0]);
                    }
                    end = sides[1].isEmpty() || sides[0].isEmpty()
                            ? length - 1 : Math.min(length - 1, Long.parseLong(sides[1]));
                    if (start < 0 || start >= length || end < start) throw new NumberFormatException();
                    partial = true;
                } catch (NumberFormatException invalid) {
                    writeHeaders(output, 416, "Range Not Satisfiable", "text/plain", 0,
                            setCookie, "Content-Range: bytes */" + length + "\r\n");
                    output.flush(); return;
                }
            }
            long count = partial ? end - start + 1 : length;
            String extras = "Accept-Ranges: bytes\r\n" + (partial
                    ? "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n" : "");
            writeHeaders(output, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                    mime(file.getName()), count, setCookie, extras);
            if (!parts[0].equals("HEAD")) {
                try (FileInputStream fileInput = new FileInputStream(file)) {
                    long toSkip = start;
                    while (toSkip > 0) {
                        long skipped = fileInput.skip(toSkip);
                        if (skipped <= 0) throw new IOException("Cannot seek into asset");
                        toSkip -= skipped;
                    }
                    byte[] buffer = new byte[16384];
                    long left = count;
                    while (left > 0) {
                        int len = fileInput.read(buffer, 0, (int)Math.min(buffer.length, left));
                        if (len < 0) break;
                        output.write(buffer, 0, len);
                        left -= len;
                    }
                }
            }
            output.flush();
        } catch (Exception ignored) {
            // The client may cancel in-flight requests while navigating.
        }
    }

    private static File indexFile(File root) {
        File[] files = root.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase("index.html")) return f;
        return null;
    }

    private File resolve(String path) throws IOException {
        File candidate = new File(siteRoot, path.substring(1)).getCanonicalFile();
        if (!candidate.toPath().startsWith(siteRoot.toPath())) return null;
        if (candidate.isDirectory()) return indexFile(candidate);
        return candidate;
    }

    private boolean hasAccessQuery(String query) {
        for (String pair : query.split("&")) if (pair.equals("access=" + secret)) return true;
        return false;
    }

    private boolean hasSession(String cookie) {
        for (String part : cookie.split(";")) {
            if (part.trim().equals(cookieName + "=" + secret)) return true;
        }
        return false;
    }

    private static String readLine(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            int b = stream.read();
            if (b == -1) return null;
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char)b);
        }
        throw new IOException("Line too long");
    }

    private static void respond(OutputStream out, int code, String text, String body,
                                boolean head, String cookie) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, code, text, "text/plain; charset=utf-8", bytes.length, cookie);
        if (!head) out.write(bytes);
        out.flush();
    }

    private static void writeHeaders(OutputStream out, int code, String description, String mime,
                                     long size, String cookie) throws IOException {
        writeHeaders(out, code, description, mime, size, cookie, "");
    }

    private static void writeHeaders(OutputStream out, int code, String description, String mime,
                                     long size, String cookie, String extra) throws IOException {
        String response = "HTTP/1.1 " + code + " " + description + "\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Length: " + size + "\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                (cookie == null ? "" : "Set-Cookie: " + cookie + "\r\n") + extra + "\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
    }

    public static String mime(String name) {
        String ext = name.toLowerCase(Locale.ROOT);
        Map<String, String> types = new HashMap<>();
        types.put("html", "text/html; charset=utf-8"); types.put("htm", "text/html; charset=utf-8");
        types.put("css", "text/css; charset=utf-8"); types.put("js", "text/javascript; charset=utf-8");
        types.put("mjs", "text/javascript; charset=utf-8"); types.put("json", "application/json; charset=utf-8");
        types.put("svg", "image/svg+xml"); types.put("png", "image/png");
        types.put("jpg", "image/jpeg"); types.put("jpeg", "image/jpeg");
        types.put("gif", "image/gif"); types.put("webp", "image/webp");
        types.put("avif", "image/avif"); types.put("ico", "image/x-icon");
        types.put("mp3", "audio/mpeg"); types.put("wav", "audio/wav");
        types.put("ogg", "audio/ogg"); types.put("mp4", "video/mp4");
        types.put("webm", "video/webm"); types.put("woff", "font/woff");
        types.put("woff2", "font/woff2"); types.put("ttf", "font/ttf");
        types.put("otf", "font/otf"); types.put("wasm", "application/wasm");
        types.put("pdf", "application/pdf"); types.put("txt", "text/plain; charset=utf-8");
        types.put("xml", "application/xml"); types.put("map", "application/json");
        String suffix = ext.substring(ext.lastIndexOf('.') + 1);
        return types.getOrDefault(suffix, "application/octet-stream");
    }

    @Override public void close() {
        active = false;
        try { listener.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}
