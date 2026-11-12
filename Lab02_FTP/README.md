# Lab 02 — FTP Server & FTP Client bằng Java (Apache FtpServer, Commons Net, SQLite)

## 1. Giới thiệu & Công nghệ sử dụng
- **Ngôn ngữ / JDK**: Java 17+, Java Swing.
- **Giao thức**: FTP chuẩn RFC 959 (Control Port mặc định `2121` + Passive Data Ports `22200–22220`), **không** phải socket TCP tự chế.
- **Thư viện ngoài**:
  - `org.apache.ftpserver:ftpserver-core` (`1.2.1`): FTP Server thực thụ hỗ trợ lệnh chuẩn (`USER`, `PASS`, `PASV`, `LIST`, `STOR`, `RETR`, `DELE`, `MKD`, `CWD`, `PWD`, `QUIT`).
  - `commons-net:commons-net` (`3.13.0`): `FTPClient` + `CopyStreamAdapter` + `ProtocolCommandListener`.
  - `org.xerial:sqlite-jdbc` (`3.54.1.0`): JDBC kết nối SQLite lưu bảng `users` và `transfer_history`.
  - `org.slf4j:slf4j-simple` (`1.7.36`) & `org.junit.jupiter:junit-jupiter` (`5.11.4`, scope `test`).

## 2. Cấu trúc thư mục
- [pom.xml](pom.xml): Cấu hình Maven cho Lab 02.
- [src/main/java/com/ltm/lab02/FtpDatabase.java](src/main/java/com/ltm/lab02/FtpDatabase.java): Khởi tạo SQLite `ftp_lab02.db`, bảng `users` (băm mật khẩu SHA-256) và `transfer_history`.
- [src/main/java/com/ltm/lab02/FtpServerApp.java](src/main/java/com/ltm/lab02/FtpServerApp.java): FTP Server (`SqliteUserManager` + `Ftplet` ghi lịch sử Upload/Download/Delete) + Giao diện Swing quản trị tài khoản & DB.
- [src/main/java/com/ltm/lab02/FtpClientApp.java](src/main/java/com/ltm/lab02/FtpClientApp.java): FTP Client dùng `FTPClient` (Passive Mode, `BINARY_FILE_TYPE`), thanh tiến trình `%`, bảng file và nhật ký lệnh FTP thực tế.
- [src/test/java/com/ltm/lab02/FtpLab02Test.java](src/test/java/com/ltm/lab02/FtpLab02Test.java): Test tự động đăng nhập, sai mật khẩu, Upload có tiến trình, Download toàn vẹn, Xóa file, phân quyền Read-Only và kiểm tra SQLite.
- [docs/HUONG_DAN_VA_BAO_VE.md](docs/HUONG_DAN_VA_BAO_VE.md): Tài liệu lý thuyết, luồng FTP + 16 câu hỏi bảo vệ.

## 3. Lệnh Build, Test & Chạy chương trình

### Biên dịch & chạy JUnit 5 test:
```powershell
mvn test
```

### Khởi chạy FTP Server:
```powershell
mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpServerApp"
```
1. Nhập **Username**, **Password**, chọn **Cho phép Upload / Xóa file** rồi bấm **Lưu / Tạo tài khoản** ngay trên giao diện Server (không hardcode mật khẩu trong code).
2. Ghi nhận **IP LAN** và **Control Port** (`2121`).

### Khởi chạy FTP Client:
```powershell
mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpClientApp"
```
Nhập `Host`, `Port` (`2121`), `User`, `Pass` đã tạo → **Đăng nhập FTP** → thử **Upload**, **Download**, **Tạo thư mục**, **Xóa File**.

## 4. Kiểm thử bằng FileZilla Client
1. Mở **FileZilla Client** trên máy cùng mạng LAN (hoặc trên chính máy đang chạy Server).
2. Nhập:
   - **Host**: IP LAN hiển thị trên Server (hoặc `127.0.0.1` nếu chạy cùng máy).
   - **Port**: `2121`.
   - **Username / Password**: tài khoản đã tạo trên bảng `users` của `FtpServerApp`.
   - Trong *Edit → Settings → Connection → FTP → Passive mode* hoặc *Site Manager → Transfer Settings*: chọn **Passive**.
3. Kéo thả file lên/xuống trên FileZilla và bấm **Làm mới lịch sử DB** ở `FtpServerApp` để thấy mọi thao tác từ FileZilla cũng được ghi vào bảng `transfer_history` của SQLite.

