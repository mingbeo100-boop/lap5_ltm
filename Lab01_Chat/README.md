# Lab 01 — Ứng dụng Chat Client/Server bằng Java (TCP, UDP Broadcast/Multicast, Audio/Video, File)

## 1. Giới thiệu & Công nghệ sử dụng
- **Ngôn ngữ / JDK**: Java 17+ (`Socket`, `ServerSocket`, `DatagramSocket`, `MulticastSocket`, `ExecutorService`, `Java Swing`, `Java Sound API`).
- **Build tool**: Apache Maven (`pom.xml` độc lập).
- **Thư viện ngoài**:
  - `com.github.sarxos:webcam-capture` (`0.3.12`): Đọc khung hình `BufferedImage` thực tế từ Webcam máy tính trên Windows/Linux/macOS (không dùng khung hình giả).
  - `org.slf4j:slf4j-simple` (`1.7.36`): Logger đi kèm cho thư viện webcam.
  - `org.junit.jupiter:junit-jupiter` (`5.11.4`, scope `test`): Kiểm thử tự động JUnit 5.

## 2. Cấu trúc thư mục
- [pom.xml](pom.xml): Khai báo dependencies và plugin biên dịch/chạy.
- [src/main/java/com/ltm/lab01/ChatPacket.java](src/main/java/com/ltm/lab01/ChatPacket.java): Lớp gói tin `Serializable` truyền qua TCP (`ObjectOutputStream` / `ObjectInputStream`).
- [src/main/java/com/ltm/lab01/ChatServer.java](src/main/java/com/ltm/lab01/ChatServer.java): TCP Server đa luồng (`ServerSocket` + `Executors.newCachedThreadPool()`) + Giao diện Swing quản trị Server.
- [src/main/java/com/ltm/lab01/ChatClient.java](src/main/java/com/ltm/lab01/ChatClient.java): Client hỗ trợ TCP (chat chung, 1-1, nhóm, file), UDP Broadcast (`255.255.255.255:5001`), UDP Multicast (`224.1.1.1:5002`), Gọi thoại thật (Java Sound PCM 16kHz), Gọi video thật (`Webcam` + JPEG).
- [src/test/java/com/ltm/lab01/ChatLab01Test.java](src/test/java/com/ltm/lab01/ChatLab01Test.java): Bộ test tự động 6 Client đồng thời, Broadcast, Multicast, truyền File, Signaling & truyền gói Audio/Video.
- [docs/HUONG_DAN_VA_BAO_VE.md](docs/HUONG_DAN_VA_BAO_VE.md): Tài liệu chi tiết 10 mục + câu hỏi bảo vệ.

## 3. Lệnh Build, Test & Chạy chương trình

> Lưu ý trên Windows PowerShell nếu `mvn` chưa có trong `PATH`, dùng đường dẫn Maven đã cài (ví dụ `C:\opt\apache-maven-3.9.9\bin\mvn.cmd`).

### Biên dịch & chạy kiểm thử tự động:
```powershell
mvn test
```

### Khởi chạy Server (Swing GUI):
```powershell
mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatServer"
```

### Khởi chạy nhiều Client (mỗi cửa sổ 1 lệnh riêng để mô phỏng 5–6 Client):
```powershell
mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatClient"
```

## 4. Chạy trên nhiều máy tính trong cùng mạng LAN
1. Máy chạy Server mở `ChatServer`, xem dòng **IP LAN** trên thanh công cụ (ví dụ `192.168.1.12`), port mặc định `5000`. Mở Firewall Windows cho cổng **TCP 5000**, **UDP 5001** (Broadcast), **UDP 5002** (Multicast) nếu cần.
2. Các máy Client chạy `ChatClient`, nhập **IP Server** = `192.168.1.12`, nhập tên khác nhau (ví dụ `SinhVien1` ... `SinhVien6`) và bấm **Kết nối Server**.
3. Chọn **Tham gia nhóm Multicast** để nhận tin gửi tới địa chỉ `224.1.1.1:5002`.

> Lưu ý phần cứng khi demo trên cùng 1 máy: Một Webcam vật lý chỉ cho phép 1 tiến trình giữ quyền mở tại một thời điểm (`WebcamLockException`). Khi chạy 2 Client trên **cùng 1 máy**, Client mở Webcam sau sẽ hiện cảnh báo ở cửa sổ gọi và vẫn xem được Video từ đối phương; muốn cả 2 chiều cùng có hình cần chạy trên 2 máy tính hoặc 2 Webcam khác nhau.

