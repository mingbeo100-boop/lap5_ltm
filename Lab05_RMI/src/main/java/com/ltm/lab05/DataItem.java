package com.ltm.lab05;

import java.io.Serializable;

/**
 * Lab 05 — Đối tượng truyền qua Java RMI ({@link Serializable}).
 * Chứa đủ metadata phục vụ đồng bộ không trùng lặp và giải quyết xung đột (version + updatedAtMs + deleted tombstone).
 */
public class DataItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id;
    public String code;
    public String name;
    public int quantity;
    public String note;
    public long version;
    public long updatedAtMs;
    public boolean deleted;
    public String originServer;

    public DataItem() {
    }

    public DataItem(String id, String code, String name, int quantity, String note,
                    long version, long updatedAtMs, boolean deleted, String originServer) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.quantity = quantity;
        this.note = note;
        this.version = version;
        this.updatedAtMs = updatedAtMs;
        this.deleted = deleted;
        this.originServer = originServer;
    }

    @Override
    public String toString() {
        return "DataItem{id=" + id + ", code=" + code + ", name=" + name
                + ", qty=" + quantity + ", v=" + version + ", del=" + deleted + ", origin=" + originServer + "}";
    }
}

