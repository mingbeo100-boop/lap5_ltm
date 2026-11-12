package com.ltm.lab02;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lab 02 — FTP Server thực thụ chuẩn RFC 959 bằng Apache FtpServer + SQLite:
 * - Tích hợp UserManager đọc/xác thực tài khoản từ SQLite bảng `users`.
 * - Tích hợp Ftplet ghi log STOR (Upload), RETR (Download), DELE (Xóa) vào SQLite `transfer_history`.
 * - Hỗ trợ mở Passive Ports (22200-22220) để tương thích cả Java Client lẫn FileZilla.
 */
public class FtpServerApp {

    public static final int DEFAULT_PORT = 2121;
    public static final String DEFAULT_PASSIVE_PORTS = "22200-22220";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static String getLocalLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** UserManager tùy biến kết nối Apache FtpServer với SQLite. */
    static class SqliteUserManager implements UserManager {
        private final FtpDatabase db;

        SqliteUserManager(FtpDatabase db) {
            this.db = db;
        }

        private BaseUser toFtpUser(FtpDatabase.UserRecord rec) {
            BaseUser u = new BaseUser();
            u.setName(rec.username);
            u.setEnabled(rec.enabled);
            u.setMaxIdleTime(600);
            try {
                Files.createDirectories(Paths.get(rec.homeDir));
            } catch (IOException ignored) {
            }
            u.setHomeDirectory(rec.homeDir);

            List<Authority> auths = new ArrayList<>();
            if (rec.canWrite) {
                auths.add(new WritePermission());
            }
            auths.add(new ConcurrentLoginPermission(0, 0));
            auths.add(new TransferRatePermission(0, 0));
            u.setAuthorities(auths);
            return u;
        }

        @Override
        public User getUserByName(String username) throws FtpException {
            try {
                FtpDatabase.UserRecord rec = db.getUser(username);
                return rec == null ? null : toFtpUser(rec);
            } catch (SQLException e) {
                throw new FtpException("Lỗi SQLite khi đọc user", e);
            }
        }

        @Override
        public String[] getAllUserNames() throws FtpException {
            try {
                List<FtpDatabase.UserRecord> list = db.listUsers();
                return list.stream().map(u -> u.username).toArray(String[]::new);
            } catch (SQLException e) {
                throw new FtpException("Lỗi SQLite khi liệt kê users", e);
            }
        }

        @Override
        public void delete(String username) throws FtpException {
            try {
                db.deleteUser(username);
            } catch (SQLException e) {
                throw new FtpException("Lỗi SQLite khi xóa user", e);
            }
        }

        @Override
        public void save(User user) throws FtpException {
            try {
                boolean canWrite = user.getAuthorities(WritePermission.class) != null
                        && !user.getAuthorities(WritePermission.class).isEmpty();
                String pwd = user.getPassword() != null ? user.getPassword() : "";
                db.upsertUser(user.getName(), pwd, user.getHomeDirectory(), canWrite, user.getEnabled());
            } catch (SQLException e) {
                throw new FtpException("Lỗi SQLite khi lưu user", e);
            }
        }

        @Override
        public boolean doesExist(String username) throws FtpException {
            return getUserByName(username) != null;
        }

        @Override
        public User authenticate(Authentication auth) throws AuthenticationFailedException {
            if (!(auth instanceof UsernamePasswordAuthentication upa)) {
                throw new AuthenticationFailedException("Chỉ hỗ trợ xác thực Username/Password.");
            }
            try {
                FtpDatabase.UserRecord rec = db.authenticate(upa.getUsername(), upa.getPassword());
                if (rec == null) {
                    throw new AuthenticationFailedException("Sai tên đăng nhập hoặc mật khẩu FTP!");
                }
                return toFtpUser(rec);
            } catch (SQLException e) {
                throw new AuthenticationFailedException("Lỗi CSDL khi xác thực: " + e.getMessage());
            }
        }

        @Override
        public String getAdminName() {
            return "admin";
        }

