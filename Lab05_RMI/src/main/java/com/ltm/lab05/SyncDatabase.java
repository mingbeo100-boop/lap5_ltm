package com.ltm.lab05;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lab 05 — Lớp JDBC / SQLite riêng của mỗi RMI Server:
 * - Bảng `items`: Lưu dữ liệu + cờ `deleted` (tombstone), `version`, `updated_at_ms`, `pending_sync`.
 * - Bảng `sync_log`: Lưu nhật ký các lần đồng bộ thành công / lỗi / catch-up sau khi kết nối lại.
 */
public class SyncDatabase {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path dbPath;

    public static class SyncLogEntry {
        public final int id;
        public final String direction;
        public final String peerId;
        public final String detail;
        public final String createdAt;

        public SyncLogEntry(int id, String direction, String peerId, String detail, String createdAt) {
            this.id = id;
            this.direction = direction;
            this.peerId = peerId;
            this.detail = detail;
            this.createdAt = createdAt;
        }
    }

    public SyncDatabase(Path dbPath) throws SQLException {
        this.dbPath = dbPath;
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ignored) {
        }
        initSchema();
    }

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    private void initSchema() throws SQLException {
        String sqlItems = "CREATE TABLE IF NOT EXISTS items ("
                + "id TEXT PRIMARY KEY, "
                + "code TEXT NOT NULL, "
                + "name TEXT NOT NULL, "
                + "quantity INTEGER NOT NULL, "
                + "note TEXT, "
                + "version INTEGER NOT NULL, "
                + "updated_at_ms INTEGER NOT NULL, "
                + "deleted INTEGER NOT NULL DEFAULT 0, "
                + "origin_server TEXT NOT NULL, "
                + "pending_sync INTEGER NOT NULL DEFAULT 0)";
        String sqlLogs = "CREATE TABLE IF NOT EXISTS sync_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "direction TEXT NOT NULL, "
                + "peer_id TEXT NOT NULL, "
                + "detail TEXT NOT NULL, "
                + "created_at TEXT NOT NULL)";
        try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
            st.execute(sqlItems);
            st.execute(sqlLogs);
        }
    }

    public synchronized DataItem insertLocalItem(String serverId, String code, String name, int quantity, String note)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        DataItem item = new DataItem(id, code.trim(), name.trim(), quantity, note == null ? "" : note.trim(),
                1L, now, false, serverId);
        String sql = "INSERT INTO items(id, code, name, quantity, note, version, updated_at_ms, deleted, origin_server, pending_sync) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 1)";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.id);
            ps.setString(2, item.code);
            ps.setString(3, item.name);
            ps.setInt(4, item.quantity);
            ps.setString(5, item.note);
            ps.setLong(6, item.version);
            ps.setLong(7, item.updatedAtMs);
            ps.setString(8, item.originServer);
            ps.executeUpdate();
        }
        return item;
    }

    public synchronized DataItem updateLocalItem(String serverId, String id, String code, String name, int quantity, String note)
            throws SQLException {
        DataItem existing = findById(id);
        if (existing == null || existing.deleted) {
            throw new IllegalArgumentException("Không tìm thấy bản ghi để sửa (id=" + id + ")");
        }
        long now = Math.max(System.currentTimeMillis(), existing.updatedAtMs + 1);
        long nextVer = existing.version + 1;
        DataItem updated = new DataItem(id, code.trim(), name.trim(), quantity,
                note == null ? "" : note.trim(), nextVer, now, false, serverId);
        String sql = "UPDATE items SET code=?, name=?, quantity=?, note=?, version=?, updated_at_ms=?, deleted=0, origin_server=?, pending_sync=1 "
                + "WHERE id=?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, updated.code);
            ps.setString(2, updated.name);
            ps.setInt(3, updated.quantity);
            ps.setString(4, updated.note);
            ps.setLong(5, updated.version);
            ps.setLong(6, updated.updatedAtMs);
            ps.setString(7, updated.originServer);
            ps.setString(8, id);
            ps.executeUpdate();
        }
        return updated;
    }

    public synchronized DataItem markDeletedLocal(String serverId, String id) throws SQLException {
        DataItem existing = findById(id);
        if (existing == null) {
            throw new IllegalArgumentException("Không tìm thấy bản ghi để xóa (id=" + id + ")");
        }
        long now = Math.max(System.currentTimeMillis(), existing.updatedAtMs + 1);
        long nextVer = existing.version + 1;
        DataItem tombstone = new DataItem(existing.id, existing.code, existing.name, existing.quantity,
                existing.note, nextVer, now, true, serverId);
        String sql = "UPDATE items SET version=?, updated_at_ms=?, deleted=1, origin_server=?, pending_sync=1 WHERE id=?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tombstone.version);
            ps.setLong(2, tombstone.updatedAtMs);
            ps.setString(3, serverId);
            ps.setString(4, id);
            ps.executeUpdate();
        }
        return tombstone;
    }

    /** Áp dụng danh sách thay đổi từ Peer Server bằng quy tắc mới hơn thắng (Last-Writer-Wins) và đặt pending_sync=0. */
    public synchronized int applyReplicatedItems(List<DataItem> peerItems) throws SQLException {
        int applied = 0;
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                for (DataItem inc : peerItems) {
                    DataItem local = findById(conn, inc.id);
                    boolean shouldApply = false;
                    if (local == null) {
                        shouldApply = true;
                    } else if (inc.updatedAtMs > local.updatedAtMs) {
                        shouldApply = true;
                    } else if (inc.updatedAtMs == local.updatedAtMs && inc.version > local.version) {
                        shouldApply = true;
                    } else if (inc.updatedAtMs == local.updatedAtMs && inc.version == local.version
                            && inc.originServer != null && local.originServer != null
                            && inc.originServer.compareTo(local.originServer) > 0) {
                        shouldApply = true;
                    }
                    if (!shouldApply) continue;

                    String upsert = "INSERT INTO items(id, code, name, quantity, note, version, updated_at_ms, deleted, origin_server, pending_sync) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0) "
                            + "ON CONFLICT(id) DO UPDATE SET "
                            + "code=excluded.code, name=excluded.name, quantity=excluded.quantity, note=excluded.note, "
                            + "version=excluded.version, updated_at_ms=excluded.updated_at_ms, deleted=excluded.deleted, "
                            + "origin_server=excluded.origin_server, pending_sync=0";
                    try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                        ps.setString(1, inc.id);
                        ps.setString(2, inc.code);
                        ps.setString(3, inc.name);
                        ps.setInt(4, inc.quantity);
                        ps.setString(5, inc.note == null ? "" : inc.note);
                        ps.setLong(6, inc.version);
                        ps.setLong(7, inc.updatedAtMs);
                        ps.setInt(8, inc.deleted ? 1 : 0);
                        ps.setString(9, inc.originServer);
                        ps.executeUpdate();
                    }
                    applied++;
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return applied;
    }

    public synchronized List<DataItem> listPendingItems() throws SQLException {
        return queryItems("SELECT * FROM items WHERE pending_sync=1 ORDER BY updated_at_ms ASC");
    }

    public synchronized int countPendingItems() throws SQLException {
        try (Connection conn = openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM items WHERE pending_sync=1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Chỉ xóa cờ pending_sync nếu version và updated_at_ms khớp đúng phiên bản vừa đẩy, tránh mất thay đổi mới ghi song song. */
    public synchronized void markSyncedItems(List<DataItem> syncedItems) throws SQLException {
        if (syncedItems == null || syncedItems.isEmpty()) return;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE items SET pending_sync=0 WHERE id=? AND version=? AND updated_at_ms=?")) {
            for (DataItem item : syncedItems) {
                ps.setString(1, item.id);
                ps.setLong(2, item.version);
                ps.setLong(3, item.updatedAtMs);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized List<DataItem> listActiveItems() throws SQLException {
        return queryItems("SELECT * FROM items WHERE deleted=0 ORDER BY updated_at_ms DESC");
    }

    public synchronized List<DataItem> listAllRawItems() throws SQLException {
        return queryItems("SELECT * FROM items ORDER BY updated_at_ms ASC");
    }

    public synchronized DataItem findById(String id) throws SQLException {
        try (Connection conn = openConnection()) {
            return findById(conn, id);
        }
    }

    private DataItem findById(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM items WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    private List<DataItem> queryItems(String sql) throws SQLException {
        List<DataItem> out = new ArrayList<>();
        try (Connection conn = openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(mapRow(rs));
            }
        }
        return out;
    }

    private DataItem mapRow(ResultSet rs) throws SQLException {
        return new DataItem(
                rs.getString("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("quantity"),
                rs.getString("note"),
                rs.getLong("version"),
                rs.getLong("updated_at_ms"),
                rs.getInt("deleted") == 1,
                rs.getString("origin_server")
        );
    }

    public synchronized void addSyncLog(String direction, String peerId, String detail) throws SQLException {
        String sql = "INSERT INTO sync_log(direction, peer_id, detail, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, direction);
            ps.setString(2, peerId);
            ps.setString(3, detail);
            ps.setString(4, LocalDateTime.now().format(TS_FMT));
            ps.executeUpdate();
        }
    }

    public synchronized List<SyncLogEntry> listSyncLogs() throws SQLException {
        List<SyncLogEntry> out = new ArrayList<>();
        String sql = "SELECT id, direction, peer_id, detail, created_at FROM sync_log ORDER BY id DESC LIMIT 100";
        try (Connection conn = openConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new SyncLogEntry(
                        rs.getInt("id"),
                        rs.getString("direction"),
                        rs.getString("peer_id"),
                        rs.getString("detail"),
                        rs.getString("created_at")
                ));
            }
        }
        return out;
    }
}

