# TÀI LIỆU HƯỚNG DẪN & BẢO VỆ — LAB 01: CHAT CLIENT/SERVER BẰNG JAVA

---

## 1. Mục đích của Lab 01
Xây dựng hệ thống giao tiếp mạng Client/Server hoàn chỉnh bằng Java thuần (kèm `sarxos webcam-capture` cho Camera thực tế), giúp sinh viên nắm vững và trình diễn thực tế các cơ chế truyền thông mạng cốt lõi:
- Truyền tin tin cậy hướng kết nối bằng **TCP Socket** (nhiều Client đồng thời, định tuyến 1-1, nhóm, gửi file).
- Truyền quảng bá nội bộ LAN bằng **UDP Broadcast** (`255.255.255.255`).
- Truyền đa hướng theo nhóm đăng ký bằng **UDP Multicast** (`224.1.1.1`).
- Xử lý **đa luồng (Multithreading)** trên cả Server (phục vụ nhiều Client) và Client (vừa gửi vừa nhận dữ liệu, thu/phát Audio & Video đồng thời).
- Truyền dữ liệu đa phương tiện thời gian thực (Microphone PCM + Webcam JPEG).

---

## 2. Giao thức sử dụng

| Giao thức | Cổng mặc định | Chức năng trong Lab 01 | Đặc điểm kỹ thuật |
| :--- | :--- | :--- | :--- |
| **TCP** | `5000` | Đăng nhập, danh sách Online/Nhóm, Chat chung, Chat 1-1, Chat nhóm, Gửi file, Signaling cuộc gọi, Relay Audio/Video | Tin cậy, đúng thứ tự, kiểm soát lỗi, hướng kết nối (dùng `ObjectOutputStream`/`ObjectInputStream` cho `ChatPacket`). |
| **UDP Broadcast** | `5001` | Gửi thông báo tới **mọi máy** lắng nghe trong mạng LAN (`255.255.255.255`) | Phi kết nối, không qua TCP Server, không đảm bảo độ tin cậy nhưng nhanh. |
| **UDP Multicast** | `5002` | Gửi tin đến **chỉ các Client đã tham gia** nhóm Multicast `224.1.1.1` | Phi kết nối, dùng địa chỉ IP lớp D (`224.0.0.0 – 239.255.255.255`). |

---

## 3. Mô hình hoạt động
1. **Mô hình Client/Server qua TCP**:
   - `ChatServer` mở `ServerSocket` tại `0.0.0.0:5000`, chờ `accept()`.
   - Mỗi Client kết nối đến được giao cho 1 worker thread trong `ExecutorService` (`handleClient`).
   - Sau gói `LOGIN` hợp lệ, Client được lưu vào `ConcurrentHashMap<String, ClientSession>` để Server tra cứu định tuyến tin nhắn riêng (`PRIVATE_MSG`), nhóm (`GROUP_MSG`), file (`FILE_TRANSFER`), tín hiệu cuộc gọi (`CALL_*`) và luồng media (`AUDIO_CHUNK`, `VIDEO_FRAME`).
2. **Mô hình ngang hàng phi kết nối qua UDP (Broadcast & Multicast)**:
   - Ngay khi kết nối/chạy Client, Client mở `DatagramSocket` bind cổng `5001` (`SO_REUSEADDR = true`) để nhận Broadcast.
   - Khi người dùng tick **"Tham gia nhóm Multicast"**, Client mở `MulticastSocket` cổng `5002` và gọi `joinGroup(InetSocketAddress("224.1.1.1", 5002), networkInterface)`.
   - Tin nhắn UDP Broadcast/Multicast đi thẳng qua mạng LAN bằng gói `DatagramPacket` mà không bắt buộc đi qua `ChatServer`.

---

## 4. Sơ đồ Client / Server

