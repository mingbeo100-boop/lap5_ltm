# TÀI LIỆU HƯỚNG DẪN & BẢO VỆ — LAB 05: JAVA RMI ĐỒNG BỘ DỮ LIỆU HAI SERVER

---

## 1. Mục đích của Lab 05
Xây dựng hệ thống phân tán gồm **2 Server độc lập (Server A và Server B)** dùng **Java RMI** và **SQLite** riêng biệt nhằm:
- Cho phép Client kết nối bất kỳ Server nào để Thêm, Sửa, Xóa, Xem dữ liệu.
- Tự động đồng bộ hai chiều mọi thay đổi dữ liệu giữa hai Server thông qua lời gọi phương thức từ xa **Java RMI**.
- Đảm bảo **không sinh vòng lặp đồng bộ vô hạn** (`A → B → A → ...`).
- Xử lý khi một Server bị mất kết nối đột ngột và **tự động phát hiện + đồng bộ bù (Catch-up Sync)** các dữ liệu bị thiếu ngay khi Server đó kết nối trở lại.

---

## 2. Giao thức & Thư viện sử dụng

| Thành phần | Công nghệ / Phiên bản | Vai trò |
| :--- | :--- | :--- |
| **Gọi hàm từ xa** | Java RMI (`java.rmi`, JDK 17 built-in) | Triển khai `SyncService extends Remote`, `UnicastRemoteObject` và `Registry`. |
| **Truyền đối tượng** | `java.io.Serializable` (`DataItem`) | Đóng gói dữ liệu bản ghi thành luồng byte truyền qua mạng TCP của RMI. |
| **Giao vận mạng** | JRMP trên nền TCP Socket | RMI tự động quản lý kết nối TCP tới cổng Registry (`1099`/`1100`) và cổng Remote Object. |
| **Lưu trữ cục bộ** | JDBC + SQLite (`org.xerial:sqlite-jdbc:3.54.1.0`) | Hai DB độc lập `server_a.db` và `server_b.db` (bảng `items`, `sync_log`). |

---

## 3. Mô hình hoạt động
1. **Cấu trúc phân tán**: Server A quản lý SQLite `server_a.db` và đăng ký RMI Registry ở cổng `1099`; Server B quản lý SQLite `server_b.db` và đăng ký RMI Registry ở cổng `1100`.
2. **Chiều Client → Server**: Client tìm `SyncService` trên Registry của Server A (hoặc B) và gọi trực tiếp `createItem`, `updateItem`, `deleteItem`, `getAllActiveItems`. Server ghi ngay xuống SQLite cục bộ (`pending_sync = 1`), trả kết quả cho Client và kích hoạt `syncCycleSafe()` đẩy thay đổi sang Peer qua `peer.replicateFromPeer(...)`.
3. **Cơ chế chống vòng lặp vô hạn**:
   - Hàm `replicateFromPeer(List<DataItem>, sourceServerId)` **CHỈ** cập nhật SQLite cục bộ (dựa theo quy tắc *Last-Writer-Wins*: chỉ ghi nếu bản ghi đến có `updatedAtMs` lớn hơn hoặc bằng nhưng `version` cao hơn bản đang lưu) rồi đặt `pending_sync = 0`.
   - Hàm `replicateFromPeer` **TUYỆT ĐỐI KHÔNG** kích hoạt `push` ngược lại Peer. Do đó chuỗi truyền thông dừng dứt khoát sau **1 bước duy nhất** (`A → B`, kết thúc).
