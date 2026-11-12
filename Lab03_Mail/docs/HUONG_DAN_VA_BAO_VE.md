# TÀI LIỆU HƯỚNG DẪN & BẢO VỆ — LAB 03: MAIL SERVER & MAIL CLIENT

---

## 1. Mục đích của Lab 03
Xây dựng ứng dụng Email hoàn chỉnh gồm **Localhost Mail Server** và **Mail Client** bằng **Jakarta Mail**, minh họa trực tiếp sự phân tách nhiệm vụ giữa:
- Giao thức **SMTP** để đẩy thư từ người gửi lên máy chủ thư.
- Giao thức **POP3** để người nhận tải thư từ hộp thư máy chủ về máy cá nhân và lưu trữ bằng **SQLite**.
- Hỗ trợ linh hoạt cả môi trường **Localhost/LAN** lẫn kết nối **Gmail** thực tế qua **STARTTLS / SSL**.

---

## 2. Giao thức & Thư viện sử dụng

| Thành phần | Công nghệ / Phiên bản | Port | Vai trò |
| :--- | :--- | :--- | :--- |
| **Gửi thư (SMTP)** | RFC 5321 qua `com.sun.mail:jakarta.mail:2.0.2` | `3025` (Localhost) / `587` (Gmail STARTTLS) | Xác thực `AUTH LOGIN` và đẩy thư (`MAIL FROM`, `RCPT TO`, `DATA`). |
| **Nhận thư (POP3)** | RFC 1939 qua `com.sun.mail:jakarta.mail:2.0.2` | `3110` (Localhost) / `995` (Gmail POP3S SSL) | Mở hộp thư `INBOX`, tải nội dung `MimeMessage` về máy Client. |
| **Localhost Server** | `com.icegreen:greenmail:2.1.15` | `3025` + `3110` | Triển khai SMTP Server & POP3 Server thật cho LAN/Localhost. |
| **Database** | SQLite (`org.xerial:sqlite-jdbc:3.54.1.0`) | Cục bộ | Bảng `accounts` và `emails`. |

---

## 3. Mô hình hoạt động
1. **Chiều gửi thư (SMTP)**: Client A soạn `MimeMessage` bằng `jakarta.mail.Session`, gọi `Transport.send(msg)` gửi qua TCP tới SMTP Server (`127.0.0.1:3025` hoặc `smtp.gmail.com:587`). Server xác thực danh tính và chuyển thư vào hộp thư của B (đồng thời lưu bản ghi vào SQLite).
2. **Chiều nhận thư (POP3)**: Client B tạo `Store = session.getStore("pop3" | "pop3s")`, kết nối tới POP3 Server (`127.0.0.1:3110` hoặc `pop.gmail.com:995`), mở `INBOX`, lấy danh sách `Message[]`, giải mã `MimeMessage` / `Multipart` và lưu vào SQLite Client (bỏ qua các thư đã có `message_uid` trùng).

---

## 4. Sơ đồ Client / Server

```text
+--------------------+   SMTP (TCP :3025 / :587 TLS)   +----------------------------+
|  Mail Client A     |================================>|     Mail Server            |
|  Transport.send()  |                                 | (GreenMail / Gmail SMTP)   |
|  + SQLite (SENT)   |                                 |   + GreenMail UserManager  |
+--------------------+                                 |   + SQLite (SERVER_INBOX)  |
                                                       +-------------+--------------+
+--------------------+   POP3 (TCP :3110 / :995 SSL)                 |
|  Mail Client B     |<==============================================+
|  Store.getFolder   |
|  + SQLite (INBOX)  |
+--------------------+
```

---