```text
                       +---------------------------------------+
                       |             ChatServer                |
                       |  ServerSocket TCP :5000               |
                       |  ExecutorService (1 Thread / Client)  |
                       |  Map<username, ClientSession>         |
                       +------+----------------+---------------+
                              |                |
             TCP Stream       |                |   TCP Stream
       (ChatPacket Serialize) |                | (ChatPacket Serialize)
                              v                v
                 +----------------+        +----------------+
                 |  ChatClient A  |        |  ChatClient B  |  ... (6+ Clients)
                 +-------+--------+        +--------+-------+
                         |                          |
                         +--- UDP Broadcast :5001 --+--> (255.255.255.255: tất cả máy)
                         +--- UDP Multicast :5002 --+--> (224.1.1.1: chỉ máy đã joinGroup)
```

---

## 5. Giải thích từng file Java
1. [ChatPacket.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab01_Chat/src/main/java/com/ltm/lab01/ChatPacket.java):
   - Định nghĩa gói dữ liệu chuẩn dùng chung cho Server và Client, implement `java.io.Serializable`.
   - `enum Type`: phân biệt 18 loại gói tin từ `LOGIN`, `PUBLIC_MSG`, `PRIVATE_MSG`, `GROUP_MSG`, `FILE_TRANSFER` đến `CALL_REQUEST`, `AUDIO_CHUNK`, `VIDEO_FRAME`.
   - Trường `byte[] binaryData`: chứa nội dung nhị phân của file đính kèm, mẫu âm thanh PCM hoặc khung hình ảnh JPEG.
2. [ChatServer.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab01_Chat/src/main/java/com/ltm/lab01/ChatServer.java):
   - `ChatServerCore`: Lõi xử lý mạng thuần túy (tách khỏi Swing), quản lý `clients` (`ConcurrentHashMap`), `groups`, chấp nhận nhiều Client bằng `Executors.newCachedThreadPool()`, xử lý ngắt kết nối đột ngột trong khối `finally`.
   - `ClientSession`: Đóng gói `Socket` + `ObjectOutputStream` + `writeLock` để đồng bộ ghi dữ liệu an toàn giữa nhiều luồng.
   - `launchGui()`: Cửa sổ Swing theo dõi IP LAN, danh sách Client đang kết nối, nhóm chat và nhật ký sự kiện thời gian thực.
3. [ChatClient.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab01_Chat/src/main/java/com/ltm/lab01/ChatClient.java):
   - `ChatClientCore`: Kết nối TCP (`connect`), luồng nhận TCP (`tcpReceiveLoop`), Broadcast (`sendBroadcast`, `startBroadcastListener`), Multicast (`joinMulticast`, `leaveMulticast`, `sendMulticast`), truyền file (`sendFile`, `saveReceivedFile`), luồng Audio (`audioWorkerLoop` qua `AudioSystem`), luồng Video (`videoCaptureLoop` qua `Webcam` + `ImageIO`).
   - `launchGui()`: Giao diện Swing 3 vùng (Kênh & Online/Nhóm bên trái; Khung chat + Emoji + nút Gửi File / Gọi Thoại / Gọi Video ở giữa; Nhật ký sự kiện bên phải) và `JDialog` hiển thị cuộc gọi Audio/Video.
4. [ChatLab01Test.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab01_Chat/src/test/java/com/ltm/lab01/ChatLab01Test.java):
   - Bộ kiểm thử JUnit 5 xác minh 6 Client online đồng thời, chat riêng/nhóm, emoji UTF-8, UDP Broadcast, UDP Multicast có lọc người tham gia, truyền file TCP và relay nhị phân Audio/Video.

---

## 6. Giải thích class và method quan trọng
- `ChatServerCore.start()` & `acceptLoop()`: Bind `ServerSocket` với `setReuseAddress(true)` và vòng lặp `accept()` đẩy từng socket vào `clientPool.submit(...)`.
- `ChatServerCore.handleClient(Socket sock)`:
  - Tạo `ObjectOutputStream` và `flush()` **trước** `ObjectInputStream` để hai đầu trao đổi stream header mà không bị treo (deadlock).
  - Xác thực gói `LOGIN` đầu tiên, kiểm tra trùng tên bằng `clients.putIfAbsent(...)`, sau đó chạy `while (running)` đọc gói và gọi `routePacket()`.
  - Khối `finally` dọn sạch session khỏi `clients` và `groups`, đóng socket và gửi `STATE_UPDATE` cập nhật lại danh sách online cho các Client khác.
