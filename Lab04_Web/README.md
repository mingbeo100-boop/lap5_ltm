# Lab 04 — Web Server & Java Swing Web Browser (HTTP, GET, HEAD, POST, PDF, MP3, MP4, SQLite)

## 1. Giới thiệu & Công nghệ sử dụng
- **Ngôn ngữ / JDK**: Java 17+, Java Swing (`JEditorPane`, `JTabbedPane`, `CardLayout`, `JFXPanel`).
- **HTTP Chuẩn**:
  - **Web Server**: `com.sun.net.httpserver.HttpServer` (có sẵn trong JDK `jdk.httpserver`), kết hợp `Executors.newFixedThreadPool(16)` phục vụ đa kết nối.
  - **Web Browser Client**: `java.net.http.HttpClient` (có sẵn trong JDK `java.net.http`) gửi trực tiếp **GET**, **HEAD**, **POST** và đọc đầy đủ **HTTP Status Code** & **Response Headers**.
- **Thư viện ngoài (`pom.xml`)**:
  - `org.openjfx:javafx-swing` (`17.0.14`) & `org.openjfx:javafx-media` (`17.0.14`): Nhúng trình phát đa phương tiện **MP3 / MP4** stream trực tiếp từ `http://...` (hỗ trợ tua `HTTP Range 206`) vào trong `JFrame` Swing thông qua `JFXPanel`.
  - `org.apache.pdfbox:pdfbox` (`2.0.32`): Đọc và kết xuất các trang PDF (`application/pdf`) thành hình ảnh để hiển thị trực tiếp ngay trên Browser.
  - `org.xerial:sqlite-jdbc` (`3.54.1.0`): Lưu lịch sử duyệt Web vào SQLite (`history`).

## 2. Cấu trúc thư mục
- [pom.xml](pom.xml): Cấu hình Maven của Lab 04.
- [www/](www): Thư mục tài nguyên tĩnh của Web Server (`index.html`, `about.html`, `tailieu.pdf`, `nhac.mp3`, `video.mp4`).
- [src/main/java/com/ltm/lab04/WebDatabase.java](src/main/java/com/ltm/lab04/WebDatabase.java): Lưu trữ và truy vấn bảng SQLite `history` (`url`, `method`, `status_code`, `content_type`, `content_length`, `visited_at`).
- [src/main/java/com/ltm/lab04/WebServerApp.java](src/main/java/com/ltm/lab04/WebServerApp.java): HTTP Server lắng nghe port mặc định `8080`, hỗ trợ GET, HEAD (Body 0 byte), POST `/api/submit`, HTTP Range `206 Partial Content`, chống truy cập trái phép Path Traversal.
- [src/main/java/com/ltm/lab04/WebBrowserApp.java](src/main/java/com/ltm/lab04/WebBrowserApp.java): Trình duyệt Java Swing có thanh URL, Back/Forward/Refresh, GET/HEAD/POST, bảng Response Headers, PDF Viewer, MP3/MP4 Player, Download file và History SQLite.
- [src/test/java/com/ltm/lab04/WebLab04Test.java](src/test/java/com/ltm/lab04/WebLab04Test.java): Test tự động kiểm tra GET HTML/PDF, HEAD 0 body, POST JSON, Range 206, JavaFX phát MP3/MP4 qua HTTP và SQLite `history`.

## 3. Lệnh Build, Test & Chạy chương trình

### Biên dịch & chạy kiểm thử tự động JUnit 5:
```powershell
mvn test
```

### Khởi chạy trình diễn (Demo 2 cửa sổ):
1. Terminal 1 — Khởi chạy Web Server (Port `8080`):
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab04.WebServerApp"
   ```
2. Terminal 2 — Khởi chạy Trình duyệt Swing Browser:
   ```powershell
   mvn compile exec:java "-Dexec.mainClass=com.ltm.lab04.WebBrowserApp"
   ```
3. Nhấn **GET** để mở `http://127.0.0.1:8080/index.html`, click các link (hoặc nút nhanh) để kiểm tra **PDF**, **MP3**, **MP4**, chuyển phương thức **HEAD** để thấy Body = `0 bytes` trong tab Headers, bấm **URL POST API** / **POST** để gửi JSON, và kiểm tra tab **3. Lịch sử truy cập (SQLite)**.

### Lưu ý khi chạy trên máy khác qua mạng LAN:
- Có thể đổi IP trong thanh URL từ `127.0.0.1` sang **IP LAN** hiển thị trên `WebServerApp` (ví dụ `http://192.168.1.12:8080/index.html`) và mở Windows Firewall cho TCP port `8080`.

