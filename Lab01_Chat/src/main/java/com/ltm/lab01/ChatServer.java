package com.ltm.lab01;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lab 01 — TCP Chat Server sử dụng ServerSocket + ExecutorService (đa luồng).
 * Có khả năng phục vụ đồng thời nhiều Client (6+ kết nối cùng lúc):
 * - Quản lý danh sách Client online & Nhóm chat.
 * - Chuyển tiếp tin nhắn chung, cá nhân 1-1, nhóm, file đính kèm.
 * - Điều phối tín hiệu gọi và luồng nhị phân Audio / Video.
 */
public class ChatServer {

    public static final int DEFAULT_PORT = 5000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Lấy địa chỉ IP LAN thực tế của máy chạy Server. */
    public static String getLocalLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Đại diện cho 1 kết nối Client đã đăng nhập thành công. */
    static class ClientSession {
        final String username;
        final Socket socket;
        final ObjectOutputStream out;
        final Object writeLock = new Object();
        final String remoteAddress;
        volatile String activeCallPeer = null;

        ClientSession(String username, Socket socket, ObjectOutputStream out) {
            this.username = username;
            this.socket = socket;
            this.out = out;
            this.remoteAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        boolean send(ChatPacket packet) {
            synchronized (writeLock) {
                try {
                    out.writeObject(packet);
                    out.flush();
                    // reset() xóa bảng tham chiếu object để tránh tràn bộ nhớ khi gửi byte[] Audio/Video liên tục
                    out.reset();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }
    }

    /** Lõi xử lý mạng của Server (tách khỏi Swing để tái sử dụng và viết Unit Test JUnit 5). */
    public static class ChatServerCore {
        private final String bindHost;
        private int port;
        private ServerSocket serverSocket;
        private ExecutorService clientPool;
        private volatile boolean running = false;

        private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Set<String>> groups = new ConcurrentHashMap<>();

        private Consumer<String> logListener;
        private Runnable stateChangeListener;

        public ChatServerCore(String bindHost, int port) {
            this.bindHost = bindHost;
            this.port = port;
            // Tạo sẵn 1 nhóm mặc định giúp sinh viên dễ kiểm thử ngay
            groups.put("NhomLapTrinhMang", ConcurrentHashMap.newKeySet());
        }

        public void setLogListener(Consumer<String> listener) {
            this.logListener = listener;
        }

        public void setStateChangeListener(Runnable listener) {
            this.stateChangeListener = listener;
        }

        public int getPort() {
            return port;
        }

        public boolean isRunning() {
            return running;
        }

        private void log(String message) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + message;
            if (logListener != null) {
                logListener.accept(line);
            } else {
                System.out.println(line);
            }
        }

        public synchronized void start() throws IOException {
            if (running) {
                return;
            }
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindHost, port));
            this.port = serverSocket.getLocalPort();
            running = true;
            clientPool = Executors.newCachedThreadPool();

            Thread acceptThread = new Thread(this::acceptLoop, "TCP-Accept-Loop");
            acceptThread.setDaemon(true);
            acceptThread.start();

            log("Server TCP khởi động tại " + bindHost + ":" + this.port + " (IP LAN: " + getLocalLanIp() + ")");
        }

        public synchronized void stop() {
            running = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
                serverSocket = null;
            }
            for (ClientSession session : clients.values()) {
                try {
                    session.socket.close();
                } catch (IOException ignored) {
                }
            }
            clients.clear();
            for (Set<String> members : groups.values()) {
                members.clear();
            }
            if (clientPool != null) {
                clientPool.shutdownNow();
                clientPool = null;
            }
            log("Server TCP đã dừng.");
            notifyStateChanged();
        }

        private void acceptLoop() {
            while (running && serverSocket != null) {
                try {
                    Socket sock = serverSocket.accept();
                    if (clientPool != null && !clientPool.isShutdown()) {
                        clientPool.submit(() -> handleClient(sock));
                    }
                } catch (IOException e) {
                    if (running) {
                        log("[Lỗi Accept] " + e.getMessage());
                    }
                    break;
                }
            }
        }

