package com.ltm.lab02;

import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử JUnit 5 cho Lab 02:
 * - FTP Server Apache Mina + SQLite UserManager (`users` & `transfer_history`).
 * - FTP Client Apache Commons Net Passive Mode: Login, Upload + Progress, List, Download, Delete, Phân quyền Read-Only.
 */
public class FtpLab02Test {

    private Path tempDir;
    private FtpServerApp.FtpServerCore server;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("lab02_ftp_test_");
        Path ftpRoot = tempDir.resolve("ftp_root");
        Path dbPath = tempDir.resolve("ftp_test.db");
        server = new FtpServerApp.FtpServerCore(ftpRoot, dbPath, 0, "22250-22270");
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    @Test
    void testRealFtpUploadDownloadDeleteAndDatabaseAudit() throws Exception {
        String testPass = "LtmTest@2026";
        server.addOrUpdateUser("sinhvien", testPass, "", true);

        // 1. Sai mật khẩu phải bị từ chối
        FtpClientApp.FtpClientCore badClient = new FtpClientApp.FtpClientCore();
        assertThrows(IOException.class, () ->
                badClient.connectAndLogin("127.0.0.1", server.getPort(), "sinhvien", "wrongpass"));

        // 2. Đăng nhập đúng + ghi nhật ký giao thức FTP thật
        List<String> protoLog = new ArrayList<>();
        FtpClientApp.FtpClientCore client = new FtpClientApp.FtpClientCore();
        client.setProtocolLogger(protoLog::add);
        try {
            client.connectAndLogin("127.0.0.1", server.getPort(), "sinhvien", testPass);
            assertTrue(client.isConnected());

            // 3. Upload file 128 KB để kiểm tra CopyStreamListener báo tiến trình
            byte[] payload = ("Dữ liệu thực hành FTP Lab 02 - Java Commons Net\n".repeat(2500))
                    .getBytes(StandardCharsets.UTF_8);
            Path localSrc = tempDir.resolve("tai_lieu_ltm.txt");
            Files.write(localSrc, payload);

            List<Integer> uploadProgress = new ArrayList<>();
            client.uploadFile(localSrc, "tai_lieu_ltm.txt", uploadProgress::add);
            assertFalse(uploadProgress.isEmpty(), "Phải ghi nhận sự kiện tiến trình upload");
            assertEquals(100, uploadProgress.get(uploadProgress.size() - 1));

            // 4. Liệt kê file trên Server
            List<FTPFile> remoteFiles = client.listFiles();
            assertTrue(remoteFiles.stream().anyMatch(f -> "tai_lieu_ltm.txt".equals(f.getName()) && f.getSize() == payload.length));

            // 5. Download về đường dẫn mới & so sánh toàn vẹn dữ liệu
            Path localDst = tempDir.resolve("downloaded_tai_lieu.txt");
            List<Integer> downloadProgress = new ArrayList<>();
            client.downloadFile("tai_lieu_ltm.txt", localDst, payload.length, downloadProgress::add);
            assertArrayEquals(payload, Files.readAllBytes(localDst));
            assertEquals(100, downloadProgress.get(downloadProgress.size() - 1));

            // 6. Tạo thư mục & Xóa file trên Server
            assertTrue(client.makeDirectory("ThucHanh"));
            client.deleteFile("tai_lieu_ltm.txt");
            assertFalse(client.listFiles().stream().anyMatch(f -> "tai_lieu_ltm.txt".equals(f.getName())));

            // 7. Xác nhận giao thức FTP thật đã chạy qua PASV / STOR / RETR / DELE
            assertTrue(protoLog.stream().anyMatch(l -> l.contains("PASV")));
            assertTrue(protoLog.stream().anyMatch(l -> l.contains("STOR tai_lieu_ltm.txt")));
            assertTrue(protoLog.stream().anyMatch(l -> l.contains("RETR tai_lieu_ltm.txt")));
            assertTrue(protoLog.stream().anyMatch(l -> l.contains("DELE tai_lieu_ltm.txt")));

            // 8. Kiểm tra dữ liệu bảng `users` & `transfer_history` trong SQLite
            assertNotNull(server.getDatabase().getUser("sinhvien"));
            List<FtpDatabase.TransferRecord> history = server.getDatabase().listTransfers(20);
            assertTrue(history.stream().anyMatch(h -> "UPLOAD".equals(h.action) && "tai_lieu_ltm.txt".equals(h.filename)));
            assertTrue(history.stream().anyMatch(h -> "DOWNLOAD".equals(h.action) && "tai_lieu_ltm.txt".equals(h.filename)));
            assertTrue(history.stream().anyMatch(h -> "DELETE".equals(h.action) && "tai_lieu_ltm.txt".equals(h.filename)));
        } finally {
            client.disconnect();
        }
    }

    @Test
    void testReadOnlyUserCannotUploadOrDelete() throws Exception {
        String roPass = "ReadOnly#123";
        Files.writeString(server.getRootDir().resolve("cong_khai.txt"), "Tai lieu chi doc", StandardCharsets.UTF_8);
        server.addOrUpdateUser("viewer", roPass, "", false);

        FtpClientApp.FtpClientCore client = new FtpClientApp.FtpClientCore();
        try {
            client.connectAndLogin("127.0.0.1", server.getPort(), "viewer", roPass);
            Path dl = tempDir.resolve("ro_dl.txt");
            client.downloadFile("cong_khai.txt", dl, 16, null);
            assertEquals("Tai lieu chi doc", Files.readString(dl, StandardCharsets.UTF_8));

            Path up = tempDir.resolve("up.txt");
            Files.writeString(up, "blocked", StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> client.uploadFile(up, "up.txt", null));
            assertThrows(IOException.class, () -> client.deleteFile("cong_khai.txt"));

            // Đợi luồng MINA Server hoàn tất callback afterCommand + ghi SQLite
            Thread.sleep(150);
            List<FtpDatabase.TransferRecord> hist = server.getDatabase().listTransfers(10);
            assertTrue(hist.stream().anyMatch(h -> "UPLOAD".equals(h.action) && h.status.startsWith("DENIED")));
            assertTrue(hist.stream().anyMatch(h -> "DELETE".equals(h.action) && h.status.startsWith("DENIED")));
        } finally {
            client.disconnect();
        }
    }
}
