package com.ltm.lab01;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamLockException;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lab 01 — Java Chat Client hỗ trợ:
 * - TCP Client/Server: Chat chung, Chat cá nhân 1-1, Chat nhóm, Gửi File đính kèm
 * - UDP Broadcast (255.255.255.255) & UDP Multicast (224.1.1.1)
 * - Gọi Thoại thật (Java Sound API PCM 16kHz) & Gọi Video thật (sarxos webcam-capture + JPEG)
 */
public class ChatClient {

    public static final int DEFAULT_UDP_BROADCAST_PORT = 5001;
    public static final String DEFAULT_MULTICAST_GROUP = "224.1.1.1";
    public static final int DEFAULT_UDP_MULTICAST_PORT = 5002;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Giao diện lắng nghe dữ liệu tin nhắn từ lõi mạng đẩy lên UI / Test. */
    @FunctionalInterface
    public interface MessageListener {
        void onMessage(String channelKey, String sender, String content, String time);
    }

    /** Giao diện lắng nghe trạng thái danh sách Online & Nhóm. */
    @FunctionalInterface
    public interface StateListener {
        void onStateUpdate(List<String> onlineUsers, Map<String, List<String>> groups);
    }

    /** Lõi xử lý mạng của Client (tách biệt với Swing để kiểm thử JUnit 5 dễ dàng). */
    public static class ChatClientCore {
        private final int broadcastPort;
        private final String multicastGroup;
        private final int multicastPort;
        private final boolean enableHardware;
        private final Path downloadsDir;

        private String username = "";
        private Socket tcpSocket;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private final Object sendLock = new Object();
        private volatile boolean connected = false;

        // Kênh UDP Broadcast & Multicast
        private DatagramSocket broadcastRecvSocket;
        private DatagramChannel multicastChannel;
        private MembershipKey multicastKey;
        private volatile boolean multicastJoined = false;

        // Trạng thái cuộc gọi Audio / Video
        private volatile String callState = "IDLE"; // IDLE | CALLING | RINGING | CONNECTED
        private volatile String callPeer = "";
        private volatile String callMode = "audio";
        private volatile boolean muted = false;
        private volatile boolean mediaRunning = false;
        private final ArrayBlockingQueue<byte[]> audioPlaybackQueue = new ArrayBlockingQueue<>(32);
        private Thread audioCaptureThread;
        private Thread audioPlayThread;
        private Thread videoThread;
        private volatile SourceDataLine speakerLine;
        private volatile TargetDataLine micLine;

        public volatile List<String> onlineUsers = new ArrayList<>();
        public volatile Map<String, List<String>> groups = new LinkedHashMap<>();

        // Callbacks
        public MessageListener onChatMessage;
        public StateListener onStateUpdate;
        public Consumer<String> onLog;
        public Consumer<String> onDisconnect;
        public BiConsumer<String, String> onIncomingCall;       // (caller, mode)
        public BiConsumer<String, String> onCallConnected;      // (peer, mode)
        public Consumer<String> onCallEnded;                    // (reason)
        public Consumer<BufferedImage> onSelfFrame;
        public Consumer<BufferedImage> onPeerFrame;
        public Consumer<String> onMediaWarning;
        public Consumer<byte[]> onRawAudioForTest;

        public ChatClientCore() {
            this(DEFAULT_UDP_BROADCAST_PORT, DEFAULT_MULTICAST_GROUP, DEFAULT_UDP_MULTICAST_PORT, true, resolveLabDir().resolve("downloads"));
        }

        public ChatClientCore(int broadcastPort, String multicastGroup, int multicastPort, boolean enableHardware, Path downloadsDir) {
            this.broadcastPort = broadcastPort;
            this.multicastGroup = multicastGroup;
            this.multicastPort = multicastPort;
            this.enableHardware = enableHardware;
            this.downloadsDir = downloadsDir;
        }

        private void log(String msg) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
            if (onLog != null) onLog.accept(line);
        }

        public boolean isConnected() { return connected; }
        public String getUsername() { return username; }
        public String getCallState() { return callState; }
        public boolean isMuted() { return muted; }
        public void setMuted(boolean muted) { this.muted = muted; }
        public boolean isMulticastJoined() { return multicastJoined; }

