# TÀI LIỆU HƯỚNG DẪN & BẢO VỆ — LAB 04: HTTP WEB SERVER & SWING BROWSER

---

## 1. Mục đích của Lab 04
Xây dựng hoàn chỉnh cặp **Web Server** và **Web Browser** bằng Java thuần + JavaFX Media/PDFBox nhằm:
- Hiểu rõ cách giao thức **HTTP/1.1** hoạt động trên nền **TCP Socket** (port `8080`).
- Triển khai thực tế và phân biệt rõ 3 phương thức HTTP cốt lõi: **GET**, **HEAD**, **POST**.
- Hiểu cách Web Server phân phát nhiều loại tài nguyên qua header **`Content-Type` (MIME type)**: HTML (`text/html`), PDF (`application/pdf`), Audio (`audio/mpeg`), Video (`video/mp4`) kèm **`Accept-Ranges: bytes` / `206 Partial Content`** phục vụ phát trực tuyến (streaming) và tải file.
- Lưu trữ lịch sử truy cập của trình duyệt vào **SQLite** thông qua **JDBC**.

---

## 2. Giao thức & Thư viện sử dụng

| Thành phần | Công nghệ / Phiên bản | Vai trò |
| :--- | :--- | :--- |
| **HTTP Server** | `com.sun.net.httpserver.HttpServer` (JDK 17 built-in) | Lắng nghe TCP port `8080`, nhận HttpExchange và trả Status + Headers + Body. |
| **HTTP Client** | `java.net.http.HttpClient` (JDK 17 built-in) | Gửi HTTP GET, HEAD, POST thực thụ và nhận Status Code, Headers, `byte[]` body. |
| **HTML Rendering** | Swing `JEditorPane` (`HTMLEditorKit`) | Render trang HTML và bắt sự kiện click thẻ `<a href="...">`. |
| **PDF Rendering** | Apache PDFBox (`org.apache.pdfbox:pdfbox:2.0.32`) | Giải mã tệp PDF tải qua HTTP thành ảnh hiển thị từng trang trong Swing. |
| **MP3 / MP4 Player** | JavaFX (`javafx-swing` + `javafx-media` `17.0.14`) | Phát luồng MP3 và video MP4 H.264/AAC trực tiếp từ HTTP URL thông qua `JFXPanel`. |
| **Database** | SQLite (`org.xerial:sqlite-jdbc:3.54.1.0`) | Lưu bảng `history` (URL, Method, HTTP Status, Content-Type, Bytes, Thời gian). |

---

## 3. Mô hình hoạt động
1. **WebServerApp** lắng nghe kết nối TCP tại `0.0.0.0:8080`, giao mỗi request cho `ExecutorService` (`FixedThreadPool(16)`).
2. Khi Browser (hoặc Chrome/Edge bất kỳ) gửi **GET `/index.html`**, Server ánh xạ về tệp `www/index.html`, gắn `Content-Type: text/html; charset=utf-8` và trả mã `200 OK` cùng nội dung file.
3. Khi Browser gửi **HEAD `/tailieu.pdf`**, Server giữ nguyên Status `200 OK` và các Response Headers (`Content-Type`, `Content-Length`, `X-Resource-Length`), nhưng gọi `exchange.sendResponseHeaders(200, -1)` để **không gửi phần Body** (Body nhận về = `0 bytes`).
4. Khi Browser gửi **POST `/api/submit`**, Server đọc toàn bộ luồng `exchange.getRequestBody()`, xử lý payload và trả JSON xác nhận kèm thời gian máy chủ.
5. Khi xem **MP3** / **MP4**, JavaFX `MediaPlayer` tự động gửi `HEAD` để kiểm tra dung lượng/Content-Type, sau đó gửi `GET` (có kèm `Range: bytes=...` khi tua hoặc đọc metadata `moov` cuối file MP4 → Server trả `206 Partial Content`).

---

## 4. Sơ đồ Client / Server

```text
+-----------------------------------+      TCP HTTP/1.1 (:8080)      +-------------------------------+
|        WebBrowserApp (Swing)      |------------------------------->|    WebServerApp (HttpServer)  |
| - HttpClient (GET / HEAD / POST)  |<-------------------------------| - ThreadPool (16 threads)     |
| - Back / Forward / Refresh        |   Status + Headers + Body/206  | - Static Router -> www/       |
| - PDFBox (PDF) / JFXPanel (MP3/4) |                                |   (index, about, pdf,mp3,mp4) |
| - SQLite (history table)          |                                | - POST API (/api/submit)      |
+-----------------------------------+                                +-------------------------------+
```

---