4. **Xử lý mất kết nối & Catch-up Sync**:
   - Khi Peer B tắt/mất mạng, `peer.ping()` ném `RemoteException`. Server A chuyển trạng thái `Peer: OFFLINE`, mọi thao tác Client lên A vẫn thành công bình thường và giữ cờ `pending_sync = 1` trong SQLite.
   - Luồng Heartbeat nền (chu kỳ 1.2 giây) liên tục gọi `ping()` thăm dò Peer. Khi Peer B chạy trở lại và `ping()` thành công, A phát hiện chuyển pha `OFFLINE → ONLINE`, lập tức thực hiện **2 chiều Catch-up**:
     1. **Push** toàn bộ các bản ghi còn thiếu / thay đổi khi offline (bao gồm cả bản ghi `deleted = 1` tombstone) sang B bằng `replicateFromPeer`.
     2. **Pull** trạng thái hiện tại của B qua `exportAllItemsForSync()` và áp dụng cục bộ vào A (trong trường hợp trước đó B cũng có dữ liệu mới).

---

## 4. Sơ đồ Client / Server

```text
                        Java RMI Registry (:1099)                  Java RMI Registry (:1100)
+----------------+      createItem / update / delete      +----------------------------+
|   RMI Client   |--------------------------------------->|         SERVER A           |
|  (Swing UI)    |   Hoặc chuyển kết nối sang Server B    | - UnicastRemoteObject      |
+----------------+---+                                    | - SQLite: server_a.db      |
                     |                                    +-------------+--------------+
                     |                                                  |
                     |       Java RMI Sync: replicateFromPeer()         | Heartbeat 1.2s
                     |       + exportAllItemsForSync() (Catch-up)       | (Chống lặp 1 chiều)
                     v                                                  v
               +---------------------------------------------------------------+
               |                         SERVER B                              |
               | - Registry (:1100) + UnicastRemoteObject ("SyncService")      |
               | - SQLite độc lập: server_b.db (items + sync_log)              |
               +---------------------------------------------------------------+
```

---

## 5. Giải thích từng file Java
1. [DataItem.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/main/java/com/ltm/lab05/DataItem.java): Lớp thực thể `Serializable` mang đầy đủ dữ liệu sản phẩm và siêu dữ liệu đồng bộ (`version`, `updatedAtMs`, `deleted`, `originServer`).
2. [SyncService.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/main/java/com/ltm/lab05/SyncService.java): Interface chuẩn `java.rmi.Remote`, mọi phương thức đều khai báo `throws RemoteException`.
3. [SyncDatabase.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/main/java/com/ltm/lab05/SyncDatabase.java): Tầng JDBC/SQLite cho mỗi Server, xử lý `insertLocalItem`, `updateLocalItem`, `markDeletedLocal` (đặt `deleted=1` làm tombstone), và `applyReplicatedItems` dùng `INSERT ... ON CONFLICT(id) DO UPDATE` kèm so sánh `(updated_at_ms, version)`.
4. [SyncServerApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/main/java/com/ltm/lab05/SyncServerApp.java): Triển khai `SyncServerCore extends UnicastRemoteObject implements SyncService`, tạo Registry, chạy Heartbeat `ScheduledExecutorService`, hỗ trợ `--role A/B --nogui` và Swing Dashboard.
5. [RmiClientApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/main/java/com/ltm/lab05/RmiClientApp.java): Giao diện Client cho phép chọn Server A / Server B hoặc nhập IP LAN, thực hiện Thêm/Sửa/Xóa/Làm mới.
6. [RmiLab05Test.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab05_RMI/src/test/java/com/ltm/lab05/RmiLab05Test.java): Chạy **2 tiến trình JVM riêng biệt** qua `ProcessBuilder` để chứng minh đồng bộ thật, chống lặp và phục hồi dữ liệu khi Server B tắt rồi bật lại.

---

## 6. Giải thích class và method quan trọng
- `SyncServerCore.start()`: Tạo RMI Registry bằng `LocateRegistry.createRegistry(myPort)` và ràng buộc đối tượng từ xa `registry.rebind("SyncService", this)`, đồng thời lên lịch `scheduler.scheduleWithFixedDelay(this::syncCycleSafe, 300, 1200, MILLISECONDS)`.
- `SyncServerCore.replicateFromPeer(List<DataItem>, String)`: Nhận danh sách thay đổi từ Peer, gọi `db.applyReplicatedItems(...)`, tuyệt đối không gọi ngược `cachedPeerStub`.
- `SyncServerCore.syncCycleSafe()`: Khối `synchronized(syncLock)` đảm bảo khi Peer mất rồi kết nối lại (`recoveredFromDisconnect == true`), Server đẩy đủ danh sách (`Push`) và kéo phần thiếu (`Pull`) giúp 2 SQLite hội tụ giống hệt nhau.
- `SyncDatabase.markDeletedLocal(...)`: Không dùng `DELETE FROM items` ngay lập tức mà đặt `deleted = 1` (**Tombstone**) và tăng `version` để lệnh xóa truyền sang được Peer đang Offline lúc trước.

