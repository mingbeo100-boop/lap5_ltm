# TÀI LIỆU HƯỚNG DẪN & BẢO VỆ — LAB 02: FTP SERVER & FTP CLIENT

---

## 1. Mục đích của Lab 02
Xây dựng cặp ứng dụng **FTP Server** và **FTP Client** hoạt động theo **giao thức FTP chuẩn RFC 959** kết hợp **JDBC + SQLite**:
- Giúp sinh viên hiểu bản chất 2 kết nối tách biệt của FTP (**Control Connection** & **Data Connection**).
- Quản lý xác thực người dùng, phân quyền thư mục/quyền ghi (`Read/Write` vs `Read-Only`) bằng CSDL SQLite (`users`).
- Ghi nhận lịch sử truyền tải (`transfer_history`) bất kể Client là phần mềm Java tự viết hay phần mềm chuẩn ngoài thị trường như **FileZilla**.

---

## 2. Giao thức sử dụng & Thư viện ngoài

| Thành phần | Công nghệ / Phiên bản | Vai trò |
| :--- | :--- | :--- |
| **Giao thức truyền file** | FTP chuẩn RFC 959 (Passive Mode + Binary `TYPE I`) | Cổng điều khiển `2121` (mặc định Lab) & dải cổng dữ liệu `22200–22220`. |
| **FTP Server Engine** | `org.apache.ftpserver:ftpserver-core:1.2.1` | Cài đặt chuẩn toàn bộ máy trạng thái FTP (`USER`, `PASS`, `PASV`, `LIST`, `STOR`, `RETR`, `DELE`, `MKD`, `CWD`, `PWD`), quản lý Passive ports. |
| **FTP Client Library** | `commons-net:commons-net:3.13.0` (`FTPClient`) | Gửi lệnh FTP chuẩn, đếm byte truyền qua `CopyStreamAdapter`, lắng nghe log lệnh qua `ProtocolCommandListener`. |
| **Database & Driver** | SQLite + `org.xerial:sqlite-jdbc:3.54.1.0` | Lưu trữ cục bộ không cần cài DBMS nặng, dùng JDBC (`PreparedStatement`, `ResultSet`). |

---

## 3. Mô hình hoạt động
1. **Control Connection (Kết nối điều khiển — TCP cổng `2121`)**:
   - Duy trì suốt phiên làm việc giữa Client và Server để trao đổi lệnh (`USER`, `PASS`, `PASV`, `STOR`, `RETR`, `DELE`) và mã phản hồi (`220`, `331`, `230`, `227`, `150`, `226`, `530`, `550`).
2. **Data Connection (Kết nối dữ liệu — Passive Mode `22200–22220`)**:
   - Khi cần liệt kê thư mục (`LIST`), tải lên (`STOR`) hoặc tải về (`RETR`), Client gửi lệnh `PASV`.
   - Server mở ngẫu nhiên 1 port trong dải `22200–22220` và trả mã `227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)`.
   - Client tính port dữ liệu `p1 * 256 + p2`, mở kết nối TCP thứ 2 tới port đó để truyền stream byte thật của tập tin.

---

## 4. Sơ đồ Client / Server

```text
+-------------------------------+                        +-----------------------------------+
|         FtpClientApp          |   TCP :2121 (Control)  |           FtpServerApp            |
|  (hoặc FileZilla Client)      |<======================>|  Apache FtpServer Core            |
|                               |  USER/PASS/PASV/STOR   |   ├── Listener (:2121, PASV)      |
|  org.apache.commons.net       |                        |   ├── SqliteUserManager -> users  |
|   .ftp.FTPClient              |   TCP :22200-22220     |   └── Audit Ftplet ->             |
|  CopyStreamAdapter (% bar)    |<---------------------->|       transfer_history (SQLite)   |
+-------------------------------+     (Data Stream)      +-----------------------------------+
```

---

## 5. Giải thích từng file Java
1. [FtpDatabase.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab02_FTP/src/main/java/com/ltm/lab02/FtpDatabase.java):
   - Kết nối SQLite qua `DriverManager.getConnection("jdbc:sqlite:...")`.
   - Khởi tạo 2 bảng bắt buộc `users` (username, password_hash SHA-256, home_dir, can_write, enabled, created_at) và `transfer_history` (username, action, filename, file_size, remote_ip, status, created_at).
2. [FtpServerApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab02_FTP/src/main/java/com/ltm/lab02/FtpServerApp.java):
   - `SqliteUserManager`: Cài đặt `org.apache.ftpserver.ftplet.UserManager`, nối cơ chế đăng nhập của Apache FtpServer trực tiếp với bảng `users` trong SQLite và cấp quyền `WritePermission` theo cột `can_write`.
   - `FtpServerCore`: Tạo `FtpServerFactory`, cấu hình Passive ports, gắn `DefaultFtplet` lắng nghe `beforeCommand`/`afterCommand` để ghi mọi sự kiện `UPLOAD`, `DOWNLOAD`, `DELETE` (cả thành công lẫn bị từ chối) vào `transfer_history`.
   - `launchGui()`: Giao diện Swing tạo tài khoản trực tiếp trên UI, xem bảng `users`, xem `transfer_history` và log lệnh.
