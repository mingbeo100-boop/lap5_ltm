# Lab 05 — Đồng bộ dữ liệu 2 Server bằng Java RMI & SQLite

## 1. Giới thiệu & Công nghệ sử dụng
- **Ngôn ngữ / JDK**: Java 17+, Java Swing.
- **Java RMI Chuẩn** (có sẵn trong `java.rmi` của JDK, không cần thư viện ngoài cho mạng):
  - `Remote Interface`: [SyncService.java](src/main/java/com/ltm/lab05/SyncService.java) kế thừa `java.rmi.Remote`.
  - `Remote Object`: `SyncServerCore` kế thừa `UnicastRemoteObject`, export và đăng ký lên `Registry` với tên `"SyncService"`.
  - `RMI Registry`: Khởi tạo bằng `LocateRegistry.createRegistry(port)` (mặc định Server A = `1099`, Server B = `1100`) và tra cứu bằng `LocateRegistry.getRegistry(host, port).lookup("SyncService")`.
  - `Java Serialization`: Lớp [DataItem.java](src/main/java/com/ltm/lab05/DataItem.java) cài đặt `java.io.Serializable` truyền trực tiếp qua RMI giữa Client ↔ Server A ↔ Server B.
- **Database**: JDBC + SQLite (`org.xerial:sqlite-jdbc:3.54.1.0`) — mỗi Server dùng 1 file SQLite độc lập (`data/server_a.db` và `data/server_b.db`) gồm bảng `items` và `sync_log`.

## 2. Cấu trúc thư mục
- [pom.xml](pom.xml): Cấu hình Maven cho Lab 05.
- [src/main/java/com/ltm/lab05/DataItem.java](src/main/java/com/ltm/lab05/DataItem.java): DTO `Serializable` chứa dữ liệu (`id`, `code`, `name`, `quantity`, `note`, `version`, `updatedAtMs`, `deleted`, `originServer`).
- [src/main/java/com/ltm/lab05/SyncService.java](src/main/java/com/ltm/lab05/SyncService.java): `Remote Interface` định nghĩa CRUD cho Client (`createItem`, `updateItem`, `deleteItem`, `getAllActiveItems`) và RPC đồng bộ giữa 2 Server (`ping`, `replicateFromPeer`, `exportAllItemsForSync`).
- [src/main/java/com/ltm/lab05/SyncDatabase.java](src/main/java/com/ltm/lab05/SyncDatabase.java): Lớp JDBC/SQLite cục bộ, áp dụng Last-Writer-Wins (`updated_at_ms` + `version`), tombstone xóa mềm và hàng đợi `pending_sync`.
- [src/main/java/com/ltm/lab05/SyncServerApp.java](src/main/java/com/ltm/lab05/SyncServerApp.java): RMI Server hỗ trợ chế độ Swing UI lẫn `--nogui` CLI, có cơ chế chống vòng lặp và Heartbeat 1.2 giây phát hiện Peer mất/có mạng để Catch-up Sync tự động.
- [src/main/java/com/ltm/lab05/RmiClientApp.java](src/main/java/com/ltm/lab05/RmiClientApp.java): Giao diện Client kết nối tới Server A hoặc Server B, thêm/sửa/xóa và đối chiếu kết quả.
- [src/test/java/com/ltm/lab05/RmiLab05Test.java](src/test/java/com/ltm/lab05/RmiLab05Test.java): Test tự động bật **2 tiến trình JVM tách biệt** (Server A & Server B) để kiểm chứng đồng bộ hai chiều, chống lặp vô hạn và Catch-up Sync sau khi kill + bật lại tiến trình Server B.

## 3. Lệnh Build, Test & Chạy chương trình

### Biên dịch & chạy kiểm thử 2 tiến trình JVM độc lập:
```powershell
mvn test
```

### Chạy trình diễn trên cùng 1 máy (3 cửa sổ):
1. **Terminal 1 — Server A** (`port 1099`, Peer `127.0.0.1:1100`):
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.SyncServerApp" "-Dexec.args=--role A"
   ```
2. **Terminal 2 — Server B** (`port 1100`, Peer `127.0.0.1:1099`):
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.SyncServerApp" "-Dexec.args=--role B"
   ```
3. **Terminal 3 — RMI Client**:
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.RmiClientApp"
   ```
   - Kết nối `Server A (127.0.0.1:1099)` → Thêm / Sửa / Xóa bản ghi → chuyển dropdown sang `Server B (127.0.0.1:1100)` → dữ liệu đã đồng bộ khớp nhau.
   - Đóng `Server B` → Thêm/Sửa dữ liệu tại `Server A` → Bật lại `Server B` → sau ~1.2 giây Heartbeat sẽ tự đồng bộ bù tất cả thay đổi còn thiếu sang B!

## 4. Triển khai thực tế trên 2 máy tính khác nhau trong mạng LAN
> **Lưu ý trung thực phạm vi kiểm thử**: Hệ thống mới được kiểm thử thực tế với **2 tiến trình JVM độc lập qua `127.0.0.1`** trên cùng máy Windows. Kịch bản **2 máy tính vật lý / 2 VM khác nhau trong LAN (`NOT TESTED` trong phiên này)** cần thực hiện đúng các bước sau:

1. Giả sử Máy 1 có IP LAN `192.168.1.10` (chạy Server A), Máy 2 có IP LAN `192.168.1.20` (chạy Server B).
2. Khi khởi động Server A trên Máy 1, truyền `-Djava.rmi.server.hostname=192.168.1.10` (hoặc điền `192.168.1.10` vào ô **RMI Hostname IP** trên giao diện `SyncServerApp`), đặt **Peer IP** = `192.168.1.20`.
3. Khi khởi động Server B trên Máy 2, truyền `-Djava.rmi.server.hostname=192.168.1.20`, đặt **Peer IP** = `192.168.1.10`.
   *(Vì Java RMI nhúng IP vào Remote Stub trả về cho máy gọi; nếu không đặt `java.rmi.server.hostname` thành IP LAN thật, máy đối diện có thể nhận nhầm `127.0.0.1` và kết nối nhầm vào chính nó).*
4. Mở Windows Firewall cho `java.exe` (hoặc mở TCP port Registry `1099`/`1100` và cổng động RMI giữa 2 máy).
5. Client từ máy thứ 3 chọn *Tùy chỉnh Host/Port* nhập `192.168.1.10:1099` hoặc `192.168.1.20:1100` để kiểm tra CRUD & đồng bộ liên máy LAN.

