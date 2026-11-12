# NGÂN HÀNG KIẾN THỨC & CÂU HỎI BẢO VỆ TỔNG HỢP (CẢ 5 LAB)

Tài liệu này tóm tắt ngắn gọn **20 khái niệm bắt buộc** xuất hiện xuyên suốt 5 bài Lab Lập trình mạng, kèm chỉ mục tới tài liệu câu hỏi mở code chi tiết của từng bài Lab.

---

## 1. Giải thích 20 khái niệm & giao thức cốt lõi (Kèm ví dụ trong Workspace)

| Khái niệm | Giải thích ngắn gọn & chính xác | Áp dụng thực tế trong Lab |
| :--- | :--- | :--- |
| **1. IP (Internet Protocol)** | Địa chỉ logic dùng định danh thiết bị/giao diện mạng trên mạng LAN/Internet (IPv4 như `127.0.0.1` loopback hay `192.168.1.12` LAN). | Các Server tự phát hiện IP LAN bằng `DatagramSocket.connect("8.8.8.8", 80)` để Client máy khác nhập kết nối. |
| **2. Port (Cổng)** | Số nguyên 16-bit (`0–65535`) định danh một **tiến trình/dịch vụ** đang lắng nghe trên cùng một địa chỉ IP. | Lab 01 (`5000/5001`), Lab 02 (`2121`), Lab 03 (`3025/3110`), Lab 04 (`8080`), Lab 05 (`1099/1100`). |
| **3. Socket** | Điểm đầu cuối giao tiếp 2 chiều gắn với cặp `(IP, Port)`, là cầu nối giữa tầng Ứng dụng và tầng Giao vận TCP/UDP. | `ServerSocket` / `Socket` (TCP), `DatagramSocket` / `DatagramChannel` (UDP). |
| **4. TCP** | Giao thức hướng kết nối (*connection-oriented*), bắt tay 3 bước, truyền **tin cậy, đúng thứ tự, không mất gói**. | Chat/File (Lab 01), FTP Control+Data (Lab 02), SMTP/POP3 (Lab 03), HTTP (Lab 04), Java RMI (Lab 05). |
| **5. UDP** | Giao thức phi kết nối (*connectionless*), đóng gói datagram gửi nhanh, trễ thấp, không đảm bảo mọi gói đều tới đích. | Truyền thông báo LAN và chat nhóm Broadcast/Multicast ở Lab 01. |
| **6. Broadcast** | Cơ chế gửi 1 gói UDP tới **mọi máy trong cùng subnet LAN** bằng địa chỉ quảng bá (`255.255.255.255`) và cờ `setBroadcast(true)`. | Nút **Gửi Broadcast** trong `Lab01_Chat/ChatClient.java`. |
| **7. Multicast** | Cơ chế gửi 1 gói UDP tới **nhóm Client đăng ký tham gia địa chỉ lớp D** (`224.0.0.0–239.255.255.255`, Lab dùng `224.1.1.1`) thay vì làm phiền cả mạng. | `DatagramChannel.open(INET)` + `.join(group, ni)` trong `Lab01_Chat/ChatClient.java`. |
| **8. Thread** | Luồng thực thi song song trong cùng JVM giúp Server phục vụ nhiều Client cùng lúc và Client không bị treo giao diện Swing. | `ExecutorService`, `SwingWorker`, `ScheduledExecutorService` xuất hiện trong cả 5 Lab. |
| **9. FTP** | *File Transfer Protocol* (RFC 959) dùng **2 kênh TCP tách biệt**: Control (`21/2121`: `USER`, `PASS`, `LIST`, `PASV`) và Data (`STOR`, `RETR`). | `FtpServerApp` (Apache FtpServer) & `FtpClientApp` (`FTPClient`) ở Lab 02. |
| **10. SMTP** | *Simple Mail Transfer Protocol* (RFC 5321) giao thức đẩy thư đi từ Mail Client lên Mail Server (`EHLO`, `AUTH`, `MAIL FROM`, `RCPT TO`, `DATA`). | `MailClientCore.sendEmail()` (`Transport.send`) & GreenMail SMTP `3025` / Gmail `587` ở Lab 03. |
| **11. POP3** | *Post Office Protocol v3* (RFC 1939) dùng Mail Client kéo email trong hộp thư `INBOX` trên Server về máy lưu trữ (`USER`, `PASS`, `LIST`, `RETR`, `QUIT`). | `MailClientCore.fetchEmailsPop3()` & GreenMail POP3 `3110` / Gmail `995` ở Lab 03. |
| **12. HTTP** | *HyperText Transfer Protocol* theo mô hình Request–Response trên nền TCP, gồm Request Line, Headers và tùy chọn Body; Server trả Status Code (`200`, `206`, `404`). | `com.sun.net.httpserver.HttpServer` & `java.net.http.HttpClient` ở Lab 04. |
| **13. GET** | Phương thức HTTP yêu cầu lấy tài nguyên (HTML, PDF, MP3, MP4) từ URL; nhận cả Headers và Body (hỗ trợ `Range` trả `206`). | Tải `index.html`, `tailieu.pdf`, streaming MP3/MP4 trong Lab 04. |
| **14. HEAD** | Giống hệt `GET` về đường dẫn và Headers (`Content-Type`, `Content-Length`), nhưng **Body trả về rỗng `0 bytes`**. Dùng kiểm tra metadata/tồn tại file. | Server gọi `sendResponseHeaders(200, -1)`, Browser xác nhận `body.length == 0` ở Lab 04. |
| **15. POST** | Phương thức HTTP gửi dữ liệu trong Request Body lên Server để xử lý/lưu trữ. | Nút **POST** gửi JSON tới `/api/submit` và nhận JSON phản hồi ở Lab 04. |
| **16. RMI** | *Remote Method Invocation* — cơ chế RPC của Java cho phép gọi phương thức trên đối tượng thuộc JVM/máy khác gần như gọi hàm cục bộ. | `SyncServerApp` ↔ `RmiClientApp` & `Server A` ↔ `Server B` ở Lab 05. |
| **17. Remote Interface** | Interface khai báo hợp đồng gọi hàm từ xa: bắt buộc kế thừa `java.rmi.Remote` và mọi method phải `throws RemoteException`. | File `Lab05_RMI/src/main/java/com/ltm/lab05/SyncService.java`. |
| **18. RMI Registry** | Dịch vụ danh bạ đặt tên của RMI nơi Server `rebind(tên, object)` và Client `lookup(tên)` để lấy Stub kết nối. | `LocateRegistry.createRegistry(port)` và `LocateRegistry.getRegistry(host, port)` ở Lab 05. |
| **19. JDBC** | *Java Database Connectivity* — bộ API chuẩn của Java (`DriverManager`, `Connection`, `PreparedStatement`, `ResultSet`) làm việc với các hệ quản trị CSDL. | Kết nối `jdbc:sqlite:...` dùng `PreparedStatement` tránh SQL Injection ở Lab 02, 03, 04, 05. |
| **20. Database (SQLite)** | Hệ quản trị CSDL quan hệ nhẹ lưu trọn vẹn trong 1 tệp `.db`, không cần cài dịch vụ ngoài, đảm bảo bền vững dữ liệu sau khi tắt ứng dụng. | `users`/`transfer_history` (Lab 02), `accounts`/`emails` (Lab 03), `history` (Lab 04), `items`/`sync_log` (Lab 05). |

---

## 2. Tài liệu chi tiết & 15+ câu hỏi mở file code trực tiếp của từng Lab

Mỗi Lab có riêng **15–16 câu hỏi bảo vệ chuyên sâu** (bao gồm các câu giảng viên chỉ thẳng vào tên file & phương thức):

1. [Tài liệu Hướng dẫn & 15+ câu hỏi Lab 01 — Chat](Lab01_Chat/docs/HUONG_DAN_VA_BAO_VE.md)
2. [Tài liệu Hướng dẫn & 15+ câu hỏi Lab 02 — FTP](Lab02_FTP/docs/HUONG_DAN_VA_BAO_VE.md)
3. [Tài liệu Hướng dẫn & 15+ câu hỏi Lab 03 — Mail](Lab03_Mail/docs/HUONG_DAN_VA_BAO_VE.md)
4. [Tài liệu Hướng dẫn & 16 câu hỏi Lab 04 — Web/Browser](Lab04_Web/docs/HUONG_DAN_VA_BAO_VE.md)
5. [Tài liệu Hướng dẫn & 16 câu hỏi Lab 05 — Java RMI](Lab05_RMI/docs/HUONG_DAN_VA_BAO_VE.md)

