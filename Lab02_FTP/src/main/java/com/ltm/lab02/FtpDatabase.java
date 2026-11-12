package com.ltm.lab02;

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
 * Quản lý cơ sở dữ liệu SQLite cho Lab 02 (FTP Server):
 * - Bảng `users`: lưu tài khoản, mật khẩu băm SHA-256, thư mục gốc (home_dir), quyền ghi.
 * - Bảng `transfer_history`: lưu lịch sử Upload, Download, Delete thực tế qua FTP.
 */
public class FtpDatabase {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String jdbcUrl;

    public static class UserRecord {
        public final int id;
        public final String username;
        public final String homeDir;
        public final boolean canWrite;
        public final boolean enabled;
        public final String createdAt;

        public UserRecord(int id, String username, String homeDir, boolean canWrite, boolean enabled, String createdAt) {
            this.id = id;
            this.username = username;
            this.homeDir = homeDir;
            this.canWrite = canWrite;
            this.enabled = enabled;
            this.createdAt = createdAt;
        }
    }

    public static class TransferRecord {
        public final int id;
        public final String username;
        public final String action;
        public final String filename;
        public final long fileSize;
        public final String remoteIp;
        public final String status;
        public final String createdAt;

        public TransferRecord(int id, String username, String action, String filename, long fileSize,
                              String remoteIp, String status, String createdAt) {
            this.id = id;
            this.username = username;
            this.action = action;
            this.filename = filename;
            this.fileSize = fileSize;
            this.remoteIp = remoteIp;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    public FtpDatabase(Path dbFile) throws SQLException {
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
        } catch (Exception ignored) {
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        initSchema();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initSchema() throws SQLException {
        String sqlUsers = """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    home_dir TEXT NOT NULL,
                    can_write INTEGER NOT NULL DEFAULT 1,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                )
                """;
        String sqlHistory = """
                CREATE TABLE IF NOT EXISTS transfer_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    action TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    remote_ip TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """;
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute(sqlUsers);
            st.execute(sqlHistory);
        }
    }

    /** Băm mật khẩu bằng SHA-256 (UTF-8) để không lưu mật khẩu thô xuống SQLite. */
    public static String hashPassword(String plain) {
        if (plain == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể khởi tạo SHA-256", e);
        }
    }

    public synchronized void upsertUser(String username, String plainPassword, String homeDir, boolean canWrite, boolean enabled)
            throws SQLException {
        String cleanUser = username == null ? "" : username.trim();
        if (cleanUser.isEmpty()) {
            throw new IllegalArgumentException("Tên đăng nhập không được để trống.");
        }
        String now = LocalDateTime.now().format(DT_FMT);
        String sql = """
                INSERT INTO users(username, password_hash, home_dir, can_write, enabled, created_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(username) DO UPDATE SET
                    password_hash = excluded.password_hash,
                    home_dir = excluded.home_dir,
                    can_write = excluded.can_write,
                    enabled = excluded.enabled
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cleanUser);
            ps.setString(2, hashPassword(plainPassword));
            ps.setString(3, homeDir);
            ps.setInt(4, canWrite ? 1 : 0);
            ps.setInt(5, enabled ? 1 : 0);
            ps.setString(6, now);
            ps.executeUpdate();
        }
    }

    public synchronized void deleteUser(String username) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE username = ?")) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    public UserRecord getUser(String username) throws SQLException {
        String sql = "SELECT id, username, home_dir, can_write, enabled, created_at FROM users WHERE username = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new UserRecord(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("home_dir"),
                            rs.getInt("can_write") == 1,
                            rs.getInt("enabled") == 1,
                            rs.getString("created_at")
                    );
                }
            }
        }
        return null;
    }

    public UserRecord authenticate(String username, String plainPassword) throws SQLException {
        String sql = "SELECT id, username, password_hash, home_dir, can_write, enabled, created_at FROM users WHERE username = ?";
        String expectedHash = hashPassword(plainPassword);
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String stored = rs.getString("password_hash");
                    boolean enabled = rs.getInt("enabled") == 1;
                    if (enabled && stored.equals(expectedHash)) {
                        return new UserRecord(
                                rs.getInt("id"),
                                rs.getString("username"),
                                rs.getString("home_dir"),
                                rs.getInt("can_write") == 1,
                                true,
                                rs.getString("created_at")
                        );
                    }
                }
            }
        }
        return null;
    }

    public List<UserRecord> listUsers() throws SQLException {
        List<UserRecord> res = new ArrayList<>();
        String sql = "SELECT id, username, home_dir, can_write, enabled, created_at FROM users ORDER BY id ASC";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                res.add(new UserRecord(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getString("home_dir"),
                        rs.getInt("can_write") == 1,
                        rs.getInt("enabled") == 1,
                        rs.getString("created_at")
                ));
            }
        }
        return res;
    }

    public synchronized void recordTransfer(String username, String action, String filename, long fileSize,
                                            String remoteIp, String status) throws SQLException {
        String sql = """
                INSERT INTO transfer_history(username, action, filename, file_size, remote_ip, status, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, action);
            ps.setString(3, filename);
            ps.setLong(4, fileSize);
            ps.setString(5, remoteIp);
            ps.setString(6, status);
            ps.setString(7, LocalDateTime.now().format(DT_FMT));
            ps.executeUpdate();
        }
    }

    public List<TransferRecord> listTransfers(int limit) throws SQLException {
        List<TransferRecord> res = new ArrayList<>();
        String sql = "SELECT id, username, action, filename, file_size, remote_ip, status, created_at FROM transfer_history ORDER BY id DESC LIMIT ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    res.add(new TransferRecord(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("action"),
                            rs.getString("filename"),
                            rs.getLong("file_size"),
                            rs.getString("remote_ip"),
                            rs.getString("status"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return res;
    }
}