3. [FtpClientApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab02_FTP/src/main/java/com/ltm/lab02/FtpClientApp.java):
   - `FtpClientCore`: Đóng gói `FTPClient`, bật `enterLocalPassiveMode()`, `setFileType(FTP.BINARY_FILE_TYPE)`, cập nhật tiến trình % qua `CopyStreamAdapter`.
   - `launchGui()`: Cửa sổ Swing nhập Host/Port/User/Pass, bảng file/thư mục, nút Upload/Download kèm `JProgressBar`, Xóa file, Tạo thư mục và cửa sổ log lệnh RFC 959.
4. [FtpLab02Test.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab02_FTP/src/test/java/com/ltm/lab02/FtpLab02Test.java):
   - Kiểm thử tự động login sai mật khẩu, Upload file 137KB kiểm tra progress 100%, Download kiểm tra toàn vẹn từng byte, Delete file, kiểm tra user chỉ-đọc (`Read-Only`) bị chặn khi Upload/Delete (`DENIED 550/450`), và kiểm tra bản ghi SQLite.

---

## 6. Giải thích class và method quan trọng
- `FtpDatabase.hashPassword(String)`: Dùng `MessageDigest.getInstance("SHA-256")` băm mật khẩu sang chuỗi hex 64 ký tự trước khi ghi vào bảng `users`.
- `SqliteUserManager.authenticate(Authentication)`: Nhận `UsernamePasswordAuthentication`, kiểm tra mật khẩu băm trong SQLite qua `db.authenticate()`, nếu hợp lệ thì trả về `BaseUser` kèm quyền `WritePermission` (nếu `canWrite = true`).
- `FtpServerCore.start()`: Tạo `ListenerFactory` bind cổng điều khiển (`2121`), `DataConnectionConfigurationFactory` giới hạn dải passive port (`22200-22220`), gắn `audit` Ftplet lắng nghe `afterCommand` kiểm tra `reply.isPositive()` trước khi kết luận `SUCCESS` hay `DENIED`.
- `FtpClientCore.uploadFile(...)` & `downloadFile(...)`: Đính kèm `CopyStreamAdapter` vào `client.setCopyStreamListener(...)` để tính `(totalBytesTransferred * 100) / totalSize` đẩy lên `SwingWorker.publish()` cập nhật `JProgressBar` theo thời gian thực mà không làm đơ giao diện.

---

## 7. Giải thích luồng dữ liệu (Upload 1 file từ Client lên Server)
1. Client mở `SwingWorker`, gọi `core.uploadFile(localFile, remoteName, progressCb)`.
2. `FTPClient` gửi lệnh `PASV` qua cổng điều khiển `2121`; Server mở 1 cổng trong `22200–22220` và trả lời `227 Entering Passive Mode (...)`.
3. `FTPClient` gửi `STOR <remoteName>` trên cổng `2121` và đồng thời mở TCP Data Socket mới nối tới Passive Port vừa nhận để đẩy luồng byte nhị phân (`BINARY_FILE_TYPE`).
4. Khi truyền xong, `CopyStreamAdapter` báo `100%`, Server trả `226 Transfer complete` trên cổng `2121`.
5. `audit` Ftplet tại Server bắt `afterCommand` (`STOR`, `reply.isPositive() == true`), gọi `db.recordTransfer(user, "UPLOAD", filename, size, ip, "SUCCESS")` ghi dòng mới vào bảng `transfer_history` trong `ftp_lab02.db`.

---

## 8. Hướng dẫn chạy
1. Từ thư mục `Lab02_FTP`, chạy Server: `mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpServerApp"`.
2. Trên giao diện Server vừa mở, tạo 1 tài khoản thử (ví dụ `sv01`, nhập mật khẩu, tick *Cho phép Upload / Xóa file*) → bấm **Lưu / Tạo tài khoản**.
3. Mở terminal thứ 2 chạy Client: `mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpClientApp"`, nhập Host `127.0.0.1`, Port `2121`, User/Pass vừa tạo và bấm **Đăng nhập FTP**.

---

