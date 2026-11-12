package com.ltm.lab02;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.io.CopyStreamAdapter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Lab 02 — FTP Client chuẩn sử dụng Apache Commons Net (FTPClient):
 * - Đăng nhập Username / Password qua lệnh USER / PASS.
 * - Bật chế độ Passive Mode (`enterLocalPassiveMode`) và truyền nhị phân (`BINARY_FILE_TYPE`).
 * - Duyệt thư mục, Upload, Download (hiển thị tiến trình % thực tế), Tạo thư mục, Xóa file.
 */
public class FtpClientApp {

    public static class FtpClientCore {
        private FTPClient client;
        private Consumer<String> protocolLogger;

        public void setProtocolLogger(Consumer<String> protocolLogger) {
            this.protocolLogger = protocolLogger;
        }

        private void emitLog(String msg) {
            if (protocolLogger != null) protocolLogger.accept(msg);
        }

        public synchronized boolean isConnected() {
            return client != null && client.isConnected();
        }

        public synchronized void connectAndLogin(String host, int port, String username, String password) throws IOException {
            disconnect();
            FTPClient ftp = new FTPClient();
            ftp.setControlEncoding("UTF-8");
            ftp.setConnectTimeout(5000);
            ftp.addProtocolCommandListener(new ProtocolCommandListener() {
                @Override
                public void protocolCommandSent(ProtocolCommandEvent event) {
                    String cmd = event.getMessage() == null ? "" : event.getMessage().trim();
                    // Che mật khẩu khi ghi nhật ký giao thức
                    if ("PASS".equalsIgnoreCase(event.getCommand())) {
                        cmd = "PASS ********";
                    }
                    emitLog("> " + cmd);
                }

                @Override
                public void protocolReplyReceived(ProtocolCommandEvent event) {
                    String rep = event.getMessage() == null ? "" : event.getMessage().trim();
                    emitLog("< " + rep);
                }
            });

            ftp.connect(host.trim(), port);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                throw new IOException("FTP Server từ chối kết nối: " + ftp.getReplyString());
            }

            if (!ftp.login(username.trim(), password)) {
                String rep = ftp.getReplyString();
                ftp.disconnect();
                throw new IOException("Đăng nhập thất bại: " + rep);
            }

            // Bắt buộc dùng Passive Mode + Binary để truyền file ảnh/PDF/EXE không bị hỏng byte
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            this.client = ftp;
        }

        public synchronized void disconnect() {
            if (client != null) {
                try {
                    if (client.isConnected()) {
                        client.logout();
                        client.disconnect();
                    }
                } catch (IOException ignored) {
                }
                client = null;
            }
        }

        public synchronized String printWorkingDirectory() throws IOException {
            ensureConnected();
            return client.printWorkingDirectory();
        }

        public synchronized List<FTPFile> listFiles() throws IOException {
            ensureConnected();
            FTPFile[] arr = client.listFiles();
            return arr == null ? Collections.emptyList() : Arrays.asList(arr);
        }

        public synchronized boolean changeDirectory(String dir) throws IOException {
            ensureConnected();
            return client.changeWorkingDirectory(dir);
        }

        public synchronized boolean parentDirectory() throws IOException {
            ensureConnected();
            return client.changeToParentDirectory();
        }

        public synchronized boolean makeDirectory(String name) throws IOException {
            ensureConnected();
            return client.makeDirectory(name);
        }