        private void handleClient(Socket sock) {
            String remote = sock.getInetAddress().getHostAddress() + ":" + sock.getPort();
            String username = null;
            try {
                // QUAN TRỌNG: Luôn khởi tạo ObjectOutputStream và flush() TRƯỚC ObjectInputStream để tránh deadlock
                ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(sock.getInputStream());

                // Đọc gói LOGIN đầu tiên
                Object firstObj = in.readObject();
                if (!(firstObj instanceof ChatPacket loginPkt) || loginPkt.type != ChatPacket.Type.LOGIN) {
                    return;
                }

                String requested = loginPkt.sender == null ? "" : loginPkt.sender.trim();
                if (requested.isEmpty() || requested.length() > 30) {
                    ChatPacket fail = new ChatPacket(ChatPacket.Type.LOGIN_ACK);
                    fail.success = false;
                    fail.text = "Tên người dùng không hợp lệ (1-30 ký tự).";
                    out.writeObject(fail);
                    out.flush();
                    return;
                }

                ClientSession newSession = new ClientSession(requested, sock, out);
                if (clients.putIfAbsent(requested, newSession) != null) {
                    ChatPacket fail = new ChatPacket(ChatPacket.Type.LOGIN_ACK);
                    fail.success = false;
                    fail.text = "Tên '" + requested + "' đã có người sử dụng.";
                    out.writeObject(fail);
                    out.flush();
                    return;
                }

                username = requested;
                groups.computeIfAbsent("NhomLapTrinhMang", k -> ConcurrentHashMap.newKeySet()).add(username);

                ChatPacket okAck = new ChatPacket(ChatPacket.Type.LOGIN_ACK);
                okAck.success = true;
                okAck.sender = username;
                okAck.text = "Đăng nhập thành công vào TCP Server!";
                newSession.send(okAck);

                log("[+] Client [" + username + "] kết nối từ " + remote);
                broadcastStateUpdate();
                broadcastExcept(ChatPacket.simple(ChatPacket.Type.SYSTEM, "Hệ thống", username + " đã tham gia phòng chat."), username);
                notifyStateChanged();

                // Vòng lặp nhận dữ liệu liên tục từ Client này
                while (running) {
                    Object obj = in.readObject();
                    if (!(obj instanceof ChatPacket pkt)) {
                        continue;
                    }
                    pkt.sender = username;
                    routePacket(newSession, pkt);
                }
            } catch (Exception e) {
                if (running && username != null) {
                    log("[Ngắt kết nối] " + username + " (" + e.getClass().getSimpleName() + ")");
                }
            } finally {
                if (username != null) {
                    ClientSession removed = clients.remove(username);
                    for (Set<String> members : groups.values()) {
                        members.remove(username);
                    }
                    String lostPeer = removed != null ? removed.activeCallPeer : null;
                    if (lostPeer != null) {
                        ClientSession peerSess = clients.get(lostPeer);
                        if (peerSess != null && username.equals(peerSess.activeCallPeer)) {
                            peerSess.activeCallPeer = null;
                            ChatPacket endPkt = new ChatPacket(ChatPacket.Type.CALL_END);
                            endPkt.sender = username;
                            endPkt.target = lostPeer;
                            endPkt.text = "Đối phương [" + username + "] đã ngắt kết nối.";
                            peerSess.send(endPkt);
                        }
                    }
                    log("[-] Client [" + username + "] (" + remote + ") đã rời khỏi Server.");
                    broadcastStateUpdate();
                    broadcastAll(ChatPacket.simple(ChatPacket.Type.SYSTEM, "Hệ thống", username + " đã rời phòng chat."));
                    notifyStateChanged();
                }
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void routePacket(ClientSession senderSession, ChatPacket pkt) {
            String sender = senderSession.username;
            switch (pkt.type) {
                case PUBLIC_MSG -> {
                    log("[TCP Chung] " + sender + ": " + pkt.text);
                    broadcastAll(pkt);
                }
                case PRIVATE_MSG -> {
                    ClientSession target = clients.get(pkt.target);
                    if (target != null && target.send(pkt)) {
                        log("[TCP Riêng] " + sender + " -> " + pkt.target + ": " + pkt.text);
                        if (!pkt.target.equals(sender)) {
                            senderSession.send(pkt);
                        }
                    } else {
                        senderSession.send(ChatPacket.simple(ChatPacket.Type.ERROR, "Hệ thống",
                                "Người dùng '" + pkt.target + "' không trực tuyến."));
                    }
                }
                case GROUP_CREATE -> {
                    String gname = pkt.groupName == null ? "" : pkt.groupName.trim();
                    if (!gname.isEmpty()) {
                        groups.computeIfAbsent(gname, k -> ConcurrentHashMap.newKeySet()).add(sender);
                        log("[Nhóm] " + sender + " tạo/vào nhóm '" + gname + "'");
                        broadcastStateUpdate();
                        notifyStateChanged();
                    }
                }
                case GROUP_JOIN -> {
                    String gname = pkt.groupName == null ? "" : pkt.groupName.trim();
                    Set<String> members = groups.get(gname);
                    if (members != null) {
                        members.add(sender);
                        log("[Nhóm] " + sender + " tham gia '" + gname + "'");
                        broadcastStateUpdate();
                        notifyStateChanged();
                    }
                }
                case GROUP_LEAVE -> {
                    String gname = pkt.groupName == null ? "" : pkt.groupName.trim();
                    Set<String> members = groups.get(gname);
                    if (members != null) {
                        members.remove(sender);
                        log("[Nhóm] " + sender + " rời nhóm '" + gname + "'");
                        broadcastStateUpdate();
                        notifyStateChanged();
                    }
                }
                case GROUP_MSG -> {
                    String gname = pkt.groupName == null ? "" : pkt.groupName.trim();
                    Set<String> members = groups.get(gname);
                    if (members == null || !members.contains(sender)) {
                        senderSession.send(ChatPacket.simple(ChatPacket.Type.ERROR, "Hệ thống",
                                "Bạn chưa tham gia nhóm '" + gname + "'."));
                        return;
                    }
                    log("[Nhóm " + gname + "] " + sender + ": " + pkt.text);
                    for (String m : members) {
                        ClientSession s = clients.get(m);
                        if (s != null) {
                            s.send(pkt);
                        }
                    }
                }
                case FILE_TRANSFER -> {
                    int size = pkt.binaryData != null ? pkt.binaryData.length : 0;
                    log("[File] " + sender + " gửi '" + pkt.fileName + "' (" + size + " bytes) -> " + pkt.scope + ":" + pkt.target);
                    if ("private".equals(pkt.scope)) {
                        ClientSession t = clients.get(pkt.target);
                        if (t == null || !t.send(pkt)) {
                            senderSession.send(ChatPacket.simple(ChatPacket.Type.ERROR, "Hệ thống",
                                    "Không thể gửi file vì '" + pkt.target + "' đã thoát."));
                        }
                    } else if ("group".equals(pkt.scope)) {
                        Set<String> members = groups.get(pkt.target);
                        if (members != null) {
                            for (String m : members) {
                                if (!m.equals(sender)) {
                                    ClientSession s = clients.get(m);
                                    if (s != null) s.send(pkt);
                                }
                            }
                        }
                    } else {
                        broadcastExcept(pkt, sender);
                    }
                }
                case CALL_REQUEST, CALL_ACCEPT, CALL_REJECT, CALL_END -> {
                    log("[Call Signaling] " + pkt.type + ": " + sender + " -> " + pkt.target + " (" + pkt.callMode + ")");
                    ClientSession t = clients.get(pkt.target);
                    if (t != null) {
                        if (pkt.type == ChatPacket.Type.CALL_REQUEST) {
                            senderSession.activeCallPeer = t.username;
                            if (t.activeCallPeer == null) {
                                t.activeCallPeer = sender;
                            }
                        } else if (pkt.type == ChatPacket.Type.CALL_ACCEPT) {
                            senderSession.activeCallPeer = t.username;
                            t.activeCallPeer = sender;
                        } else {
                            if (Objects.equals(senderSession.activeCallPeer, pkt.target)) {
                                senderSession.activeCallPeer = null;
                            }
                            if (Objects.equals(t.activeCallPeer, sender)) {
                                t.activeCallPeer = null;
                            }
                        }
                        t.send(pkt);
                    } else if (pkt.type == ChatPacket.Type.CALL_REQUEST) {
                        ChatPacket rej = new ChatPacket(ChatPacket.Type.CALL_REJECT);
                        rej.sender = pkt.target;
                        rej.target = sender;
                        rej.text = "Người nhận '" + pkt.target + "' không trực tuyến.";
                        senderSession.send(rej);
                    } else if (Objects.equals(senderSession.activeCallPeer, pkt.target)) {
                        senderSession.activeCallPeer = null;
                    }
                }
                case AUDIO_CHUNK, VIDEO_FRAME -> {
                    ClientSession t = clients.get(pkt.target);
                    if (t != null) {
                        t.send(pkt);
                    }
                }
                default -> {
                }
            }
        }

        private void broadcastStateUpdate() {
            ChatPacket state = new ChatPacket(ChatPacket.Type.STATE_UPDATE);
            List<String> users = new ArrayList<>(clients.keySet());
            Collections.sort(users);
            state.onlineUsers = users;

            Map<String, List<String>> grpCopy = new LinkedHashMap<>();
            groups.forEach((k, v) -> {
                List<String> list = new ArrayList<>(v);
                Collections.sort(list);
                grpCopy.put(k, list);
            });
            state.groups = grpCopy;
            broadcastAll(state);
        }

        private void broadcastAll(ChatPacket pkt) {
            for (ClientSession s : clients.values()) {
                s.send(pkt);
            }
        }

        private void broadcastExcept(ChatPacket pkt, String excludeUser) {
            for (ClientSession s : clients.values()) {
                if (!s.username.equals(excludeUser)) {
                    s.send(pkt);
                }
            }
        }

        private void notifyStateChanged() {
            if (stateChangeListener != null) {
                stateChangeListener.run();
            }
        }

        public List<String> getClientDescriptions() {
            List<String> res = new ArrayList<>();
            clients.keySet().stream().sorted().forEach(u -> {
                ClientSession s = clients.get(u);
                if (s != null) {
                    res.add(u + "  (" + s.remoteAddress + ")");
                }
            });
            return res;
        }

        public Map<String, List<String>> getGroupsSnapshot() {
            Map<String, List<String>> res = new LinkedHashMap<>();
            groups.keySet().stream().sorted().forEach(g -> {
                List<String> m = new ArrayList<>(groups.getOrDefault(g, Collections.emptySet()));
                Collections.sort(m);
                res.put(g, m);
            });
            return res;
        }
    }

    // =========================================================================
    // GIAO DIỆN SWING QUẢN LÝ SERVER
    // =========================================================================
    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        if (args.length > 0 && "--nogui".equals(args[0])) {
            ChatServerCore core = new ChatServerCore("0.0.0.0", DEFAULT_PORT);
            try {
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
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 01 — Java TCP Chat Server");
        frame.setSize(920, 600);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        // Thanh điều khiển trên
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JLabel lblIp = new JLabel("IP LAN: " + getLocalLanIp());
        lblIp.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblIp.setForeground(new Color(15, 118, 110));

        JTextField txtHost = new JTextField("0.0.0.0", 9);
        JTextField txtPort = new JTextField(String.valueOf(defaultPort), 5);
        JButton btnToggle = new JButton("Khởi chạy Server");
        btnToggle.setUI(new BasicButtonUI());
        btnToggle.setBackground(new Color(37, 99, 235));
        btnToggle.setForeground(Color.WHITE);
        btnToggle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnToggle.setBorder(new EmptyBorder(6, 14, 6, 14));
        btnToggle.setFocusPainted(false);

        JLabel lblStatus = new JLabel("● Đã dừng");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblStatus.setForeground(new Color(220, 38, 38));

        topBar.add(lblIp);
        topBar.add(new JLabel("Bind Host:"));
        topBar.add(txtHost);
        topBar.add(new JLabel("Port TCP:"));
        topBar.add(txtPort);
        topBar.add(btnToggle);
        topBar.add(lblStatus);
        root.add(topBar, BorderLayout.NORTH);

        // Vùng bên trái: danh sách Client & Nhóm
        DefaultListModel<String> clientsModel = new DefaultListModel<>();
        JList<String> listClients = new JList<>(clientsModel);
        listClients.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane scrollClients = new JScrollPane(listClients);
        scrollClients.setBorder(new TitledBorder("Client đang kết nối (0)"));

        DefaultListModel<String> groupsModel = new DefaultListModel<>();
        JList<String> listGroups = new JList<>(groupsModel);
        listGroups.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane scrollGroups = new JScrollPane(listGroups);
        scrollGroups.setBorder(new TitledBorder("Nhóm Chat TCP"));

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollClients, scrollGroups);
        leftSplit.setDividerLocation(260);
        leftSplit.setPreferredSize(new Dimension(300, 0));

        // Vùng bên phải: Nhật ký Server
        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(226, 232, 240));
        JScrollPane scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(new TitledBorder("Nhật ký kết nối & truyền dữ liệu"));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, scrollLog);
        mainSplit.setDividerLocation(310);
        root.add(mainSplit, BorderLayout.CENTER);