## 9. Hướng dẫn kiểm thử
1. Chạy `mvn test` kiểm tra cả 2 ca kiểm thử (`testRealFtpUploadDownloadDeleteAndDatabaseAudit` và `testReadOnlyUserCannotUploadOrDelete`).
2. Thử với FileZilla: Kết nối tới `127.0.0.1` (hoặc IP LAN Server), cổng `2121`, Transfer mode **Passive**, upload/download file và kiểm tra bảng `transfer_history` trên giao diện `FtpServerApp` tự động cập nhật.
3. Thử tạo user không tick quyền ghi → đăng nhập trên Client hoặc FileZilla → Download thành công nhưng Upload/Delete báo lỗi `550 Permission denied` / `450 No permission` và DB lưu trạng thái `DENIED`.

---

## 10. Lỗi thường gặp & Cách xử lý

| Lỗi | Nguyên nhân | Cách khắc phục |
| :--- | :--- | :--- |
| `Permission denied` khi khởi chạy cổng `21` | Trên Linux/macOS hoặc Windows chính sách chặt, cổng `< 1024` cần quyền root/admin | Code chọn mặc định port `2121` (không cần admin); muốn dùng `21` chạy quyền Administrator và mở port `21`. |
| Đăng nhập được nhưng `LIST` / `Upload` bị treo trên mạng LAN | Client đang bật Active Mode (Server phải kết nối ngược lại máy Client nên bị Windows Firewall chặn) | Bật **Passive Mode** (`enterLocalPassiveMode()` đã mặc định trong code; trên FileZilla chọn Passive) và mở dải port `22200–22220` trên máy Server. |
| File ảnh/PDF/EXE tải về bị hỏng | Truyền ở chế độ ASCII khiến ký tự `\n` bị đổi thành `\r\n` | Code đã luôn gọi `ftp.setFileType(FTP.BINARY_FILE_TYPE)` ngay sau khi login. |
| `[SQLITE_BUSY]` hoặc dữ liệu chưa thấy ở tool ngoài | File DB chưa đóng/đang giữ write transaction | Code dùng `synchronized` cho các hàm ghi DB và mở/đóng `Connection` gọn theo từng truy vấn. |

---

## 11. Ngân hàng 15 câu hỏi bảo vệ (Kèm câu hỏi mở file code trực tiếp)

1. **FTP là gì? Khác gì so với việc tự mở 1 `ServerSocket` đọc/ghi byte rồi gọi là truyền file?**
   - *Trả lời*: FTP (File Transfer Protocol, RFC 959) là giao thức tầng ứng dụng chuẩn hóa có tập lệnh quy chuẩn (`USER`, `PASS`, `PASV`, `LIST`, `STOR`, `RETR`, `DELE`) và mã trạng thái (`220`, `230`, `150`, `226`, `550`), nhờ đó mọi FTP Client chuẩn trên thế giới (như FileZilla, WinSCP, `FTPClient`) đều kết nối được với Server của mình.
2. **Tại sao FTP cần tới 2 kết nối TCP (Control và Data)?**
   - *Trả lời*: Kênh **Control** (`2121`) luôn mở để gửi lệnh và nhận mã trả lời ngay cả lúc đang truyền file; kênh **Data** (`22200–22220`) chỉ mở tạm thời để truyền danh sách thư mục hoặc luồng byte tập tin rồi đóng ngay để đánh dấu kết thúc file (`EOF` / mã `226`).
3. **Phân biệt Active Mode và Passive Mode (`PASV`) trong FTP?**
   - *Trả lời*: Ở Active Mode, Client mở port lắng nghe rồi gửi `PORT` để Server kết nối ngược lại Client (thường bị Firewall/NAT máy Client chặn). Ở **Passive Mode**, Server mở port trong `22200–22220` và báo Client chủ động kết nối tới, nên Client sau NAT/Firewall đều dùng ổn định.
4. **ASCII Mode và Binary Mode (`TYPE I`) trong FTP khác nhau thế nào?**
   - *Trả lời*: ASCII Mode tự động chuyển đổi ký tự xuống dòng (`CRLF`/`LF`) giữa Windows và Linux nên làm hỏng file nhị phân (PDF, PNG, ZIP). Binary Mode (`FTP.BINARY_FILE_TYPE`) giữ nguyên 100% từng byte dữ liệu.
5. **Apache FtpServer đóng vai trò gì trong bài này?**
   - *Trả lời*: Cung cấp nhân máy chủ FTP chuẩn RFC 959 xây trên Apache MINA NIO (xử lý socket bất đồng bộ, parser lệnh FTP, cơ chế PASV/PORT, phiên làm việc `FtpSession`), giúp ta tập trung tích hợp nghiệp vụ qua `UserManager` và `Ftplet` gắn SQLite.
6. **JDBC là gì? Các bước cơ bản truy vấn DB bằng JDBC?**
   - *Trả lời*: JDBC (Java Database Connectivity) là API chuẩn của Java để làm việc với cơ sở dữ liệu quan hệ. Các bước: lấy `Connection` qua `DriverManager.getConnection()`, tạo `PreparedStatement`, truyền tham số chống SQL Injection, gọi `executeQuery()`/`executeUpdate()` và đóng tài nguyên bằng `try-with-resources`.
