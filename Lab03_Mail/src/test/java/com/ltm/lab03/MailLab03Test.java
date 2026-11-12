package com.ltm.lab03;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tự động JUnit 5 cho Lab 03:
 * - Khởi chạy Mail Server nội bộ thực thụ (GreenMail SMTP + POP3 + SQLite).
 * - Tạo 2 tài khoản A & B.
 * - A gửi Email thực tế qua SMTP Jakarta Mail.
 * - Kiểm tra Server SQLite đã lưu thư.
 * - B nhận Email qua POP3 Jakarta Mail (và sai mật khẩu bị từ chối).
 * - Kiểm tra Client SQLite lưu đúng thư và không bị trùng khi fetch nhiều lần.
 */
public class MailLab03Test {

    private Path tempDir;
    private MailServerApp.LocalMailServerCore mailServer;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("lab03_mail_test_");
        mailServer = new MailServerApp.LocalMailServerCore("127.0.0.1", 0, 0, tempDir.resolve("server.db"));
        mailServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mailServer != null) mailServer.stop();
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }

    @Test
    void testLocalhostSmtpSendPop3ReceiveAndSqlitePersistence() throws Exception {
        String passA = "PassUserA#2026";
        String passB = "PassUserB#2026";
        mailServer.createLocalAccount("sv_a@ltm.local", passA);
        mailServer.createLocalAccount("sv_b@ltm.local", passB);

        // Cấu hình Client A & B trỏ về cổng SMTP / POP3 thực tế của Server
        MailClientApp.MailConfig cfgA = new MailClientApp.MailConfig();
        cfgA.mode = "LOCALHOST";
        cfgA.email = "sv_a@ltm.local";
        cfgA.password = passA;
        cfgA.smtpHost = "127.0.0.1";
        cfgA.smtpPort = mailServer.getSmtpPort();
        cfgA.pop3Host = "127.0.0.1";
        cfgA.pop3Port = mailServer.getPop3Port();

        MailClientApp.MailConfig cfgB = new MailClientApp.MailConfig();
        cfgB.mode = "LOCALHOST";
        cfgB.email = "sv_b@ltm.local";
        cfgB.password = passB;
        cfgB.smtpHost = "127.0.0.1";
        cfgB.smtpPort = mailServer.getSmtpPort();
        cfgB.pop3Host = "127.0.0.1";
        cfgB.pop3Port = mailServer.getPop3Port();

        MailClientApp.MailClientCore clientA = new MailClientApp.MailClientCore(tempDir.resolve("client_a.db"));
        MailClientApp.MailClientCore clientB = new MailClientApp.MailClientCore(tempDir.resolve("client_b.db"));

        List<String> smtpLogs = new ArrayList<>();
        List<String> pop3Logs = new ArrayList<>();
        clientA.setProtocolLogger(smtpLogs::add);
        clientB.setProtocolLogger(pop3Logs::add);

        // 1. A gửi thư cho B qua SMTP
        clientA.sendEmail(cfgA, "sv_b@ltm.local", "Báo cáo Lab 03 Mail", "Nội dung kiểm thử SMTP -> POP3 tiếng Việt UTF-8.");

        // Kiểm tra bảng SENT trên SQLite của A
        List<MailDatabase.EmailRecord> sentA = clientA.getDatabase().listEmails("sv_a@ltm.local", "SENT");
        assertEquals(1, sentA.size());
        assertEquals("Báo cáo Lab 03 Mail", sentA.get(0).subject);

        // Kiểm tra bảng SERVER_INBOX trên SQLite của Server
        List<MailDatabase.EmailRecord> srvMails = mailServer.getDatabase().listEmails("sv_b@ltm.local", "SERVER_INBOX");
        assertEquals(1, srvMails.size());
        assertEquals("sv_a@ltm.local", srvMails.get(0).sender);

        // 2. Sai mật khẩu POP3 phải bị từ chối
        MailClientApp.MailConfig wrongCfg = new MailClientApp.MailConfig();
        wrongCfg.email = "sv_b@ltm.local";
        wrongCfg.password = "WrongPass";
        wrongCfg.pop3Host = "127.0.0.1";
        wrongCfg.pop3Port = mailServer.getPop3Port();
        assertThrows(AuthenticationFailedException.class, () -> clientB.fetchEmailsPop3(wrongCfg));

        // 3. B nhận thư qua POP3 & lưu vào SQLite
        List<MailDatabase.EmailRecord> inboxB = clientB.fetchEmailsPop3(cfgB);
        assertEquals(1, inboxB.size());
        assertEquals("Báo cáo Lab 03 Mail", inboxB.get(0).subject);
        assertTrue(inboxB.get(0).body.contains("tiếng Việt UTF-8"));

        // Gọi nhận POP3 lần thứ 2 không được tạo dòng trùng trong SQLite (UNIQUE constraint + INSERT OR IGNORE)
        List<MailDatabase.EmailRecord> inboxBSecond = clientB.fetchEmailsPop3(cfgB);
        assertEquals(1, inboxBSecond.size());

        // 4. Xác nhận nhật ký giao thức SMTP & POP3 thật đã chạy
        assertTrue(smtpLogs.stream().anyMatch(l -> l.contains("MAIL FROM:") || l.contains("250")));
        assertTrue(pop3Logs.stream().anyMatch(l -> l.contains("RETR 1") || l.contains("+OK")));
    }
}