        @Override
        public boolean isAdmin(String username) {
            return "admin".equalsIgnoreCase(username);
        }
    }

    /** Lõi quản lý Apache FtpServer & sự kiện ghi nhận vào SQLite. */
    public static class FtpServerCore {
        private final Path rootDir;
        private final FtpDatabase db;
        private FtpServer server;
        private int port;
        private final String passivePorts;
        private volatile boolean running = false;

        private Consumer<String> logListener;
        private Runnable historyChangeListener;

        public FtpServerCore(Path rootDir, Path dbPath, int port, String passivePorts) throws SQLException, IOException {
            this.rootDir = rootDir.toAbsolutePath();
            Files.createDirectories(this.rootDir);
            this.db = new FtpDatabase(dbPath);
            this.port = port;
            this.passivePorts = passivePorts;
        }

        public void setLogListener(Consumer<String> listener) {
            this.logListener = listener;
        }

        public void setHistoryChangeListener(Runnable listener) {
            this.historyChangeListener = listener;
        }

        public FtpDatabase getDatabase() {
            return db;
        }

        public Path getRootDir() {
            return rootDir;
        }

        public int getPort() {
            return port;
        }

        public boolean isRunning() {
            return running;
        }

        private void log(String msg) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
            if (logListener != null) logListener.accept(line);
            else System.out.println(line);
        }

        public synchronized void start() throws FtpException {
            if (running) return;
            FtpServerFactory serverFactory = new FtpServerFactory();
            ListenerFactory listenerFactory = new ListenerFactory();
            listenerFactory.setPort(port);

            DataConnectionConfigurationFactory dcc = new DataConnectionConfigurationFactory();
            dcc.setPassivePorts(passivePorts);
            listenerFactory.setDataConnectionConfiguration(dcc.createDataConnectionConfiguration());

            serverFactory.addListener("default", listenerFactory.createListener());
            serverFactory.setUserManager(new SqliteUserManager(db));

            // Gắn Ftplet theo dõi chính xác kết quả lệnh PASS / STOR / RETR / DELE dựa trên FtpReply
            Map<String, Ftplet> ftplets = new LinkedHashMap<>();
            ftplets.put("audit", new DefaultFtplet() {
                @Override
                public FtpletResult beforeCommand(FtpSession session, FtpRequest request) {
                    String cmd = request.getCommand() != null ? request.getCommand().toUpperCase() : "";
                    if ("DELE".equals(cmd)) {
                        session.setAttribute("pendingDeleteSize", resolveSize(session, request));
                    }
                    return FtpletResult.DEFAULT;
                }

                @Override
                public FtpletResult afterCommand(FtpSession session, FtpRequest request, FtpReply reply) {
                    String cmd = request.getCommand() != null ? request.getCommand().toUpperCase() : "";
                    boolean ok = reply != null && reply.isPositive();
                    String code = reply != null ? String.valueOf(reply.getCode()) : "?";
                    String status = ok ? "SUCCESS" : "DENIED (" + code + ")";

                    if ("PASS".equals(cmd)) {
                        String ip = session.getClientAddress() != null && session.getClientAddress().getAddress() != null
                                ? session.getClientAddress().getAddress().getHostAddress() : "?";
                        if (session.isLoggedIn() && session.getUser() != null) {
                            log("[LOGIN OK] User '" + session.getUser().getName() + "' từ " + ip);
                        } else {
                            log("[LOGIN FAIL] Từ " + ip + " (" + code + ")");
                        }
                    } else if ("STOR".equals(cmd) || "APPE".equals(cmd)) {
                        long sz = ok ? resolveSize(session, request) : 0L;
                        recordEvent(session, request, "UPLOAD", sz, status);
                    } else if ("RETR".equals(cmd)) {
                        recordEvent(session, request, "DOWNLOAD", resolveSize(session, request), status);
                    } else if ("DELE".equals(cmd)) {
                        Object szObj = session.getAttribute("pendingDeleteSize");
                        long size = szObj instanceof Long l ? l : 0L;
                        recordEvent(session, request, "DELETE", size, status);
                    }
                    return FtpletResult.DEFAULT;
                }
            });
            serverFactory.setFtplets(ftplets);

            server = serverFactory.createServer();
            server.start();
            this.port = serverFactory.getListener("default").getPort();
            this.running = true;
            log("FTP Server đã khởi chạy tại port " + this.port + " (Passive: " + passivePorts + ", Root: " + rootDir + ")");
        }