- `ClientSession.send(ChatPacket)`: Dùng `synchronized (writeLock)` chống đan xen byte khi nhiều luồng gửi cho cùng 1 Client; gọi `out.reset()` sau mỗi gói để xóa cache đối tượng `ObjectOutputStream`, tránh rò rỉ bộ nhớ khi gửi Audio/Video liên tục.
- `ChatClientCore.joinMulticast()`:
  - Mở `DatagramChannel.open(StandardProtocolFamily.INET)` (ép IPv4 thuần), bật `SO_REUSEADDR` trước `bind()` để nhiều Client trên cùng 1 máy Windows đều nhận được gói Multicast.
  - Chọn `NetworkInterface` IPv4 hỗ trợ multicast (`findBestMulticastInterface()`) để gọi `dc.join(groupAddr, ni)`.
- `ChatClientCore.audioPlaybackLoop()`, `audioCaptureLoop()` & `videoCaptureLoop()`:
  - Thu/phát âm thanh PCM `16kHz, 16-bit, Mono` tách riêng 2 luồng (`TargetDataLine` và `SourceDataLine` đọc từ `ArrayBlockingQueue`), tránh làm nghẽn luồng đọc TCP.
  - Thu ảnh thật từ `Webcam.getDefault()` (320×240), nén JPEG bằng `ImageIO.write`, đóng camera an toàn trong khối `finally` bởi chính luồng đã mở nó.

---

## 7. Giải thích luồng dữ liệu

### A. Luồng gửi tin nhắn riêng / nhóm / file (TCP)
1. Client A đóng gói `ChatPacket(PRIVATE_MSG | GROUP_MSG | FILE_TRANSFER)` và gọi `out.writeObject(pkt)` gửi tới Server.
2. Luồng `handleClient` của Client A tại Server đọc gói tin qua `in.readObject()`, gán lại `pkt.sender = username` (trống giả mạo người gửi).
3. `routePacket()` tra cứu `clients.get(pkt.target)` (nếu 1-1) hoặc duyệt tập `groups.get(pkt.groupName)` (nếu nhóm) và đẩy gói tới `ObjectOutputStream` của người nhận.
4. Luồng `tcpReceiveLoop()` bên phía Client B nhận gói tin và gọi callback Swing (`SwingUtilities.invokeLater`) cập nhật khung chat hoặc lưu file vào thư mục `downloads/`.

### B. Luồng Audio & Video thật (Capture → Encode → Transport → Decode → Render)
1. **Capture (Thu dữ liệu phần cứng)**:
   - Audio: `TargetDataLine.read(buf, 0, 1024)` đọc byte âm thanh thô từ Microphone thật (PCM 16kHz, 16-bit, Mono).
   - Video: `Webcam.getDefault().getImage()` chụp `BufferedImage` thực tế 320×240 (~15 FPS) từ Camera.
2. **Encode (Đóng gói / Nén)**:
   - Audio: giữ nguyên mẫu PCM 16-bit (`byte[]`) để độ trễ cực thấp (không tốn CPU giải mã).
   - Video: nén `BufferedImage` thành `byte[]` định dạng **JPEG** bằng `ImageIO.write(frame, "jpg", baos)` nhằm giảm dung lượng khung hình ~25 lần qua mạng.
3. **Transport (Truyền tải)**:
   - Đóng gói vào `ChatPacket(AUDIO_CHUNK | VIDEO_FRAME)` kèm `target = callPeer` và gửi qua TCP `ObjectOutputStream` tới Server để Server chuyển tiếp đúng người nhận (`clients.get(pkt.target).send(pkt)`).
4. **Decode (Giải mã)**:
   - Audio: Client nhận lấy thẳng mảng `byte[]` PCM.
   - Video: Client gọi `ImageIO.read(new ByteArrayInputStream(jpeg))` chuyển mảng byte JPEG thành `BufferedImage`.
5. **Render (Phát / Hiển thị)**:
   - Audio: ghi ra loa bằng `SourceDataLine.write(pcm, 0, pcm.length)`.
   - Video: cập nhật `JLabel.setIcon(new ImageIcon(...))` thông qua `SwingUtilities.invokeLater`.