---

## 7. Giải thích luồng dữ liệu
1. Client gọi `stubA.createItem("SP01", ...)` qua TCP tới cổng RMI của Server A.
2. `SyncServerCore` của A tạo UUID, chèn vào `server_a.db` với `version=1, pending_sync=1`, và gọi `triggerImmediatePush()`.
3. `syncCycleSafe()` của A gọi `stubB.replicateFromPeer([SP01], "SERVER_A")`. Server B lưu `SP01` vào `server_b.db` với `pending_sync=0` và kết thúc không phản hồi ngược.
4. Nếu B bị tắt trước đó: `stubB.ping()` thất bại → `peerOnline=false`. Các thay đổi tiếp theo ở A tích lũy với `pending_sync=1`. Khi B chạy lại, Heartbeat của A gọi `ping()` thành công → đẩy toàn bộ thay đổi + xóa tombstone sang B, và kéo dữ liệu mới từ B về A.

---

## 8. Hướng dẫn chạy & 9. Hướng dẫn kiểm thử
- Chạy kiểm thử tự động 2 tiến trình JVM: `mvn test`.
- Chạy trình diễn giao diện: bật `SyncServerApp --role A`, `SyncServerApp --role B`, và `RmiClientApp`. Kiểm thử tuần tự:
  1. Thêm ở A → chuyển Client sang B kiểm tra thấy ngay.
  2. Sửa/Xóa ở B → chuyển Client sang A kiểm tra cập nhật đúng.
  3. Tắt cửa sổ Server B → Thêm/Sửa/Xóa ở A → Bật lại Server B → quan sát log `[Catch-up Sync]` và bảng dữ liệu hai bên đồng nhất hoàn toàn.
- Hướng dẫn chạy trên **2 máy tính vật lý LAN** (chưa kiểm thử thực tế trên 2 máy khác nhau trong phiên này): xem mục 4 trong [README.md](../README.md).

---

## 10. Lỗi thường gặp & Cách xử lý

| Lỗi | Nguyên nhân | Cách khắc phục |
| :--- | :--- | :--- |
| `ConnectException: Connection refused to host: 127.0.0.1` khi chạy trên 2 máy LAN | Server chưa cấu hình `-Djava.rmi.server.hostname` là IP LAN thật nên Stub gửi nhầm `127.0.0.1` | Điền đúng IP LAN của máy chứa Server vào ô **RMI Hostname IP** (hoặc truyền `-Djava.rmi.server.hostname=<LAN_IP>`) trước khi Start. |
| `Port already in use` | Hai Server đang mở cùng một cổng `1099` trên cùng 1 máy | Dùng vai trò `SERVER_A` cổng `1099` và `SERVER_B` cổng `1100` (mở khác cổng nếu chung 1 máy). |
| `NotBoundException: SyncService` | Client kết nối trước khi Server bấm *Khởi chạy RMI Server* | Khởi chạy Server trước khi Client bấm *Kết nối*. |
| `SQLITE_BUSY` nếu mở DB bằng tool ngoài cùng lúc | Tool xem SQLite đang khóa transaction | Code đã cài `PRAGMA busy_timeout=5000`; hãy đóng transaction trên công cụ xem DB ngoài khi cần. |

---

## 11. Ngân hàng 16 câu hỏi bảo vệ (Kèm câu hỏi mở file code trực tiếp)

