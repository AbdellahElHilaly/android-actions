import com.htmlshelf.core.SafeZipExtractor;
import com.htmlshelf.core.SiteHttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CoreTests {
    private static int assertions = 0;

    private static void ok(boolean condition, String explanation) {
        assertions++;
        if (!condition) throw new AssertionError(explanation);
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static File tmp() throws Exception { return Files.createTempDirectory("htmlshelf-").toFile(); }

    private static String fetch(String url, String cookie, int expectedStatus, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        int status = c.getResponseCode();
        ok(status == expectedStatus, url + " returned " + status + " instead of " + expectedStatus);
        String body = "";
        if (!method.equals("HEAD")) {
            byte[] data = (status < 400 ? c.getInputStream() : c.getErrorStream()).readAllBytes();
            body = new String(data, StandardCharsets.UTF_8);
        }
        c.disconnect();
        return body;
    }

    private static void testZip() throws Exception {
        File output = tmp();
        try {
            Map<String,String> normal = new LinkedHashMap<>();
            normal.put("app/assets/style.css", "body{color:red}");
            normal.put("app/index.html", "<html>Hello</html>");
            normal.put("extra/index.html", "Other");
            String root = SafeZipExtractor.extractAndLocateIndex(
                    new ByteArrayInputStream(zip(normal)), output);
            ok(root.equals("app"), "Picks earliest lexicographic equally shallow index");
            ok(new File(output,"app/assets/style.css").isFile(), "Assets copied");
            ok(new File(output,"app/index.html").isFile(), "Nested index copied");
        } finally { SafeZipExtractor.deleteTree(output); }

        for (String path : new String[] {"../outside.html", "../escape/index.html", "/absolute/index.html", "C:/evil/index.html", "ok/../../bad.html", "..\\evil.html"}) {
            output = tmp();
            try {
                boolean blocked = false;
                try { SafeZipExtractor.extractAndLocateIndex(
                        new ByteArrayInputStream(zip(Map.of(path,"bad"))), output); }
                catch (java.io.IOException e) { blocked = true; }
                ok(blocked, "ZIP traversal blocked: " + path);
            } finally { SafeZipExtractor.deleteTree(output); }
        }
        output = tmp();
        try {
            boolean noIndex = false;
            try { SafeZipExtractor.extractAndLocateIndex(
                    new ByteArrayInputStream(zip(Map.of("style.css","test"))), output); }
            catch (java.io.IOException e) { noIndex = true; }
            ok(noIndex, "ZIP without index rejected");
        } finally { SafeZipExtractor.deleteTree(output); }
    }

    private static void testHttp() throws Exception {
        File root = tmp();
        try {
            Files.writeString(new File(root,"index.html").toPath(), "<html>Homepage</html>");
            File assets = new File(root,"assets");
            ok(assets.mkdir(), "Assets folder created");
            Files.writeString(new File(assets,"main.css").toPath(), "h1 { color: green }");
            Files.writeString(new File(assets,"song.mp3").toPath(), "fake-audio");
            File parentSecret = new File(root.getParentFile(), "very-private-file.txt");
            Files.writeString(parentSecret.toPath(), "DO-NOT-SERVE");
            try (SiteHttpServer server = new SiteHttpServer(root)) {
                String url = server.launchUrl();
                String base = url.substring(0,url.indexOf("/?"));
                fetch(base+"/",null,403,"GET");
                ok(fetch(url,null,200,"GET").contains("Homepage"), "Launch URL opens HTML");
                String cookie = "";
                // Name and secret are tied to the URL, first response sets this cookie.
                HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
                c.getInputStream().close();
                cookie = c.getHeaderField("Set-Cookie").split(";", 2)[0];
                c.disconnect();
                ok(cookie.startsWith("hs_"), "Session cookie is unique");
                ok(fetch(base+"/assets/main.css",cookie,200,"GET").contains("green"), "Root assets work");
                ok(fetch(base+"/assets/song.mp3",cookie,200,"GET").equals("fake-audio"), "Audio works");
                HttpURLConnection range = (HttpURLConnection)new URL(base+"/assets/song.mp3").openConnection();
                range.setRequestProperty("Cookie", cookie);
                range.setRequestProperty("Range", "bytes=5-9");
                ok(range.getResponseCode() == 206, "Partial content status");
                ok("bytes 5-9/10".equals(range.getHeaderField("Content-Range")), "Content-Range header");
                ok(new String(range.getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("audio"), "Range reads exact bytes");
                range.disconnect();
                HttpURLConnection badRange = (HttpURLConnection)new URL(base+"/assets/song.mp3").openConnection();
                badRange.setRequestProperty("Cookie", cookie);
                badRange.setRequestProperty("Range", "bytes=999-");
                ok(badRange.getResponseCode() == 416, "Invalid range rejected");
                badRange.disconnect();
                fetch(base+"/index.html",cookie,200,"HEAD");
                ok(fetch(base+"/client-side-route",cookie,200,"GET").contains("Homepage"), "SPA fallback works");
                fetch(base+"/assets/missing.css",cookie,404,"GET");
                String traversal = base+"/%2e%2e/very-private-file.txt";
                String result = fetch(traversal,cookie,404,"GET");
                ok(!result.contains("DO-NOT-SERVE"), "Traversal response cannot read parent");
                try (SiteHttpServer second = new SiteHttpServer(root)) {
                    String secondBase = second.launchUrl().split("/\\?")[0];
                    // Do not use another site's cookie for the new server.
                    fetch(second.launchUrl().replace("/?access=", "/assets/main.css?nothing="),cookie,403,"GET");
                }
            }
            parentSecret.delete();
        } finally { SafeZipExtractor.deleteTree(root); }
    }

    public static void main(String[] args) throws Exception {
        testZip();
        testHttp();
        ok(SiteHttpServer.mime("a.svg").equals("image/svg+xml"), "SVG mime");
        ok(SiteHttpServer.mime("a.woff2").equals("font/woff2"), "Font mime");
        ok(SiteHttpServer.mime("index.HTML").startsWith("text/html"), "Case-insensitive html mime");
        System.out.println("PASS: " + assertions + " core assertions");
    }
}