## 5. Giải thích từng file Java
1. [MailDatabase.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab03_Mail/src/main/java/com/ltm/lab03/MailDatabase.java): Quản lý kết nối SQLite, khởi tạo bảng `accounts` và `emails`, xử lý băm SHA-256 và chèn chống trùng bằng `INSERT OR IGNORE ... UNIQUE(mailbox_email, direction, message_uid)`.
2. [MailServerApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab03_Mail/src/main/java/com/ltm/lab03/MailServerApp.java): Khởi chạy `GreenMail` SMTP + POP3, bật `setAuthRequired(true)`, cài `MessageDeliveryHandler` lưu email vào SQLite Server mỗi khi SMTP nhận thư mới, kèm giao diện Swing quản trị tài khoản nội bộ và bảng `emails`.
3. [MailClientApp.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab03_Mail/src/main/java/com/ltm/lab03/MailClientApp.java): Triển khai `sendEmail()` (SMTP) và `fetchEmailsPop3()` (POP3) bằng Jakarta Mail, hàm đệ quy `extractTextContent()` đọc cả email văn bản thuần lẫn HTML Multipart, stream log giao thức thật (che mật khẩu) và UI Swing chọn chế độ Localhost/Gmail.
4. [MailLab03Test.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab03_Mail/src/test/java/com/ltm/lab03/MailLab03Test.java): Kiểm thử end-to-end 2 tài khoản `sv_a` và `sv_b` gửi nhận qua SMTP + POP3 và xác minh SQLite.

---

## 6. Giải thích class và method quan trọng
- `LocalMailServerCore.start()`: Tạo 2 `ServerSetup` (`SMTP` & `POP3`), bọc `MessageDeliveryHandler` mặc định của GreenMail để vừa giao vào hộp thư POP3 vừa lưu SQLite.
- `MailClientCore.sendEmail(...)`: Tạo `Session` với `Authenticator`, bật `session.setDebug(true)` đẩy log ra UI, tạo `MimeMessage` chuẩn UTF-8 và gọi `Transport.send(msg)`.
- `MailClientCore.fetchEmailsPop3(...)`: Gọi `session.getStore("pop3" | "pop3s")`, kết nối `store.connect(...)`, mở `INBOX` chế độ `READ_ONLY`, đọc nội dung từng thư lưu SQLite, đóng trong khối `finally`.
- `MailClientCore.extractTextContent(Part)`: Đọc nội dung email lồng nhiều tầng (`multipart/alternative`, `text/plain`, `text/html`).

---

## 7. Giải thích luồng dữ liệu
1. Người dùng nhập Email/Mật khẩu (hoặc nạp từ `mail.properties`) và bấm **Gửi Email qua SMTP**.
2. `SwingWorker` gọi `core.sendEmail()` -> Jakarta Mail mở socket TCP tới cổng SMTP (`3025` / `587`) -> trao đổi `EHLO`, `STARTTLS` (nếu Gmail), `AUTH LOGIN`, `MAIL FROM`, `RCPT TO`, `DATA`, `.` -> nhận mã `250 OK` -> lưu bản ghi `SENT` vào `mail_client_lab03.db`.
3. Ở phía Server Localhost, `MessageDeliveryHandler` nhận bản sao `MovingMessage`, lưu vào `mail_server_lab03.db` (`SERVER_INBOX`) và giữ bản gốc trong hộp thư POP3 của người nhận.
4. Người nhận bấm **Nhận Email qua POP3** -> `core.fetchEmailsPop3()` mở socket TCP tới cổng POP3 (`3110` / `995`) -> `USER`, `PASS`, `STAT`, `LIST`, `RETR i`, `QUIT` -> lưu vào `INBOX` SQLite Client và hiển thị lên bảng.

---

## 8. Hướng dẫn chạy & 9. Hướng dẫn kiểm thử
- Chạy `mvn test` để kiểm thử tự động Localhost SMTP/POP3/SQLite.
- Chạy `MailServerApp` (tạo 2 tài khoản `a@ltm.local`, `b@ltm.local`), chạy 2 instance `MailClientApp`, gửi từ A sang B và kiểm tra B bấm **Nhận Email qua POP3** nhận đúng tiêu đề, nội dung tiếng Việt UTF-8 và kiểm tra DB hiển thị đủ bản ghi.
- **Chế độ Gmail**: Xem chi tiết điều kiện 2FA + App Password + bật POP trong [README.md](../README.md).

---

## 10. Lỗi thường gặp & Cách xử lý