        public synchronized void uploadFile(Path localFile, String remoteName, IntConsumer progressPercent) throws IOException {
            ensureConnected();
            long totalSize = Math.max(1L, Files.size(localFile));
            client.setCopyStreamListener(new CopyStreamAdapter() {
                @Override
                public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
                    int pct = (int) Math.min(100, (totalBytesTransferred * 100L) / totalSize);
                    if (progressPercent != null) progressPercent.accept(pct);
                }
            });
            try (InputStream is = new BufferedInputStream(Files.newInputStream(localFile))) {
                boolean ok = client.storeFile(remoteName, is);
                if (!ok) {
                    throw new IOException("Upload thất bại: " + client.getReplyString());
                }
                if (progressPercent != null) progressPercent.accept(100);
            } finally {
                client.setCopyStreamListener(null);
            }
        }

        public synchronized void downloadFile(String remoteName, Path localTarget, long knownSize, IntConsumer progressPercent)
                throws IOException {
            ensureConnected();
            long totalSize = Math.max(1L, knownSize);
            client.setCopyStreamListener(new CopyStreamAdapter() {
                @Override
                public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
                    long denom = streamSize > 0 ? streamSize : totalSize;
                    int pct = (int) Math.min(100, (totalBytesTransferred * 100L) / denom);
                    if (progressPercent != null) progressPercent.accept(pct);
                }
            });
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(localTarget))) {
                boolean ok = client.retrieveFile(remoteName, os);
                if (!ok) {
                    throw new IOException("Download thất bại: " + client.getReplyString());
                }
                if (progressPercent != null) progressPercent.accept(100);
            } finally {
                client.setCopyStreamListener(null);
            }
        }

        public synchronized void deleteFile(String remoteName) throws IOException {
            ensureConnected();
            if (!client.deleteFile(remoteName)) {
                throw new IOException("Không xóa được file: " + client.getReplyString());
            }
        }

        private void ensureConnected() throws IOException {
            if (!isConnected()) throw new IOException("Chưa kết nối FTP Server.");
        }
    }

    // =========================================================================
    // GIAO DIỆN JAVA SWING CHO FTP CLIENT
    // =========================================================================
    private static void styleBtn(JButton b, Color bg) {
        b.setUI(new BasicButtonUI());
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(FtpClientApp::launchGui);
    }

    public static void launchGui() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 02 — Java FTP Client (Apache Commons Net)");
        frame.setSize(1020, 670);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        FtpClientCore core = new FtpClientCore();

        // 1. THANH KẾT NỐI
        JPanel connBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        connBar.setBackground(Color.WHITE);
        connBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));
        JTextField txtHost = new JTextField("127.0.0.1", 10);
        JTextField txtPort = new JTextField(String.valueOf(FtpServerApp.DEFAULT_PORT), 5);
        JTextField txtUser = new JTextField(10);
        JPasswordField txtPass = new JPasswordField(10);
        JButton btnConn = new JButton("Đăng nhập FTP");
        styleBtn(btnConn, new Color(37, 99, 235));
        JLabel lblState = new JLabel("● Chưa kết nối");
        lblState.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblState.setForeground(new Color(220, 38, 38));

        connBar.add(new JLabel("Host:")); connBar.add(txtHost);
        connBar.add(new JLabel("Port:")); connBar.add(txtPort);
        connBar.add(new JLabel("User:")); connBar.add(txtUser);
        connBar.add(new JLabel("Pass:")); connBar.add(txtPass);
        connBar.add(btnConn);
        connBar.add(lblState);
        root.add(connBar, BorderLayout.NORTH);

        // 2. THANH THAO TÁC + BẢNG FILE TRÊN SERVER
        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.setBackground(Color.WHITE);
        centerPanel.setBorder(new TitledBorder("Thư mục & Tập tin trên FTP Server"));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.setBackground(new Color(248, 250, 252));
        JLabel lblPwd = new JLabel("PWD: /");
        lblPwd.setFont(new Font("Consolas", Font.BOLD, 12));
        JButton btnUp = new JButton(".. Lên");
        JButton btnRefresh = new JButton("Làm mới");
        JButton btnMkdir = new JButton("Tạo thư mục");
        JButton btnUpload = new JButton("Upload File");
        styleBtn(btnUpload, new Color(5, 150, 105));
        JButton btnDownload = new JButton("Download File");
        styleBtn(btnDownload, new Color(79, 70, 229));
        JButton btnDelete = new JButton("Xóa File");
        styleBtn(btnDelete, new Color(220, 38, 38));

        toolbar.add(lblPwd);
        toolbar.add(btnUp);
        toolbar.add(btnRefresh);
        toolbar.add(btnMkdir);
        toolbar.add(btnUpload);
        toolbar.add(btnDownload);
        toolbar.add(btnDelete);
        centerPanel.add(toolbar, BorderLayout.NORTH);

        DefaultTableModel filesModel = new DefaultTableModel(new String[]{"Tên", "Loại", "Kích thước (Bytes)", "Ngày sửa"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblFiles = new JTable(filesModel);
        tblFiles.setRowHeight(24);
        centerPanel.add(new JScrollPane(tblFiles), BorderLayout.CENTER);

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Sẵn sàng");
        progressBar.setPreferredSize(new Dimension(0, 24));
        centerPanel.add(progressBar, BorderLayout.SOUTH);

        // 3. NHẬT KÝ GIAO THỨC FTP (USER / PASS / PASV / STOR / RETR)
        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(226, 232, 240));
        JScrollPane logScroll = new JScrollPane(txtLog);
        logScroll.setBorder(new TitledBorder("Nhật ký lệnh FTP (RFC 959)"));
        logScroll.setPreferredSize(new Dimension(0, 185));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerPanel, logScroll);
        mainSplit.setDividerLocation(390);
        root.add(mainSplit, BorderLayout.CENTER);

        core.setProtocolLogger(msg -> SwingUtilities.invokeLater(() -> {
            txtLog.append(msg + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        }));

        SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        Runnable reloadRemoteFiles = () -> new SwingWorker<List<FTPFile>, Void>() {
            String pwd = "/";
            @Override protected List<FTPFile> doInBackground() throws Exception {
                pwd = core.printWorkingDirectory();
                return core.listFiles();
            }
            @Override protected void done() {
                try {
                    List<FTPFile> list = get();
                    lblPwd.setText("PWD: " + pwd);
                    filesModel.setRowCount(0);
                    for (FTPFile f : list) {
                        String name = f.getName();
                        if (".".equals(name) || "..".equals(name)) continue;
                        String type = f.isDirectory() ? "DIR" : "FILE";
                        String time = f.getTimestamp() != null ? tsFmt.format(f.getTimestamp().getTime()) : "";
                        filesModel.addRow(new Object[]{name, type, f.getSize(), time});
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Lỗi đọc danh sách file: " + ex.getMessage(), "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();

        btnConn.addActionListener(e -> {
            if (!core.isConnected()) {
                int port;
                try {
                    port = Integer.parseInt(txtPort.getText().trim());
                    if (port < 1 || port > 65535) throw new IllegalArgumentException();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Cổng FTP phải là số nguyên từ 1 đến 65535.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                String host = txtHost.getText().trim();
                String user = txtUser.getText().trim();
                String pass = new String(txtPass.getPassword());
                if (host.isEmpty() || user.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "Vui lòng nhập đầy đủ Host và Username.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                btnConn.setEnabled(false);
                lblState.setText("● Đang kết nối...");
                lblState.setForeground(new Color(217, 119, 6));
                new SwingWorker<Void, Void>() {
                    @Override protected Void doInBackground() throws Exception {
                        core.connectAndLogin(host, port, user, pass);
                        return null;
                    }
                    @Override protected void done() {
                        btnConn.setEnabled(true);
                        try {
                            get();
                            btnConn.setText("Ngắt kết nối");
                            btnConn.setBackground(new Color(220, 38, 38));
                            lblState.setText("● Online [" + user + "]");
                            lblState.setForeground(new Color(5, 150, 105));
                            reloadRemoteFiles.run();
                        } catch (Exception ex) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            lblState.setText("● Chưa kết nối");
                            lblState.setForeground(new Color(220, 38, 38));
                            JOptionPane.showMessageDialog(frame, "Không kết nối được: " + cause.getMessage(), "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            } else {
                core.disconnect();
                btnConn.setText("Đăng nhập FTP");
                btnConn.setBackground(new Color(37, 99, 235));
                lblState.setText("● Đã ngắt kết nối");
                lblState.setForeground(new Color(220, 38, 38));
                filesModel.setRowCount(0);
            }
        });

        tblFiles.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && core.isConnected()) {
                    int row = tblFiles.getSelectedRow();
                    if (row >= 0 && "DIR".equals(filesModel.getValueAt(row, 1))) {
                        String dir = String.valueOf(filesModel.getValueAt(row, 0));
                        new SwingWorker<Boolean, Void>() {
                            @Override protected Boolean doInBackground() throws Exception {
                                return core.changeDirectory(dir);
                            }
                            @Override protected void done() {
                                try {
                                    if (get()) reloadRemoteFiles.run();
                                } catch (Exception ex) {
                                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                                    JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
                                }
                            }
                        }.execute();
                    }
                }
            }
        });

        btnRefresh.addActionListener(e -> { if (core.isConnected()) reloadRemoteFiles.run(); });
        btnUp.addActionListener(e -> {
            if (!core.isConnected()) return;
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    core.parentDirectory();
                    return null;
                }
                @Override protected void done() {
                    try { get(); reloadRemoteFiles.run(); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });
        btnMkdir.addActionListener(e -> {
            if (!core.isConnected()) return;
            String name = JOptionPane.showInputDialog(frame, "Nhập tên thư mục mới:");
            if (name != null && !name.isBlank()) {
                String cleanDir = name.trim();
                new SwingWorker<Boolean, Void>() {
                    @Override protected Boolean doInBackground() throws Exception {
                        return core.makeDirectory(cleanDir);
                    }
                    @Override protected void done() {
                        try {
                            if (!get()) {
                                JOptionPane.showMessageDialog(frame, "Server từ chối tạo thư mục (kiểm tra quyền ghi).", "Từ chối", JOptionPane.WARNING_MESSAGE);
                            }
                            reloadRemoteFiles.run();
                        } catch (Exception ex) {
                            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                            JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi FTP", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            }
        });

        btnUpload.addActionListener(e -> {
            if (!core.isConnected()) {
                JOptionPane.showMessageDialog(frame, "Vui lòng đăng nhập FTP Server trước.");
                return;
            }
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            File localFile = fc.getSelectedFile();
            progressBar.setValue(0);
            progressBar.setString("Đang Upload: " + localFile.getName() + " (0%)");

            new SwingWorker<Void, Integer>() {
                @Override protected Void doInBackground() throws Exception {
                    core.uploadFile(localFile.toPath(), localFile.getName(), this::publish);
                    return null;
                }
                @Override protected void process(List<Integer> chunks) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    progressBar.setString("Đang Upload: " + localFile.getName() + " (" + latest + "%)");
                }
                @Override protected void done() {
                    try {
                        get();
                        progressBar.setString("Upload hoàn tất: " + localFile.getName());
                        reloadRemoteFiles.run();
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        progressBar.setString("Lỗi Upload");
                        JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi Upload", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        btnDownload.addActionListener(e -> {
            if (!core.isConnected()) return;
            int row = tblFiles.getSelectedRow();
            if (row < 0 || "DIR".equals(filesModel.getValueAt(row, 1))) {
                JOptionPane.showMessageDialog(frame, "Chọn một FILE trong bảng để tải về.");
                return;
            }
            String remoteName = String.valueOf(filesModel.getValueAt(row, 0));
            long size = Long.parseLong(String.valueOf(filesModel.getValueAt(row, 2)));
            Path defaultDlDir = FtpServerApp.resolveLabDir().resolve("downloads");
            try { Files.createDirectories(defaultDlDir); } catch (IOException ignored) {}
            JFileChooser fc = new JFileChooser(defaultDlDir.toFile());
            fc.setSelectedFile(new File(defaultDlDir.toFile(), remoteName));
            if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
            Path target = fc.getSelectedFile().toPath();

            progressBar.setValue(0);
            progressBar.setString("Đang Download: " + remoteName + " (0%)");
            new SwingWorker<Void, Integer>() {
                @Override protected Void doInBackground() throws Exception {
                    core.downloadFile(remoteName, target, size, this::publish);
                    return null;
                }
                @Override protected void process(List<Integer> chunks) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    progressBar.setString("Đang Download: " + remoteName + " (" + latest + "%)");
                }
                @Override protected void done() {
                    try {
                        get();
                        progressBar.setString("Download hoàn tất -> " + target.getFileName());
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        progressBar.setString("Lỗi Download");
                        JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi Download", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        btnDelete.addActionListener(e -> {
            if (!core.isConnected()) return;
            int row = tblFiles.getSelectedRow();
            if (row < 0 || "DIR".equals(filesModel.getValueAt(row, 1))) {
                JOptionPane.showMessageDialog(frame, "Hãy chọn một FILE để xóa.");
                return;
            }
            String remoteName = String.valueOf(filesModel.getValueAt(row, 0));
            int conf = JOptionPane.showConfirmDialog(frame, "Xác nhận xóa file '" + remoteName + "' trên Server?", "Xác nhận", JOptionPane.YES_NO_OPTION);
            if (conf != JOptionPane.YES_OPTION) return;
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    core.deleteFile(remoteName);
                    return null;
                }
                @Override protected void done() {
                    try { get(); reloadRemoteFiles.run(); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(frame, c.getMessage(), "Lỗi Xóa File", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { core.disconnect(); }
        });
        frame.setVisible(true);
    }
}