> **Ghi chú thư viện Video & Dependency**: `com.github.sarxos:webcam-capture:0.3.12` đóng gói sẵn driver BridJ JNI cho Windows x64 bên trong file JAR Maven nên **không cần cài thêm OpenCV/DLL thủ công** trên Windows.

---

## 8. Hướng dẫn chạy
1. Cài đặt **JDK 17+** và **Maven 3.9+** (đã test với JDK `17.0.20.1` và Maven `3.9.9`).
2. Vào thư mục `Lab01_Chat`:
   - Mở Terminal 1 chạy Server: `mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatServer"`
   - Mở Terminal 2..7 chạy 5–6 Client: `mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatClient"`
3. Hoặc chạy Server chế độ không giao diện: `mvn compile exec:java "-Dexec.mainClass=com.ltm.lab01.ChatServer" "-Dexec.args=--nogui"`.

---

## 9. Hướng dẫn kiểm thử
1. Kết nối 6 Client (`SV1`..`SV6`): Quan sát bảng bên trái của `ChatServer` hiển thị đủ 6 dòng `username (IP:port)`.
2. Chat chung & Emoji: Chọn `Chat Chung (TCP)` → bấm chọn emoji `😀 🎉` → gửi → cả 6 Client đều nhận đúng emoji UTF-8.
3. Chat riêng & Chat nhóm: Click chọn `SV2` trong danh sách Online để gửi tin riêng 1-1; tạo nhóm `NhomJava`, cho `SV1` và `SV4` tham gia rồi nhắn tin → chỉ thành viên trong nhóm thấy.
4. UDP Broadcast & UDP Multicast:
   - Chọn `UDP Broadcast (LAN)` → gửi → mọi Client đều nhận (kể cả khi chưa tham gia Multicast).
   - Cho `SV1`, `SV2` tick `Tham gia nhóm Multicast`, `SV3` không tick → `SV1` gửi ở kênh `UDP Multicast` → `SV2` nhận được, `SV3` không nhận.
5. Gửi file: Bấm `Gửi File` chọn một file ảnh/PDF/TXT → Client nhận tự lưu về `downloads/<ten>_<file>` và kiểm tra mở file đọc được toàn vẹn.
6. Gọi Audio/Video: Chọn 1 người trong danh sách Online → bấm `Gọi Thoại` hoặc `Gọi Video` → bên kia bấm `Chấp nhận` → kiểm tra âm thanh nói vào mic phát ra loa và khung hình Webcam hiển thị.

---

## 10. Lỗi thường gặp & Cách xử lý

| Lỗi | Nguyên nhân | Cách khắc phục |
| :--- | :--- | :--- |
| Treo ngay lúc `connect()` ở `new ObjectInputStream(...)` | Cả Client và Server đều tạo `ObjectInputStream` trước `ObjectOutputStream` | Luôn tạo `new ObjectOutputStream(...)` và `flush()` trước khi tạo `ObjectInputStream`. |
| `WebcamLockException` khi mở 2 Client gọi Video trên cùng 1 máy | Một Webcam vật lý chỉ cho 1 tiến trình giữ lock tại 1 thời điểm | Đưa 2 Client lên 2 máy khác nhau trong LAN (hoặc dùng thêm Webcam USB thứ 2). |
| Gửi UDP Multicast không nhận được trên Windows có VPN / VMware | `MulticastSocket` mặc định chọn nhầm card mạng ảo | Code đã lọc `findBestMulticastInterface()` chọn card IPv4 đang bật, hỗ trợ Multicast và không phải loopback; tắt adapter VPN nếu vẫn chặn. |
| `BindException: Address already in use` ở cổng UDP 5001/5002 | Mở Client không bật `setReuseAddress(true)` hoặc chương trình cũ chưa tắt | Luôn gọi `setReuseAddress(true)` trước `bind()` (đã tích hợp sẵn). |
| Lỗi Font ô vuông thay cho Emoji | Dùng font cũ không có glyph Emoji | Giao diện đã set `Font("Segoe UI Emoji", ...)` trên Windows 10/11. |

---