| Lỗi | Nguyên nhân | Cách khắc phục |
| :--- | :--- | :--- |
| `535-5.7.8 Username and Password not accepted` (Gmail) | Nhập mật khẩu đăng nhập Google thay vì **App Password**, hoặc chưa bật 2-Step Verification | Bật 2FA trên Google Account và tạo App Password 16 ký tự để nhập vào UI. |
| POP3 Gmail không thấy thư mới | Chưa bật POP trong Settings của Gmail web | Vào *Gmail Settings → Forwarding and POP/IMAP* → chọn Enable POP → Save. |
| `AuthenticationFailedException` ở chế độ Localhost | Chưa tạo tài khoản trên `MailServerApp` hoặc Mail Server chưa bật | Tạo đúng email + password ở bảng bên trái của `MailServerApp` khi Server đang chạy. |
| Lỗi font tiếng Việt trong tiêu đề / nội dung | Thiết lập sai charset khi đóng gói `MimeMessage` | Dùng `msg.setSubject(subject, "UTF-8")` và `msg.setText(body, "UTF-8")` (đã cài sẵn). |

---

## 11. Ngân hàng 15 câu hỏi bảo vệ (Kèm câu hỏi mở file code trực tiếp)

1. **SMTP là gì? SMTP dùng cho chiều nào?**
   - *Trả lời*: SMTP (Simple Mail Transfer Protocol) là giao thức chuẩn truyền email theo chiều **gửi đi** (từ Mail Client lên Mail Server, hoặc chuyển tiếp giữa các Mail Server với nhau). Cổng chuẩn là `25`, `587` (STARTTLS), `465` (SMTPS).
2. **POP3 là gì? Tại sao gửi thư dùng SMTP mà nhận thư phải dùng POP3?**
   - *Trả lời*: POP3 (Post Office Protocol v3) là giao thức giúp Mail Client kết nối tới hộp thư trên Server để **tải thư về** máy cá nhân (cổng `110` / `995` SSL). SMTP chỉ có vai trò *push* (đẩy thư đi) nên người dùng tắt mở máy thất thường cần POP3 để chủ động *pull* (kéo thư về) khi rảnh.
3. **Phân biệt POP3 và IMAP ngắn gọn?**
   - *Trả lời*: POP3 tải thư về máy (mặc định đơn giản, lưu cục bộ, ít đồng bộ thư mục ngược lại server); IMAP quản lý và đồng bộ trạng thái đọc/xóa/thư mục trực tiếp trên Server giữa nhiều thiết bị.
4. **Jakarta Mail (tiền thân JavaMail) đóng vai trò gì?**
   - *Trả lời*: Là thư viện chuẩn Java cài đặt sẵn giao thức SMTP, POP3, IMAP, chuẩn MIME (`MimeMessage`, `Multipart`, đính kèm file, mã hóa UTF-8) giúp lập trình viên không phải tự viết tay từng chuỗi lệnh `EHLO`/`RETR` trên Socket thô.
5. **STARTTLS (port 587) khác gì SSL trực tiếp (port 465/995)?**
   - *Trả lời*: STARTTLS kết nối TCP thường trước, trao đổi `EHLO`, rồi gửi lệnh `STARTTLS` để nâng cấp kết nối hiện tại lên TLS mã hóa; còn SSL trực tiếp (Implicit SSL ở POP3S `995`) thiết lập tầng TLS ngay từ gói bắt tay đầu tiên.
6. **Tại sao kết nối Gmail hiện nay bắt buộc dùng App Password thay vì mật khẩu thường?**
   - *Trả lời*: Để bảo mật, Google đã tắt quyền "Less secure apps". Các ứng dụng SMTP/POP3 bên ngoài chỉ xác thực được khi tài khoản bật bảo mật 2 lớp và dùng mã **App Password 16 ký tự** sinh riêng cho ứng dụng đó.
7. **Tại sao không được hardcode mật khẩu/App Password trong file `.java`?**
   - *Trả lời*: Hardcode mật khẩu làm lộ thông tin đăng nhập cá nhân khi đẩy code lên GitHub hoặc nộp bài đồ án. Phải để người dùng nhập từ giao diện hoặc đọc từ file cấu hình ngoài (`mail.properties` được ignore).
