package com.ltm.lab03;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Quản lý CSDL SQLite cho Lab 03 (Mail Server & Client):
 * - Bảng `accounts`: danh sách tài khoản email nội bộ (chỉ lưu SHA-256 hash mật khẩu).
 * - Bảng `emails`: lưu toàn bộ thư đã gửi (SENT), thư đến (INBOX / SERVER_INBOX) kèm người gửi, người nhận, tiêu đề, thời gian, nội dung.
 */
public class MailDatabase {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String jdbcUrl;

    public static class AccountRecord {
        public final int id;
        public final String email;
        public final String mode;
        public final String createdAt;

        public AccountRecord(int id, String email, String mode, String createdAt) {
            this.id = id;
            this.email = email;
            this.mode = mode;
            this.createdAt = createdAt;
        }
    }

    public static class EmailRecord {
        public final int id;
        public final String mailboxEmail;
        public final String direction;
        public final String mode;
        public final String sender;
        public final String recipient;
        public final String subject;
        public final String body;
        public final String sentAt;

        public EmailRecord(int id, String mailboxEmail, String direction, String mode,
                           String sender, String recipient, String subject, String body, String sentAt) {
            this.id = id;
            this.mailboxEmail = mailboxEmail;
            this.direction = direction;
            this.mode = mode;
            this.sender = sender;
            this.recipient = recipient;
            this.subject = subject;
            this.body = body;
            this.sentAt = sentAt;
        }
    }

    public MailDatabase(Path dbFile) throws SQLException {
        try {
            if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
        } catch (Exception ignored) {
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        initSchema();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() throws SQLException {
        String sqlAccounts = """
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """;
        String sqlEmails = """
                CREATE TABLE IF NOT EXISTS emails (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mailbox_email TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    recipient TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sent_at TEXT NOT NULL,
                    message_uid TEXT NOT NULL,
                    UNIQUE(mailbox_email, direction, message_uid)
                )
                """;
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute(sqlAccounts);
            st.execute(sqlEmails);
        }
    }

    public static String hashSha256(String raw) {
        if (raw == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không thể băm SHA-256", e);
        }
    }

    public synchronized void saveAccount(String email, String plainPassword, String mode) throws SQLException {
        String sql = """
                INSERT INTO accounts(email, password_hash, mode, created_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(email) DO UPDATE SET
                    password_hash = excluded.password_hash,
                    mode = excluded.mode
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email.trim());
            ps.setString(2, hashSha256(plainPassword));
            ps.setString(3, mode);
            ps.setString(4, LocalDateTime.now().format(DT_FMT));
            ps.executeUpdate();
        }
    }

    public List<AccountRecord> listAccounts() throws SQLException {
        List<AccountRecord> res = new ArrayList<>();
        String sql = "SELECT id, email, mode, created_at FROM accounts ORDER BY id ASC";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                res.add(new AccountRecord(
                        rs.getInt("id"),
                        rs.getString("email"),
                        rs.getString("mode"),
                        rs.getString("created_at")
                ));
            }
        }
        return res;
    }

    public synchronized void saveEmail(String mailboxEmail, String direction, String mode,
                                       String sender, String recipient, String subject, String body,
                                       String sentAt, String messageUid) throws SQLException {
        String uid = (messageUid != null && !messageUid.isBlank())
                ? messageUid
                : hashSha256(sender + "|" + recipient + "|" + subject + "|" + sentAt + "|" + body);
        String sql = """
                INSERT OR IGNORE INTO emails(mailbox_email, direction, mode, sender, recipient, subject, body, sent_at, message_uid)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, mailboxEmail);
            ps.setString(2, direction);
            ps.setString(3, mode);
            ps.setString(4, sender);
            ps.setString(5, recipient);
            ps.setString(6, subject);
            ps.setString(7, body);
            ps.setString(8, sentAt);
            ps.setString(9, uid);
            ps.executeUpdate();
        }
    }

    public List<EmailRecord> listEmails(String mailboxEmail, String direction) throws SQLException {
        List<EmailRecord> res = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, mailbox_email, direction, mode, sender, recipient, subject, body, sent_at FROM emails WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (mailboxEmail != null && !mailboxEmail.isBlank()) {
            sql.append(" AND mailbox_email = ?");
            params.add(mailboxEmail);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = ?");
            params.add(direction);
        }
        sql.append(" ORDER BY id DESC");

        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    res.add(new EmailRecord(
                            rs.getInt("id"),
                            rs.getString("mailbox_email"),
                            rs.getString("direction"),
                            rs.getString("mode"),
                            rs.getString("sender"),
                            rs.getString("recipient"),
                            rs.getString("subject"),
                            rs.getString("body"),
                            rs.getString("sent_at")
                    ));
                }
            }
        }
        return res;
    }
}

