# BỘ 5 LAB THỰC HÀNH LẬP TRÌNH MẠNG BẰNG JAVA

Workspace độc lập gồm **5 bài Lab Lập trình mạng** xây dựng **100% bằng Java 17+, Maven, Java Swing và JDBC + SQLite** (không sử dụng Python trong bất kỳ bài Lab nào). Mỗi bài Lab có thư mục, tệp `pom.xml`, mã nguồn, kiểm thử JUnit 5, hướng dẫn chạy và tài liệu bảo vệ riêng biệt.

---

## 1. Danh sách 5 bài Lab độc lập

| Lab | Thư mục | Giao thức & Công nghệ chính | Tài liệu chi tiết |
| :--- | :--- | :--- | :--- |
| **Lab 01** | [Lab01_Chat/](Lab01_Chat/) | TCP Multi-client (6+ Client), UDP Broadcast (`255.255.255.255`), UDP Multicast (`224.1.1.1`), File Transfer, Java Sound Audio, Webcam Video | [README](Lab01_Chat/README.md) • [Hướng dẫn & Bảo vệ](Lab01_Chat/docs/HUONG_DAN_VA_BAO_VE.md) |
| **Lab 02** | [Lab02_FTP/](Lab02_FTP/) | FTP Protocol chuẩn (RFC 959), Passive Mode (`PASV`), Upload/Download tiến trình %, Phân quyền Read-Only/Write, SQLite (`users`, `transfer_history`), tương thích FileZilla | [README](Lab02_FTP/README.md) • [Hướng dẫn & Bảo vệ](Lab02_FTP/docs/HUONG_DAN_VA_BAO_VE.md) |
| **Lab 03** | [Lab03_Mail/](Lab03_Mail/) | Jakarta Mail SMTP gửi thư & POP3 nhận thư, Localhost/LAN Server + Gmail STARTTLS/POP3S (không hardcode mật khẩu), SQLite (`accounts`, `emails`) | [README](Lab03_Mail/README.md) • [Hướng dẫn & Bảo vệ](Lab03_Mail/docs/HUONG_DAN_VA_BAO_VE.md) |
| **Lab 04** | [Lab04_Web/](Lab04_Web/) | HTTP/1.1 Server (`www/`: HTML, PDF, MP3, MP4) + Browser Swing (`GET`, `HEAD` 0 body bytes, `POST`, Back/Forward/Refresh, Download, Headers Viewer, PDFBox, MP3/MP4 Streaming `206 Range`), SQLite (`history`) | [README](Lab04_Web/README.md) • [Hướng dẫn & Bảo vệ](Lab04_Web/docs/HUONG_DAN_VA_BAO_VE.md) |
| **Lab 05** | [Lab05_RMI/](Lab05_RMI/) | Java RMI hai Server (`Server A`, `Server B`), `Remote Interface`, `UnicastRemoteObject`, `RMI Registry`, `Serializable`, SQLite độc lập mỗi Server, đồng bộ 2 chiều, chống lặp vô hạn, Catch-up Sync khi mất/nối lại mạng | [README](Lab05_RMI/README.md) • [Hướng dẫn & Bảo vệ](Lab05_RMI/docs/HUONG_DAN_VA_BAO_VE.md) |

---

## 2. Yêu cầu môi trường & Danh mục thư viện ngoài

### Môi trường chung
- **JDK**: Java 17 trở lên (đã kiểm thử với Microsoft OpenJDK `17.0.20.1`).
- **Build Tool**: Apache Maven 3.9+ (Maven tự động tải dependency qua `pom.xml` cho từng Lab).
- **Hệ điều hành**: Windows 10/11 (hoạt động tương thích qua LAN).

### Bảng tổng hợp các thư viện ngoài được sử dụng

| Thư viện (Maven Coordinates) | Phiên bản | Lab sử dụng | Chức năng & Lưu ý cài đặt |
| :--- | :--- | :--- | :--- |
| `com.github.sarxos:webcam-capture` | `0.3.12` | Lab 01 | Chụp khung hình thực tế từ Webcam qua OpenIMAJ/bridj (tự đóng gói qua Maven, không cần cài C++ SDK ngoài trên Windows 64-bit). |
| `org.apache.ftpserver:ftpserver-core` | `1.2.1` | Lab 02 | Triển khai FTP Server chuẩn RFC 959 trên JVM, hỗ trợ `PASV`, FileZilla và `Ftplet` chặn sự kiện ghi SQLite. |
| `commons-net:commons-net` | `3.11.1` | Lab 02 | Cung cấp `FTPClient` chuẩn cho ứng dụng FTP Client Java. |
| `com.sun.mail:jakarta.mail` | `2.0.2` | Lab 03 | Thư viện Jakarta Mail giao tiếp SMTP / POP3 / STARTTLS / POP3S thật. |
| `com.icegreen:greenmail` | `2.1.15` | Lab 03 | SMTP Server (`3025`) & POP3 Server (`3110`) thật cho chế độ Localhost/LAN. |
| `org.openjfx:javafx-swing` & `javafx-media` | `17.0.14` | Lab 04 | Nhúng `JFXPanel` + `MediaPlayer` phát trực tiếp MP3 & MP4 H.264/AAC từ HTTP URL trong Swing. |
| `org.apache.pdfbox:pdfbox` | `2.0.32` | Lab 04 | Đọc và render từng trang PDF thành ảnh hiển thị ngay trong Browser Swing. |
| `org.xerial:sqlite-jdbc` | `3.54.1.0` | Lab 02, 03, 04, 05 | Trình điều khiển JDBC kết nối CSDL SQLite cục bộ (tự nhúng native DLL Windows bên trong JAR). |
| `org.slf4j:slf4j-simple` | `1.7.36` | Lab 01, 02, 03 | Logging gọn cho FTP/GreenMail/Webcam không gây cảnh báo `SLF4J: Failed to load class`. |
| `org.junit.jupiter:junit-jupiter` | `5.11.4` | Cả 5 Lab | Framework JUnit 5 kiểm thử tự động các giao thức mạng (scope `test`). |

*(Thư viện chuẩn JDK được ưu tiên sử dụng tối đa: `java.net.Socket`, `ServerSocket`, `DatagramSocket`, `DatagramChannel`, `javax.sound.sampled`, `com.sun.net.httpserver.HttpServer`, `java.net.http.HttpClient`, `java.rmi.*`, `javax.swing.*`, `java.sql.*`).*

---

## 3. Tài liệu bổ trợ cấp Root
- [HUONG_DAN_CHAY.md](HUONG_DAN_CHAY.md): Toàn bộ lệnh `mvn test` và `mvn exec:java` cho từng Lab + hướng dẫn chạy qua mạng LAN giữa nhiều máy.
- [CAU_HOI_BAO_VE.md](CAU_HOI_BAO_VE.md): Tổng hợp giải thích cốt lõi 20 khái niệm/giao thức Lập trình mạng và liên kết ngân hàng 15+ câu hỏi mở file code cho từng Lab.

