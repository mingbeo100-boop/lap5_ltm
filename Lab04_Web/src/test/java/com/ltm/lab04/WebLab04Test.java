package com.ltm.lab04;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tích hợp thật cho Lab 04:
 * - HTTP Server (www/ thật gồm index.html, tailieu.pdf, nhac.mp3, video.mp4).
 * - HTTP GET, HEAD (0 body bytes), POST (/api/submit), HTTP 206 Range.
 * - PDFBox đọc PDF nhận từ HTTP, JavaFX MediaPlayer stream MP3 & MP4 trực tiếp qua HTTP URL.
 * - Lưu lịch sử SQLite bảng `history`.
 */
public class WebLab04Test {

    @Test
    void testHttpGetHeadPostPdfRangeMediaAndSqliteHistory(@TempDir Path tmpDir) throws Exception {
        Path wwwDir = WebServerApp.resolveLabDir().resolve("www").toAbsolutePath().normalize();
        assertTrue(Files.exists(wwwDir.resolve("index.html")), "Thiếu index.html trong www/");
        assertTrue(Files.exists(wwwDir.resolve("tailieu.pdf")), "Thiếu tailieu.pdf trong www/");
        assertTrue(Files.exists(wwwDir.resolve("nhac.mp3")), "Thiếu nhac.mp3 trong www/");
        assertTrue(Files.exists(wwwDir.resolve("video.mp4")), "Thiếu video.mp4 trong www/");

        WebServerApp.HttpWebServerCore server = new WebServerApp.HttpWebServerCore(0, wwwDir);
        server.start();
        try {
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            WebBrowserApp.BrowserHttpClientCore client =
                    new WebBrowserApp.BrowserHttpClientCore(tmpDir.resolve("browser_test.db"));

            // 1. GET Trang chủ HTML
            WebBrowserApp.HttpResult home = client.execute(base + "/index.html", "GET", "", true);
            assertEquals(200, home.statusCode);
            assertTrue(home.contentType.contains("text/html"));
            assertTrue(new String(home.body, StandardCharsets.UTF_8).contains("LAB 04"));

            // 2. GET PDF + parse thật bằng Apache PDFBox + tải xuống file
            WebBrowserApp.HttpResult pdfGet = client.execute(base + "/tailieu.pdf", "GET", "", true);
            assertEquals(200, pdfGet.statusCode);
            assertEquals("application/pdf", pdfGet.contentType);
            assertEquals(Files.size(wwwDir.resolve("tailieu.pdf")), pdfGet.body.length);
            try (PDDocument doc = PDDocument.load(pdfGet.body)) {
                assertEquals(2, doc.getNumberOfPages());
            }
            Path downloadedPdf = tmpDir.resolve("tailieu_downloaded.pdf");
            Files.write(downloadedPdf, pdfGet.body);
            assertEquals(Files.size(wwwDir.resolve("tailieu.pdf")), Files.size(downloadedPdf));

            // 3. HEAD PDF: Chỉ lấy Header, Body PHẢI rỗng 0 byte
            WebBrowserApp.HttpResult pdfHead = client.execute(base + "/tailieu.pdf", "HEAD", "", true);
            assertEquals(200, pdfHead.statusCode);
            assertEquals("application/pdf", pdfHead.contentType);
            assertEquals(0, pdfHead.body.length, "HTTP HEAD không được phép có body");
            assertEquals(Files.size(wwwDir.resolve("tailieu.pdf")), pdfHead.declaredLength);

            // 4. POST tới /api/submit
            String payload = "{\"maSV\":\"B21DCCN001\",\"lab\":\"Lab04\"}";
            WebBrowserApp.HttpResult postRes = client.execute(base + "/api/submit", "POST", payload, true);
            assertEquals(200, postRes.statusCode);
            assertTrue(postRes.contentType.contains("application/json"));
            assertTrue(new String(postRes.body, StandardCharsets.UTF_8).contains("B21DCCN001"));

            // 5. HTTP Range (206 Partial Content) trên video.mp4
            HttpClient raw = HttpClient.newHttpClient();
            HttpRequest rangeReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/video.mp4"))
                    .header("Range", "bytes=0-1023")
                    .GET()
                    .build();
            HttpResponse<byte[]> rangeResp = raw.send(rangeReq, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(206, rangeResp.statusCode());
            assertEquals(1024, rangeResp.body().length);

            // 6. Phát trực tiếp nhac.mp3 và video.mp4 qua HTTP URL bằng JavaFX MediaPlayer
            CountDownLatch jfxReady = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                new JFXPanel();
                jfxReady.countDown();
            });
            assertTrue(jfxReady.await(5, TimeUnit.SECONDS));
            Platform.setImplicitExit(false);

            assertHttpMediaPlays(base + "/nhac.mp3", false);
            assertHttpMediaPlays(base + "/video.mp4", true);

            // 7. Kiểm tra lịch sử SQLite bảng `history`
            List<WebDatabase.HistoryRecord> hist = client.getDatabase().listHistory();
            assertEquals(4, hist.size());
            assertEquals("POST", hist.get(0).method);
            assertEquals("HEAD", hist.get(1).method);
            assertEquals(0L, hist.get(1).contentLength);
        } finally {
            server.stop();
        }
    }

    private void assertHttpMediaPlays(String httpUrl, boolean expectVideoDimensions) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>("TIMEOUT");
        AtomicReference<MediaPlayer> strongRef = new AtomicReference<>();
        Platform.runLater(() -> {
            Media media = new Media(httpUrl);
            MediaPlayer mp = new MediaPlayer(media);
            strongRef.set(mp);
            mp.setOnReady(() -> {
                double sec = mp.getTotalDuration().toSeconds();
                int w = media.getWidth();
                int h = media.getHeight();
                mp.dispose();
                strongRef.set(null);
                if (sec <= 0) {
                    status.set("INVALID_DURATION:" + sec);
                } else if (expectVideoDimensions && (w <= 0 || h <= 0)) {
                    status.set("NO_VIDEO_DIM:" + w + "x" + h);
                } else {
                    status.set("OK:" + w + "x" + h + ":" + String.format("%.2fs", sec));
                }
                done.countDown();
            });
            mp.setOnError(() -> {
                String err = mp.getError() != null ? mp.getError().toString() : "UNKNOWN";
                mp.dispose();
                strongRef.set(null);
                status.set("ERROR:" + err);
                done.countDown();
            });
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "Timeout loading media: " + httpUrl);
        assertTrue(status.get().startsWith("OK:"), "Media HTTP stream failed for " + httpUrl + " -> " + status.get());
    }
}