7. **Database dùng trong Lab 02 là gì? Tại sao chọn SQLite?**
   - *Trả lời*: SQLite — CSDL nhúng lưu gọn trong 1 file (`ftp_lab02.db`), không cần cài đặt server DB riêng, hỗ trợ ACID và SQL chuẩn nên rất thuận tiện cho đồ án di động nhiều máy.
8. **Ý nghĩa các mã phản hồi FTP: `220`, `230`, `227`, `150`, `226`, `530`, `550`?**
   - *Trả lời*: `220` Server sẵn sàng; `230` Đăng nhập thành công; `227` Vào Passive Mode kèm IP/Port dữ liệu; `150` Chuẩn bị mở kênh dữ liệu; `226` Truyền dữ liệu xong; `530` Chưa đăng nhập / sai mật khẩu; `550` File không tồn tại hoặc không có quyền thao tác.
9. *(Giảng viên mở `FtpDatabase.java` hàm `upsertUser` và `hashPassword`)* **Tại sao không lưu trực tiếp mật khẩu vào cột `password`, và tại sao dùng `PreparedStatement` thay vì cộng chuỗi SQL?**
   - *Trả lời*: Mật khẩu được băm bằng `SHA-256` để tránh lộ plaintext nếu lộ file `.db`; dùng `PreparedStatement` với dấu `?` giúp tránh hoàn toàn lỗi SQL Injection khi username có ký tự đặc biệt.
10. *(Giảng viên mở `FtpServerApp.java` class `SqliteUserManager`)* **Làm sao Apache FtpServer chặn được user `viewer` không cho Upload/Xóa file?**
    - *Trả lời*: Khi tạo `BaseUser` trong `toFtpUser()`, nếu cột `can_write == 1` trong SQLite mới thêm `new WritePermission()` vào `user.setAuthorities(auths)`. Khi nhận lệnh `STOR` hoặc `DELE`, FtpServer gọi `user.authorize(new WriteRequest())`, thấy thiếu quyền nên trả mã `550` / `450`.
11. *(Giảng viên mở `FtpServerApp.java` phần `audit` Ftplet)* **Tại sao lại ghi bảng `transfer_history` trong `afterCommand` thay vì `onUploadEnd`?**
    - *Trả lời*: Thực nghiệm với `ftpserver-core` cho thấy một số callback như `onUploadEnd`/`onDeleteEnd` vẫn chạy ngay cả khi user bị từ chối quyền ghi (`550`). Kiểm tra `reply.isPositive()` trong `afterCommand` giúp phân biệt chính xác giao dịch `SUCCESS` hay `DENIED (550/450)`.
12. *(Giảng viên mở `FtpClientApp.java` dòng `enterLocalPassiveMode()` & `setFileType`)* **Nếu xóa 2 dòng này ở Client thì hiện tượng gì xảy ra?**
    - *Trả lời*: Bỏ `enterLocalPassiveMode()` khiến Client mặc định Active Mode dễ treo ở firewall LAN; bỏ `BINARY_FILE_TYPE` khiến file nhị phân (PDF/MP3/ZIP) bị sai byte xuống dòng.
13. *(Giảng viên mở `FtpClientApp.java` phần `uploadFile`)* **Thanh phần trăm `JProgressBar` cập nhật bằng cách nào khi file đang upload?**
    - *Trả lời*: Gắn `CopyStreamAdapter` vào `client.setCopyStreamListener(...)`. Mỗi khối byte ghi xong ra socket, Commons Net gọi `bytesTransferred(total, ...)`, từ đó `SwingWorker.publish(pct)` đẩy phần trăm về EDT vẽ lại thanh tiến trình mượt mà.
14. *(Giảng viên mở `FtpClientApp.java` phần `ProtocolCommandListener`)* **Tại sao khi ghi log lệnh phải kiểm tra `"PASS".equalsIgnoreCase(event.getCommand())`?**
    - *Trả lời*: Để che mật khẩu thành `PASS ********` khi hiển thị lên khung log giao diện, đảm bảo nguyên tắc bảo mật không lộ thông tin nhạy cảm trên màn hình demo.
15. *(Giảng viên mở `FtpClientApp.java` hàm `launchGui`)* **Tại sao Upload/Download không gọi thẳng trong `ActionListener` của nút bấm mà phải bọc bằng `SwingWorker`?**
    - *Trả lời*: Nếu truyền file lớn trực tiếp trên ActionListener (luồng EDT), giao diện Swing sẽ đứng cứng ("Not Responding") không vẽ được thanh tiến trình hay nút bấm cho tới khi file tải xong.

