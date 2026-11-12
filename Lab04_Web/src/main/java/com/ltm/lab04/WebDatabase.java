package com.ltm.lab04;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab 04 — Quản lý SQLite lưu lịch sử duyệt Web (bảng `history`).
 */
public class WebDatabase {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path dbPath;

    public static class HistoryRecord {
        public final int id;
        public final String url;
        public final String method;
        public final int statusCode;
        public final String contentType;
        public final long contentLength;
        public final String visitedAt;

        public HistoryRecord(int id, String url, String method, int statusCode,
                             String contentType, long contentLength, String visitedAt) {
            this.id = id;
            this.url = url;
            this.method = method;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.visitedAt = visitedAt;
        }
    }

    public WebDatabase(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ignored) {
        }
        initSchema();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    private void initSchema() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "url TEXT NOT NULL, "
                + "method TEXT NOT NULL, "
                + "status_code INTEGER NOT NULL, "
                + "content_type TEXT, "
                + "content_length INTEGER NOT NULL DEFAULT 0, "
                + "visited_at TEXT NOT NULL)";
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    public void recordHistory(String url, String method, int statusCode,
                              String contentType, long contentLength) throws SQLException {
        String sql = "INSERT INTO history(url, method, status_code, content_type, content_length, visited_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setString(2, method);
            ps.setInt(3, statusCode);
            ps.setString(4, contentType == null ? "" : contentType);
            ps.setLong(5, contentLength);
            ps.setString(6, LocalDateTime.now().format(TS_FMT));
            ps.executeUpdate();
        }
    }

    public List<HistoryRecord> listHistory() throws SQLException {
        List<HistoryRecord> out = new ArrayList<>();
        String sql = "SELECT id, url, method, status_code, content_type, content_length, visited_at "
                + "FROM history ORDER BY id DESC LIMIT 200";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new HistoryRecord(
                        rs.getInt("id"),
                        rs.getString("url"),
                        rs.getString("method"),
                        rs.getInt("status_code"),
                        rs.getString("content_type"),
                        rs.getLong("content_length"),
                        rs.getString("visited_at")
                ));
            }
        }
        return out;
    }

    public void clearHistory() throws SQLException {
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM history");
        }
    }
}

