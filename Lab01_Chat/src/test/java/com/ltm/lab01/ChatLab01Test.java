package com.ltm.lab01;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử tự động JUnit 5 cho Lab 01:
 * TCP 6 Clients, Chat riêng, Chat nhóm, Emoji, File Transfer,
 * UDP Broadcast, UDP Multicast, Call Signaling & Media Relay.
 */
public class ChatLab01Test {

    static {
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    private ChatServer.ChatServerCore server;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("lab01_test_");
        server = new ChatServer.ChatServerCore("127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }
    }

    @Test
    void testSixConcurrentClientsPrivateGroupAndEmoji() throws Exception {
        List<ChatClient.ChatClientCore> clients = new ArrayList<>();
        List<List<String>> inbox = new ArrayList<>();

        try {
            for (int i = 1; i <= 6; i++) {
                String name = "SV" + i;
                List<String> msgs = new CopyOnWriteArrayList<>();
                inbox.add(msgs);

                ChatClient.ChatClientCore c = new ChatClient.ChatClientCore(55201, "224.1.1.1", 55202, false, tempDir.resolve("dl" + i));
                c.onChatMessage = (ch, sender, text, t) -> msgs.add(ch + "|" + sender + "|" + text);
                c.connect("127.0.0.1", server.getPort(), name);
                clients.add(c);
            }

            Thread.sleep(350);
            // Cả 6 client đều online
            assertEquals(6, server.getClientDescriptions().size());
            assertEquals(6, clients.get(0).onlineUsers.size());

            // 1. Chat chung + Emoji UTF-8
            assertTrue(clients.get(0).sendPublic("Xin chào LTM 😀👍"));
            Thread.sleep(250);
            for (int i = 0; i < 6; i++) {
                assertTrue(inbox.get(i).stream().anyMatch(m -> m.startsWith("__public__|SV1|Xin chào LTM 😀👍")));
            }

            // 2. Chat cá nhân 1-1: SV1 -> SV2 (SV3 không được thấy)
            assertTrue(clients.get(0).sendPrivate("SV2", "Tin nhắn mật 1-1"));
            Thread.sleep(200);
            assertTrue(inbox.get(1).stream().anyMatch(m -> m.startsWith("user:SV1|SV1|Tin nhắn mật 1-1")));
            assertFalse(inbox.get(2).stream().anyMatch(m -> m.contains("Tin nhắn mật 1-1")));

            // 3. Tạo nhóm & chat nhóm: SV1 + SV4 vào NhomJava, SV5 không vào
            assertTrue(clients.get(0).createGroup("NhomJava"));
            Thread.sleep(100);
            assertTrue(clients.get(3).joinGroup("NhomJava"));
            Thread.sleep(100);
            assertTrue(clients.get(0).sendGroup("NhomJava", "Họp đồ án tối nay 🎉"));
            Thread.sleep(200);
            assertTrue(inbox.get(3).stream().anyMatch(m -> m.startsWith("group:NhomJava|SV1|Họp đồ án tối nay 🎉")));
            assertFalse(inbox.get(4).stream().anyMatch(m -> m.contains("Họp đồ án tối nay 🎉")));
        } finally {
            for (ChatClient.ChatClientCore c : clients) c.disconnect();
        }
    }

    @Test
    void testFileTransferUdpBroadcastAndMulticast() throws Exception {
        Path dl1 = tempDir.resolve("dl_an");
        Path dl2 = tempDir.resolve("dl_binh");
        Path dl3 = tempDir.resolve("dl_chi");

        ChatClient.ChatClientCore c1 = new ChatClient.ChatClientCore(55203, "224.1.1.1", 55204, false, dl1);
        ChatClient.ChatClientCore c2 = new ChatClient.ChatClientCore(55203, "224.1.1.1", 55204, false, dl2);
        ChatClient.ChatClientCore c3 = new ChatClient.ChatClientCore(55203, "224.1.1.1", 55204, false, dl3);

        List<String> msgs2 = new CopyOnWriteArrayList<>();
        List<String> msgs3 = new CopyOnWriteArrayList<>();
        c2.onChatMessage = (ch, s, t, tm) -> msgs2.add(ch + "|" + t);
        c3.onChatMessage = (ch, s, t, tm) -> msgs3.add(ch + "|" + t);

        try {
            c1.connect("127.0.0.1", server.getPort(), "An");
            c2.connect("127.0.0.1", server.getPort(), "Binh");
            c3.connect("127.0.0.1", server.getPort(), "Chi");
            Thread.sleep(200);

            // Chỉ An & Binh tham gia UDP Multicast; Chi không tham gia
            assertTrue(c1.joinMulticast());
            assertTrue(c2.joinMulticast());
            Thread.sleep(150);

            // 1. UDP Broadcast -> Cả Binh và Chi đều nhận được
            assertTrue(c1.sendBroadcast("Thong bao Broadcast LAN"));
            Thread.sleep(250);
            assertTrue(msgs2.stream().anyMatch(m -> m.startsWith("__broadcast__|Thong bao Broadcast LAN")));
            assertTrue(msgs3.stream().anyMatch(m -> m.startsWith("__broadcast__|Thong bao Broadcast LAN")));

            // 2. UDP Multicast -> Binh nhận được, Chi KHÔNG nhận được
            assertTrue(c1.sendMulticast("Tin nhom Multicast 224.1.1.1"));
            Thread.sleep(250);
            assertTrue(msgs2.stream().anyMatch(m -> m.startsWith("__multicast__|Tin nhom Multicast 224.1.1.1")));
            assertFalse(msgs3.stream().anyMatch(m -> m.contains("Tin nhom Multicast 224.1.1.1")));

            // 3. Truyền file thật qua TCP 1-1 từ An -> Binh
            byte[] raw = "Noi dung file bai tap Java Socket Lab 01".getBytes();
            File src = tempDir.resolve("bai_tap.txt").toFile();
            Files.write(src.toPath(), raw);

            assertTrue(c1.sendFile(src, "private", "Binh"));
            Thread.sleep(300);

            Path expectedSaved = dl2.resolve("Binh_bai_tap.txt");
            assertTrue(Files.exists(expectedSaved), "File nhận phải được lưu tự động");
            assertArrayEquals(raw, Files.readAllBytes(expectedSaved));
        } finally {
            c1.disconnect();
            c2.disconnect();
            c3.disconnect();
        }
    }

    @Test
    void testCallSignalingAndBinaryAudioVideoRelay() throws Exception {
        ChatClient.ChatClientCore alice = new ChatClient.ChatClientCore(55205, "224.1.1.1", 55206, false, tempDir.resolve("a"));
        ChatClient.ChatClientCore bob = new ChatClient.ChatClientCore(55205, "224.1.1.1", 55206, false, tempDir.resolve("b"));

        List<String> incomingCalls = new CopyOnWriteArrayList<>();
        AtomicBoolean audioReceived = new AtomicBoolean(false);
        AtomicBoolean videoReceived = new AtomicBoolean(false);

        bob.onIncomingCall = (caller, mode) -> incomingCalls.add(caller + ":" + mode);
        bob.onRawAudioForTest = data -> { if (data.length == 512) audioReceived.set(true); };
        bob.onPeerFrame = img -> { if (img.getWidth() == 32 && img.getHeight() == 24) videoReceived.set(true); };

        try {
            alice.connect("127.0.0.1", server.getPort(), "Alice");
            bob.connect("127.0.0.1", server.getPort(), "Bob");
            Thread.sleep(150);

            assertTrue(alice.startCall("Bob", "video"));
            Thread.sleep(200);
            assertEquals(Collections.singletonList("Alice:video"), incomingCalls);

            bob.acceptCall("Alice", "video");
            bob.startMediaSession("video"); // mở bộ giải mã nhận frame
            Thread.sleep(200);
            assertEquals("CONNECTED", alice.getCallState());
            assertEquals("CONNECTED", bob.getCallState());

            // Gửi 1 gói Audio PCM thật qua TCP relay
            ChatPacket audioPkt = new ChatPacket(ChatPacket.Type.AUDIO_CHUNK);
            audioPkt.target = "Bob";
            audioPkt.binaryData = new byte[512];
            assertTrue(alice.sendPacket(audioPkt));

            // Gửi 1 khung hình JPEG thật qua TCP relay
            BufferedImage img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
            ChatPacket videoPkt = new ChatPacket(ChatPacket.Type.VIDEO_FRAME);
            videoPkt.target = "Bob";
            videoPkt.binaryData = baos.toByteArray();
            assertTrue(alice.sendPacket(videoPkt));

            Thread.sleep(250);
            assertTrue(audioReceived.get(), "Bob phải nhận được dữ liệu nhị phân Audio PCM qua Server");
            assertTrue(videoReceived.get(), "Bob phải giải mã được khung hình JPEG gửi từ Alice");

            alice.endCall();
            Thread.sleep(200);
            assertEquals("IDLE", alice.getCallState());
            assertEquals("IDLE", bob.getCallState());
        } finally {
            alice.disconnect();
            bob.disconnect();
        }
    }

    @Test
    void testCallStateRecoveryOnRejectDisconnectAndReconnect() throws Exception {
        ChatClient.ChatClientCore alice = new ChatClient.ChatClientCore(55207, "224.1.1.1", 55208, false, tempDir.resolve("ra"));
        ChatClient.ChatClientCore bob = new ChatClient.ChatClientCore(55207, "224.1.1.1", 55208, false, tempDir.resolve("rb"));
        List<String> endedReasons = new CopyOnWriteArrayList<>();
        alice.onCallEnded = endedReasons::add;
        bob.onCallEnded = endedReasons::add;

        try {
            alice.connect("127.0.0.1", server.getPort(), "Alice");
            bob.connect("127.0.0.1", server.getPort(), "Bob");
            Thread.sleep(150);

            // 1. Từ chối cuộc gọi -> cả 2 phải quay về IDLE để gọi tiếp được
            assertTrue(alice.startCall("Bob", "audio"));
            Thread.sleep(150);
            assertEquals("CALLING", alice.getCallState());
            assertEquals("RINGING", bob.getCallState());

            bob.rejectCall("Alice", "Từ chối cuộc gọi.");
            Thread.sleep(150);
            assertEquals("IDLE", alice.getCallState());
            assertEquals("IDLE", bob.getCallState());

            // 2. Ngắt kết nối giữa cuộc gọi -> đối phương tự về IDLE, bên ngắt kết nối lại vẫn gọi được
            assertTrue(alice.startCall("Bob", "video"));
            Thread.sleep(150);
            bob.acceptCall("Alice", "video");
            Thread.sleep(150);
            assertEquals("CONNECTED", alice.getCallState());
            assertEquals("CONNECTED", bob.getCallState());

            bob.disconnect();
            Thread.sleep(200);
            assertEquals("IDLE", bob.getCallState());
            assertEquals("IDLE", alice.getCallState());

            // Bob kết nối lại và gọi lại Alice thành công
            bob.connect("127.0.0.1", server.getPort(), "Bob");
            Thread.sleep(150);
            assertTrue(bob.startCall("Alice", "audio"), "Sau khi ngắt kết nối và kết nối lại phải gọi được tiếp");
            Thread.sleep(150);
            assertEquals("CALLING", bob.getCallState());
            assertEquals("RINGING", alice.getCallState());
            assertFalse(endedReasons.isEmpty());
        } finally {
            alice.disconnect();
            bob.disconnect();
        }
    }
}