        /** Kết nối TCP Server và khởi động lắng nghe UDP Broadcast. */
        public void connect(String host, int port, String user) throws IOException {
            if (connected) return;
            String cleanUser = user == null ? "" : user.trim();
            if (cleanUser.isEmpty()) throw new IOException("Tên người dùng không được trống.");

            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host.trim(), port), 5000);
            // Luôn tạo ObjectOutputStream và flush() trước ObjectInputStream
            ObjectOutputStream oos = new ObjectOutputStream(sock.getOutputStream());
            oos.flush();
            ObjectInputStream ois = new ObjectInputStream(sock.getInputStream());

            ChatPacket login = new ChatPacket(ChatPacket.Type.LOGIN);
            login.sender = cleanUser;
            oos.writeObject(login);
            oos.flush();

            Object respObj;
            try {
                respObj = ois.readObject();
            } catch (ClassNotFoundException e) {
                sock.close();
                throw new IOException("Lỗi định dạng gói tin xác nhận.");
            }

            if (!(respObj instanceof ChatPacket ack) || !ack.success) {
                String reason = (respObj instanceof ChatPacket p && p.text != null) ? p.text : "Server từ chối đăng nhập.";
                sock.close();
                throw new IOException(reason);
            }

            this.tcpSocket = sock;
            this.out = oos;
            this.in = ois;
            this.username = cleanUser;
            this.connected = true;

            Thread recvThread = new Thread(this::tcpReceiveLoop, "TCP-Client-Recv-" + cleanUser);
            recvThread.setDaemon(true);
            recvThread.start();

            startBroadcastListener();
            log("Đã kết nối TCP Server " + host + ":" + port + " với tên [" + cleanUser + "]");
        }

        public void disconnect() {
            boolean wasConnected = connected;
            if (wasConnected && !"IDLE".equals(callState) && callPeer != null && !callPeer.isEmpty()) {
                ChatPacket sig = new ChatPacket("RINGING".equals(callState) ? ChatPacket.Type.CALL_REJECT : ChatPacket.Type.CALL_END);
                sig.target = callPeer;
                if ("RINGING".equals(callState)) sig.text = "Người nhận đã thoát.";
                sendPacket(sig);
            }
            connected = false;
            mediaRunning = false;
            callState = "IDLE";
            callPeer = "";
            stopMediaSession();
            leaveMulticast();

            if (broadcastRecvSocket != null) {
                broadcastRecvSocket.close();
                broadcastRecvSocket = null;
            }
            if (tcpSocket != null) {
                try {
                    tcpSocket.close();
                } catch (IOException ignored) {
                }
                tcpSocket = null;
            }
            onlineUsers = new ArrayList<>();
            groups = new LinkedHashMap<>();
            if (wasConnected) {
                log("Đã ngắt kết nối khỏi Server.");
            }
        }

        public boolean sendPacket(ChatPacket pkt) {
            if (!connected || out == null) return false;
            synchronized (sendLock) {
                try {
                    out.writeObject(pkt);
                    out.flush();
                    out.reset();
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        }

        public boolean sendPublic(String text) {
            if (text == null || text.isBlank()) return false;
            return sendPacket(ChatPacket.simple(ChatPacket.Type.PUBLIC_MSG, username, text));
        }

        public boolean sendPrivate(String target, String text) {
            if (target == null || text == null || text.isBlank()) return false;
            ChatPacket p = ChatPacket.simple(ChatPacket.Type.PRIVATE_MSG, username, text);
            p.target = target;
            return sendPacket(p);
        }

        public boolean createGroup(String groupName) {
            if (groupName == null || groupName.isBlank()) return false;
            ChatPacket p = new ChatPacket(ChatPacket.Type.GROUP_CREATE);
            p.groupName = groupName.trim();
            return sendPacket(p);
        }

        public boolean joinGroup(String groupName) {
            ChatPacket p = new ChatPacket(ChatPacket.Type.GROUP_JOIN);
            p.groupName = groupName;
            return sendPacket(p);
        }

        public boolean sendGroup(String groupName, String text) {
            if (text == null || text.isBlank()) return false;
            ChatPacket p = ChatPacket.simple(ChatPacket.Type.GROUP_MSG, username, text);
            p.groupName = groupName;
            return sendPacket(p);
        }

        public boolean sendFile(File file, String scope, String target) throws IOException {
            if (!connected) throw new IOException("Chưa kết nối Server.");
            byte[] bytes = Files.readAllBytes(file.toPath());
            ChatPacket p = new ChatPacket(ChatPacket.Type.FILE_TRANSFER);
            p.scope = scope;
            p.target = target;
            p.fileName = file.getName();
            p.binaryData = bytes;
            boolean ok = sendPacket(p);
            if (ok) log("Đã gửi file [" + file.getName() + "] (" + bytes.length + " bytes) tới " + scope);
            return ok;
        }

        // -----------------------------------------------------------------
        // UDP BROADCAST & UDP MULTICAST
        // -----------------------------------------------------------------
        private void startBroadcastListener() {
            try {
                DatagramSocket sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(broadcastPort));
                this.broadcastRecvSocket = sock;

                Thread t = new Thread(() -> udpLoop(sock, "__broadcast__"), "UDP-Broadcast-Recv");
                t.setDaemon(true);
                t.start();
                log("Đang lắng nghe UDP Broadcast trên cổng " + broadcastPort);
            } catch (IOException e) {
                log("[Cảnh báo UDP Broadcast] Không thể mở cổng " + broadcastPort + ": " + e.getMessage());
            }
        }

        public boolean sendBroadcast(String text) {
            if (text == null || text.isBlank()) return false;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                String payload = formatUdpPayload(username.isEmpty() ? "Khach" : username, text);
                byte[] buf = payload.getBytes(StandardCharsets.UTF_8);
                DatagramPacket pkt = new DatagramPacket(buf, buf.length, InetAddress.getByName("255.255.255.255"), broadcastPort);
                ds.send(pkt);
                log("Đã gửi UDP Broadcast tới 255.255.255.255:" + broadcastPort);
                return true;
            } catch (IOException e) {
                log("[Lỗi UDP Broadcast] " + e.getMessage());
                return false;
            }
        }

        public synchronized boolean joinMulticast() {
            if (multicastJoined) return true;
            try {
                // Sử dụng DatagramChannel(StandardProtocolFamily.INET) để luôn ép IPv4 thuần kể cả khi không truyền -Djava.net.preferIPv4Stack=true
                DatagramChannel dc = DatagramChannel.open(StandardProtocolFamily.INET);
                dc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                dc.bind(new InetSocketAddress(multicastPort));
                InetAddress groupAddr = InetAddress.getByName(multicastGroup);
                NetworkInterface ni = findBestMulticastInterface();
                if (ni == null) {
                    ni = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
                }
                this.multicastKey = dc.join(groupAddr, ni);
                this.multicastChannel = dc;
                this.multicastJoined = true;

                Thread t = new Thread(() -> multicastReceiveLoop(dc), "UDP-Multicast-Recv");
                t.setDaemon(true);
                t.start();
                log("Đã tham gia nhóm UDP Multicast " + multicastGroup + ":" + multicastPort + " (" + ni.getName() + ")");
                return true;
            } catch (IOException e) {
                log("[Lỗi UDP Multicast] " + e.getMessage());
                return false;
            }
        }

        public synchronized void leaveMulticast() {
            multicastJoined = false;
            if (multicastKey != null) {
                try {
                    multicastKey.drop();
                } catch (Exception ignored) {
                }
                multicastKey = null;
            }
            if (multicastChannel != null) {
                try {
                    multicastChannel.close();
                } catch (IOException ignored) {
                }
                multicastChannel = null;
                log("Đã rời nhóm UDP Multicast " + multicastGroup);
            }
        }

        public boolean sendMulticast(String text) {
            if (text == null || text.isBlank()) return false;
            try (DatagramChannel dc = DatagramChannel.open(StandardProtocolFamily.INET)) {
                NetworkInterface ni = findBestMulticastInterface();
                if (ni != null) {
                    dc.setOption(StandardSocketOptions.IP_MULTICAST_IF, ni);
                }
                dc.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 2);
                String payload = formatUdpPayload(username.isEmpty() ? "Khach" : username, text);
                byte[] buf = payload.getBytes(StandardCharsets.UTF_8);
                dc.send(ByteBuffer.wrap(buf), new InetSocketAddress(InetAddress.getByName(multicastGroup), multicastPort));
                log("Đã gửi UDP Multicast tới " + multicastGroup + ":" + multicastPort);
                return true;
            } catch (IOException e) {
                log("[Lỗi UDP Multicast] " + e.getMessage());
                return false;
            }
        }

        private void multicastReceiveLoop(DatagramChannel dc) {
            ByteBuffer bb = ByteBuffer.allocate(8192);
            while (dc.isOpen()) {
                try {
                    bb.clear();
                    SocketAddress src = dc.receive(bb);
                    bb.flip();
                    byte[] data = new byte[bb.remaining()];
                    bb.get(data);
                    String srcIp = (src instanceof InetSocketAddress isa && isa.getAddress() != null)
                            ? isa.getAddress().getHostAddress() : "?";
                    dispatchUdpText("__multicast__", srcIp, new String(data, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    break;
                }
            }
        }

        private void dispatchUdpText(String channelKey, String srcIp, String raw) {
            String[] parts = raw.split("\t", 3);
            String sender = (parts.length == 3 ? parts[0] : "?") + " (" + srcIp + ")";
            String tStr = parts.length == 3 ? parts[1] : LocalTime.now().format(TIME_FMT);
            String content = parts.length == 3 ? parts[2] : raw;
            if (onChatMessage != null) {
                onChatMessage.onMessage(channelKey, sender, content, tStr);
            }
        }

        private NetworkInterface findBestMulticastInterface() throws SocketException {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface fallback = null;
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || !ni.supportsMulticast()) continue;
                boolean hasIpv4 = Collections.list(ni.getInetAddresses()).stream().anyMatch(a -> a instanceof Inet4Address);
                if (!hasIpv4) continue;
                if (!ni.isLoopback()) return ni;
                fallback = ni;
            }
            return fallback;
        }

        private String formatUdpPayload(String sender, String text) {
            return sender + "\t" + LocalTime.now().format(TIME_FMT) + "\t" + text;
        }

        private void udpLoop(DatagramSocket sock, String channelKey) {
            byte[] buf = new byte[8192];
            while (!sock.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    sock.receive(pkt);
                    String raw = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    dispatchUdpText(channelKey, pkt.getAddress().getHostAddress(), raw);
                } catch (IOException e) {
                    break;
                }
            }
        }

        // -----------------------------------------------------------------
        // NHẬN DỮ LIỆU TCP & GỌI AUDIO / VIDEO THỰC TẾ
        // -----------------------------------------------------------------
        private void tcpReceiveLoop() {
            try {
                while (connected && in != null) {
                    Object obj = in.readObject();
                    if (obj instanceof ChatPacket pkt) {
                        handleIncomingPacket(pkt);
                    }
                }
            } catch (Exception e) {
                if (connected) {
                    connected = false;
                    mediaRunning = false;
                    callState = "IDLE";
                    callPeer = "";
                    stopMediaSession();
                    log("Mất kết nối với TCP Server: " + e.getMessage());
                    if (onDisconnect != null) onDisconnect.accept("Mất kết nối với Server.");
                }
            }
        }

        private void handleIncomingPacket(ChatPacket pkt) {
            switch (pkt.type) {
                case STATE_UPDATE -> {
                    this.onlineUsers = pkt.onlineUsers != null ? pkt.onlineUsers : new ArrayList<>();
                    this.groups = pkt.groups != null ? pkt.groups : new LinkedHashMap<>();
                    if (!"IDLE".equals(callState) && !callPeer.isEmpty() && !this.onlineUsers.contains(callPeer)) {
                        String lost = callPeer;
                        mediaRunning = false;
                        callState = "IDLE";
                        callPeer = "";
                        stopMediaSession();
                        log("Đối phương [" + lost + "] đã rời khỏi Server.");
                        if (onCallEnded != null) onCallEnded.accept("Đối phương [" + lost + "] đã rời khỏi Server.");
                    }
                    if (onStateUpdate != null) onStateUpdate.onStateUpdate(this.onlineUsers, this.groups);
                }
                case SYSTEM, ERROR -> {
                    log(pkt.text);
                    if (onChatMessage != null) onChatMessage.onMessage("__public__", pkt.sender, pkt.text, pkt.timestamp);
                }
                case PUBLIC_MSG -> {
                    if (onChatMessage != null) onChatMessage.onMessage("__public__", pkt.sender, pkt.text, pkt.timestamp);
                }
                case PRIVATE_MSG -> {
                    String partner = pkt.sender.equals(username) ? pkt.target : pkt.sender;
                    if (onChatMessage != null) onChatMessage.onMessage("user:" + partner, pkt.sender, pkt.text, pkt.timestamp);
                }
                case GROUP_MSG -> {
                    if (onChatMessage != null) onChatMessage.onMessage("group:" + pkt.groupName, pkt.sender, pkt.text, pkt.timestamp);
                }
                case FILE_TRANSFER -> {
                    try {
                        Path saved = saveReceivedFile(pkt.fileName, pkt.binaryData != null ? pkt.binaryData : new byte[0]);
                        int kb = (pkt.binaryData != null ? pkt.binaryData.length : 0);
                        String notice = "📎 Đã gửi file [" + pkt.fileName + "] (" + kb + " bytes) -> Lưu tại: " + saved.toAbsolutePath();
                        log("Nhận file [" + pkt.fileName + "] từ " + pkt.sender + " -> " + saved);
                        String ch = "private".equals(pkt.scope) ? "user:" + pkt.sender
                                : ("group".equals(pkt.scope) ? "group:" + pkt.target : "__public__");
                        if (onChatMessage != null) onChatMessage.onMessage(ch, pkt.sender, notice, pkt.timestamp);
                    } catch (IOException e) {
                        log("[Lỗi lưu file nhận] " + e.getMessage());
                    }
                }
                case CALL_REQUEST -> {
                    if (!"IDLE".equals(callState)) {
                        rejectCall(pkt.sender, "Người nhận đang bận.");
                        return;
                    }
                    callState = "RINGING";
                    callPeer = pkt.sender;
                    callMode = pkt.callMode != null ? pkt.callMode : "audio";
                    log("Cuộc gọi " + callMode.toUpperCase() + " đến từ [" + pkt.sender + "]");
                    if (onIncomingCall != null) onIncomingCall.accept(pkt.sender, callMode);
                }
                case CALL_ACCEPT -> {
                    if (!"CALLING".equals(callState) || !Objects.equals(pkt.sender, callPeer)) {
                        return;
                    }
                    callState = "CONNECTED";
                    callPeer = pkt.sender;
                    callMode = pkt.callMode != null ? pkt.callMode : callMode;
                    log("[" + pkt.sender + "] đã chấp nhận cuộc gọi " + callMode.toUpperCase());
                    if (enableHardware) startMediaSession(callMode);
                    if (onCallConnected != null) onCallConnected.accept(pkt.sender, callMode);
                }
                case CALL_REJECT -> {
                    if ("IDLE".equals(callState) || (pkt.sender != null && !callPeer.isEmpty() && !Objects.equals(pkt.sender, callPeer))) {
                        return;
                    }
                    mediaRunning = false;
                    callState = "IDLE";
                    callPeer = "";
                    stopMediaSession();
                    log("Cuộc gọi bị từ chối: " + pkt.text);
                    if (onCallEnded != null) onCallEnded.accept(pkt.text != null ? pkt.text : "Cuộc gọi bị từ chối.");
                }
                case CALL_END -> {
                    if ("IDLE".equals(callState) || (pkt.sender != null && !callPeer.isEmpty() && !Objects.equals(pkt.sender, callPeer))) {
                        return;
                    }
                    String peer = callPeer;
                    String reason = (pkt.text != null && !pkt.text.isBlank()) ? pkt.text : "Đối phương đã kết thúc cuộc gọi.";
                    mediaRunning = false;
                    callState = "IDLE";
                    callPeer = "";
                    stopMediaSession();
                    log("[" + peer + "] " + reason);
                    if (onCallEnded != null) onCallEnded.accept(reason);
                }
                case AUDIO_CHUNK -> {
                    if (onRawAudioForTest != null && pkt.binaryData != null) {
                        onRawAudioForTest.accept(pkt.binaryData);
                    }
                    playIncomingAudio(pkt.binaryData);
                }
                case VIDEO_FRAME -> decodeIncomingVideo(pkt.binaryData);
                default -> {
                }
            }
        }

        private Path saveReceivedFile(String rawName, byte[] data) throws IOException {
            Files.createDirectories(downloadsDir);
            String cleanName = new File(rawName == null ? "file.bin" : rawName).getName();
            if (cleanName.isEmpty()) cleanName = "file.bin";
            Path target = downloadsDir.resolve(username + "_" + cleanName);
            Files.write(target, data);
            return target;
        }

        public boolean startCall(String target, String mode) {
            if (!connected || !"IDLE".equals(callState) || target == null || target.isBlank() || target.equals(username)) {
                return false;
            }
            callState = "CALLING";
            callPeer = target;
            callMode = mode;
            ChatPacket req = new ChatPacket(ChatPacket.Type.CALL_REQUEST);
            req.target = target;
            req.callMode = mode;
            log("Đang gọi " + mode.toUpperCase() + " tới [" + target + "]...");
            boolean sent = sendPacket(req);
            if (!sent) {
                callState = "IDLE";
                callPeer = "";
            }
            return sent;
        }

        public void acceptCall(String caller, String mode) {
            if (!connected || caller == null || caller.isBlank()) return;
            callState = "CONNECTED";
            callPeer = caller;
            callMode = mode;
            ChatPacket acc = new ChatPacket(ChatPacket.Type.CALL_ACCEPT);
            acc.target = caller;
            acc.callMode = mode;
            if (!sendPacket(acc)) {
                callState = "IDLE";
                callPeer = "";
                return;
            }
            log("Đã chấp nhận cuộc gọi " + mode.toUpperCase() + " từ [" + caller + "]");
            if (enableHardware) startMediaSession(mode);
        }

        public void rejectCall(String caller, String reason) {
            if (caller == null || caller.isBlank()) return;
            ChatPacket rej = new ChatPacket(ChatPacket.Type.CALL_REJECT);
            rej.target = caller;
            rej.text = reason;
            sendPacket(rej);
            if (caller.equals(callPeer)) {
                callState = "IDLE";
                callPeer = "";
            }
        }

        public void endCall() {
            if ("IDLE".equals(callState) && !mediaRunning) return;
            String peer = callPeer;
            mediaRunning = false;
            callState = "IDLE";
            callPeer = "";
            if (peer != null && !peer.isEmpty()) {
                ChatPacket end = new ChatPacket(ChatPacket.Type.CALL_END);
                end.target = peer;
                sendPacket(end);
            }
            stopMediaSession();
            log("Đã kết thúc cuộc gọi.");
        }

        /** Khởi động thu/phát Microphone, Loa (Java Sound API) và Camera (sarxos webcam-capture). */
        public synchronized void startMediaSession(String mode) {
            stopMediaSession();
            mediaRunning = true;
            muted = false;
            audioPlaybackQueue.clear();
            if (!enableHardware) {
                return;
            }

            // 1. Luồng phát loa riêng biệt (không làm treo luồng đọc TCP)
            audioPlayThread = new Thread(this::audioPlaybackLoop, "Media-Speaker-Worker");
            audioPlayThread.setDaemon(true);
            audioPlayThread.start();

            // 2. Luồng thu Microphone (PCM 16kHz, 16-bit, Mono)
            audioCaptureThread = new Thread(this::audioCaptureLoop, "Media-Mic-Worker");
            audioCaptureThread.setDaemon(true);
            audioCaptureThread.start();

            // 3. Luồng thu/phát Video (nếu là chế độ Video Call)
            if ("video".equals(mode)) {
                videoThread = new Thread(this::videoCaptureLoop, "Media-Video-Worker");
                videoThread.setDaemon(true);
                videoThread.start();
            }
        }

        private void audioPlaybackLoop() {
            AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
            SourceDataLine spk = null;
            try {
                DataLine.Info spkInfo = new DataLine.Info(SourceDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(spkInfo)) {
                    warnMedia("Không tìm thấy thiết bị Loa phù hợp (PCM 16kHz).");
                    return;
                }
                spk = (SourceDataLine) AudioSystem.getLine(spkInfo);
                spk.open(fmt, 8192);
                spk.start();
                speakerLine = spk;

                while (mediaRunning && "CONNECTED".equals(callState)) {
                    byte[] pcm = audioPlaybackQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm != null) {
                        spk.write(pcm, 0, pcm.length);
                    }
                }
            } catch (Exception e) {
                if (mediaRunning) {
                    warnMedia("Lỗi loa phát âm thanh: " + e.getMessage());
                }
            } finally {
                speakerLine = null;
                if (spk != null) {
                    try { spk.stop(); spk.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void audioCaptureLoop() {
            AudioFormat fmt = new AudioFormat(16000f, 16, 1, true, false);
            TargetDataLine mic = null;
            try {
                DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(micInfo)) {
                    warnMedia("Không tìm thấy Microphone trên máy tính này.");
                    return;
                }
                mic = (TargetDataLine) AudioSystem.getLine(micInfo);
                mic.open(fmt, 4096);
                mic.start();
                micLine = mic;
                log("Microphone & Loa (Java Sound PCM 16kHz) đã sẵn sàng.");

                byte[] buf = new byte[1024];
                while (mediaRunning && "CONNECTED".equals(callState)) {
                    int n = mic.read(buf, 0, buf.length);
                    if (n > 0 && !muted && !callPeer.isEmpty()) {
                        ChatPacket pkt = new ChatPacket(ChatPacket.Type.AUDIO_CHUNK);
                        pkt.target = callPeer;
                        pkt.binaryData = Arrays.copyOf(buf, n);
                        sendPacket(pkt);
                    }
                }
            } catch (Exception e) {
                if (mediaRunning) {
                    warnMedia("Lỗi thiết bị Microphone: " + e.getMessage());
                }
            } finally {
                micLine = null;
                if (mic != null) {
                    try { mic.stop(); mic.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void videoCaptureLoop() {
            Webcam cam = null;
            try {
                cam = Webcam.getDefault();
                if (cam == null) {
                    warnMedia("Không tìm thấy Camera trên máy này (bạn vẫn có thể xem Video từ đối phương).");
                    return;
                }
                Dimension[] supportedSizes = cam.getViewSizes();
                if (supportedSizes != null && supportedSizes.length > 0) {
                    Dimension chosen = supportedSizes[0];
                    for (Dimension d : supportedSizes) {
                        if (d.width == 320 && d.height == 240) {
                            chosen = d;
                            break;
                        }
                        if (d.width >= 320 && d.height >= 240 && (chosen.width < 320 || d.width < chosen.width)) {
                            chosen = d;
                        }
                    }
                    cam.setViewSize(chosen);
                }
                cam.open();
                log("Đã mở Webcam [" + cam.getName() + "] truyền khung hình JPEG.");

                while (mediaRunning && "CONNECTED".equals(callState)) {
                    long t0 = System.currentTimeMillis();
                    BufferedImage frame = cam.getImage();
                    if (frame != null) {
                        if (onSelfFrame != null) onSelfFrame.accept(frame);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(frame, "jpg", baos);
                        if (!callPeer.isEmpty()) {
                            ChatPacket pkt = new ChatPacket(ChatPacket.Type.VIDEO_FRAME);
                            pkt.target = callPeer;
                            pkt.binaryData = baos.toByteArray();
                            sendPacket(pkt);
                        }
                    }
                    long sleep = Math.max(10, 66 - (System.currentTimeMillis() - t0)); // ~15 FPS
                    Thread.sleep(sleep);
                }
            } catch (WebcamLockException lockEx) {
                if (mediaRunning) {
                    warnMedia("Webcam đang được cửa sổ/app khác dùng (1 máy chỉ mở 1 Camera; vẫn xem được đối phương).");
                }
            } catch (Throwable t) {
                if (mediaRunning) {
                    warnMedia("Không mở được Webcam: " + t.getMessage());
                }
            } finally {
                Thread.interrupted();
                if (cam != null && cam.isOpen()) {
                    try {
                        cam.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void playIncomingAudio(byte[] pcm) {
            if (!mediaRunning || pcm == null || speakerLine == null) return;
            // Đưa vào hàng đợi phát; nếu bị đầy thì bỏ bớt khung cũ để giữ độ trễ thấp
            if (!audioPlaybackQueue.offer(pcm)) {
                audioPlaybackQueue.poll();
                audioPlaybackQueue.offer(pcm);
            }
        }

        private void decodeIncomingVideo(byte[] jpeg) {
            if (!mediaRunning || onPeerFrame == null || jpeg == null) return;
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                if (img != null) onPeerFrame.accept(img);
            } catch (IOException ignored) {
            }
        }

        private void warnMedia(String msg) {
            log("[Cảnh báo Media] " + msg);
            if (onMediaWarning != null) onMediaWarning.accept(msg);
        }

        private void closeAudioLines() {
            if (micLine != null) {
                try { micLine.stop(); micLine.close(); } catch (Exception ignored) {}
                micLine = null;
            }
            if (speakerLine != null) {
                try { speakerLine.stop(); speakerLine.close(); } catch (Exception ignored) {}
                speakerLine = null;
            }
        }

        public synchronized void stopMediaSession() {
            mediaRunning = false;
            audioPlaybackQueue.clear();
            closeAudioLines();
            if (videoThread != null && videoThread.isAlive() && Thread.currentThread() != videoThread) {
                try { videoThread.join(2500); } catch (InterruptedException ignored) {}
            }
            if (audioCaptureThread != null && audioCaptureThread.isAlive() && Thread.currentThread() != audioCaptureThread) {
                try { audioCaptureThread.join(1000); } catch (InterruptedException ignored) {}
            }
            if (audioPlayThread != null && audioPlayThread.isAlive() && Thread.currentThread() != audioPlayThread) {
                try { audioPlayThread.join(1000); } catch (InterruptedException ignored) {}
            }
            videoThread = null;
            audioCaptureThread = null;
            audioPlayThread = null;
        }
    }

    // =========================================================================
    // GIAO DIỆN JAVA SWING CHO CLIENT
    // =========================================================================
    static Path resolveLabDir() {
        try {
            var cs = ChatClient.class.getProtectionDomain().getCodeSource();
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
        Path nested = cwd.resolve("LTM_5_LABS").resolve("Lab01_Chat");
        if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        Path sibling = cwd.resolve("Lab01_Chat");
        if (Files.isRegularFile(sibling.resolve("pom.xml"))) return sibling;
        return cwd;
    }

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        SwingUtilities.invokeLater(ChatClient::launchGui);
    }

    private static void styleActionButton(JButton btn, Color bg) {
        btn.setUI(new BasicButtonUI());
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public static void launchGui() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 01 — Java Chat Client (TCP + UDP + Audio/Video)");
        frame.setSize(1120, 690);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        ChatClientCore core = new ChatClientCore();
        Map<String, List<String>> chatHistories = new ConcurrentHashMap<>();
        chatHistories.put("__public__", Collections.synchronizedList(new ArrayList<>()));
        chatHistories.put("__broadcast__", Collections.synchronizedList(new ArrayList<>()));
        chatHistories.put("__multicast__", Collections.synchronizedList(new ArrayList<>()));
        final String[] activeChannel = {"__public__"};
        final JDialog[] activeCallDialog = {null};
        final JLabel[] activeCallStatusLabel = {null};
        final JDialog[] incomingDialog = {null};

        // 1. THANH KẾT NỐI TRÊN CÙNG
        JPanel connBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        connBar.setBackground(Color.WHITE);
        connBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JTextField txtIp = new JTextField("127.0.0.1", 11);
        JTextField txtPort = new JTextField(String.valueOf(ChatServer.DEFAULT_PORT), 5);
        JTextField txtUser = new JTextField("SinhVien1", 11);
        JButton btnConn = new JButton("Kết nối Server");
        styleActionButton(btnConn, new Color(37, 99, 235));

        JLabel lblConnState = new JLabel("● Chưa kết nối");
        lblConnState.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblConnState.setForeground(new Color(220, 38, 38));

        connBar.add(new JLabel("IP Server:"));
        connBar.add(txtIp);
        connBar.add(new JLabel("Port:"));
        connBar.add(txtPort);
        connBar.add(new JLabel("Tên bạn:"));
        connBar.add(txtUser);
        connBar.add(btnConn);
        connBar.add(Box.createHorizontalStrut(12));
        connBar.add(lblConnState);
        root.add(connBar, BorderLayout.NORTH);

        // 2. CỘT TRÁI: KÊNH + NGƯỜI ONLINE + NHÓM + MULTICAST
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setPreferredSize(new Dimension(255, 0));
        leftPanel.setOpaque(false);

        JPanel udpCard = new JPanel(new GridLayout(4, 1, 4, 4));
        udpCard.setBackground(Color.WHITE);
        udpCard.setBorder(new TitledBorder("Kênh Giao Tiếp"));
        JButton btnChPublic = new JButton("Chat Chung (TCP)");
        JButton btnChBroadcast = new JButton("UDP Broadcast (LAN)");
        JButton btnChMulticast = new JButton("UDP Multicast (224.1.1.1)");
        JCheckBox chkMulticast = new JCheckBox("Tham gia nhóm Multicast");
        chkMulticast.setBackground(Color.WHITE);
        udpCard.add(btnChPublic);
        udpCard.add(btnChBroadcast);
        udpCard.add(btnChMulticast);
        udpCard.add(chkMulticast);
        leftPanel.add(udpCard, BorderLayout.NORTH);

        DefaultListModel<String> usersModel = new DefaultListModel<>();
        JList<String> listUsers = new JList<>(usersModel);
        listUsers.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane scrollUsers = new JScrollPane(listUsers);
        scrollUsers.setBorder(new TitledBorder("Người dùng Online (Chat 1-1)"));

        JPanel grpWrap = new JPanel(new BorderLayout(4, 4));
        grpWrap.setBorder(new TitledBorder("Nhóm Chat (TCP)"));
        grpWrap.setBackground(Color.WHITE);
        JPanel grpAddBar = new JPanel(new BorderLayout(4, 0));
        JTextField txtGroup = new JTextField();
        JButton btnAddGroup = new JButton("+ Nhóm");
        grpAddBar.add(txtGroup, BorderLayout.CENTER);
        grpAddBar.add(btnAddGroup, BorderLayout.EAST);
        DefaultListModel<String> groupsModel = new DefaultListModel<>();
        JList<String> listGroups = new JList<>(groupsModel);
        grpWrap.add(grpAddBar, BorderLayout.NORTH);
        grpWrap.add(new JScrollPane(listGroups), BorderLayout.CENTER);

        JSplitPane leftLists = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollUsers, grpWrap);
        leftLists.setDividerLocation(190);
        leftPanel.add(leftLists, BorderLayout.CENTER);

        // 3. KHU VỰC CHAT CHÍNH Ở GIỮA
        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.setBackground(Color.WHITE);
        centerPanel.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JPanel chatHeader = new JPanel(new BorderLayout(6, 6));
        chatHeader.setBackground(new Color(248, 250, 252));
        chatHeader.setBorder(new EmptyBorder(6, 10, 6, 10));
        JLabel lblChannelTitle = new JLabel("Phòng Chat Chung (TCP Server)");
        lblChannelTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actionBtns.setOpaque(false);
        JButton btnFile = new JButton("Gửi File");
        styleActionButton(btnFile, new Color(71, 85, 105));
        JButton btnAudio = new JButton("Gọi Thoại");
        styleActionButton(btnAudio, new Color(148, 163, 184));
        JButton btnVideo = new JButton("Gọi Video");
        styleActionButton(btnVideo, new Color(148, 163, 184));
        String callHintTip = "Chọn 1 người trong 'Người dùng Online (Chat 1-1)' bên trái để bật Gọi Thoại / Gọi Video";
        btnAudio.setToolTipText(callHintTip);
        btnVideo.setToolTipText(callHintTip);
        actionBtns.add(btnFile);
        actionBtns.add(btnAudio);
        actionBtns.add(btnVideo);
        chatHeader.add(lblChannelTitle, BorderLayout.WEST);
        chatHeader.add(actionBtns, BorderLayout.EAST);
        centerPanel.add(chatHeader, BorderLayout.NORTH);

        JTextArea txtChat = new JTextArea();
        txtChat.setEditable(false);
        txtChat.setLineWrap(true);
        txtChat.setWrapStyleWord(true);
        txtChat.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));
        centerPanel.add(new JScrollPane(txtChat), BorderLayout.CENTER);

        JPanel bottomInput = new JPanel(new BorderLayout(4, 4));
        bottomInput.setBorder(new EmptyBorder(4, 8, 8, 8));
        bottomInput.setBackground(Color.WHITE);

        // Thanh Emoji Picker
        JPanel emojiPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        emojiPanel.setBackground(Color.WHITE);
        emojiPanel.add(new JLabel("Emoji:"));
        JTextField txtInput = new JTextField();
        txtInput.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));
        for (String em : new String[]{"😀", "😂", "😍", "👍", "❤️", "🎉", "🔥", "😎", "😭", "🙏"}) {
            JButton eb = new JButton(em);
            eb.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
            eb.setMargin(new Insets(2, 6, 2, 6));
            eb.addActionListener(ev -> {
                txtInput.setText(txtInput.getText() + em);
                txtInput.requestFocusInWindow();
            });
            emojiPanel.add(eb);
        }
        bottomInput.add(emojiPanel, BorderLayout.NORTH);

        JPanel sendRow = new JPanel(new BorderLayout(6, 0));
        JButton btnSend = new JButton("Gửi");
        styleActionButton(btnSend, new Color(37, 99, 235));
        sendRow.add(txtInput, BorderLayout.CENTER);
        sendRow.add(btnSend, BorderLayout.EAST);
        bottomInput.add(sendRow, BorderLayout.CENTER);
        centerPanel.add(bottomInput, BorderLayout.SOUTH);

        // 4. CỘT PHẢI: NHẬT KÝ SỰ KIỆN MẠNG
        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(txtLog);
        logScroll.setPreferredSize(new Dimension(265, 0));
        logScroll.setBorder(new TitledBorder("Nhật ký sự kiện & lỗi"));

        JPanel workspace = new JPanel(new BorderLayout(8, 0));
        workspace.setOpaque(false);
        workspace.add(leftPanel, BorderLayout.WEST);
        workspace.add(centerPanel, BorderLayout.CENTER);
        workspace.add(logScroll, BorderLayout.EAST);
        root.add(workspace, BorderLayout.CENTER);

        // HÀM TIỆN ÍCH CẬP NHẬT UI
        Runnable refreshChat = () -> {
            txtChat.setText("");
            List<String> lines = chatHistories.getOrDefault(activeChannel[0], Collections.emptyList());
            synchronized (lines) {
                for (String line : lines) {
                    txtChat.append(line + "\n");
                }
            }
            txtChat.setCaretPosition(txtChat.getDocument().getLength());
        };

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });

        Runnable updateCallButtonsUi = () -> {
            boolean readyPrivate = core.isConnected() && activeChannel[0].startsWith("user:");
            btnAudio.setBackground(readyPrivate ? new Color(5, 150, 105) : new Color(148, 163, 184));
            btnVideo.setBackground(readyPrivate ? new Color(79, 70, 229) : new Color(148, 163, 184));
            String tip = readyPrivate
                    ? "Bấm để gọi 1-1 tới [" + activeChannel[0].substring(5) + "]"
                    : callHintTip;
            btnAudio.setToolTipText(tip);
            btnVideo.setToolTipText(tip);
        };

        BiConsumer<String, String> switchChannel = (chKey, title) -> {
            activeChannel[0] = chKey;
            chatHistories.computeIfAbsent(chKey, k -> Collections.synchronizedList(new ArrayList<>()));
            lblChannelTitle.setText(title);
            updateCallButtonsUi.run();
            btnFile.setEnabled(!chKey.startsWith("__broadcast__") && !chKey.startsWith("__multicast__"));
            refreshChat.run();
        };

        btnChPublic.addActionListener(e -> {
            listUsers.clearSelection();
            listGroups.clearSelection();
            switchChannel.accept("__public__", "Phòng Chat Chung (TCP Server)");
        });
        btnChBroadcast.addActionListener(e -> {
            listUsers.clearSelection();
            listGroups.clearSelection();
            switchChannel.accept("__broadcast__", "Kênh UDP Broadcast (255.255.255.255:" + core.broadcastPort + ")");
        });
        btnChMulticast.addActionListener(e -> {
            listUsers.clearSelection();
            listGroups.clearSelection();
            switchChannel.accept("__multicast__", "Kênh UDP Multicast (" + core.multicastGroup + ":" + core.multicastPort + ")");
        });

        chkMulticast.addActionListener(e -> {
            if (chkMulticast.isSelected()) {
                if (!core.joinMulticast()) {
                    chkMulticast.setSelected(false);
                    JOptionPane.showMessageDialog(frame, "Không thể tham gia nhóm Multicast.", "Lỗi UDP", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                core.leaveMulticast();
            }
        });

        listUsers.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String sel = listUsers.getSelectedValue();
                if (sel != null && !sel.endsWith("(Bạn)")) {
                    listGroups.clearSelection();
                    String targetCh = "user:" + sel;
                    if (!targetCh.equals(activeChannel[0])) {
                        switchChannel.accept(targetCh, "Chat Cá Nhân (TCP) với [" + sel + "]");
                    }
                }
            }
        });

        listGroups.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String sel = listGroups.getSelectedValue();
                if (sel != null) {
                    listUsers.clearSelection();
                    int idx = sel.lastIndexOf(" (");
                    String gname = idx > 0 ? sel.substring(0, idx) : sel;
                    core.joinGroup(gname);
                    switchChannel.accept("group:" + gname, "Nhóm Chat TCP [" + gname + "]");
                }
            }
        });

        btnAddGroup.addActionListener(e -> {
            if (!core.isConnected()) {
                JOptionPane.showMessageDialog(frame, "Hãy kết nối tới TCP Server trước!", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String g = txtGroup.getText().trim();
            if (!g.isEmpty()) {
                core.createGroup(g);
                txtGroup.setText("");
                switchChannel.accept("group:" + g, "Nhóm Chat TCP [" + g + "]");
            }
        });

        Runnable sendAction = () -> {
            String text = txtInput.getText().trim();
            if (text.isEmpty()) return;
            txtInput.setText("");

            String ch = activeChannel[0];
            if ("__broadcast__".equals(ch)) {
                core.sendBroadcast(text);
                return;
            }
            if ("__multicast__".equals(ch)) {
                core.sendMulticast(text);
                return;
            }
            if (!core.isConnected()) {
                JOptionPane.showMessageDialog(frame, "Hãy kết nối tới TCP Server trước!", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if ("__public__".equals(ch)) core.sendPublic(text);
            else if (ch.startsWith("user:")) core.sendPrivate(ch.substring(5), text);
            else if (ch.startsWith("group:")) core.sendGroup(ch.substring(6), text);
        };
        btnSend.addActionListener(e -> sendAction.run());
        txtInput.addActionListener(e -> sendAction.run());

        btnFile.addActionListener(e -> {
            if (!core.isConnected()) {
                JOptionPane.showMessageDialog(frame, "Vui lòng kết nối Server trước.", "Thông báo", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                String ch = activeChannel[0];
                String scope = ch.startsWith("user:") ? "private" : (ch.startsWith("group:") ? "group" : "public");
                String target = ch.contains(":") ? ch.split(":", 2)[1] : "";
                try {
                    core.sendFile(f, scope, target);
                    chatHistories.computeIfAbsent(ch, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add("[" + LocalTime.now().format(TIME_FMT) + "] Bạn: 📎 Đã gửi file [" + f.getName() + "]");
                    refreshChat.run();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(frame, "Lỗi gửi file: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // CỬA SỔ CUỘC GỌI AUDIO & VIDEO
        BiConsumer<String, String> openCallWindow = (peer, mode) -> {
            if (activeCallDialog[0] != null) activeCallDialog[0].dispose();
            JDialog dlg = new JDialog(frame, "Cuộc gọi " + mode.toUpperCase() + " — " + peer, false);
            dlg.setSize("video".equals(mode) ? 680 : 380, "video".equals(mode) ? 520 : 220);
            dlg.setLocationRelativeTo(frame);
            activeCallDialog[0] = dlg;

            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBorder(new EmptyBorder(10, 10, 10, 10));
            JLabel lblCallSt = new JLabel("Đang kết nối " + mode.toUpperCase() + " với [" + peer + "]...", SwingConstants.CENTER);
            lblCallSt.setFont(new Font("Segoe UI", Font.BOLD, 14));
            JLabel lblWarn = new JLabel("", SwingConstants.CENTER);
            lblWarn.setForeground(new Color(217, 119, 6));

            JPanel topBox = new JPanel(new GridLayout(2, 1));
            topBox.add(lblCallSt);
            topBox.add(lblWarn);
            p.add(topBox, BorderLayout.NORTH);

            JLabel lblPeerVideo = new JLabel("Đang chờ hình ảnh từ đối phương...", SwingConstants.CENTER);
            lblPeerVideo.setOpaque(true);
            lblPeerVideo.setBackground(new Color(15, 23, 42));
            lblPeerVideo.setForeground(Color.LIGHT_GRAY);
            JLabel lblSelfVideo = new JLabel("Camera bạn", SwingConstants.CENTER);
            lblSelfVideo.setPreferredSize(new Dimension(170, 130));
            lblSelfVideo.setOpaque(true);
            lblSelfVideo.setBackground(new Color(30, 41, 59));
            lblSelfVideo.setForeground(Color.WHITE);

            if ("video".equals(mode)) {
                JPanel vidWrap = new JPanel(new BorderLayout(6, 0));
                vidWrap.add(lblPeerVideo, BorderLayout.CENTER);
                vidWrap.add(lblSelfVideo, BorderLayout.EAST);
                p.add(vidWrap, BorderLayout.CENTER);

                core.onPeerFrame = img -> SwingUtilities.invokeLater(() -> {
                    lblPeerVideo.setText("");
                    lblPeerVideo.setIcon(new ImageIcon(img.getScaledInstance(440, 330, Image.SCALE_FAST)));
                });
                core.onSelfFrame = img -> SwingUtilities.invokeLater(() -> {
                    lblSelfVideo.setText("");
                    lblSelfVideo.setIcon(new ImageIcon(img.getScaledInstance(160, 120, Image.SCALE_FAST)));
                });
            }

            core.onMediaWarning = w -> SwingUtilities.invokeLater(() -> lblWarn.setText(w));

            JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
            JButton btnMute = new JButton("Tắt Mic");
            JButton btnHangup = new JButton("Kết thúc");
            styleActionButton(btnHangup, new Color(220, 38, 38));

            btnMute.addActionListener(ev -> {
                core.setMuted(!core.isMuted());
                btnMute.setText(core.isMuted() ? "Bật Mic" : "Tắt Mic");
            });

            final boolean[] callClosed = {false};
            Runnable closeAct = () -> {
                if (callClosed[0]) return;
                callClosed[0] = true;
                core.onPeerFrame = null;
                core.onSelfFrame = null;
                core.onMediaWarning = null;
                core.endCall();
                dlg.dispose();
                activeCallDialog[0] = null;
                activeCallStatusLabel[0] = null;
            };
            btnHangup.addActionListener(ev -> closeAct.run());
            dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dlg.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    closeAct.run();
                }
            });

            ctrl.add(btnMute);
            ctrl.add(btnHangup);
            p.add(ctrl, BorderLayout.SOUTH);
            dlg.setContentPane(p);
            activeCallStatusLabel[0] = lblCallSt;
            dlg.setVisible(true);
        };

        Consumer<String> initiateCallUi = mode -> {
            if (!core.isConnected()) {
                JOptionPane.showMessageDialog(frame, "Hãy kết nối tới TCP Server trước khi thực hiện cuộc gọi!", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!activeChannel[0].startsWith("user:")) {
                String hint = core.onlineUsers.size() <= 1
                        ? "Hiện chưa có người dùng khác online.\nHãy mở Client thứ 2 kết nối cùng Server, rồi click chọn tên họ trong mục 'Người dùng Online (Chat 1-1)' bên trái."
                        : "Cuộc gọi chỉ hỗ trợ 1-1.\nHãy click chọn tên người nhận trong danh sách 'Người dùng Online (Chat 1-1)' bên trái trước khi bấm Gọi!";
                JOptionPane.showMessageDialog(frame, hint, "Chưa chọn người nhận cuộc gọi", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String peer = activeChannel[0].substring(5);
            if (!core.onlineUsers.contains(peer)) {
                JOptionPane.showMessageDialog(frame, "Người dùng [" + peer + "] hiện không còn trực tuyến.", "Không thể gọi", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!"IDLE".equals(core.getCallState())) {
                JOptionPane.showMessageDialog(frame, "Đang có cuộc gọi (" + core.getCallState() + "). Vui lòng kết thúc cuộc gọi hiện tại trước.", "Đang bận", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (core.startCall(peer, mode)) {
                openCallWindow.accept(peer, mode);
            } else {
                JOptionPane.showMessageDialog(frame, "Không thể gửi yêu cầu gọi tới [" + peer + "]. Vui lòng kiểm tra kết nối Server.", "Lỗi cuộc gọi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnAudio.addActionListener(e -> initiateCallUi.accept("audio"));
        btnVideo.addActionListener(e -> initiateCallUi.accept("video"));

        // GẮN CALLBACK TỪ CORE LÊN SWING EDT
        core.onLog = appendLog;
        core.onChatMessage = (chKey, sender, text, tStr) -> SwingUtilities.invokeLater(() -> {
            chatHistories.computeIfAbsent(chKey, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add("[" + tStr + "] " + sender + ": " + text);
            if (chKey.equals(activeChannel[0])) {
                refreshChat.run();
            }
        });
        core.onStateUpdate = (users, grpMap) -> SwingUtilities.invokeLater(() -> {
            usersModel.clear();
            for (String u : users) {
                usersModel.addElement(u.equals(core.getUsername()) ? u + " (Bạn)" : u);
            }
            groupsModel.clear();
            grpMap.forEach((g, members) -> groupsModel.addElement(g + " (" + members.size() + ")"));
            if (activeChannel[0].startsWith("user:")) {
                String peer = activeChannel[0].substring(5);
                if (users.contains(peer) && !peer.equals(core.getUsername())) {
                    listUsers.setSelectedValue(peer, true);
                }
            }
            updateCallButtonsUi.run();
        });
        core.onIncomingCall = (caller, mode) -> SwingUtilities.invokeLater(() -> {
            if (incomingDialog[0] != null) incomingDialog[0].dispose();
            JDialog pop = new JDialog(frame, "Cuộc gọi đến", false);
            pop.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            pop.setSize(360, 170);
            pop.setLocationRelativeTo(frame);
            incomingDialog[0] = pop;
            final boolean[] responded = {false};

            JPanel jp = new JPanel(new BorderLayout(8, 8));
            jp.setBorder(new EmptyBorder(14, 14, 14, 14));
            jp.add(new JLabel("Cuộc gọi " + mode.toUpperCase() + " từ [" + caller + "]...", SwingConstants.CENTER), BorderLayout.CENTER);
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
            JButton btnAcc = new JButton("Chấp nhận");
            styleActionButton(btnAcc, new Color(5, 150, 105));
            JButton btnRej = new JButton("Từ chối");
            styleActionButton(btnRej, new Color(220, 38, 38));
            btnAcc.addActionListener(ev -> {
                if (responded[0]) return;
                responded[0] = true;
                pop.dispose();
                incomingDialog[0] = null;
                // Mở cửa sổ và gắn callback hình ảnh/âm thanh trước khi khởi động Webcam/Micro
                openCallWindow.accept(caller, mode);
                if (activeCallStatusLabel[0] != null) {
                    activeCallStatusLabel[0].setText("🟢 Đang đàm thoại " + mode.toUpperCase() + " với [" + caller + "]");
                }
                core.acceptCall(caller, mode);
            });
            Runnable declineAct = () -> {
                if (responded[0]) return;
                responded[0] = true;
                pop.dispose();
                incomingDialog[0] = null;
                core.rejectCall(caller, "Từ chối cuộc gọi.");
            };
            btnRej.addActionListener(ev -> declineAct.run());
            pop.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    declineAct.run();
                }
            });
            btns.add(btnAcc);
            btns.add(btnRej);
            jp.add(btns, BorderLayout.SOUTH);
            pop.setContentPane(jp);
            pop.setVisible(true);
            pop.toFront();
            Toolkit.getDefaultToolkit().beep();
        });
        core.onCallConnected = (peer, mode) -> SwingUtilities.invokeLater(() -> {
            if (activeCallStatusLabel[0] != null) {
                activeCallStatusLabel[0].setText("🟢 Đang đàm thoại " + mode.toUpperCase() + " với [" + peer + "]");
            }
        });
        core.onCallEnded = reason -> SwingUtilities.invokeLater(() -> {
            if (incomingDialog[0] != null) { incomingDialog[0].dispose(); incomingDialog[0] = null; }
            if (activeCallDialog[0] != null) { activeCallDialog[0].dispose(); activeCallDialog[0] = null; }
            activeCallStatusLabel[0] = null;
            core.onPeerFrame = null;
            core.onSelfFrame = null;
            core.onMediaWarning = null;
            JOptionPane.showMessageDialog(frame, reason, "Thông báo cuộc gọi", JOptionPane.INFORMATION_MESSAGE);
        });
        core.onDisconnect = reason -> SwingUtilities.invokeLater(() -> {
            if (incomingDialog[0] != null) { incomingDialog[0].dispose(); incomingDialog[0] = null; }
            if (activeCallDialog[0] != null) { activeCallDialog[0].dispose(); activeCallDialog[0] = null; }
            activeCallStatusLabel[0] = null;
            btnConn.setText("Kết nối Server");
            btnConn.setBackground(new Color(37, 99, 235));
            chkMulticast.setSelected(false);
            lblConnState.setText("● " + reason);
            lblConnState.setForeground(new Color(220, 38, 38));
            usersModel.clear();
            groupsModel.clear();
            updateCallButtonsUi.run();
        });

        btnConn.addActionListener(e -> {
            if (!core.isConnected()) {
                int parsedPort;
                try {
                    parsedPort = Integer.parseInt(txtPort.getText().trim());
                    if (parsedPort < 1 || parsedPort > 65535) throw new IllegalArgumentException();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Cổng (Port) phải là số nguyên từ 1 đến 65535.", "Lỗi", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                String host = txtIp.getText().trim();
                String user = txtUser.getText().trim();
                btnConn.setEnabled(false);
                lblConnState.setText("● Đang kết nối...");
                lblConnState.setForeground(new Color(217, 119, 6));

                new SwingWorker<Void, Void>() {
                    @Override protected Void doInBackground() throws Exception {
                        core.connect(host, parsedPort, user);
                        return null;
                    }
                    @Override protected void done() {
                        btnConn.setEnabled(true);
                        try {
                            get();
                            btnConn.setText("Ngắt kết nối");
                            btnConn.setBackground(new Color(220, 38, 38));
                            lblConnState.setText("● Online [" + core.getUsername() + "]");
                            lblConnState.setForeground(new Color(5, 150, 105));
                            frame.setTitle("Lab 01 — Java Chat Client [" + core.getUsername() + "]");
                            updateCallButtonsUi.run();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            lblConnState.setText("● Chưa kết nối");
                            lblConnState.setForeground(new Color(220, 38, 38));
                            JOptionPane.showMessageDialog(frame, "Lỗi kết nối: " + cause.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                core.disconnect();
                btnConn.setText("Kết nối Server");
                btnConn.setBackground(new Color(37, 99, 235));
                lblConnState.setText("● Đã ngắt kết nối");
                lblConnState.setForeground(new Color(220, 38, 38));
                chkMulticast.setSelected(false);
                usersModel.clear();
                groupsModel.clear();
                updateCallButtonsUi.run();
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                core.disconnect();
            }
        });
        frame.setVisible(true);
    }
}
