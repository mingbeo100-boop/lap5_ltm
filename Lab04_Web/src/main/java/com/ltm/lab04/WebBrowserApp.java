package com.ltm.lab04;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lab 04 — Java Swing Web Browser dùng {@link java.net.http.HttpClient}:
 * - Gửi request HTTP thực sự: GET, HEAD (chỉ header, body 0 byte), POST (gửi body).
 * - Điều hướng Back, Forward, Refresh, History (lưu SQLite), Download tệp.
 * - Hiển thị Status Code & Response Headers, xem HTML, đọc PDF (PDFBox), nghe MP3 / xem MP4 (JavaFX Media).
 */
public class WebBrowserApp {

    public static final String DEFAULT_HOME_URL = "http://127.0.0.1:" + WebServerApp.DEFAULT_PORT + "/index.html";

    /** Kết quả một lần gọi HTTP GET / HEAD / POST. */
    public static class HttpResult {
        public final String url;
        public final String method;
        public final int statusCode;
        public final Map<String, List<String>> headers;
        public final String contentType;
        public final long declaredLength;
        public final byte[] body;
        public final long elapsedMs;

        public HttpResult(String url, String method, int statusCode,
                          Map<String, List<String>> headers, String contentType,
                          long declaredLength, byte[] body, long elapsedMs) {
            this.url = url;
            this.method = method;
            this.statusCode = statusCode;
            this.headers = headers;
            this.contentType = contentType;
            this.declaredLength = declaredLength;
            this.body = body;
            this.elapsedMs = elapsedMs;
        }
    }

    /** Lõi HTTP Client + SQLite tách khỏi giao diện Swing. */
    public static class BrowserHttpClientCore {
        private final HttpClient httpClient;
        private final WebDatabase db;

        public BrowserHttpClientCore(Path dbPath) throws SQLException {
            this.db = new WebDatabase(dbPath);
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        public WebDatabase getDatabase() {
            return db;
        }

        public static String normalizeUrl(String rawUrl) {
            String u = rawUrl == null ? "" : rawUrl.trim();
            if (u.isEmpty()) return DEFAULT_HOME_URL;
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                return "http://" + u;
            }
            return u;
        }

        /** Thực hiện HTTP request (GET, HEAD hoặc POST) và ghi lịch sử vào SQLite. */
        public HttpResult execute(String rawUrl, String method, String postBody, boolean saveHistory)
                throws IOException, InterruptedException, SQLException {
            String url = normalizeUrl(rawUrl);
            String m = method.toUpperCase(Locale.ROOT);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20));

            if ("HEAD".equals(m)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else if ("POST".equals(m)) {
                String payload = postBody == null ? "" : postBody;
                builder.header("Content-Type", "application/json; charset=utf-8");
                builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            } else {
                m = "GET";
                builder.GET();
            }

            long t0 = System.nanoTime();
            HttpResponse<byte[]> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - t0) / 1_000_000L;

            Map<String, List<String>> hdrs = resp.headers().map();
            String contentType = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            long declaredLen = resp.headers().firstValueAsLong("Content-Length")
                    .orElseGet(() -> resp.headers().firstValueAsLong("X-Resource-Length")
                            .orElse(resp.body() == null ? 0L : resp.body().length));
            byte[] body = resp.body() == null ? new byte[0] : resp.body();

            if (saveHistory) {
                db.recordHistory(url, m, resp.statusCode(), contentType,
                        "HEAD".equals(m) ? 0L : body.length);
            }