        final ChatServerCore[] serverRef = new ChatServerCore[1];

        Runnable refreshLists = () -> SwingUtilities.invokeLater(() -> {
            ChatServerCore srv = serverRef[0];
            clientsModel.clear();
            groupsModel.clear();
            if (srv != null) {
                List<String> cls = srv.getClientDescriptions();
                scrollClients.setBorder(new TitledBorder("Client đang kết nối (" + cls.size() + ")"));
                cls.forEach(clientsModel::addElement);
                srv.getGroupsSnapshot().forEach((g, m) -> groupsModel.addElement("# " + g + " (" + m.size() + "): " + String.join(", ", m)));
            }
        });

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });

        Runnable startAction = () -> {
            try {
                int p = Integer.parseInt(txtPort.getText().trim());
                if (p < 1 || p > 65535) {
                    throw new IllegalArgumentException("Cổng (Port) phải nằm trong khoảng 1–65535.");
                }
                ChatServerCore srv = new ChatServerCore(txtHost.getText().trim(), p);
                srv.setLogListener(appendLog);
                srv.setStateChangeListener(refreshLists);
                srv.start();
                serverRef[0] = srv;

                btnToggle.setText("Dừng Server");
                btnToggle.setBackground(new Color(220, 38, 38));
                lblStatus.setText("● Đang chạy (Port " + srv.getPort() + ")");
                lblStatus.setForeground(new Color(5, 150, 105));
                txtHost.setEnabled(false);
                txtPort.setEnabled(false);
                refreshLists.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi khởi động Server: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnToggle.addActionListener(e -> {
            if (serverRef[0] == null || !serverRef[0].isRunning()) {
                startAction.run();
            } else {
                serverRef[0].stop();
                serverRef[0] = null;
                btnToggle.setText("Khởi chạy Server");
                btnToggle.setBackground(new Color(37, 99, 235));
                lblStatus.setText("● Đã dừng");
                lblStatus.setForeground(new Color(220, 38, 38));
                txtHost.setEnabled(true);
                txtPort.setEnabled(true);
                refreshLists.run();
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (serverRef[0] != null) {
                    serverRef[0].stop();
                }
            }
        });

        frame.setVisible(true);
        startAction.run();
    }
}
