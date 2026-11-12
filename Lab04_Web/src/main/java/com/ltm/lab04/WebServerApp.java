package com.ltm.lab04;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lab 04 — HTTP Web Server thực thụ dùng {@link com.sun.net.httpserver.HttpServer}:
 * - Phục vụ tài nguyên tĩnh trong thư mục {@code www/} (Trang chủ HTML, PDF, MP3, MP4).
 * - Hỗ trợ đầy đủ 3 phương thức GET, HEAD (chỉ trả Header, Body 0 byte), POST (/api/submit).
 * - Hỗ trợ HTTP Byte-Range (206 Partial Content) để trình phát JavaFX Media tua MP3/MP4 qua HTTP.
 */
public class WebServerApp {

    public static final int DEFAULT_PORT = 8080;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FULL_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String getLocalLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    public static class RequestLogEntry {
        public final String time;
        public final String clientIp;
        public final String method;
        public final String path;
        public final int status;
        public final long bytesSent;

        public RequestLogEntry(String time, String clientIp, String method, String path, int status, long bytesSent) {
            this.time = time;
            this.clientIp = clientIp;
            this.method = method;
            this.path = path;
            this.status = status;
            this.bytesSent = bytesSent;
        }
    }

    /** Lõi HTTP Web Server tách khỏi Swing UI để kiểm thử tự động dễ dàng. */
    public static class HttpWebServerCore {
        private final int requestedPort;
        private final Path wwwDir;
        private HttpServer server;
        private ExecutorService executor;
        private volatile boolean running = false;

        private Consumer<String> textLogListener;
        private Consumer<RequestLogEntry> requestLogListener;

        public HttpWebServerCore(int port, Path wwwDir) {
            this.requestedPort = port;
            this.wwwDir = wwwDir.toAbsolutePath().normalize();
        }

        public void setTextLogListener(Consumer<String> listener) {
            this.textLogListener = listener;
        }

        public void setRequestLogListener(Consumer<RequestLogEntry> listener) {
            this.requestLogListener = listener;
        }

        public Path getWwwDir() {
            return wwwDir;
        }

        public int getPort() {
            return server != null ? server.getAddress().getPort() : requestedPort;
        }

        public boolean isRunning() {
            return running;
        }

        private void logText(String msg) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
            if (textLogListener != null) textLogListener.accept(line);
            else System.out.println(line);
        }

        private void emitReqLog(HttpExchange ex, String path, int status, long bytes) {
            if (requestLogListener != null) {
                String ip = ex.getRemoteAddress() != null ? ex.getRemoteAddress().getAddress().getHostAddress() : "?";
                requestLogListener.accept(new RequestLogEntry(
                        LocalTime.now().format(TIME_FMT),
                        ip,
                        ex.getRequestMethod(),
                        path,
                        status,
                        bytes
                ));
            }
        }

        public synchronized void start() throws IOException {
            if (running) return;
            Files.createDirectories(wwwDir);
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", requestedPort), 64);
            executor = Executors.newFixedThreadPool(16);
            server.setExecutor(executor);
            server.createContext("/", this::handleRequest);
            server.start();
            running = true;
            logText("Web Server đã khởi chạy tại http://" + getLocalLanIp() + ":" + getPort()
                    + " | Thư mục www: " + wwwDir);
        }

        public synchronized void stop() {
            if (!running) return;
            running = false;
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            logText("Web Server đã dừng.");
        }

        private void handleRequest(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            String rawPath = ex.getRequestURI().getPath();
            if (rawPath == null || rawPath.isEmpty()) rawPath = "/";

            try {
                if ("/api/submit".equals(rawPath)) {
                    handleApiSubmit(ex, method);
                    return;
                }
                handleStaticFile(ex, method, rawPath);
            } catch (Exception e) {
                // Bỏ qua lỗi broken pipe khi trình phát media ngắt kết nối hoặc lúc server đang dừng
                if (!running) return;
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                if (!msg.contains("connection reset") && !msg.contains("broken pipe") && !msg.contains("abort")) {
                    logText("[HTTP ERROR] " + method + " " + rawPath + ": " + e.getMessage());
                }
            } finally {
                ex.close();
            }
        }