        private long resolveSize(FtpSession session, FtpRequest request) {
            try {
                if (request.getArgument() != null) {
                    FtpFile f = session.getFileSystemView().getFile(request.getArgument());
                    if (f != null && f.doesExist()) return f.getSize();
                }
            } catch (Exception ignored) {
            }
            return 0L;
        }

        private void recordEvent(FtpSession session, FtpRequest request, String action, long size, String status) {
            String user = session.getUser() != null ? session.getUser().getName() : "anonymous";
            String ip = session.getClientAddress() != null && session.getClientAddress().getAddress() != null
                    ? session.getClientAddress().getAddress().getHostAddress() : "?";
            String filename = request.getArgument() != null ? request.getArgument() : "?";
            try {
                db.recordTransfer(user, action, filename, size, ip, status);
                log("[" + action + "] User=" + user + " | File=" + filename + " (" + size + " bytes) | " + status + " | IP=" + ip);
                if (historyChangeListener != null) historyChangeListener.run();
            } catch (SQLException e) {
                log("[Lỗi lưu lịch sử SQLite] " + e.getMessage());
            }
        }

        public synchronized void stop() {
            running = false;
            if (server != null) {
                server.stop();
                server = null;
                log("FTP Server đã dừng.");
            }
        }

        public void addOrUpdateUser(String username, String plainPassword, String subFolder, boolean canWrite)
                throws SQLException, IOException {
            Path userHome = (subFolder == null || subFolder.isBlank())
                    ? rootDir
                    : rootDir.resolve(subFolder.trim()).normalize();
            Files.createDirectories(userHome);
            db.upsertUser(username, plainPassword, userHome.toAbsolutePath().toString(), canWrite, true);
            log("[User DB] Đã lưu tài khoản '" + username + "' (Quyền ghi=" + canWrite + ", Thư mục=" + userHome + ")");
        }
    }

    // =========================================================================
    // GIAO DIỆN SWING QUẢN TRỊ FTP SERVER & SQLITE
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
            var cs = FtpServerApp.class.getProtectionDomain().getCodeSource();
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
        Path nested = cwd.resolve("LTM_5_LABS").resolve("Lab02_FTP");
        if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        Path sibling = cwd.resolve("Lab02_FTP");
        if (Files.isRegularFile(sibling.resolve("pom.xml"))) return sibling;
        return cwd;
    }

    public static void main(String[] args) {
        Path labDir = resolveLabDir();
        if (args.length > 0 && "--nogui".equals(args[0])) {
            try {
                FtpServerCore core = new FtpServerCore(
                        labDir.resolve("ftp_root"), labDir.resolve("data").resolve("ftp_lab02.db"), DEFAULT_PORT, DEFAULT_PASSIVE_PORTS);
                core.start();
                Thread.currentThread().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }
        SwingUtilities.invokeLater(() -> launchGui(DEFAULT_PORT));
    }

    public static void launchGui(int defaultPort) {
        Path labDir = resolveLabDir();
        launchGui(defaultPort, labDir.resolve("ftp_root"), labDir.resolve("data").resolve("ftp_lab02.db"));
    }

    public static void launchGui(int defaultPort, Path rootFolder, Path dbFile) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 02 — Java FTP Server & SQLite Manager");
        frame.setSize(1120, 690);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        final FtpServerCore[] serverRef = {null};

        // 1. THANH ĐIỀU KHIỂN SERVER
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JLabel lblIp = new JLabel("IP LAN: " + getLocalLanIp());
        lblIp.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblIp.setForeground(new Color(15, 118, 110));

        JTextField txtPort = new JTextField(String.valueOf(defaultPort), 5);
        JLabel lblPasv = new JLabel("Passive Ports: " + DEFAULT_PASSIVE_PORTS);
        JButton btnToggle = new JButton("Khởi chạy FTP Server");
        styleBtn(btnToggle, new Color(37, 99, 235));
        JLabel lblStatus = new JLabel("● Đã dừng");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblStatus.setForeground(new Color(220, 38, 38));

        topBar.add(lblIp);
        topBar.add(new JLabel("Control Port:"));
        topBar.add(txtPort);
        topBar.add(lblPasv);
        topBar.add(btnToggle);
        topBar.add(lblStatus);
        root.add(topBar, BorderLayout.NORTH);

        // 2. BÊN TRÁI: QUẢN LÝ TÀI KHOẢN USERS (SQLITE)
        JPanel userPanel = new JPanel(new BorderLayout(6, 6));
        userPanel.setPreferredSize(new Dimension(380, 0));
        userPanel.setBackground(Color.WHITE);
        userPanel.setBorder(new TitledBorder("Bảng SQLite: users (Tài khoản FTP)"));

        DefaultTableModel userTableModel = new DefaultTableModel(new String[]{"User", "Ghi", "Thư mục gốc"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblUsers = new JTable(userTableModel);
        userPanel.add(new JScrollPane(tblUsers), BorderLayout.CENTER);

        JPanel formUser = new JPanel(new GridLayout(5, 2, 6, 6));
        formUser.setBorder(new EmptyBorder(6, 6, 6, 6));
        formUser.setBackground(Color.WHITE);
        JTextField txtNewUser = new JTextField();
        JPasswordField txtNewPass = new JPasswordField();
        JTextField txtHomeSub = new JTextField();
        JCheckBox chkWrite = new JCheckBox("Cho phép Upload / Xóa file", true);
        chkWrite.setBackground(Color.WHITE);
        JButton btnSaveUser = new JButton("Lưu / Tạo tài khoản");
        styleBtn(btnSaveUser, new Color(5, 150, 105));
        JButton btnDelUser = new JButton("Xóa tài khoản chọn");
        styleBtn(btnDelUser, new Color(220, 38, 38));

        formUser.add(new JLabel("Username:"));
        formUser.add(txtNewUser);
        formUser.add(new JLabel("Password (nhập tay):"));
        formUser.add(txtNewPass);
        formUser.add(new JLabel("Thư mục con (tùy chọn):"));
        formUser.add(txtHomeSub);
        formUser.add(new JLabel("Quyền hạn:"));
        formUser.add(chkWrite);
        formUser.add(btnSaveUser);
        formUser.add(btnDelUser);
        userPanel.add(formUser, BorderLayout.SOUTH);

        // 3. BÊN PHẢI: LỊCH SỬ TRANSFER_HISTORY + NHẬT KÝ SERVER
        DefaultTableModel histModel = new DefaultTableModel(
                new String[]{"ID", "User", "Hành động", "Tên File", "Bytes", "Client IP", "Trạng thái", "Thời gian"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblHist = new JTable(histModel);
        JPanel histWrap = new JPanel(new BorderLayout(4, 4));
        histWrap.setBorder(new TitledBorder("Bảng SQLite: transfer_history (Upload / Download / Delete)"));
        histWrap.add(new JScrollPane(tblHist), BorderLayout.CENTER);
        JButton btnRefreshHist = new JButton("Làm mới lịch sử DB");
        histWrap.add(btnRefreshHist, BorderLayout.SOUTH);

        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(226, 232, 240));
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(new TitledBorder("Nhật ký giao thức FTP Server"));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, histWrap, scrollLog);
        rightSplit.setDividerLocation(300);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, userPanel, rightSplit);
        mainSplit.setDividerLocation(390);
        root.add(mainSplit, BorderLayout.CENTER);

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });

        Runnable refreshTables = () -> SwingUtilities.invokeLater(() -> {
            FtpServerCore srv = serverRef[0];
            if (srv == null) return;
            try {
                userTableModel.setRowCount(0);
                for (FtpDatabase.UserRecord u : srv.getDatabase().listUsers()) {
                    userTableModel.addRow(new Object[]{u.username, u.canWrite ? "Read/Write" : "Read-Only", u.homeDir});
                }
                histModel.setRowCount(0);
                for (FtpDatabase.TransferRecord tr : srv.getDatabase().listTransfers(100)) {
                    histModel.addRow(new Object[]{tr.id, tr.username, tr.action, tr.filename, tr.fileSize, tr.remoteIp, tr.status, tr.createdAt});
                }
            } catch (SQLException ex) {
                appendLog.accept("[Lỗi đọc DB] " + ex.getMessage());
            }
        });

        try {
            FtpServerCore initCore = new FtpServerCore(rootFolder, dbFile, defaultPort, DEFAULT_PASSIVE_PORTS);
            initCore.setLogListener(appendLog);
            initCore.setHistoryChangeListener(refreshTables);
            serverRef[0] = initCore;
            refreshTables.run();
            if (initCore.getDatabase().listUsers().isEmpty()) {
                appendLog.accept("Gợi ý: Hãy tạo ít nhất 1 tài khoản FTP ở bảng bên trái trước khi Client hoặc FileZilla đăng nhập.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Lỗi khởi tạo SQLite/FTP: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }

        btnSaveUser.addActionListener(e -> {
            FtpServerCore srv = serverRef[0];
            if (srv == null) return;
            String u = txtNewUser.getText().trim();
            String p = new String(txtNewPass.getPassword());
            if (u.isEmpty() || p.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Vui lòng nhập đầy đủ Username và Password.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                srv.addOrUpdateUser(u, p, txtHomeSub.getText().trim(), chkWrite.isSelected());
                txtNewPass.setText("");
                refreshTables.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi lưu user: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnDelUser.addActionListener(e -> {
            FtpServerCore srv = serverRef[0];
            int row = tblUsers.getSelectedRow();
            if (srv == null || row < 0) return;
            String u = String.valueOf(userTableModel.getValueAt(row, 0));
            try {
                srv.getDatabase().deleteUser(u);
                appendLog.accept("[User DB] Đã xóa tài khoản '" + u + "'");
                refreshTables.run();
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi xóa user: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        btnRefreshHist.addActionListener(e -> refreshTables.run());

        Runnable startServerAct = () -> {
            try {
                int port = Integer.parseInt(txtPort.getText().trim());
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Cổng (Port) phải nằm trong khoảng 1–65535.");
                }
                if (serverRef[0] == null || serverRef[0].getPort() != port) {
                    FtpServerCore next = new FtpServerCore(rootFolder, dbFile, port, DEFAULT_PASSIVE_PORTS);
                    next.setLogListener(appendLog);
                    next.setHistoryChangeListener(refreshTables);
                    serverRef[0] = next;
                }
                serverRef[0].start();
                btnToggle.setText("Dừng FTP Server");
                btnToggle.setBackground(new Color(220, 38, 38));
                lblStatus.setText("● Đang chạy (Port " + serverRef[0].getPort() + ")");
                lblStatus.setForeground(new Color(5, 150, 105));
                txtPort.setEnabled(false);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi khởi động FTP Server: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnToggle.addActionListener(e -> {
            if (serverRef[0] == null || !serverRef[0].isRunning()) {
                startServerAct.run();
            } else {
                serverRef[0].stop();
                btnToggle.setText("Khởi chạy FTP Server");
                btnToggle.setBackground(new Color(37, 99, 235));
                lblStatus.setText("● Đã dừng");
                lblStatus.setForeground(new Color(220, 38, 38));
                txtPort.setEnabled(true);
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (serverRef[0] != null) serverRef[0].stop();
            }
        });

        frame.setVisible(true);
        startServerAct.run();
    }
}