## 5. Giải thích từng file Java
1. [WebDatabase.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab04_Web/src/main/java/com/ltm/lab04/WebDatabase.java): Tạo bảng `history` trong SQLite, cung cấp hàm `recordHistory()`, `listHistory()` và `clearHistory()`.
2. [WebServerApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab04_Web/src/main/java/com/ltm/lab04/WebServerApp.java): Triển khai `HttpWebServerCore` dùng `HttpServer`, hàm `handleApiSubmit()` xử lý POST/GET/HEAD cho `/api/submit`, hàm `handleStaticFile()` kiểm tra an toàn đường dẫn, đoán MIME type, xử lý `HEAD` không body và `GET` có hỗ trợ `Range 206 Partial Content`.
3. [WebBrowserApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab04_Web/src/main/java/com/ltm/lab04/WebBrowserApp.java): Triển khai `BrowserHttpClientCore` (dùng `java.net.http.HttpClient`), ngăn xếp `backStack`/`forwardStack`, kết xuất đa loại nội dung bằng `CardLayout` (HTML `JEditorPane`, JSON/Text, PDFBox `PDFRenderer`, JavaFX `JFXPanel` + `MediaPlayer`), bảng Response Headers và tab Lịch sử SQLite.
4. [WebLab04Test.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab04_Web/src/test/java/com/ltm/lab04/WebLab04Test.java): Kiểm thử end-to-end GET, HEAD (body = 0 byte), POST, HTTP Range 206, PDFBox, JavaFX phát MP3/MP4 qua HTTP thật và SQLite `history`.

---

## 6. Giải thích class và method quan trọng
- `HttpWebServerCore.handleStaticFile(...)`: Ánh xạ URI tới thư mục `www/`, kiểm tra `!target.startsWith(wwwDir)` chống Path Traversal, trả `200 OK` hoặc `206 Partial Content`, và gọi `ex.sendResponseHeaders(200, -1)` riêng cho `HEAD`.
- `BrowserHttpClientCore.execute(url, method, postBody, saveHistory)`: Dùng `HttpClient.send(..., BodyHandlers.ofByteArray())` gửi đúng HTTP method, đo thời gian phản hồi, trích xuất headers và lưu `history` vào SQLite.
- `WebBrowserApp.renderPdfIntoPanel(...)`: Nạp `PDDocument.load(pdfBytes)` và gọi `PDFRenderer.renderImageWithDPI(...)` để hiển thị trang PDF mà không cần mở phần mềm ngoài.
- `loadMediaUrl` (trong `WebBrowserApp.launchGui`): Khởi tạo `new Media(httpUrl)` và `MediaPlayer`, gắn `MediaView` (cho MP4) hoặc `Audio Badge` (cho MP3) trên JavaFX Application Thread (`Platform.runLater`).

---

## 7. Giải thích luồng dữ liệu
1. Người dùng nhập URL (hoặc click link trong HTML) và chọn nút **GET**, **HEAD** hoặc **POST**.
2. `SwingWorker` gọi `core.execute()` trên thread nền (không làm đơ giao diện Swing) để gửi bản tin HTTP Request tới TCP port `8080`.
3. `HttpServer` nhận request, ghi log Client IP/Method/Path/Status lên bảng Server UI, trả bản tin HTTP Response tương ứng.
4. Browser nhận Status Code, Response Headers và `byte[]` body:
   - Nếu là **HEAD** → chỉ hiển thị Headers và ghi chú Body = `0 bytes`.
   - Nếu `text/html` → nạp vào `JEditorPane`.
   - Nếu `application/pdf` → render thành từng trang ảnh qua `PDFBox`.
   - Nếu `audio/mpeg` / `video/mp4` → truyền thẳng URL `http://...` cho JavaFX `MediaPlayer` để streaming.
   - Ghi ngay 1 dòng lịch sử vào bảng `history` trong SQLite.

---

## 8. Hướng dẫn chạy & 9. Hướng dẫn kiểm thử
1. Chạy `mvn test` tại thư mục `Lab04_Web/` → kiểm tra JUnit test xanh 100%.
2. Chạy `WebServerApp`, xác nhận trạng thái *Đang chạy (Port 8080)* và thấy đủ 5 file trong bảng `www/` (`index.html`, `about.html`, `tailieu.pdf`, `nhac.mp3`, `video.mp4`).
3. Chạy `WebBrowserApp`:
   - Bấm **GET** → xem `index.html`, click link `/about.html`, kiểm tra nút **← Back** và **Forward →**, bấm **↻ Refresh**.
   - Bấm **Xem PDF** → quan sát 2 trang PDF render trên màn hình → bấm **Download** để lưu file PDF về máy và mở thử bằng trình đọc PDF.
   - Bấm **HEAD** trên URL `/tailieu.pdf` → kiểm tra tab *HTTP Status & Headers* thấy `HTTP 200`, `Content-Length: 1929`, nhưng `Body nhận được: 0 bytes`.
   - Bấm **Nghe MP3** → nghe giai điệu phát qua loa và kéo thanh tua.
   - Bấm **Xem MP4** → xem video 3 slide + âm thanh chạy qua HTTP (quan sát console Server xuất hiện mã `206 Partial Content`).
   - Bấm **URL POST API** → kiểm tra Server console nhận đủ JSON payload từ Browser.
   - Mở tab **3. Lịch sử truy cập (SQLite)** → kiểm tra lịch sử GET/HEAD/POST đã lưu; tắt mở lại Browser thấy lịch sử vẫn còn trong SQLite.

