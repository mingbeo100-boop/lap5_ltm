package com.ltm.lab05;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lab 05 — Java RMI Server (dùng chung cho cả Server A và Server B):
 * - Đăng ký Remote Object {@link SyncService} lên RMI Registry cục bộ ("SyncService").
 * - Lưu dữ liệu SQLite riêng qua {@link SyncDatabase}.
 * - Đồng bộ 2 chiều qua Java RMI, chống vòng lặp vô hạn (replicateFromPeer không gọi ngược Peer).
 * - Heartbeat kiểm tra Peer: khi Peer mất kết nối rồi bật lại, tự động Push + Pull để đồng bộ bù (Catch-up Sync).
 */
public class SyncServerApp {

    public static final String SERVICE_NAME = "SyncService";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    static {
        // Giảm timeout chờ phản hồi RMI khi Peer bị ngắt kết nối đột ngột (3 giây)
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "3000");
        System.setProperty("sun.rmi.transport.connectionTimeout", "3000");
    }

    public static String getLocalLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Lõi RMI Sync Server (tách khỏi Swing để có thể chạy chế độ headless --nogui đa tiến trình). */
    public static class SyncServerCore extends UnicastRemoteObject implements SyncService {
        private final String serverId;
        private final int myPort;
        private final String peerHost;
        private final int peerPort;
        private final SyncDatabase db;

        private Registry registry;
        private ScheduledExecutorService scheduler;
        private ExecutorService pushExecutor;
        private volatile boolean running = false;
        private volatile boolean peerOnline = false;
        private volatile boolean needsInitialFullSync = true;
        private SyncService cachedPeerStub;
        private final Object syncLock = new Object();
        private final AtomicBoolean wasPeerOfflineOnce = new AtomicBoolean(false);
        private final AtomicLong inboundReplicationCount = new AtomicLong(0);

        private Consumer<String> logListener;
        private Runnable stateChangeListener;

        public SyncServerCore(String serverId, int myPort, String peerHost, int peerPort, Path dbPath)
                throws RemoteException, SQLException {
            super(0);
            this.serverId = serverId;
            this.myPort = myPort;
            this.peerHost = peerHost;
            this.peerPort = peerPort;
            this.db = new SyncDatabase(dbPath);
        }

        public void setLogListener(Consumer<String> logListener) {
            this.logListener = logListener;
        }

        public void setStateChangeListener(Runnable listener) {
            this.stateChangeListener = listener;
        }

        public SyncDatabase getDatabase() {
            return db;
        }

        public boolean isRunning() {
            return running;
        }

        public boolean isPeerOnline() {
            return peerOnline;
        }

        public String getServerId() {
            return serverId;
        }

        public int getMyPort() {
            return myPort;
        }

        public long getInboundReplicationCount() {
            return inboundReplicationCount.get();
        }

        private void log(String msg) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "][" + serverId + "] " + msg;
            if (logListener != null) logListener.accept(line);
            else System.out.println(line);
        }

        private void notifyUi() {
            if (stateChangeListener != null) stateChangeListener.run();
        }

        public synchronized void start() throws RemoteException {
            if (running) return;
            registry = LocateRegistry.createRegistry(myPort);
            registry.rebind(SERVICE_NAME, this);
            running = true;

            pushExecutor = Executors.newSingleThreadExecutor();
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::syncCycleSafe, 300, 1200, TimeUnit.MILLISECONDS);

            log("RMI Server sẵn sàng tại rmi://" + System.getProperty("java.rmi.server.hostname", "127.0.0.1")
                    + ":" + myPort + "/" + SERVICE_NAME + " | Peer=" + peerHost + ":" + peerPort);
            notifyUi();
        }

        public synchronized void stop() {
            running = false;
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            if (pushExecutor != null) {
                pushExecutor.shutdownNow();
                pushExecutor = null;
            }
            if (registry != null) {
                try {
                    registry.unbind(SERVICE_NAME);
                } catch (Exception ignored) {
                }
                try {
                    UnicastRemoteObject.unexportObject(registry, true);
                } catch (Exception ignored) {
                }
                registry = null;
            }
            try {
                UnicastRemoteObject.unexportObject(this, true);
            } catch (Exception ignored) {
            }
            cachedPeerStub = null;
            peerOnline = false;
            log("RMI Server đã dừng.");
            notifyUi();
        }

        @Override
        public String ping() throws RemoteException {
            return serverId;
        }

        @Override
        public List<DataItem> getAllActiveItems() throws RemoteException {
            try {
                return db.listActiveItems();
            } catch (SQLException e) {
                throw new RemoteException("Lỗi đọc SQLite", e);
            }
        }

        @Override
        public DataItem createItem(String code, String name, int quantity, String note) throws RemoteException {
            try {
                DataItem item = db.insertLocalItem(serverId, code, name, quantity, note);
                log("[Client CRUD] Thêm mới " + item.code + " (" + item.name + ", SL=" + item.quantity + ")");
                notifyUi();
                triggerImmediatePush();
                return item;
            } catch (SQLException e) {
                throw new RemoteException("Lỗi thêm SQLite", e);
            }
        }

        @Override
        public DataItem updateItem(String id, String code, String name, int quantity, String note) throws RemoteException {
            try {
                DataItem item = db.updateLocalItem(serverId, id, code, name, quantity, note);
                log("[Client CRUD] Cập nhật " + item.code + " -> v" + item.version);
                notifyUi();
                triggerImmediatePush();
                return item;
            } catch (Exception e) {
                throw new RemoteException("Lỗi cập nhật SQLite: " + e.getMessage(), e);
            }
        }

        @Override
        public void deleteItem(String id) throws RemoteException {
            try {
                DataItem tomb = db.markDeletedLocal(serverId, id);
                log("[Client CRUD] Xóa bản ghi " + tomb.code + " (tombstone v" + tomb.version + ")");
                notifyUi();
                triggerImmediatePush();
            } catch (Exception e) {
                throw new RemoteException("Lỗi xóa SQLite: " + e.getMessage(), e);
            }
        }

        /**
         * Điểm chặn vòng lặp vô hạn:
         * replicateFromPeer CHỈ áp dụng dữ liệu xuống SQLite của Server này và TUYỆT ĐỐI KHÔNG gọi ngược lại Peer.
         */
        @Override
        public int replicateFromPeer(List<DataItem> peerItems, String sourceServerId) throws RemoteException {
            if (peerItems == null || peerItems.isEmpty()) return 0;
            try {
                int applied = db.applyReplicatedItems(peerItems);
                inboundReplicationCount.incrementAndGet();
                if (applied > 0) {
                    db.addSyncLog("INBOUND", sourceServerId, "Nhận đồng bộ " + applied + "/" + peerItems.size() + " thay đổi");
                    log("[RMI Sync <- " + sourceServerId + "] Đã cập nhật " + applied + " bản ghi vào SQLite cục bộ.");
                    notifyUi();
                }
                return applied;
            } catch (SQLException e) {
                throw new RemoteException("Lỗi ghi đồng bộ SQLite", e);
            }
        }

        @Override
        public List<DataItem> exportAllItemsForSync() throws RemoteException {
            try {
                return db.listAllRawItems();
            } catch (SQLException e) {
                throw new RemoteException("Lỗi xuất dữ liệu SQLite", e);
            }
        }

        private void triggerImmediatePush() {
            if (pushExecutor != null && running) {
                pushExecutor.submit(this::syncCycleSafe);
            }
        }

        public void syncCycleSafe() {
            if (!running) return;
            synchronized (syncLock) {
                if (!running) return;
                try {
                    if (cachedPeerStub == null) {
                        Registry peerReg = LocateRegistry.getRegistry(peerHost, peerPort);
                        cachedPeerStub = (SyncService) peerReg.lookup(SERVICE_NAME);
                    }
                    String remoteId = cachedPeerStub.ping();
                    boolean recoveredFromDisconnect = !peerOnline && wasPeerOfflineOnce.get();
                    boolean doFullReconcile = recoveredFromDisconnect || needsInitialFullSync;
                    peerOnline = true;

                    // 1. Đẩy các thay đổi cục bộ sang Peer (nếu mới kết nối lại thì đẩy toàn bộ trạng thái để đảm bảo khớp)
                    List<DataItem> toPush = doFullReconcile ? db.listAllRawItems() : db.listPendingItems();
                    if (!toPush.isEmpty()) {
                        int peerApplied = cachedPeerStub.replicateFromPeer(toPush, serverId);
                        db.markSyncedItems(toPush);
                        if (recoveredFromDisconnect || peerApplied > 0) {
                            db.addSyncLog("OUTBOUND", remoteId,
                                    (recoveredFromDisconnect ? "[Catch-up] " : "") + "Đẩy " + toPush.size() + " mục, Peer nhận " + peerApplied);
                            log("[RMI Sync -> " + remoteId + "] Đã đồng bộ " + toPush.size() + " bản ghi (Peer cập nhật: " + peerApplied + ").");
                        }
                    }

                    // 2. Nếu vừa mới khởi động hoặc Peer vừa kết nối lại sau sự cố, kéo nốt thay đổi từ Peer về (Catch-up Pull)
                    if (doFullReconcile) {
                        List<DataItem> peerAll = cachedPeerStub.exportAllItemsForSync();
                        int pulled = db.applyReplicatedItems(peerAll);
                        needsInitialFullSync = false;
                        wasPeerOfflineOnce.set(false);
                        if (pulled > 0) {
                            db.addSyncLog("CATCHUP_PULL", remoteId, "Kéo bù " + pulled + " thay đổi sau khi nối lại Peer");
                            log("[Catch-up Sync <- " + remoteId + "] Phát hiện và đồng bộ bù " + pulled + " bản ghi còn thiếu.");
                        } else if (recoveredFromDisconnect) {
                            log("[Reconnect] Peer " + remoteId + " đã ONLINE trở lại — Dữ liệu 2 Server khớp hoàn toàn.");
                        }
                    }
                    notifyUi();
                } catch (Exception e) {
                    if (peerOnline || !wasPeerOfflineOnce.get()) {
                        log("[Cảnh báo] Peer Server (" + peerHost + ":" + peerPort + ") đang OFFLINE — thay đổi mới sẽ lưu hàng đợi chờ đồng bộ lại.");
                        try {
                            db.addSyncLog("OFFLINE", peerHost + ":" + peerPort, "Peer mất kết nối: " + e.getClass().getSimpleName());
                        } catch (SQLException ignored) {
                        }
                    }
                    peerOnline = false;
                    cachedPeerStub = null;
                    wasPeerOfflineOnce.set(true);
                    notifyUi();
                }
            }
        }
    }

    // =========================================================================
    // GIAO DIỆN SWING QUẢN TRỊ SERVER A / SERVER B + HỖ TRỢ --nogui CLI
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
            var cs = SyncServerApp.class.getProtectionDomain().getCodeSource();
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
        Path nested = cwd.resolve("LTM_5_LABS").resolve("Lab05_RMI");
        if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        Path sibling = cwd.resolve("Lab05_RMI");
        if (Files.isRegularFile(sibling.resolve("pom.xml"))) return sibling;
        return cwd;
    }

    public static void main(String[] args) throws Exception {
        String id = "SERVER_A";
        int port = 1099;
        String peerHost = "127.0.0.1";
        int peerPort = 1100;
        String dbPathStr = null;
        boolean nogui = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--role".equalsIgnoreCase(a) && i + 1 < args.length) {
                String r = args[++i].toUpperCase();
                if ("B".equals(r)) {
                    id = "SERVER_B";
                    port = 1100;
                    peerPort = 1099;
                }
            } else if ("--id".equalsIgnoreCase(a) && i + 1 < args.length) {
                id = args[++i];
            } else if ("--port".equalsIgnoreCase(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--peerHost".equalsIgnoreCase(a) && i + 1 < args.length) {
                peerHost = args[++i];
            } else if ("--peerPort".equalsIgnoreCase(a) && i + 1 < args.length) {
                peerPort = Integer.parseInt(args[++i]);
            } else if ("--db".equalsIgnoreCase(a) && i + 1 < args.length) {
                dbPathStr = args[++i];
            } else if ("--nogui".equalsIgnoreCase(a)) {
                nogui = true;
            }
        }
        Path dbPath = dbPathStr != null
                ? Paths.get(dbPathStr)
                : resolveLabDir().resolve("data").resolve(id.toLowerCase() + ".db");

        if (nogui) {
            SyncServerCore core = new SyncServerCore(id, port, peerHost, peerPort, dbPath);
            core.start();
            System.out.println("RMI_SERVER_READY:" + id + ":" + port);
            System.out.flush();
            Runtime.getRuntime().addShutdownHook(new Thread(core::stop));
            Thread.sleep(Long.MAX_VALUE);
            return;
        }

        final String fId = id;
        final int fPort = port;
        final String fPeerHost = peerHost;
        final int fPeerPort = peerPort;
        SwingUtilities.invokeLater(() -> launchGui(fId, fPort, fPeerHost, fPeerPort, dbPath));
    }

    public static void launchGui(String defaultId, int defaultPort, String defaultPeerHost, int defaultPeerPort, Path defaultDbPath) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 05 — Java RMI Sync Server (" + defaultId + ")");
        frame.setSize(1080, 680);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        // Thanh cấu hình RMI Server & Peer
        JPanel top = new JPanel(new GridLayout(2, 1, 4, 4));
        top.setBackground(Color.WHITE);
        top.setBorder(new TitledBorder("Cấu hình RMI Server & Peer Đồng bộ (LAN IP máy này: " + getLocalLanIp() + ")"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.setBackground(Color.WHITE);
        JComboBox<String> cboPreset = new JComboBox<>(new String[]{"SERVER_A (1099 -> Peer 1100)", "SERVER_B (1100 -> Peer 1099)"});
        if ("SERVER_B".equalsIgnoreCase(defaultId)) cboPreset.setSelectedIndex(1);
        JTextField txtId = new JTextField(defaultId, 9);
        JTextField txtExportIp = new JTextField(System.getProperty("java.rmi.server.hostname", "127.0.0.1"), 12);
        JTextField txtPort = new JTextField(String.valueOf(defaultPort), 5);
        JTextField txtPeerHost = new JTextField(defaultPeerHost, 12);
        JTextField txtPeerPort = new JTextField(String.valueOf(defaultPeerPort), 5);

        row1.add(new JLabel("Vai trò:")); row1.add(cboPreset);
        row1.add(new JLabel("ID:")); row1.add(txtId);
        row1.add(new JLabel("RMI Hostname IP:")); row1.add(txtExportIp);
        row1.add(new JLabel("Port:")); row1.add(txtPort);
        row1.add(new JLabel("Peer IP:")); row1.add(txtPeerHost);
        row1.add(new JLabel("Peer Port:")); row1.add(txtPeerPort);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        row2.setBackground(Color.WHITE);
        JButton btnToggle = new JButton("Khởi chạy RMI Server");
        styleBtn(btnToggle, new Color(37, 99, 235));
        JButton btnSyncNow = new JButton("Đồng bộ bù ngay");
        styleBtn(btnSyncNow, new Color(5, 150, 105));
        JLabel lblStatus = new JLabel("● Chưa khởi chạy");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblStatus.setForeground(new Color(220, 38, 38));
        JLabel lblPeer = new JLabel("Peer: Chưa kiểm tra");
        lblPeer.setFont(new Font("Segoe UI", Font.BOLD, 12));
        JLabel lblPending = new JLabel("Chờ đồng bộ: 0");
        lblPending.setFont(new Font("Segoe UI", Font.BOLD, 12));

        row2.add(btnToggle);
        row2.add(btnSyncNow);
        row2.add(lblStatus);
        row2.add(lblPeer);
        row2.add(lblPending);

        top.add(row1);
        top.add(row2);
        root.add(top, BorderLayout.NORTH);

        cboPreset.addActionListener(e -> {
            if (cboPreset.getSelectedIndex() == 0) {
                txtId.setText("SERVER_A");
                txtPort.setText("1099");
                txtPeerPort.setText("1100");
            } else {
                txtId.setText("SERVER_B");
                txtPort.setText("1100");
                txtPeerPort.setText("1099");
            }
            frame.setTitle("Lab 05 — Java RMI Sync Server (" + txtId.getText().trim() + ")");
        });

        // Bảng dữ liệu SQLite cục bộ trên Server này
        DefaultTableModel itemModel = new DefaultTableModel(
                new String[]{"ID", "Mã SP", "Tên mặt hàng", "Số lượng", "Ghi chú", "Ver", "Trạng thái", "Nguồn tạo"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblItems = new JTable(itemModel);
        tblItems.setRowHeight(22);
        JScrollPane spItems = new JScrollPane(tblItems);
        spItems.setBorder(new TitledBorder("Dữ liệu hiện có trong SQLite cục bộ của Server này"));

        DefaultTableModel syncModel = new DefaultTableModel(
                new String[]{"Thời gian", "Hướng đồng bộ", "Peer", "Chi tiết"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblSyncLogs = new JTable(syncModel);
        tblSyncLogs.setRowHeight(22);
        JScrollPane spSyncLogs = new JScrollPane(tblSyncLogs);
        spSyncLogs.setBorder(new TitledBorder("Nhật ký Đồng bộ RMI (SQLite sync_log)"));

        JTextArea txtConsole = new JTextArea();
        txtConsole.setEditable(false);
        txtConsole.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtConsole.setBackground(new Color(15, 23, 42));
        txtConsole.setForeground(new Color(226, 232, 240));
        JScrollPane spConsole = new JScrollPane(txtConsole);
        spConsole.setBorder(new TitledBorder("Log trạng thái kết nối & Catch-up Sync"));

        JSplitPane bottomSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spSyncLogs, spConsole);
        bottomSplit.setDividerLocation(500);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spItems, bottomSplit);
        mainSplit.setDividerLocation(290);
        root.add(mainSplit, BorderLayout.CENTER);

        final SyncServerCore[] srvRef = {null};
        final Path[] activeDbRef = {defaultDbPath};

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtConsole.append(line + "\n");
            txtConsole.setCaretPosition(txtConsole.getDocument().getLength());
        });

        Runnable refreshUi = () -> SwingUtilities.invokeLater(() -> {
            SyncServerCore s = srvRef[0];
            if (s == null) return;
            lblPeer.setText(s.isPeerOnline() ? "Peer: ● ONLINE" : "Peer: ● OFFLINE (Đợi kết nối lại)");
            lblPeer.setForeground(s.isPeerOnline() ? new Color(5, 150, 105) : new Color(217, 119, 6));
            try {
                int pending = s.getDatabase().countPendingItems();
                lblPending.setText("Chờ đồng bộ: " + pending);
                lblPending.setForeground(pending > 0 ? new Color(220, 38, 38) : new Color(51, 65, 85));

                itemModel.setRowCount(0);
                for (DataItem it : s.getDatabase().listAllRawItems()) {
                    String shortId = (it.id != null && it.id.length() > 8) ? it.id.substring(0, 8) : String.valueOf(it.id);
                    itemModel.addRow(new Object[]{
                            shortId, it.code, it.name, it.quantity, it.note,
                            it.version, it.deleted ? "ĐÃ XÓA (Tombstone)" : "Hoạt động", it.originServer
                    });
                }
                syncModel.setRowCount(0);
                for (SyncDatabase.SyncLogEntry sl : s.getDatabase().listSyncLogs()) {
                    syncModel.addRow(new Object[]{sl.createdAt, sl.direction, sl.peerId, sl.detail});
                }
            } catch (SQLException ex) {
                appendLog.accept("Lỗi đọc SQLite: " + ex.getMessage());
            }
        });

        Runnable startAct = () -> {
            try {
                String id = txtId.getText().trim();
                int port = Integer.parseInt(txtPort.getText().trim());
                String peerH = txtPeerHost.getText().trim();
                int peerP = Integer.parseInt(txtPeerPort.getText().trim());
                if (port < 1 || port > 65535 || peerP < 1 || peerP > 65535) {
                    throw new IllegalArgumentException("Cổng RMI và Peer Port phải nằm trong khoảng 1–65535.");
                }
                if (id.isEmpty() || peerH.isEmpty()) {
                    throw new IllegalArgumentException("Server ID và Peer IP không được để trống.");
                }
                System.setProperty("java.rmi.server.hostname", txtExportIp.getText().trim());
                Path dbParent = defaultDbPath.toAbsolutePath().normalize().getParent();
                Path dbP = (id.equalsIgnoreCase(defaultId) || dbParent == null)
                        ? defaultDbPath
                        : dbParent.resolve(id.toLowerCase() + ".db");
                activeDbRef[0] = dbP;

                SyncServerCore core = new SyncServerCore(id, port, peerH, peerP, dbP);
                core.setLogListener(appendLog);
                core.setStateChangeListener(refreshUi);
                core.start();
                srvRef[0] = core;

                btnToggle.setText("Dừng RMI Server");
                btnToggle.setBackground(new Color(220, 38, 38));
                lblStatus.setText("● Đang chạy (" + id + " :" + port + ")");
                lblStatus.setForeground(new Color(5, 150, 105));
                cboPreset.setEnabled(false);
                txtId.setEnabled(false);
                txtExportIp.setEnabled(false);
                txtPort.setEnabled(false);
                refreshUi.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi khởi động RMI Server: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnToggle.addActionListener(e -> {
            if (srvRef[0] == null || !srvRef[0].isRunning()) {
                startAct.run();
            } else {
                srvRef[0].stop();
                btnToggle.setText("Khởi chạy RMI Server");
                btnToggle.setBackground(new Color(37, 99, 235));
                lblStatus.setText("● Đã dừng");
                lblStatus.setForeground(new Color(220, 38, 38));
                cboPreset.setEnabled(true);
                txtId.setEnabled(true);
                txtExportIp.setEnabled(true);
                txtPort.setEnabled(true);
            }
        });
        btnSyncNow.addActionListener(e -> {
            if (srvRef[0] != null && srvRef[0].isRunning()) {
                new SwingWorker<Void, Void>() {
                    @Override protected Void doInBackground() {
                        srvRef[0].syncCycleSafe();
                        return null;
                    }
                }.execute();
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (srvRef[0] != null) srvRef[0].stop();
            }
        });
        frame.setVisible(true);
    }
}

