# HƯỚNG DẪN BUILD, CHẠY & KIỂM THỬ CẢ 5 LAB

> Chạy mỗi Lab bằng file `.bat` mở nhanh trong từng thư mục Lab, qua menu **Run & Debug (`Ctrl+Shift+D`)** trong VS Code, hoặc dùng Maven tại thư mục riêng của từng Lab (`Lab01_Chat` đến `Lab05_RMI`). Nếu VS Code báo thiếu lớp Java, mở Command Palette (`Ctrl+Shift+P`) -> chạy **`Java: Clean Java Language Server Workspace`** -> Reload.
> Nếu `mvn` chưa nằm trong biến `PATH`, dùng đường dẫn đầy đủ (máy hiện tại: `C:\opt\apache-maven-3.9.9\bin\mvn.cmd`).

---

## 1. Kiểm thử tự động toàn bộ (JUnit 5 — Khuyến nghị chạy trước khi trình diễn)

```powershell
# Chạy lần lượt tại từng thư mục Lab:
mvn -f Lab01_Chat\pom.xml test
mvn -f Lab02_FTP\pom.xml test
mvn -f Lab03_Mail\pom.xml test
mvn -f Lab04_Web\pom.xml test
mvn -f Lab05_RMI\pom.xml test
```

---

## 2. Hướng dẫn chạy giao diện từng Lab

### Lab 01 — Chat Client/Server (`Lab01_Chat/`)
1. **Terminal 1 — Chạy Chat Server** (TCP `5000`, UDP `5001`):
   ```powershell
   cd Lab01_Chat
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatServer"
   ```
2. **Terminal 2..7 — Chạy nhiều Client (5–6 Client)**:
   ```powershell
   cd Lab01_Chat
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatClient"
   ```
   - Nhập tên người dùng (An, Bình, Cường...), nhấn **Kết nối**, thử Chat chung (Group), Chat 1-1, Emoji, **Gửi Broadcast**, **Gửi Multicast**, **Đính kèm File**, **Gọi Thoại** và **Gọi Video Webcam**.

---

### Lab 02 — FTP Server & FTP Client (`Lab02_FTP/`)
1. **Terminal 1 — Chạy FTP Server** (Control port `2121`, Passive `22200-22220`):
   ```powershell
   cd Lab02_FTP
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpServerApp"
   ```
   - Tạo tối thiểu 2 tài khoản từ bảng trái (ví dụ `sv_rw` quyền **Read/Write** và `sv_ro` quyền **Read-Only**).
2. **Terminal 2 — Chạy FTP Client Swing**:
   ```powershell
   cd Lab02_FTP
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab02.FtpClientApp"
   ```
   - Kết nối `127.0.0.1:2121`, Upload (xem thanh tiến trình %), Download, Xóa file, kiểm tra bảng `transfer_history` SQLite trên Server.
   - *(Kiểm thử bằng FileZilla — thủ công bên ngoài)*: Mở FileZilla Client → Host `127.0.0.1`, Port `2121`, Username/Password đã tạo → Quickconnect → thử tải lên/tải xuống.

---

### Lab 03 — Mail Server & Mail Client (`Lab03_Mail/`)
1. **Terminal 1 — Chạy Localhost Mail Server** (SMTP `3025`, POP3 `3110`):
   ```powershell
   cd Lab03_Mail
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab03.MailServerApp"
   ```
   - Tạo 2 tài khoản nội bộ trên giao diện (ví dụ `a@ltm.local` và `b@ltm.local`, tự nhập mật khẩu).
2. **Terminal 2 & 3 — Chạy 2 cửa sổ Mail Client**:
   ```powershell
   cd Lab03_Mail
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab03.MailClientApp"
   ```
   - **Chế độ Localhost**: Cửa sổ A gửi thư qua SMTP tới `b@ltm.local`; Cửa sổ B bấm **Nhận Email qua POP3** để tải thư và kiểm tra SQLite.
   - **Chế độ Gmail** *(Cần cấu hình tài khoản Gmail thật có 2FA + App Password 16 ký tự + bật POP trong Gmail Settings)*: Chọn `GMAIL`, nhập Gmail & App Password trên UI (hoặc nạp từ `mail.properties` copy từ `mail.properties.example`).

---

### Lab 04 — HTTP Web Server & Java Swing Browser (`Lab04_Web/`)
1. **Terminal 1 — Chạy Web Server** (Port `8080`, phục vụ `www/`):
   ```powershell
   cd Lab04_Web
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab04.WebServerApp"
   ```
2. **Terminal 2 — Chạy Trình duyệt Swing Browser**:
   ```powershell
   cd Lab04_Web
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab04.WebBrowserApp"
   ```
   - Bấm **GET** mở `http://127.0.0.1:8080/index.html`, click link sang `/about.html`, thử **← Back**, **Forward →**, **↻ Refresh**, nút nhanh **Xem PDF**, **Nghe MP3**, **Xem MP4**, **Download**, chuyển nút **HEAD** xem Body `0 bytes` trong Tab Headers, bấm **URL POST API** kiểm tra `POST /api/submit` và mở tab **3. Lịch sử truy cập (SQLite)**.

---

### Lab 05 — Java RMI Đồng bộ 2 Server (`Lab05_RMI/`)
1. **Terminal 1 — Chạy Server A** (Port `1099`, Peer `1100`):
   ```powershell
   cd Lab05_RMI
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.SyncServerApp" "-Dexec.args=--role A"
   ```
2. **Terminal 2 — Chạy Server B** (Port `1100`, Peer `1099`):
   ```powershell
   cd Lab05_RMI
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.SyncServerApp" "-Dexec.args=--role B"
   ```
3. **Terminal 3 — Chạy RMI Client**:
   ```powershell
   cd Lab05_RMI
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab05.RmiClientApp"
   ```
   - Kết nối Server A (`127.0.0.1:1099`) → Thêm/Sửa/Xóa → đổi dropdown sang Server B (`127.0.0.1:1100`) kiểm tra đồng bộ.
   - Tắt cửa sổ Server B → thao tác thêm/sửa/xóa tại Server A → bật lại Server B → quan sát Catch-up Sync tự động bù đầy đủ dữ liệu.

---

## 3. Lưu ý triển khai thực tế nhiều máy trong mạng LAN
1. Tắt/cho phép ngoại lệ trên **Windows Defender Firewall** cho `java.exe` / các cổng TCP & UDP của từng Lab (`5000-5002` cho Lab 01, `2121` & `22200-22220` cho Lab 02, `3025/3110` cho Lab 03, `8080` cho Lab 04, `1099/1100` cho Lab 05).
2. Trên máy Client/Peer, thay `127.0.0.1` bằng **IP LAN** mà giao diện Server đã tự dò và hiển thị sẵn (ví dụ `192.168.1.x`).
3. Riêng **Lab 05 (Java RMI)** chạy giữa 2 máy LAN khác nhau **bắt buộc** nhập đúng IP LAN của máy chứa Server vào ô **RMI Hostname IP** (hoặc truyền `-Djava.rmi.server.hostname=<LAN_IP>`) trước khi bấm khởi chạy Server.
4. Riêng **Lab 01 UDP Multicast (`224.1.1.1`)**: Các máy trong LAN cần kết nối cùng Switch/Wi-Fi AP có hỗ trợ IGMP multicast (một số mạng Wi-Fi công cộng/ký túc xá chặn gói Multicast; nếu bị chặn hãy thử bằng mạng LAN dây hoặc hotspot cục bộ).