---

## 10. Lỗi thường gặp & Cách xử lý

| Lỗi | Nguyên nhân | Cách khắc phục |
| :--- | :--- | :--- |
| `ConnectException` khi bấm GET | Quên khởi chạy `WebServerApp` hoặc nhập sai Port | Khởi chạy `WebServerApp` trước, kiểm tra Port khớp `8080`. |
| Mở MP3/MP4 không có tiếng/hình | Tệp `nhac.mp3` hoặc `video.mp4` chưa có trong `www/` hoặc thiếu hỗ trợ `206 Range` | Đảm bảo thư mục làm việc trỏ đúng tới `Lab04_Web/` nơi chứa `www/nhac.mp3` và `www/video.mp4`. |
| `404 Not Found` | Gõ sai tên tệp trong URL (`index.htm` thay vì `index.html`) | Kiểm tra cột *Đường dẫn HTTP* trên giao diện `WebServerApp`. |
| Link click không mở | HTML href chứa đường dẫn sai định dạng URI | Trình duyệt tự gọi `URI.create(base).resolve(href)`; kiểm tra đường dẫn bắt đầu bằng `/`. |

---

## 11. Ngân hàng 16 câu hỏi bảo vệ (Kèm câu hỏi mở file code trực tiếp)

1. **HTTP là gì? Nó hoạt động ở tầng nào và dùng giao thức tầng giao vận nào?**
   - *Trả lời*: HTTP (*HyperText Transfer Protocol*) là giao thức tầng Ứng dụng theo mô hình Request–Response phi trạng thái (stateless), hoạt động mặc định trên **TCP** (port `80` cho HTTP, `443` cho HTTPS; Lab 04 dùng port `8080`).
2. **Phân biệt Socket, IP và Port trong Lab 04?**
   - *Trả lời*: **IP** (`127.0.0.1` hoặc IP LAN `192.168.x.x`) định danh máy chủ; **Port** (`8080`) định danh tiến trình `WebServerApp`; **Socket** là điểm đầu cuối kết hợp `(IP, Port)` tạo kênh TCP truyền thông điệp HTTP giữa Browser và Server.
3. **Sự khác biệt cốt lõi giữa `TCP` và `UDP`? Tại sao HTTP chọn TCP?**
   - *Trả lời*: TCP hướng kết nối, đảm bảo dữ liệu HTML/PDF/MP4 đến đủ byte, đúng thứ tự và có kiểm soát tắc nghẽn; UDP không kết nối, có thể mất gói. Web không thể mất byte file HTML hay PDF nên HTTP/1.1 dùng TCP.
4. **Phân biệt phương thức `GET`, `HEAD` và `POST`?**
   - *Trả lời*:
     - **GET**: Yêu cầu lấy tài nguyên (HTML, PDF, MP3, MP4) từ Server (tham số nếu có nằm trên URL query string), trả Headers + Body.
     - **HEAD**: Giống GET nhưng yêu cầu Server **chỉ trả Status Code và Response Headers, tuyệt đối không gửi Body** (`0 bytes`). Dùng để kiểm tra tệp có tồn tại (`200`/`404`), kiểu MIME hay kích thước `Content-Length` trước khi tải.
     - **POST**: Gửi dữ liệu mới (JSON, form, file) nằm trong **Request Body** lên Server (`/api/submit`) để xử lý.
5. **Làm thế nào chứng minh Browser của em gửi HTTP Request thật chứ không đổi giao diện giả?**
   - *Trả lời*: Dùng `java.net.http.HttpClient.send(...)` nối tới TCP port `8080`. Khi tắt `WebServerApp`, Browser báo `Connection refused`. Khi bật, mỗi cú click đều ghi log thực tế kèm IP Client, Method, Path, HTTP Status và kích thước byte truyền đi ở cả Server lẫn Wireshark.
6. **Tại sao khi phát MP4 trên JavaFX `MediaPlayer`, Server lại ghi nhận mã HTTP `206 Partial Content`?**
   - *Trả lời*: Để phát/tua video nhanh mà không cần tải hết file trước, trình phát gửi request kèm header `Range: bytes=start-end` (ví dụ đọc header/atom `moov` ở cuối file MP4). Server cắt đúng dải byte đó bằng `RandomAccessFile` và trả mã `206 Partial Content` kèm `Content-Range`.