1. **Java RMI là gì? Khác gì với Socket TCP thô ở Lab 01?**
   - *Trả lời*: RMI (*Remote Method Invocation*) là cơ chế **RPC hướng đối tượng** cho phép một JVM gọi trực tiếp phương thức trên đối tượng nằm ở JVM/máy khác gần như gọi hàm cục bộ, không cần tự thiết kế chuỗi gói tin phân tách thủ công như Socket TCP thuần.
2. **Remote Interface là gì và có quy tắc bắt buộc nào?**
   - *Trả lời*: Là interface định nghĩa các hàm có thể gọi từ xa (file `SyncService.java`). Bắt buộc phải `extends java.rmi.Remote` và **mọi phương thức đều phải khai báo `throws RemoteException`** để xử lý sự cố mạng.
3. **RMI Registry là gì? Vai trò của `rebind` và `lookup`?**
   - *Trả lời*: RMI Registry là dịch vụ danh bạ tên đặt ở Server (`LocateRegistry.createRegistry(port)`). Server dùng `rebind("SyncService", stub)` để đăng ký dịch vụ; Client hoặc Peer dùng `getRegistry(host, port).lookup("SyncService")` để xin đối tượng đại diện (**Stub**).
4. **`UnicastRemoteObject` dùng để làm gì?**
   - *Trả lời*: Lớp cơ sở cho đối tượng Server (`SyncServerCore extends UnicastRemoteObject`) giúp tự động *export* đối tượng lắng nghe trên một TCP socket và sinh ra đối tượng **Stub** gửi qua mạng cho Client.
5. **Tại sao `DataItem` bắt buộc phải `implements Serializable`?**
   - *Trả lời*: Vì khi truyền tham số hoặc giá trị trả về là đối tượng `DataItem` giữa các JVM khác nhau qua RMI, Java dùng **Object Serialization** để tuần tự hóa object thành luồng byte qua mạng TCP (truyền theo giá trị / pass-by-value).
6. **Phân biệt Socket, IP, Port và TCP trong ngữ cảnh Java RMI?**
   - *Trả lời*: Dù lập trình viên chỉ gọi hàm Java, bên dưới tầng vận chuyển JRMP của RMI vẫn mở **TCP Socket** gắn với `(IP máy Server, Port Registry 1099/1100 và Port Remote Object)` để đảm bảo các byte tuần tự hóa truyền đi tin cậy.
7. **Thread và ExecutorService dùng để làm gì trong `SyncServerApp`?**
   - *Trả lời*: `ScheduledExecutorService` chạy luồng Heartbeat nền mỗi 1.2 giây để phát hiện Peer mất/có mạng và đồng bộ bù; `ExecutorService` (`SingleThreadExecutor`) đẩy đồng bộ bất đồng bộ để Client thao tác CRUD không bị nghẽn khi mạng chậm; ngoài ra RMI Runtime tự cấp các Worker Thread xử lý nhiều Client gọi đồng thời.
8. **Em thiết kế cơ chế chống vòng lặp đồng bộ vô hạn như thế nào?**
   - *Trả lời*: Tách rạch ròi 2 luồng: Client CRUD (`createItem`/`updateItem`/`deleteItem`) mới đánh dấu `pending_sync=1` và gọi `replicateFromPeer` sang Peer; còn bản thân hàm `replicateFromPeer` trên Peer **chỉ ghi xuống SQLite (`pending_sync=0`) và tuyệt đối không đẩy ngược lại**, cộng thêm điều kiện chỉ áp dụng khi bản ghi mới hơn (`updatedAtMs`/`version` cao hơn).
9. **Tại sao chức năng Xóa phải dùng Tombstone (`deleted = 1`) thay vì xóa hẳn dòng (`DELETE FROM items`)?**
   - *Trả lời*: Nếu Server B đang tắt mạng khi Client xóa `SP02` ở Server A: nếu A xóa bay dòng `SP02` khỏi SQLite ngay, lát sau B bật lại sẽ tưởng A bị thiếu `SP02` rồi **đồng bộ ngược khiến bản ghi ma sống lại**! Lưu cờ `deleted = 1` kèm `version` tăng lên giúp A truyền được lệnh xóa sang B khi Catch-up.