## 11. Ngân hàng 16 câu hỏi bảo vệ (Kèm câu hỏi mở file code trực tiếp)

1. **Socket là gì?**
   - *Trả lời*: Socket là điểm cuối (endpoint) cho giao tiếp hai chiều giữa hai chương trình qua mạng, được xác định bởi cặp `(Địa chỉ IP, Số hiệu Port)`. Ví dụ: trong [ChatClient.java](file:///c:/Skills-Stitch/LTM_5_LABS/Lab01_Chat/src/main/java/com/ltm/lab01/ChatClient.java), `new Socket()` kết nối tới `192.168.1.12:5000`.
2. **Địa chỉ IP là gì?**
   - *Trả lời*: IP (Internet Protocol) là địa chỉ logic dùng để định danh duy nhất một thiết bị (hoặc card mạng) trong mạng LAN/Internet. Ví dụ: `127.0.0.1` là loopback nội bộ máy mình, `192.168.1.12` là IP LAN nội bộ.
3. **Port (Cổng) là gì? Tại sao cần Port khi đã có IP?**
   - *Trả lời*: Port là số nguyên 16-bit (`0–65535`) định danh tiến trình/dịch vụ cụ thể trên cùng một máy tính. Một máy chỉ có 1 IP nhưng chạy nhiều ứng dụng (Chat port `5000`, Broadcast `5001`, Multicast `5002`).
4. **TCP là gì? Điểm mạnh/yếu khi dùng cho ứng dụng Chat?**
   - *Trả lời*: TCP là giao thức truyền vận hướng kết nối (3-way handshake), đảm bảo dữ liệu đến đủ, đúng thứ tự và kiểm soát tắc nghẽn. Ưu điểm: gửi chữ và file không mất gói; nhược điểm: overhead cao hơn UDP nếu gửi media thời gian thực lúc mạng yếu.
5. **UDP là gì? Tại sao không dùng luôn TCP cho Broadcast/Multicast?**
   - *Trả lời*: UDP là giao thức phi kết nối, gửi từng gói độc lập (`DatagramPacket`) không cần bắt tay hay xác nhận ACK. TCP bắt buộc kết nối 1-1 nên về mặt giao thức TCP **không hỗ trợ Broadcast và Multicast**, chỉ UDP làm được.
6. **Phân biệt UDP Broadcast và UDP Multicast?**
   - *Trả lời*: Broadcast gửi tới **tất cả** các máy trong cùng subnet (địa chỉ `255.255.255.255`), máy không quan tâm cũng phải nhận gói ở tầng card mạng. Multicast gửi tới **địa chỉ nhóm lớp D** (ví dụ `224.1.1.1`), chỉ máy nào đã gọi `joinGroup()` mới nhận.
7. **Thread và ExecutorService là gì? Nếu không dùng Thread thì `ChatServer` gặp lỗi gì?**
   - *Trả lời*: Thread là luồng thực thi độc lập trong tiến trình; `ExecutorService` quản lý nhóm thread tái sử dụng hiệu quả. Nếu không dùng Thread, vòng lặp `serverSocket.accept()` và `in.readObject()` của Client A sẽ block toàn bộ Server, khiến Client B, C... không thể đăng nhập hoặc nhắn tin cho tới khi Client A thoát.
8. **Tại sao phải dùng `SwingUtilities.invokeLater(...)` khi nhận tin nhắn mạng?**
   - *Trả lời*: Java Swing **không thread-safe**; luồng đọc mạng (`TCP-Client-Recv`, `UDP-Broadcast-Recv`) chạy nền, còn mọi thao tác vẽ/cập nhật `JTextArea`, `JList`, `JLabel` bắt buộc phải thực thi trên luồng sự kiện EDT (Event Dispatch Thread).
9. *(Giảng viên mở file `ChatServer.java` dòng 190–192)* **Tại sao bắt buộc tạo `ObjectOutputStream` và gọi `flush()` TRƯỚC `ObjectInputStream`?**
   - *Trả lời*: Khi khởi tạo `ObjectInputStream`, Java chờ đọc 4 byte stream header từ đầu bên kia gửi sang. Nếu cả Server lẫn Client đều tạo `ObjectInputStream` trước, cả hai cùng đứng đợi nhau dẫn tới **deadlock**. Tạo `ObjectOutputStream` + `flush()` trước sẽ đẩy header đi ngay để đầu kia khởi tạo xong.
10. *(Giảng viên mở file `ChatServer.java` dòng 63–74)* **Lệnh `out.reset()` dùng để làm gì? Bỏ đi có chạy được không?**
    - *Trả lời*: `ObjectOutputStream` lưu bảng tham chiếu các object đã gửi. Nếu gọi Video/Audio 15–30 lần/giây mà không gọi `out.reset()`, bảng cache trong JVM phình to liên tục dẫn tới `OutOfMemoryError` và bên nhận có thể nhận object cũ bị cache.
11. *(Giảng viên mở file `ChatServer.java` dòng 240)* **Tại sao Server gán lại `pkt.sender = username` thay vì tin hoàn toàn gói Client gửi lên?**
    - *Trả lời*: Để chống giả mạo danh tính (spoofing). Client dù cố tình sửa field `pkt.sender` thành tên người khác thì Server vẫn lấy đúng `username` đã xác thực gắn với socket đó.
12. *(Giảng viên mở file `ChatClient.java` phương thức `startBroadcastListener` & `joinMulticast`)* **Cài đặt `SO_REUSEADDR = true` trước `bind()` giải quyết vấn đề gì?**
    - *Trả lời*: Cho phép nhiều tiến trình Client chạy trên **cùng một máy tính** Windows cùng bind vào chung cổng UDP `5001` (Broadcast) hoặc `5002` (Multicast) để test demo 1 máy nhận tin đồng thời mà không bị lỗi `BindException`.
13. *(Giảng viên mở file `ChatClient.java` phương thức `joinMulticast`, `sendMulticast`, `findBestMulticastInterface`)* **Tại sao Multicast dùng `DatagramChannel.open(StandardProtocolFamily.INET)` và lọc `NetworkInterface`?**
    - *Trả lời*: Trên Windows với JDK 17, `new MulticastSocket()` mặc định tạo dual-stack socket (IPv6+IPv4); nếu máy chưa truyền `-Djava.net.preferIPv4Stack=true`, gọi `setNetworkInterface` cho card Wi-Fi IPv4 có thể văng `Invalid argument: setsockopt`. Dùng `DatagramChannel.open(StandardProtocolFamily.INET)` ép socket IPv4 thuần ngay trong code và kết hợp `findBestMulticastInterface()` tránh gửi nhầm vào card mạng ảo (VPN/VMware).
14. *(Giảng viên mở file `ChatClient.java` dòng 603–642)* **Video Call trong code đang truyền dữ liệu như thế nào? Có phải video streaming chuyên dụng (RTP/RTSP) không?**
    - *Trả lời*: Không, để phù hợp phạm vi môn LTM bằng Java thuần + `webcam-capture`, Client chụp `BufferedImage` thực từ Webcam (~15 FPS), nén JPEG bằng `ImageIO.write`, rồi gửi mảng `byte[]` qua TCP Server relay sang đối phương để `ImageIO.read` và vẽ lên `JLabel`.
15. *(Giảng viên mở file `ChatServer.java` dòng 248–257)* **Khi Client đột ngột tắt cửa sổ hoặc rớt mạng, Server xử lý tài nguyên như thế nào?**
    - *Trả lời*: Đọc socket văng `EOFException`/`SocketException`, luồng thoát `while (running)` và luôn đi vào khối `finally` để xóa user khỏi `ConcurrentHashMap clients`, gỡ khỏi các nhóm, đóng `sock.close()`, và gửi `STATE_UPDATE` thông báo danh sách Online mới cho các Client còn lại.
16. *(Giảng viên mở file `ChatPacket.java`)* **Tại sao `ChatPacket` có `private static final long serialVersionUID = 1L;`?**
    - *Trả lời*: Để đảm bảo khả năng tương thích phiên bản lớp khi `ObjectInputStream` deserialize gói tin giữa hai máy chạy Server và Client. Nếu thiếu, hai bản build hơi khác nhau có thể ném `InvalidClassException`.