        private void handleApiSubmit(HttpExchange ex, String method) throws IOException {
            Headers respHeaders = ex.getResponseHeaders();
            respHeaders.set("Server", "LTM-Lab04-Java-HttpServer/1.0");
            respHeaders.set("Content-Type", "application/json; charset=utf-8");

            if ("HEAD".equals(method)) {
                byte[] sample = buildApiJson("HEAD", "(no-body)").getBytes(StandardCharsets.UTF_8);
                respHeaders.set("X-Resource-Length", String.valueOf(sample.length));
                ex.sendResponseHeaders(200, -1);
                emitReqLog(ex, "/api/submit", 200, 0);
                logText("HEAD /api/submit -> 200 OK (0 body bytes)");
                return;
            }

            if ("GET".equals(method)) {
                byte[] out = buildApiJson("GET", "Gửi HTTP POST tới /api/submit kèm dữ liệu body để kiểm thử.")
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
                emitReqLog(ex, "/api/submit", 200, out.length);
                logText("GET /api/submit -> 200 OK (" + out.length + " bytes)");
                return;
            }

            if ("POST".equals(method)) {
                byte[] reqBytes = ex.getRequestBody().readAllBytes();
                String bodyStr = new String(reqBytes, StandardCharsets.UTF_8);
                byte[] out = buildApiJson("POST", bodyStr).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
                emitReqLog(ex, "/api/submit", 200, out.length);
                logText("POST /api/submit <- Payload (" + reqBytes.length + " bytes): " + bodyStr);
                return;
            }

            byte[] err = "{\"error\":\"Method Not Allowed\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(405, err.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(err);
            }
            emitReqLog(ex, "/api/submit", 405, err.length);
        }

        private String buildApiJson(String method, String payload) {
            String esc = payload.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", "").replace("\n", "\\n");
            return "{\n"
                    + "  \"status\": \"OK\",\n"
                    + "  \"method\": \"" + method + "\",\n"
                    + "  \"serverTime\": \"" + LocalDateTime.now().format(FULL_TS) + "\",\n"
                    + "  \"receivedPayload\": \"" + esc + "\"\n"
                    + "}";
        }

        private void handleStaticFile(HttpExchange ex, String method, String rawPath) throws IOException {
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
                sendSimpleText(ex, rawPath, 405, "Method Not Allowed", "HEAD".equals(method));
                return;
            }

            if (rawPath.contains("\\")) {
                sendSimpleText(ex, rawPath, 403, "403 Forbidden: Đường dẫn không hợp lệ.", "HEAD".equals(method));
                return;
            }
            String rel = "/".equals(rawPath) ? "index.html" : rawPath.replaceFirst("^/+", "");
            Path target = wwwDir.resolve(rel).normalize();

            // Ngăn chặn Path Traversal (vd: ../../etc/passwd)
            if (!target.startsWith(wwwDir)) {
                sendSimpleText(ex, rawPath, 403, "403 Forbidden: Truy cập ngoài thư mục www bị từ chối.", "HEAD".equals(method));
                return;
            }

            if (!Files.exists(target) || Files.isDirectory(target)) {
                sendSimpleText(ex, rawPath, 404, "404 Not Found: Không tìm thấy " + rawPath + " trong thư mục www/", "HEAD".equals(method));
                return;
            }

            long fileSize = Files.size(target);
            String mime = guessMimeType(target.getFileName().toString());
            Headers headers = ex.getResponseHeaders();
            headers.set("Server", "LTM-Lab04-Java-HttpServer/1.0");
            headers.set("Content-Type", mime);
            headers.set("Accept-Ranges", "bytes");
            headers.set("X-Resource-Length", String.valueOf(fileSize));
            headers.set("Cache-Control", "no-cache");

            // HTTP HEAD: Chỉ gửi Status Code + Headers, KHÔNG gửi Body (độ dài body truyền -1)
            if ("HEAD".equals(method)) {
                headers.set("Content-Length", String.valueOf(fileSize));
                ex.sendResponseHeaders(200, -1);
                emitReqLog(ex, rawPath, 200, 0);
                logText("HEAD " + rawPath + " -> 200 OK | Content-Type=" + mime + " | X-Resource-Length=" + fileSize + " (0 body bytes)");
                return;
            }

            // Hỗ trợ HTTP Range (206 Partial Content) cho trình phát video MP4 / MP3 tua nhanh
            String rangeHeader = ex.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileSize > 0) {
                long start = 0;
                long end = fileSize - 1;
                String spec = rangeHeader.substring("bytes=".length()).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String sPart = spec.substring(0, dash).trim();
                    String ePart = spec.substring(dash + 1).trim();
                    if (!sPart.isEmpty()) start = Long.parseLong(sPart);
                    if (!ePart.isEmpty()) end = Math.min(Long.parseLong(ePart), fileSize - 1);
                }
                if (start > end || start < 0 || start >= fileSize) {
                    headers.set("Content-Range", "bytes */" + fileSize);
                    ex.sendResponseHeaders(416, -1);
                    emitReqLog(ex, rawPath, 416, 0);
                    return;
                }
                long chunkLen = end - start + 1;
                headers.set("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
                ex.sendResponseHeaders(206, chunkLen);
                streamFileRange(target, start, chunkLen, ex.getResponseBody());
                emitReqLog(ex, rawPath, 206, chunkLen);
                logText(method + " " + rawPath + " -> 206 Partial Content (" + start + "-" + end + "/" + fileSize + ")");
                return;
            }

            // HTTP GET / POST tệp tĩnh -> 200 OK kèm toàn bộ bytes của tệp
            ex.sendResponseHeaders(200, fileSize);
            try (InputStream in = Files.newInputStream(target); OutputStream os = ex.getResponseBody()) {
                in.transferTo(os);
            }
            emitReqLog(ex, rawPath, 200, fileSize);
            logText(method + " " + rawPath + " -> 200 OK (" + fileSize + " bytes | " + mime + ")");
        }

        private void streamFileRange(Path file, long offset, long length, OutputStream os) throws IOException {
            byte[] buf = new byte[16384];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(offset);
                long remaining = length;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = raf.read(buf, 0, toRead);
                    if (n < 0) break;
                    os.write(buf, 0, n);
                    remaining -= n;
                }
            }
        }

        private void sendSimpleText(HttpExchange ex, String rawPath, int status, String text, boolean headOnly) throws IOException {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Server", "LTM-Lab04-Java-HttpServer/1.0");
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            if (headOnly) {
                ex.sendResponseHeaders(status, -1);
                emitReqLog(ex, rawPath, status, 0);
            } else {
                ex.sendResponseHeaders(status, data.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(data);
                }
                emitReqLog(ex, rawPath, status, data.length);
            }
            logText(ex.getRequestMethod() + " " + rawPath + " -> " + status);
        }
    }

    // =========================================================================
    // GIAO DIỆN SWING QUẢN TRỊ HTTP WEB SERVER
    // =========================================================================
    private static void styleBtn(JButton b, Color bg) {
        b.setUI(new BasicButtonUI());
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
    }

    static Path resolveLabDir() {
        try {
            var cs = WebServerApp.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path cur = Paths.get(cs.getLocation().toURI()).toAbsolutePath().normalize();
                for (int i = 0; i < 4 && cur != null; i++) {
                    if (Files.isRegularFile(cur.resolve("pom.xml"))) return cur;
                    cur = cur.getParent();
                }
            }
        } catch (Exception ignored) {
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("pom.xml"))) return cwd;
        Path nested = cwd.resolve("LTM_5_LABS").resolve("Lab04_Web");
        if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        Path sibling = cwd.resolve("Lab04_Web");
        if (Files.isRegularFile(sibling.resolve("pom.xml"))) return sibling;
        return cwd;
    }

    public static void main(String[] args) {
        Path wwwPath = resolveLabDir().resolve("www");
        SwingUtilities.invokeLater(() -> launchGui(DEFAULT_PORT, wwwPath));
    }

    public static void launchGui(int defaultPort, Path wwwDir) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 04 — Java HTTP Web Server (HttpServer + thư mục www/)");
        frame.setSize(1060, 660);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        // Thanh điều khiển trên cùng
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JLabel lblIp = new JLabel("IP LAN: " + getLocalLanIp());
        lblIp.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblIp.setForeground(new Color(15, 118, 110));

        JTextField txtPort = new JTextField(String.valueOf(defaultPort), 5);
        JButton btnToggle = new JButton("Khởi chạy Web Server");
        styleBtn(btnToggle, new Color(37, 99, 235));
        JButton btnRefreshFiles = new JButton("Làm mới thư mục www/");
        styleBtn(btnRefreshFiles, new Color(15, 118, 110));
        JLabel lblStatus = new JLabel("● Đã dừng");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblStatus.setForeground(new Color(220, 38, 38));

        topBar.add(lblIp);
        topBar.add(new JLabel("Port:"));
        topBar.add(txtPort);
        topBar.add(btnToggle);
        topBar.add(btnRefreshFiles);
        topBar.add(lblStatus);
        root.add(topBar, BorderLayout.NORTH);

        // Bảng file tĩnh trong www/
        DefaultTableModel fileModel = new DefaultTableModel(new String[]{"Tên tệp (www/)", "Đường dẫn HTTP", "Kích thước (bytes)", "MIME Content-Type"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblFiles = new JTable(fileModel);
        tblFiles.setRowHeight(22);
        JScrollPane spFiles = new JScrollPane(tblFiles);
        spFiles.setBorder(new TitledBorder("Tài nguyên đang phục vụ trong thư mục www/ (" + wwwDir.toAbsolutePath().normalize() + ")"));
        spFiles.setPreferredSize(new Dimension(0, 180));

        // Bảng nhật ký request HTTP
        DefaultTableModel reqModel = new DefaultTableModel(new String[]{"Thời gian", "Client IP", "Method", "URL Path", "HTTP Status", "Body Bytes"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblReqs = new JTable(reqModel);
        tblReqs.setRowHeight(22);
        JScrollPane spReqs = new JScrollPane(tblReqs);
        spReqs.setBorder(new TitledBorder("Lịch sử Request HTTP đến Server"));

        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(226, 232, 240));
        JScrollPane spLog = new JScrollPane(txtLog);
        spLog.setBorder(new TitledBorder("Console Log Server (GET / HEAD / POST / 206 Range)"));
        spLog.setPreferredSize(new Dimension(0, 170));

        JSplitPane midSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spFiles, spReqs);
        midSplit.setDividerLocation(180);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, midSplit, spLog);
        mainSplit.setDividerLocation(390);
        root.add(mainSplit, BorderLayout.CENTER);

        final HttpWebServerCore[] srvRef = {null};

        Runnable loadWwwTable = () -> {
            fileModel.setRowCount(0);
            try {
                Files.createDirectories(wwwDir);
                int activePort = srvRef[0] != null && srvRef[0].isRunning() ? srvRef[0].getPort() : defaultPort;
                try (var stream = Files.list(wwwDir)) {
                    List<Path> sorted = stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                    for (Path p : sorted) {
                        String name = p.getFileName().toString();
                        fileModel.addRow(new Object[]{
                                name,
                                "http://127.0.0.1:" + activePort + "/" + name,
                                Files.size(p),
                                guessMimeType(name)
                        });
                    }
                }
            } catch (IOException ex) {
                txtLog.append("Lỗi đọc thư mục www/: " + ex.getMessage() + "\n");
            }
        };

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });

        Consumer<RequestLogEntry> appendReq = r -> SwingUtilities.invokeLater(() -> {
            reqModel.insertRow(0, new Object[]{r.time, r.clientIp, r.method, r.path, r.status, r.bytesSent});
            if (reqModel.getRowCount() > 250) reqModel.removeRow(250);
        });

        Runnable startAct = () -> {
            try {
                int port = Integer.parseInt(txtPort.getText().trim());
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Cổng (Port) phải nằm trong khoảng 1–65535.");
                }
                HttpWebServerCore core = new HttpWebServerCore(port, wwwDir);
                core.setTextLogListener(appendLog);
                core.setRequestLogListener(appendReq);
                core.start();
                srvRef[0] = core;

                btnToggle.setText("Dừng Web Server");
                btnToggle.setBackground(new Color(220, 38, 38));
                lblStatus.setText("● Đang chạy (Port " + core.getPort() + ")");
                lblStatus.setForeground(new Color(5, 150, 105));
                txtPort.setEnabled(false);
                loadWwwTable.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi khởi chạy Web Server: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnToggle.addActionListener(e -> {
            if (srvRef[0] == null || !srvRef[0].isRunning()) {
                startAct.run();
            } else {
                srvRef[0].stop();
                btnToggle.setText("Khởi chạy Web Server");
                btnToggle.setBackground(new Color(37, 99, 235));
                lblStatus.setText("● Đã dừng");
                lblStatus.setForeground(new Color(220, 38, 38));
                txtPort.setEnabled(true);
            }
        });
        btnRefreshFiles.addActionListener(e -> loadWwwTable.run());

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (srvRef[0] != null) srvRef[0].stop();
            }
        });
        frame.setVisible(true);
        startAct.run();
    }
}
