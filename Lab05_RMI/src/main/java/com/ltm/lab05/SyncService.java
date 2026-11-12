package com.ltm.lab05;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Lab 05 — Remote Interface chuẩn Java RMI.
 * Được dùng chung cho cả Client gọi thao tác CRUD lẫn Server A <-> Server B gọi đồng bộ dữ liệu.
 */
public interface SyncService extends Remote {

    /** Kiểm tra Server còn hoạt động và trả về Server ID (vd: "SERVER_A"). */
    String ping() throws RemoteException;

    /** Lấy danh sách các bản ghi đang hoạt động (chưa xóa mềm) cho Client hiển thị. */
    List<DataItem> getAllActiveItems() throws RemoteException;

    /** Client thêm bản ghi mới vào Server này, sau đó Server chủ động đẩy đồng bộ sang Peer Server. */
    DataItem createItem(String code, String name, int quantity, String note) throws RemoteException;

    /** Client cập nhật bản ghi trên Server này và đồng bộ sang Peer Server. */
    DataItem updateItem(String id, String code, String name, int quantity, String note) throws RemoteException;

    /** Client xóa bản ghi trên Server này (đánh dấu tombstone deleted=true để đồng bộ xóa sang Peer). */
    void deleteItem(String id) throws RemoteException;

    /**
     * Phương thức nội bộ giữa 2 Server: nhận danh sách thay đổi từ Peer Server để cập nhật vào SQLite cục bộ.
     * QUAN TRỌNG: Hàm này CHỈ ghi xuống SQLite và KHÔNG gọi ngược lại Peer -> Triệt tiêu vòng lặp đồng bộ vô hạn.
     * @return số lượng bản ghi thực sự được cập nhật/thêm mới vào DB cục bộ.
     */
    int replicateFromPeer(List<DataItem> peerItems, String sourceServerId) throws RemoteException;

    /** Xuất toàn bộ bản ghi (kể cả tombstone đã xóa) để Peer đồng bộ bù (catch-up) khi kết nối trở lại. */
    List<DataItem> exportAllItemsForSync() throws RemoteException;
}