10. **Nếu Server A và Server B cùng sửa một bản ghi khi đang mất kết nối thì giải quyết xung đột ra sao?**
    - *Trả lời*: Dùng chiến lược **Last-Writer-Wins (LWW)** trong `SyncDatabase.applyReplicatedItems`: so sánh dấu thời gian `updatedAtMs` (nếu bằng nhau thì so tiếp `version`), bản ghi nào mới hơn sẽ ghi đè bản ghi cũ để cả 2 SQLite hội tụ về cùng 1 kết quả nhất quán.
11. **JDBC và SQLite Database đóng vai trò gì trong Lab 05?**
    - *Trả lời*: Mỗi Server lưu trạng thái bền vững xuống file SQLite riêng (`server_a.db` / `server_b.db`) qua JDBC, nên dù tắt hẳn Server rồi khởi động lại, dữ liệu và các bản ghi chờ đồng bộ (`pending_sync=1`) không bị mất.
12. **Vì sao khi mang Lab 05 chạy trên 2 máy tính LAN khác nhau lại phải cấu hình `-Djava.rmi.server.hostname`?**
    - *Trả lời*: Vì Stub do RMI Registry trả cho máy ngoài chứa sẵn IP do JVM Server khai báo. Nếu Server khai báo `127.0.0.1`, máy kia nhận Stub xong sẽ thử gọi lại `127.0.0.1` của chính nó và lỗi `Connection refused`. Phải đặt thuộc tính này bằng IP LAN thật của máy Server.
13. *(Câu hỏi mở file code trực tiếp)* **Mở `SyncServerApp.java`: chỉ ra chỗ đăng ký RMI Service và chỗ chặn vòng lặp vô hạn?**
    - *Trả lời*: Trong `start()`, lệnh `LocateRegistry.createRegistry(myPort)` và `registry.rebind(SERVICE_NAME, this)` mở Registry. Còn trong phương thức `replicateFromPeer(...)`, code chỉ gọi `db.applyReplicatedItems(peerItems)` và không hề gọi `triggerImmediatePush()` hay `cachedPeerStub`.
14. *(Câu hỏi mở file code trực tiếp)* **Mở `SyncServerApp.java`: chỉ ra đoạn code xử lý Catch-up Sync khi Peer vừa kết nối trở lại?**
    - *Trả lời*: Trong `syncCycleSafe()`, biến `recoveredFromDisconnect = !peerOnline && wasPeerOfflineOnce.get()`. Khi `peer.ping()` thành công trở lại, Server vừa đẩy `toPush = db.listAllRawItems()` sang `cachedPeerStub.replicateFromPeer(...)`, vừa gọi `cachedPeerStub.exportAllItemsForSync()` kéo những phần còn thiếu từ Peer về áp dụng xuống DB cục bộ.
15. *(Câu hỏi mở file code trực tiếp)* **Mở `RmiClientApp.java`: Client có kết nối trực tiếp vào file SQLite của Server không?**
    - *Trả lời*: **Không.** `RmiClientApp.java` không chứa bất kỳ lệnh SQL/JDBC nào; toàn bộ thao tác đọc ghi đều đi qua `LocateRegistry.getRegistry(host, port).lookup("SyncService")` và gọi `SyncService` qua mạng.
16. **So sánh nhanh khi nào nên dùng Java Socket thuần (Lab 01) và khi nào nên dùng Java RMI (Lab 05)?**
    - *Trả lời*: Socket TCP/UDP thuần thích hợp khi truyền luồng nhị phân thô tốc độ cao (Audio/Video, file lớn, giao tiếp đa ngôn ngữ); còn Java RMI phù hợp khi hệ thống đều bằng Java cần gọi dịch vụ nghiệp vụ và đồng bộ đối tượng giữa các Server nhanh, gọn, ít mã phân tích gói tin.