8. **Database SQLite trong Lab 03 giải quyết bài toán gì?**
   - *Trả lời*: Lưu lịch sử thư đã gửi (`SENT`), thư đã tải (`INBOX`) ở Client để xem lại offline không cần tải lại từ Server, và lưu nhật ký hộp thư + tài khoản trên Server (`accounts`, `emails`).
9. **Giải thích các trường cơ bản của 1 gói Email chuẩn MIME?**
   - *Trả lời*: Gồm `From` (người gửi), `To` (người nhận), `Subject` (tiêu đề), `Date` (thời gian gửi), `Message-ID` (mã định danh duy nhất), `Content-Type` (text/plain, multipart...) và `Body`.
10. *(Giảng viên mở `MailDatabase.java` câu lệnh `CREATE TABLE emails`)* **Ràng buộc `UNIQUE(mailbox_email, direction, message_uid)` có tác dụng gì?**
    - *Trả lời*: Khi người dùng bấm **Nhận Email qua POP3** nhiều lần mà thư cũ vẫn còn trên Server, `INSERT OR IGNORE` kết hợp ràng buộc `UNIQUE` này ngăn lưu trùng lặp cùng 1 email vào SQLite.
11. *(Giảng viên mở `MailServerApp.java` phần `MessageDeliveryHandler`)* **Làm thế nào `MailServerApp` bắt được nội dung thư vừa gửi qua SMTP để lưu vào SQLite?**
    - *Trả lời*: GreenMail cho phép bọc `MessageDeliveryHandler`. Khi SMTP Server giao `MovingMessage` cho `targetAddr`, handler của ta chạy `defaultHandler.handle()` để đưa vào hộp thư POP3 của GreenMail, đồng thời gọi `persistSmtpMailToDb()` trích `MimeMessage` lưu ngay vào SQLite.
12. *(Giảng viên mở `MailClientApp.java` hàm `fetchEmailsPop3`)* **Tham số `false` trong `inbox.close(false)` có ý nghĩa gì?**
    - *Trả lời*: `expunge = false` nghĩa là đóng hộp thư POP3 mà **không** ra lệnh xóa các thư đã đánh dấu `DELETED` trên Server. Nếu truyền `true` khi có cờ xóa, Server POP3 sẽ xóa thư khỏi hộp thư online.
13. *(Giảng viên mở `MailClientApp.java` hàm `createDebugStream`)* **Đoạn code `session.setDebug(true)` và `createDebugStream()` làm nhiệm vụ gì?**
    - *Trả lời*: Chuyển hướng luồng log debug giao thức thật của Jakarta Mail từ `System.out` sang khung nhật ký Swing để hiển thị trực quan các lệnh SMTP/POP3 (`EHLO`, `MAIL FROM`, `RETR`), đồng thời lọc che `AUTH`/`PASS` thành `********`.
14. *(Giảng viên mở `MailClientApp.java` hàm `extractTextContent(Part)`)* **Tại sao không gọi `msg.getContent().toString()` là xong mà phải kiểm tra `multipart/*`?**
    - *Trả lời*: Email thực tế (nhất là từ Gmail) thường có cấu trúc `Multipart` chứa nhiều phần (`text/plain`, `text/html`, tệp đính kèm). Nếu gọi `getContent().toString()` sẽ chỉ in ra tên class `MimeMultipart@...`, nên cần duyệt đệ quy từng `BodyPart`.
15. *(Giảng viên mở `MailClientApp.java` phần nút `btnSendSmtp` / `btnFetchPop3`)* **Tại sao lại phải đặt `Transport.send` và `store.connect` trong `SwingWorker`?**
    - *Trả lời*: Vì bắt tay TLS với Gmail (`smtp.gmail.com:587`) hoặc đọc mạng mất 1–3 giây; nếu chạy trên luồng giao diện EDT sẽ làm toàn bộ cửa sổ bị treo đơ trong lúc gửi/nhận.

