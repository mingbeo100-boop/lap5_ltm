# Lab 03 — Mail Server & Mail Client bằng Java (Jakarta Mail SMTP/POP3, Localhost/Gmail, SQLite)

## 1. Giới thiệu & Công nghệ sử dụng
- **Ngôn ngữ / JDK**: Java 17+, Java Swing.
- **Giao thức Email thực tế**:
  - **SMTP** (*Simple Mail Transfer Protocol*): gửi thư đi (`Transport.send`).
  - **POP3** (*Post Office Protocol v3*): kết nối hộp thư để nhận thư về (`Store` + `Folder("INBOX")`).
- **Thư viện ngoài**:
  - `com.sun.mail:jakarta.mail` (`2.0.2`): Thư viện Jakarta Mail chuẩn để nói chuyện qua giao thức SMTP/POP3/STARTTLS/SSL.
  - `com.icegreen:greenmail` (`2.1.15`): Cung cấp SMTP Server (`3025`) và POP3 Server (`3110`) thật cho chế độ Localhost/LAN mà không cần cài Postfix/Dovecot.
  - `org.xerial:sqlite-jdbc` (`3.54.1.0`): Lưu trữ danh sách tài khoản (`accounts`) và hộp thư (`emails`) vào SQLite.

## 2. Cấu trúc thư mục
- [pom.xml](pom.xml): Cấu hình Maven cho Lab 03.
- [src/main/java/com/ltm/lab03/MailDatabase.java](src/main/java/com/ltm/lab03/MailDatabase.java): Khởi tạo SQLite bảng `accounts` và `emails` (có khóa `UNIQUE(mailbox_email, direction, message_uid)` tránh lưu trùng thư POP3).
- [src/main/java/com/ltm/lab03/MailServerApp.java](src/main/java/com/ltm/lab03/MailServerApp.java): Localhost/LAN Mail Server (SMTP + POP3) ghi mọi thư nhận được vào SQLite + Giao diện Swing.
- [src/main/java/com/ltm/lab03/MailClientApp.java](src/main/java/com/ltm/lab03/MailClientApp.java): Mail Client dùng Jakarta Mail hỗ trợ 2 chế độ **LOCALHOST** và **GMAIL**, xem Inbox/Sent, soạn thư, nhật ký lệnh SMTP/POP3.
- [mail.properties.example](mail.properties.example): Mẫu file cấu hình tài khoản (không chứa mật khẩu).
- [src/test/java/com/ltm/lab03/MailLab03Test.java](src/test/java/com/ltm/lab03/MailLab03Test.java): Test tự động SMTP gửi -> SQLite -> POP3 nhận -> chống trùng & sai mật khẩu.

## 3. Lệnh Build, Test & Chạy chương trình

### Biên dịch & chạy JUnit 5 test:
```powershell
mvn test
```

### Khởi chạy chế độ Localhost / LAN:
1. Mở Terminal 1 chạy Mail Server:
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab03.MailServerApp"
   ```
   Tạo 2 tài khoản thử nghiệm ngay trên form bên trái (ví dụ `a@ltm.local` và `b@ltm.local`, tự nhập mật khẩu).
2. Mở Terminal 2 & 3 chạy 2 cửa sổ Mail Client:
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab03.MailClientApp"
   ```
   - Cửa sổ 1: Chọn `LOCALHOST`, Email `a@ltm.local`, nhập mật khẩu → Soạn thư gửi cho `b@ltm.local`.
   - Cửa sổ 2: Chọn `LOCALHOST`, Email `b@ltm.local`, nhập mật khẩu → Bấm **Nhận Email qua POP3** để tải thư từ Server về xem và lưu vào SQLite.

## 4. Hướng dẫn cấu hình & giới hạn chế độ Gmail
1. Tài khoản Gmail cá nhân **bắt buộc bật Xác minh 2 bước (2-Step Verification)** tại Google Account → Security.
2. Tạo **App Password (Mật khẩu ứng dụng 16 ký tự)** trong mục *Security → App passwords* (Google không còn cho dùng mật khẩu đăng nhập Gmail thông thường cho SMTP/POP3 bên thứ ba).
3. Để nhận thư từ Gmail qua POP3, vào *Gmail (web) → Settings (Xem tất cả chế độ cài đặt) → Forwarding and POP/IMAP* → Chọn **Enable POP for all mail / mail that arrives from now on** và **Save Changes**.
4. Trên `MailClientApp`, chọn chế độ `GMAIL` (tự điền `smtp.gmail.com:587` + `STARTTLS`, `pop.gmail.com:995` + `POP3 SSL`), nhập Email & App Password (hoặc nạp từ file `mail.properties` copy từ `mail.properties.example`). **Không ghi mật khẩu thật vào mã nguồn.**
5. **Lưu ý giới hạn thực tế**: Tài khoản Google Workspace trường học/công ty có thể bị quản trị viên khóa quyền App Password hoặc khóa POP3; ngoài ra Gmail POP3 mặc định trả theo batch (~250 thư gần nhất) hoặc đánh dấu trạng thái POP tùy cấu hình trên tài khoản.