            return new HttpResult(url, m, resp.statusCode(), hdrs, contentType, declaredLen, body, ms);
        }
    }

    // =========================================================================
    // GIAO DIỆN TRÌNH DUYỆT JAVA SWING + PDFBOX + JAVAFX MEDIA PLAYER
    // =========================================================================
    private static void styleBtn(JButton b, java.awt.Color bg) {
        b.setUI(new BasicButtonUI());
        b.setBackground(bg);
        b.setForeground(java.awt.Color.WHITE);
        b.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 11, 6, 11));
    }

    private static String formatTimeSec(double sec) {
        if (Double.isNaN(sec) || sec < 0) sec = 0;
        int total = (int) Math.round(sec);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static String resolveBasePrefix(String currentText, String fallbackBase) {
        try {
            URI u = URI.create(BrowserHttpClientCore.normalizeUrl(currentText));
            if (u.getScheme() != null && u.getAuthority() != null) {
                return u.getScheme() + "://" + u.getAuthority();
            }
        } catch (Exception ignored) {
        }
        return fallbackBase;
    }

    static String suggestDownloadFilename(String rawUrl) {
        try {
            String path = URI.create(BrowserHttpClientCore.normalizeUrl(rawUrl)).getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                String safe = last.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                if (!safe.isEmpty()) return safe;
            }
        } catch (Exception ignored) {
        }
        return "downloaded.html";
    }

    public static void main(String[] args) {
        Path labDir = WebServerApp.resolveLabDir();
        SwingUtilities.invokeLater(() -> launchGui(
                DEFAULT_HOME_URL,
                labDir.resolve("data").resolve("browser_lab04.db"),
                labDir.resolve("downloads")
        ));
    }

    public static void launchGui(String initialUrl, Path dbPath, Path downloadDir) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final BrowserHttpClientCore core;
        try {
            core = new BrowserHttpClientCore(dbPath);
            Files.createDirectories(downloadDir);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Lỗi khởi tạo Browser SQLite: " + e.getMessage());
            return;
        }

        JFrame frame = new JFrame("Lab 04 — Java Swing Web Browser (HttpClient + SQLite + PDF/MP3/MP4)");
        frame.setSize(1200, 780);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(6, 8, 8, 8));
        root.setBackground(new java.awt.Color(241, 245, 249));
        frame.setContentPane(root);

        // 1. THANH ĐỊA CHỈ & PHƯƠNG THỨC HTTP (TOP PANEL)
        JPanel northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));
        northContainer.setBackground(java.awt.Color.WHITE);
        northContainer.setBorder(BorderFactory.createLineBorder(new java.awt.Color(203, 213, 225)));

        JPanel navRow = new JPanel(new BorderLayout(6, 4));
        navRow.setBackground(java.awt.Color.WHITE);
        navRow.setBorder(new EmptyBorder(6, 8, 4, 8));

        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        navLeft.setBackground(java.awt.Color.WHITE);
        JButton btnBack = new JButton("← Back");
        JButton btnForward = new JButton("Forward →");
        JButton btnRefresh = new JButton("↻ Refresh");
        JButton btnHome = new JButton("🏠 Home");
        styleBtn(btnBack, new java.awt.Color(71, 85, 105));
        styleBtn(btnForward, new java.awt.Color(71, 85, 105));
        styleBtn(btnRefresh, new java.awt.Color(14, 116, 144));
        styleBtn(btnHome, new java.awt.Color(15, 118, 110));
        btnBack.setEnabled(false);
        btnForward.setEnabled(false);
        navLeft.add(btnBack);
        navLeft.add(btnForward);
        navLeft.add(btnRefresh);
        navLeft.add(btnHome);

        JTextField txtUrl = new JTextField(initialUrl);
        txtUrl.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13));

        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        navRight.setBackground(java.awt.Color.WHITE);
        JButton btnGet = new JButton("GET");
        JButton btnHead = new JButton("HEAD");
        JButton btnPost = new JButton("POST");
        JButton btnDownload = new JButton("Download");
        JButton btnHistoryTab = new JButton("History");
        styleBtn(btnGet, new java.awt.Color(37, 99, 235));
        styleBtn(btnHead, new java.awt.Color(13, 148, 136));
        styleBtn(btnPost, new java.awt.Color(124, 58, 237));
        styleBtn(btnDownload, new java.awt.Color(5, 150, 105));
        styleBtn(btnHistoryTab, new java.awt.Color(51, 65, 85));
        navRight.add(btnGet);
        navRight.add(btnHead);
        navRight.add(btnPost);
        navRight.add(btnDownload);
        navRight.add(btnHistoryTab);

        navRow.add(navLeft, BorderLayout.WEST);
        navRow.add(txtUrl, BorderLayout.CENTER);
        navRow.add(navRight, BorderLayout.EAST);

        // Dòng 2: Nút tắt kiểm thử dịch vụ & ô nhập POST Body
        JPanel quickRow = new JPanel(new BorderLayout(8, 4));
        quickRow.setBackground(new java.awt.Color(248, 250, 252));
        quickRow.setBorder(new EmptyBorder(4, 8, 6, 8));

        JPanel quickLinks = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        quickLinks.setBackground(new java.awt.Color(248, 250, 252));
        quickLinks.add(new JLabel("Mở nhanh:"));
        JButton btnQHtml = new JButton("Trang chủ");
        JButton btnQAbout = new JButton("Giới thiệu");
        JButton btnQPdf = new JButton("Xem PDF");
        JButton btnQMp3 = new JButton("Nghe MP3");
        JButton btnQMp4 = new JButton("Xem MP4");
        JButton btnQApi = new JButton("URL POST API");
        for (JButton qb : new JButton[]{btnQHtml, btnQAbout, btnQPdf, btnQMp3, btnQMp4, btnQApi}) {
            qb.setMargin(new Insets(2, 6, 2, 6));
            quickLinks.add(qb);
        }

        JPanel postPanel = new JPanel(new BorderLayout(4, 0));
        postPanel.setBackground(new java.awt.Color(248, 250, 252));
        postPanel.add(new JLabel("POST Body:"), BorderLayout.WEST);
        JTextField txtPostBody = new JTextField("{\"sinhVien\":\"Nguyen Van A\",\"lab\":4,\"mon\":\"Lap trinh mang\"}", 26);
        postPanel.add(txtPostBody, BorderLayout.CENTER);

        quickRow.add(quickLinks, BorderLayout.WEST);
        quickRow.add(postPanel, BorderLayout.CENTER);

        // Dòng trạng thái HTTP Status Code + tóm tắt Header
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 4));
        statusBar.setBackground(new java.awt.Color(226, 232, 240));
        JLabel lblHttpStatus = new JLabel("Sẵn sàng — Hãy bật WebServerApp trước rồi nhấn GET / Enter.");
        lblHttpStatus.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 12));
        JLabel lblMetaInfo = new JLabel("");
        lblMetaInfo.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 12));
        statusBar.add(lblHttpStatus);
        statusBar.add(lblMetaInfo);

        northContainer.add(navRow);
        northContainer.add(quickRow);
        northContainer.add(statusBar);
        root.add(northContainer, BorderLayout.NORTH);

        // 2. KHU VỰC HIỂN THỊ CHÍNH (CARD LAYOUT CHO HTML / TEXT / PDF / MEDIA PLAYER)
        CardLayout viewCards = new CardLayout();
        JPanel cardPanel = new JPanel(viewCards);

        // Card A: HTML Viewer
        JEditorPane htmlPane = new JEditorPane();
        htmlPane.setEditable(false);
        htmlPane.setContentType("text/html; charset=UTF-8");
        htmlPane.getDocument().putProperty("IgnoreCharsetDirective", Boolean.TRUE);
        htmlPane.setText("<html><body style='font-family:Segoe UI;padding:16px;'><h2>Lab 04 Java Swing Browser</h2>"
                + "<p>Nhấn <b>GET</b> để tải <code>" + initialUrl + "</code>.</p></body></html>");
        cardPanel.add(new JScrollPane(htmlPane), "HTML");

        // Card B: Plain / JSON / HEAD summary viewer
        JTextArea textViewer = new JTextArea();
        textViewer.setEditable(false);
        textViewer.setLineWrap(true);
        textViewer.setWrapStyleWord(true);
        textViewer.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13));
        cardPanel.add(new JScrollPane(textViewer), "TEXT");

        // Card C: PDF Viewer (render qua Apache PDFBox)
        JPanel pdfContainer = new JPanel(new BorderLayout(4, 4));
        JLabel lblPdfInfo = new JLabel("PDF Viewer (Apache PDFBox)");
        lblPdfInfo.setBorder(new EmptyBorder(4, 8, 4, 8));
        lblPdfInfo.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 12));
        JPanel pdfPagesBox = new JPanel();
        pdfPagesBox.setLayout(new BoxLayout(pdfPagesBox, BoxLayout.Y_AXIS));
        pdfPagesBox.setBackground(new java.awt.Color(100, 116, 139));
        pdfContainer.add(lblPdfInfo, BorderLayout.NORTH);
        pdfContainer.add(new JScrollPane(pdfPagesBox), BorderLayout.CENTER);
        cardPanel.add(pdfContainer, "PDF");

        // Card D: MP3 / MP4 JavaFX Media Player
        JPanel mediaContainer = new JPanel(new BorderLayout(4, 4));
        mediaContainer.setBackground(new java.awt.Color(15, 23, 42));
        JFXPanel jfxPanel = new JFXPanel();
        mediaContainer.add(jfxPanel, BorderLayout.CENTER);

        JPanel mediaCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        mediaCtrl.setBackground(new java.awt.Color(30, 41, 59));
        JButton btnPlay = new JButton("▶ Play");
        JButton btnPause = new JButton("⏸ Pause");
        JButton btnStop = new JButton("⏹ Stop");
        styleBtn(btnPlay, new java.awt.Color(37, 99, 235));
        styleBtn(btnPause, new java.awt.Color(217, 119, 6));
        styleBtn(btnStop, new java.awt.Color(220, 38, 38));
        JSlider seekSlider = new JSlider(0, 1000, 0);
        seekSlider.setPreferredSize(new Dimension(420, 24));
        seekSlider.setBackground(new java.awt.Color(30, 41, 59));
        JLabel lblMediaTime = new JLabel("00:00 / 00:00");
        lblMediaTime.setForeground(java.awt.Color.WHITE);
        lblMediaTime.setFont(new java.awt.Font("Consolas", java.awt.Font.BOLD, 13));
        mediaCtrl.add(btnPlay);
        mediaCtrl.add(btnPause);
        mediaCtrl.add(btnStop);
        mediaCtrl.add(seekSlider);
        mediaCtrl.add(lblMediaTime);
        mediaContainer.add(mediaCtrl, BorderLayout.SOUTH);
        cardPanel.add(mediaContainer, "MEDIA");

        // Khởi tạo JavaFX Scene 1 lần duy nhất và giữ Platform alive
        final MediaPlayer[] activePlayer = {null};
        final StackPane[] fxRootRef = {null};
        final MediaView[] mediaViewRef = {null};
        final VBox[] audioBadgeRef = {null};
        final Text[] audioTitleRef = {null};
        final AtomicBoolean sliderDragging = new AtomicBoolean(false);

        Platform.setImplicitExit(false);
        Platform.runLater(() -> {
            StackPane fxRoot = new StackPane();
            fxRoot.setStyle("-fx-background-color: #0f172a;");

            MediaView mv = new MediaView();
            mv.setPreserveRatio(true);
            mv.fitWidthProperty().bind(fxRoot.widthProperty().subtract(24));
            mv.fitHeightProperty().bind(fxRoot.heightProperty().subtract(24));

            VBox audioBadge = new VBox(10);
            audioBadge.setAlignment(Pos.CENTER);
            Text icon = new Text("♪ AUDIO STREAMING (HTTP / MP3) ♪");
            icon.setFill(Color.web("#38bdf8"));
            icon.setFont(javafx.scene.text.Font.font("Segoe UI", FontWeight.BOLD, 22));
            Text title = new Text("Đang tải luồng âm thanh...");
            title.setFill(Color.web("#e2e8f0"));
            title.setFont(javafx.scene.text.Font.font("Consolas", 14));
            audioBadge.getChildren().addAll(icon, title);
            audioBadge.setVisible(false);

            fxRoot.getChildren().addAll(mv, audioBadge);
            fxRootRef[0] = fxRoot;
            mediaViewRef[0] = mv;
            audioBadgeRef[0] = audioBadge;
            audioTitleRef[0] = title;
            jfxPanel.setScene(new Scene(fxRoot));
        });

        Runnable disposeActiveMedia = () -> Platform.runLater(() -> {
            if (activePlayer[0] != null) {
                try {
                    activePlayer[0].stop();
                    activePlayer[0].dispose();
                } catch (Exception ignored) {
                }
                activePlayer[0] = null;
            }
            if (mediaViewRef[0] != null) mediaViewRef[0].setMediaPlayer(null);
            SwingUtilities.invokeLater(() -> {
                seekSlider.setValue(0);
                lblMediaTime.setText("00:00 / 00:00");
            });
        });

        final java.util.function.BiConsumer<String, Boolean> loadMediaUrl = (mediaUrl, isAudio) -> {
            disposeActiveMedia.run();
            viewCards.show(cardPanel, "MEDIA");
            Platform.runLater(() -> {
                try {
                    Media media = new Media(mediaUrl);
                    MediaPlayer player = new MediaPlayer(media);
                    activePlayer[0] = player;

                    if (isAudio) {
                        mediaViewRef[0].setVisible(false);
                        mediaViewRef[0].setMediaPlayer(null);
                        audioBadgeRef[0].setVisible(true);
                        audioTitleRef[0].setText("Nguồn HTTP: " + mediaUrl);
                    } else {
                        audioBadgeRef[0].setVisible(false);
                        mediaViewRef[0].setVisible(true);
                        mediaViewRef[0].setMediaPlayer(player);
                    }

                    player.setOnReady(() -> {
                        Duration total = player.getTotalDuration();
                        SwingUtilities.invokeLater(() -> lblMediaTime.setText("00:00 / " + formatTimeSec(total.toSeconds())));
                        player.play();
                    });
                    player.currentTimeProperty().addListener((obs, oldT, newT) -> {
                        Duration total = player.getTotalDuration();
                        if (total == null || total.toMillis() <= 0 || sliderDragging.get()) return;
                        double ratio = Math.min(1.0, Math.max(0.0, newT.toMillis() / total.toMillis()));
                        SwingUtilities.invokeLater(() -> {
                            seekSlider.setValue((int) (ratio * 1000));
                            lblMediaTime.setText(formatTimeSec(newT.toSeconds()) + " / " + formatTimeSec(total.toSeconds()));
                        });
                    });
                    player.setOnError(() -> SwingUtilities.invokeLater(() -> {
                        String err = player.getError() != null ? player.getError().getMessage() : "Unknown Media Error";
                        lblHttpStatus.setText("Lỗi phát Media: " + err);
                    }));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "Không mở được media: " + ex.getMessage()));
                }
            });
        };

        btnPlay.addActionListener(e -> Platform.runLater(() -> {
            if (activePlayer[0] != null) activePlayer[0].play();
        }));
        btnPause.addActionListener(e -> Platform.runLater(() -> {
            if (activePlayer[0] != null) activePlayer[0].pause();
        }));
        btnStop.addActionListener(e -> Platform.runLater(() -> {
            if (activePlayer[0] != null) {
                activePlayer[0].stop();
                SwingUtilities.invokeLater(() -> seekSlider.setValue(0));
            }
        }));
        seekSlider.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { sliderDragging.set(true); }
            @Override public void mouseReleased(MouseEvent e) {
                sliderDragging.set(false);
                int val = seekSlider.getValue();
                Platform.runLater(() -> {
                    if (activePlayer[0] != null && activePlayer[0].getTotalDuration() != null) {
                        double ratio = val / 1000.0;
                        activePlayer[0].seek(activePlayer[0].getTotalDuration().multiply(ratio));
                    }
                });
            }
        });

        // 3. CÁC TAB CHÍNH: NỘI DUNG | HTTP HEADERS & RAW | LỊCH SỬ (SQLITE)
        JTabbedPane mainTabs = new JTabbedPane();
        mainTabs.addTab("1. Nội dung Web / PDF / Media", cardPanel);

        // Tab 2: HTTP Status Code & Response Headers
        JPanel hdrPanel = new JPanel(new BorderLayout(6, 6));
        hdrPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        DefaultTableModel hdrModel = new DefaultTableModel(new String[]{"Header Name", "Header Value"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblHeaders = new JTable(hdrModel);
        tblHeaders.setRowHeight(22);
        JTextArea txtRawPreview = new JTextArea();
        txtRawPreview.setEditable(false);
        txtRawPreview.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 12));
        JSplitPane hdrSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(tblHeaders),
                new JScrollPane(txtRawPreview));
        hdrSplit.setDividerLocation(250);
        ((JScrollPane) hdrSplit.getTopComponent()).setBorder(new TitledBorder("HTTP Response Headers (Từ Server trả về)"));
        ((JScrollPane) hdrSplit.getBottomComponent()).setBorder(new TitledBorder("Raw Response Body (Văn bản / HTML / JSON hoặc mô tả Binary)"));
        hdrPanel.add(hdrSplit, BorderLayout.CENTER);
        mainTabs.addTab("2. HTTP Status & Headers", hdrPanel);

        // Tab 3: History SQLite
        JPanel histPanel = new JPanel(new BorderLayout(6, 6));
        histPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        JPanel histBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton btnReloadHist = new JButton("Làm mới từ SQLite");
        JButton btnClearHist = new JButton("Xóa lịch sử");
        histBtns.add(btnReloadHist);
        histBtns.add(btnClearHist);
        DefaultTableModel histModel = new DefaultTableModel(
                new String[]{"ID", "Thời gian", "Method", "Status", "Content-Type", "Body Bytes", "URL"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblHistory = new JTable(histModel);
        tblHistory.setRowHeight(22);
        histPanel.add(histBtns, BorderLayout.NORTH);
        histPanel.add(new JScrollPane(tblHistory), BorderLayout.CENTER);
        mainTabs.addTab("3. Lịch sử truy cập (SQLite)", histPanel);

        root.add(mainTabs, BorderLayout.CENTER);

        // Ngăn xếp Back / Forward & kết quả gần nhất
        Deque<String> backStack = new ArrayDeque<>();
        Deque<String> forwardStack = new ArrayDeque<>();
        final String[] currentUrl = {null};
        final HttpResult[] lastResult = {null};

        Runnable refreshHistoryTable = () -> {
            try {
                histModel.setRowCount(0);
                for (WebDatabase.HistoryRecord h : core.getDatabase().listHistory()) {
                    histModel.addRow(new Object[]{h.id, h.visitedAt, h.method, h.statusCode, h.contentType, h.contentLength, h.url});
                }
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi đọc SQLite History: " + ex.getMessage());
            }
        };

        Runnable updateNavButtons = () -> {
            btnBack.setEnabled(!backStack.isEmpty());
            btnForward.setEnabled(!forwardStack.isEmpty());
        };

        // Hàm điều hướng tổng quát thực hiện HTTP request thật qua SwingWorker
        final java.util.function.Consumer<Object[]> performRequest = params -> {
            String targetUrl = BrowserHttpClientCore.normalizeUrl((String) params[0]);
            String method = (String) params[1];
            String postPayload = (String) params[2];
            String navMode = (String) params[3]; // "NEW" | "BACK" | "FORWARD" | "REFRESH"

            txtUrl.setText(targetUrl);
            lblHttpStatus.setText("Đang gửi " + method + " -> " + targetUrl + " ...");
            lblHttpStatus.setForeground(new java.awt.Color(37, 99, 235));
            btnGet.setEnabled(false);
            btnHead.setEnabled(false);
            btnPost.setEnabled(false);

            new SwingWorker<HttpResult, Void>() {
                @Override protected HttpResult doInBackground() throws Exception {
                    return core.execute(targetUrl, method, postPayload, true);
                }

                @Override protected void done() {
                    btnGet.setEnabled(true);
                    btnHead.setEnabled(true);
                    btnPost.setEnabled(true);
                    try {
                        HttpResult res = get();
                        if ("NEW".equals(navMode) && currentUrl[0] != null && !currentUrl[0].equals(res.url)) {
                            backStack.push(currentUrl[0]);
                            forwardStack.clear();
                        }
                        currentUrl[0] = res.url;
                        lastResult[0] = res;
                        updateNavButtons.run();

                        // Status bar
                        lblHttpStatus.setText("HTTP " + res.statusCode + " | Method: " + res.method);
                        lblHttpStatus.setForeground(res.statusCode >= 400
                                ? new java.awt.Color(220, 38, 38)
                                : new java.awt.Color(5, 150, 105));
                        lblMetaInfo.setText("Content-Type: " + res.contentType
                                + " | Body nhận được: " + res.body.length + " bytes"
                                + " (Declared Size: " + res.declaredLength + " B) | " + res.elapsedMs + " ms");

                        // Tab Headers
                        hdrModel.setRowCount(0);
                        res.headers.forEach((k, vals) -> {
                            for (String v : vals) hdrModel.addRow(new Object[]{k, v});
                        });

                        String mime = res.contentType.toLowerCase(Locale.ROOT);
                        if ("HEAD".equals(res.method)) {
                            disposeActiveMedia.run();
                            textViewer.setText("=== KẾT QUẢ HTTP HEAD ===\n"
                                    + "URL: " + res.url + "\n"
                                    + "Status Code: " + res.statusCode + "\n"
                                    + "Body Bytes Received: " + res.body.length + " bytes (Đúng chuẩn RFC: HEAD không tải Body!)\n\n"
                                    + "--- Response Headers ---\n"
                                    + formatHeadersText(res.headers));
                            txtRawPreview.setText("(HEAD request: Body rỗng 0 bytes)");
                            viewCards.show(cardPanel, "TEXT");
                            mainTabs.setSelectedIndex(1); // Tự mở tab Headers để người dùng quan sát rõ ngay
                        } else if (mime.contains("audio/mpeg") || res.url.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                            txtRawPreview.setText("(Binary Audio MP3: " + res.body.length + " bytes)");
                            loadMediaUrl.accept(res.url, true);
                            mainTabs.setSelectedIndex(0);
                        } else if (mime.contains("video/mp4") || res.url.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                            txtRawPreview.setText("(Binary Video MP4: " + res.body.length + " bytes)");
                            loadMediaUrl.accept(res.url, false);
                            mainTabs.setSelectedIndex(0);
                        } else if (mime.contains("application/pdf") || res.url.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                            disposeActiveMedia.run();
                            txtRawPreview.setText("(Binary PDF Document: " + res.body.length + " bytes)");
                            renderPdfIntoPanel(res.body, pdfPagesBox, lblPdfInfo, res.url);
                            viewCards.show(cardPanel, "PDF");
                            mainTabs.setSelectedIndex(0);
                        } else if (mime.contains("text/html") && res.statusCode < 400) {
                            disposeActiveMedia.run();
                            String html = new String(res.body, StandardCharsets.UTF_8);
                            txtRawPreview.setText(html);
                            htmlPane.getDocument().putProperty("IgnoreCharsetDirective", Boolean.TRUE);
                            htmlPane.setText(html);
                            htmlPane.setCaretPosition(0);
                            viewCards.show(cardPanel, "HTML");
                            mainTabs.setSelectedIndex(0);
                        } else {
                            disposeActiveMedia.run();
                            String bodyStr = new String(res.body, StandardCharsets.UTF_8);
                            txtRawPreview.setText(bodyStr);
                            textViewer.setText("=== HTTP " + res.method + " (" + res.statusCode + ") ===\n"
                                    + "URL: " + res.url + "\n\n" + bodyStr);
                            viewCards.show(cardPanel, "TEXT");
                            mainTabs.setSelectedIndex(0);
                        }
                        refreshHistoryTable.run();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        disposeActiveMedia.run();
                        lblHttpStatus.setText("Lỗi kết nối HTTP: " + cause.getMessage());
                        lblHttpStatus.setForeground(new java.awt.Color(220, 38, 38));
                        textViewer.setText("Không thể kết nối tới Web Server: " + targetUrl
                                + "\nChi tiết lỗi: " + cause.getMessage()
                                + "\n\nHãy đảm bảo WebServerApp đang chạy đúng IP và Port.");
                        viewCards.show(cardPanel, "TEXT");
                        mainTabs.setSelectedIndex(0);
                    }
                }
            }.execute();
        };

        // Sự kiện click hyperlink trong HTML
        htmlPane.addHyperlinkListener(ev -> {
            if (ev.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    String base = currentUrl[0] != null ? currentUrl[0] : initialUrl;
                    String next = URI.create(base).resolve(ev.getDescription()).toString();
                    performRequest.accept(new Object[]{next, "GET", "", "NEW"});
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Link không hợp lệ: " + ev.getDescription());
                }
            }
        });

        // Các nút điều hướng chính
        txtUrl.addActionListener(e -> performRequest.accept(new Object[]{txtUrl.getText(), "GET", "", "NEW"}));
        btnGet.addActionListener(e -> performRequest.accept(new Object[]{txtUrl.getText(), "GET", "", "NEW"}));
        btnHead.addActionListener(e -> performRequest.accept(new Object[]{txtUrl.getText(), "HEAD", "", "NEW"}));
        btnPost.addActionListener(e -> performRequest.accept(new Object[]{txtUrl.getText(), "POST", txtPostBody.getText(), "NEW"}));
        btnRefresh.addActionListener(e -> performRequest.accept(new Object[]{txtUrl.getText(), "GET", "", "REFRESH"}));
        btnHome.addActionListener(e -> performRequest.accept(new Object[]{initialUrl, "GET", "", "NEW"}));

        btnBack.addActionListener(e -> {
            if (!backStack.isEmpty()) {
                if (currentUrl[0] != null) forwardStack.push(currentUrl[0]);
                String prev = backStack.pop();
                performRequest.accept(new Object[]{prev, "GET", "", "BACK"});
            }
        });
        btnForward.addActionListener(e -> {
            if (!forwardStack.isEmpty()) {
                if (currentUrl[0] != null) backStack.push(currentUrl[0]);
                String next = forwardStack.pop();
                performRequest.accept(new Object[]{next, "GET", "", "FORWARD"});
            }
        });

        // Nút mở nhanh (tự động bám theo Host/Port hiện tại trên thanh địa chỉ)
        String basePrefix = initialUrl.replaceAll("/[^/]*$", "");
        btnQHtml.addActionListener(e -> performRequest.accept(new Object[]{resolveBasePrefix(txtUrl.getText(), basePrefix) + "/index.html", "GET", "", "NEW"}));
        btnQAbout.addActionListener(e -> performRequest.accept(new Object[]{resolveBasePrefix(txtUrl.getText(), basePrefix) + "/about.html", "GET", "", "NEW"}));
        btnQPdf.addActionListener(e -> performRequest.accept(new Object[]{resolveBasePrefix(txtUrl.getText(), basePrefix) + "/tailieu.pdf", "GET", "", "NEW"}));
        btnQMp3.addActionListener(e -> performRequest.accept(new Object[]{resolveBasePrefix(txtUrl.getText(), basePrefix) + "/nhac.mp3", "GET", "", "NEW"}));
        btnQMp4.addActionListener(e -> performRequest.accept(new Object[]{resolveBasePrefix(txtUrl.getText(), basePrefix) + "/video.mp4", "GET", "", "NEW"}));
        btnQApi.addActionListener(e -> {
            String apiUrl = resolveBasePrefix(txtUrl.getText(), basePrefix) + "/api/submit";
            txtUrl.setText(apiUrl);
            performRequest.accept(new Object[]{apiUrl, "POST", txtPostBody.getText(), "NEW"});
        });

        // Nút Download (nếu đang xem URL hoặc vừa gửi HEAD thì tải lại bằng GET rồi lưu file)
        btnDownload.addActionListener(e -> {
            String url = BrowserHttpClientCore.normalizeUrl(txtUrl.getText());
            String suggested = suggestDownloadFilename(url);
            JFileChooser fc = new JFileChooser(downloadDir.toFile());
            fc.setSelectedFile(new java.io.File(downloadDir.toFile(), suggested));
            if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path dest = fc.getSelectedFile().toPath();

            btnDownload.setEnabled(false);
            new SwingWorker<Long, Void>() {
                @Override protected Long doInBackground() throws Exception {
                    byte[] bytes;
                    if (lastResult[0] != null && url.equals(lastResult[0].url)
                            && !"HEAD".equals(lastResult[0].method) && lastResult[0].body.length > 0) {
                        bytes = lastResult[0].body;
                    } else {
                        HttpResult dl = core.execute(url, "GET", "", true);
                        if (dl.statusCode >= 400) throw new IOException("HTTP Error " + dl.statusCode);
                        bytes = dl.body;
                    }
                    Files.write(dest, bytes);
                    return (long) bytes.length;
                }

                @Override protected void done() {
                    btnDownload.setEnabled(true);
                    try {
                        long sz = get();
                        refreshHistoryTable.run();
                        JOptionPane.showMessageDialog(frame, "Đã tải xuống (" + sz + " bytes):\n" + dest.toAbsolutePath());
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(frame, "Lỗi Download: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        // Tab Lịch sử
        btnHistoryTab.addActionListener(e -> {
            refreshHistoryTable.run();
            mainTabs.setSelectedIndex(2);
        });
        btnReloadHist.addActionListener(e -> refreshHistoryTable.run());
        btnClearHist.addActionListener(e -> {
            try {
                core.getDatabase().clearHistory();
                refreshHistoryTable.run();
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi xóa SQLite History: " + ex.getMessage());
            }
        });
        tblHistory.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && tblHistory.getSelectedRow() >= 0) {
                    String u = String.valueOf(histModel.getValueAt(tblHistory.getSelectedRow(), 6));
                    performRequest.accept(new Object[]{u, "GET", "", "NEW"});
                }
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                disposeActiveMedia.run();
            }
        });

        refreshHistoryTable.run();
        frame.setVisible(true);
    }

    private static String formatHeadersText(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, vs) -> sb.append(k).append(": ").append(String.join(", ", vs)).append("\n"));
        return sb.toString();
    }

    private static void renderPdfIntoPanel(byte[] pdfBytes, JPanel pagesBox, JLabel lblPdfInfo, String url) {
        pagesBox.removeAll();
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = doc.getNumberOfPages();
            lblPdfInfo.setText("PDF Viewer — " + url + " (" + pages + " trang, " + pdfBytes.length + " bytes)");
            for (int i = 0; i < pages; i++) {
                BufferedImage bim = renderer.renderImageWithDPI(i, 120f);
                JLabel pageImg = new JLabel(new ImageIcon(bim));
                pageImg.setAlignmentX(Component.CENTER_ALIGNMENT);
                pageImg.setBorder(BorderFactory.createCompoundBorder(
                        new EmptyBorder(10, 10, 10, 10),
                        BorderFactory.createLineBorder(java.awt.Color.DARK_GRAY, 1)
                ));
                pagesBox.add(pageImg);
            }
        } catch (Exception ex) {
            lblPdfInfo.setText("Không đọc được PDF: " + ex.getMessage());
        }
        pagesBox.revalidate();
        pagesBox.repaint();
    }
}