7. **Header `Content-Type` (MIME Type) dùng để làm gì?**
   - *Trả lời*: Cho Client biết định dạng dữ liệu trong Body để chọn bộ giải mã phù hợp: `text/html` mở bằng HTML Viewer, `application/pdf` mở bằng PDFBox, `audio/mpeg` phát MP3, `video/mp4` phát MP4.
8. **Chức năng Back và Forward trên trình duyệt hoạt động theo cấu trúc dữ liệu nào?**
   - *Trả lời*: Dùng hai ngăn xếp (`Deque<String> backStack` và `forwardStack`). Khi đi sang trang mới, URL cũ đẩy vào `backStack` và xóa `forwardStack`; khi nhấn Back, pop `backStack` và đẩy URL hiện tại sang `forwardStack`.
9. **Thread được dùng ở đâu trong `WebServerApp` và `WebBrowserApp`?**
   - *Trả lời*: Server dùng `Executors.newFixedThreadPool(16)` xử lý nhiều kết nối HTTP song song; Client dùng `SwingWorker` (Thread nền) thực hiện `HttpClient.send()` để không làm treo Event Dispatch Thread (EDT) của Swing, và JavaFX thread riêng (`Platform.runLater`) điều khiển `MediaPlayer`.
10. **JDBC và SQLite Database đóng vai trò gì trong Lab 04?**
    - *Trả lời*: `WebDatabase` dùng **JDBC** (`DriverManager.getConnection("jdbc:sqlite:...")`) lưu mọi lượt `GET`/`HEAD`/`POST` vào bảng `history`, giúp xem lại lịch sử ngay cả sau khi tắt mở lại Browser.
11. **Lỗi bảo mật Path Traversal là gì và Server đã chặn thế nào?**
    - *Trả lời*: Nếu Client gửi URL chứa `../` (vd `/../../Windows/win.ini`) để đọc file ngoài thư mục `www/`, hàm `handleStaticFile` gọi `wwwDir.resolve(rel).normalize()` và kiểm tra `!target.startsWith(wwwDir)` để chặn bằng mã `403 Forbidden`.
12. *(Câu hỏi mở file code trực tiếp)* **Mở `WebServerApp.java`: chỉ ra đoạn code phân biệt `HEAD` và `GET`?**
    - *Trả lời*: Mở phương thức `handleStaticFile` → nhánh `if ("HEAD".equals(method))` đặt `Content-Length` / `X-Resource-Length` và gọi **`ex.sendResponseHeaders(200, -1)`** (tham số `-1` báo với `HttpServer` không mở luồng ghi response body), khác với `GET` truyền `fileSize` và ghi file qua `in.transferTo(os)`.
13. *(Câu hỏi mở file code trực tiếp)* **Mở `WebBrowserApp.java`: chỉ ra đoạn code tạo và gửi HTTP Request thật cho cả 3 phương thức GET/HEAD/POST?**
    - *Trả lời*: Trong class `BrowserHttpClientCore`, phương thức `execute(...)` tạo `HttpRequest.newBuilder()`, dùng `.method("HEAD", BodyPublishers.noBody())`, `.POST(BodyPublishers.ofString(...))` hoặc `.GET()`, sau đó gọi `httpClient.send(builder.build(), BodyHandlers.ofByteArray())`.
14. *(Câu hỏi mở file code trực tiếp)* **Mở `WebBrowserApp.java`: tại sao cần `Platform.setImplicitExit(false)` và `SwingWorker`?**
    - *Trả lời*: `SwingWorker` giữ giao diện Swing mượt khi mạng chậm; `Platform.setImplicitExit(false)` ngăn JavaFX Runtime tự tắt ngầm khi `MediaView` chuyển bài hoặc tạm ẩn sang tab HTML/PDF.
15. *(Câu hỏi mở file code trực tiếp)* **Mở `WebDatabase.java`: câu lệnh SQL nào lấy lịch sử mới nhất lên đầu và tại sao dùng `PreparedStatement`?**
    - *Trả lời*: Trong `listHistory()`, câu lệnh `SELECT ... FROM history ORDER BY id DESC LIMIT 200`. Dùng `PreparedStatement` (`?` placeholder) trong `recordHistory()` giúp tránh lỗi SQL Injection nếu URL chứa dấu nháy đơn `'`.
16. **Nêu sự khác nhau giữa HTTP (`200 OK`) và HTTP (`404 Not Found`) trong bài Lab?**
    - *Trả lời*: `200 OK` nghĩa là URL ánh xạ đúng tài nguyên hợp lệ trong `www/` (hoặc `/api/submit`); `404 Not Found` trả về kèm thông báo text khi file không tồn tại trong thư mục gốc của Web Server.

