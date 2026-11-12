package com.ltm.lab05;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.LocateRegistry;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm thử Java RMI thực tế bằng 2 tiến trình JVM độc lập qua ProcessBuilder:
 * - Server A (JVM 1) + SQLite serverA.db
 * - Server B (JVM 2) + SQLite serverB.db
 * - Test: Thêm tại A -> kiểm B; Sửa/Xóa tại B -> kiểm A; Ngắt Process B -> sửa tại A -> bật lại B -> Catch-up Sync tự động.
 */
public class RmiLab05Test {

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private Process startServerProcess(String id, int port, int peerPort, Path dbPath) throws Exception {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-cp", cp,
                SyncServerApp.class.getName(),
                "--id", id,
                "--port", String.valueOf(port),
                "--peerHost", "127.0.0.1",
                "--peerPort", String.valueOf(peerPort),
                "--db", dbPath.toAbsolutePath().toString(),
                "--nogui"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Đọc luồng stdout của tiến trình con để tránh nghẽn buffer và in ra nếu cần theo dõi
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[PROC-" + id + "] " + line);
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return p;
    }

    private SyncService awaitStub(int port, String expectedId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                SyncService s = (SyncService) LocateRegistry.getRegistry("127.0.0.1", port)
                        .lookup(SyncServerApp.SERVICE_NAME);
                if (expectedId.equals(s.ping())) return s;
            } catch (Exception ignored) {
            }
            Thread.sleep(150);
        }
        fail("Timeout chờ RMI Server " + expectedId + " trên port " + port);
        return null;
    }

    private void awaitCondition(long timeoutMs, java.util.function.BooleanSupplier cond, String message) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(150);
        }
        fail(message);
    }

    @Test
    void testTwoJvmProcessesBidirectionalSyncNoLoopAndOfflineCatchUp(@TempDir Path tmpDir) throws Exception {
        int portA = findFreePort();
        int portB = findFreePort();
        assertNotEquals(portA, portB);

        Path dbA = tmpDir.resolve("serverA.db");
        Path dbB = tmpDir.resolve("serverB.db");

        Process procA = null;
        Process procB = null;
        try {
            procA = startServerProcess("SERVER_A", portA, portB, dbA);
            procB = startServerProcess("SERVER_B", portB, portA, dbB);

            SyncService stubA = awaitStub(portA, "SERVER_A");
            SyncService stubB = awaitStub(portB, "SERVER_B");

            // 1. Client thêm SP01 & SP02 trên Server A -> xác nhận tự đồng bộ sang Server B
            DataItem sp1 = stubA.createItem("SP01", "Router WiFi 6", 5, "Phòng Lab");
            DataItem sp2 = stubA.createItem("SP02", "Cáp mạng Cat6", 100, "Cuộn");

            awaitCondition(5000, () -> {
                try {
                    return stubB.getAllActiveItems().size() == 2;
                } catch (Exception e) {
                    return false;
                }
            }, "Server B không nhận được SP01 & SP02 đồng bộ từ A");

            // 2. Client sửa SP01 (qty 5 -> 9) và xóa SP02 tại Server B -> kiểm tra Server A nhận đủ
            stubB.updateItem(sp1.id, "SP01", "Router WiFi 6 Pro", 9, "Đã sửa tại B");
            stubB.deleteItem(sp2.id);

            awaitCondition(5000, () -> {
                try {
                    List<DataItem> itemsA = stubA.getAllActiveItems();
                    return itemsA.size() == 1
                            && itemsA.get(0).quantity == 9
                            && "Router WiFi 6 Pro".equals(itemsA.get(0).name);
                } catch (Exception e) {
                    return false;
                }
            }, "Server A không cập nhật sửa/xóa từ Server B");

            // 3. Kiểm tra không có vòng lặp đồng bộ vô hạn (chờ 2 giây -> version giữ nguyên ở v2)
            Thread.sleep(2000);
            assertEquals(2L, stubA.getAllActiveItems().get(0).version);
            assertEquals(2L, stubB.getAllActiveItems().get(0).version);

            // 4. Giả lập Server B mất kết nối đột ngột (dừng hẳn tiến trình JVM B)
            procB.destroyForcibly();
            procB.waitFor();
            procB = null;

            // Client vẫn thêm SP03, thêm rồi xóa SP04 (tombstone), và sửa SP01 (9 -> 15) tại Server A
            DataItem sp3 = stubA.createItem("SP03", "Switch PoE 24P", 4, "Khi B offline");
            DataItem sp4Tmp = stubA.createItem("SP04", "Tam xoa khi B tat", 1, "Test tombstone");
            stubA.deleteItem(sp4Tmp.id);
            stubA.updateItem(sp1.id, "SP01", "Router WiFi 6 Pro", 15, "Cập nhật khi B offline");

            assertEquals(2, stubA.getAllActiveItems().size());

            // 5. Khởi động lại JVM Process Server B trên đúng file SQLite serverB.db cũ
            procB = startServerProcess("SERVER_B", portB, portA, dbB);
            SyncService stubBReconnected = awaitStub(portB, "SERVER_B");

            // Chờ cơ chế Heartbeat tự phát hiện B online trở lại và Catch-up Sync đồng bộ bù
            awaitCondition(7000, () -> {
                try {
                    List<DataItem> listB = stubBReconnected.getAllActiveItems();
                    if (listB.size() != 2) return false;
                    Map<String, DataItem> map = new HashMap<>();
                    for (DataItem it : listB) map.put(it.id, it);
                    DataItem b1 = map.get(sp1.id);
                    DataItem b3 = map.get(sp3.id);
                    return b1 != null && b1.quantity == 15 && b3 != null && "Switch PoE 24P".equals(b3.name);
                } catch (Exception e) {
                    return false;
                }
            }, "Sau khi Server B kết nối trở lại, Catch-up Sync không đồng bộ đủ dữ liệu còn thiếu!");
        } finally {
            if (procB != null && procB.isAlive()) procB.destroyForcibly();
            if (procA != null && procA.isAlive()) procA.destroyForcibly();
        }
    }
}

