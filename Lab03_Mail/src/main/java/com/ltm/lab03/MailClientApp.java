package com.ltm.lab03;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lab 03 — Mail Client chuẩn Jakarta Mail hỗ trợ:
 * - Gửi email thực tế qua giao thức SMTP (`Transport.send`).
 * - Nhận email thực tế qua giao thức POP3 (`Store` -> `INBOX`).
 * - Chế độ Localhost / LAN và chế độ Gmail (STARTTLS port 587 + POP3S port 995).
 * - Lưu hộp thư đến và thư đã gửi vào CSDL SQLite cục bộ.
 */
public class MailClientApp {

    private static final SimpleDateFormat MAIL_DT_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static class MailConfig {
        public String mode = "LOCALHOST"; // LOCALHOST | GMAIL
        public String email = "";
        public String password = "";      // Nhập từ UI hoặc mail.properties, không hardcode
        public String smtpHost = "127.0.0.1";
        public int smtpPort = MailServerApp.DEFAULT_SMTP_PORT;
        public String pop3Host = "127.0.0.1";
        public int pop3Port = MailServerApp.DEFAULT_POP3_PORT;
        public boolean useStartTls = false;
        public boolean usePop3Ssl = false;
    }

    /** Lõi xử lý SMTP / POP3 + SQLite tách khỏi giao diện Swing. */
    public static class MailClientCore {
        private final MailDatabase db;
        private Consumer<String> protocolLogger;

        public MailClientCore(Path dbPath) throws SQLException {
            this.db = new MailDatabase(dbPath);
        }

        public void setProtocolLogger(Consumer<String> protocolLogger) {
            this.protocolLogger = protocolLogger;
        }

        public MailDatabase getDatabase() {
            return db;
        }

        private void emitLog(String s) {
            if (protocolLogger != null) protocolLogger.accept(s);
        }

        private PrintStream createDebugStream() {
            OutputStream os = new OutputStream() {
                private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
                @Override public void write(int b) {
                    if (b == '\n') {
                        String line = buf.toString(StandardCharsets.UTF_8).replace("\r", "");
                        buf.reset();
                        // Tránh in mật khẩu thô nếu log debug chứa AUTH PLAIN/LOGIN
                        if (!line.startsWith("AUTH ") && !line.startsWith("PASS ")) {
                            emitLog(line);
                        } else if (line.startsWith("PASS ")) {
                            emitLog("PASS ********");
                        } else {
                            emitLog("AUTH ********");
                        }
                    } else {
                        buf.write(b);
                    }
                }
            };
            return new PrintStream(os, true, StandardCharsets.UTF_8);
        }

        /** Gửi Email thực tế qua giao thức SMTP và lưu bản sao vào SQLite (`SENT`). */
        public void sendEmail(MailConfig cfg, String recipient, String subject, String bodyText)
                throws MessagingException, SQLException {
            Properties props = new Properties();
            props.put("mail.smtp.host", cfg.smtpHost.trim());
            props.put("mail.smtp.port", String.valueOf(cfg.smtpPort));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            if (cfg.useStartTls) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
            }

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(cfg.email.trim(), cfg.password);
                }
            });
            session.setDebugOut(createDebugStream());
            session.setDebug(true);

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(cfg.email.trim()));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient.trim()));
            msg.setSubject(subject, "UTF-8");
            Date now = new Date();
            msg.setSentDate(now);
            msg.setText(bodyText, "UTF-8");

            Transport.send(msg);

            db.saveEmail(
                    cfg.email.trim(),
                    "SENT",
                    cfg.mode,
                    cfg.email.trim(),
                    recipient.trim(),
                    subject,
                    bodyText,
                    MAIL_DT_FMT.format(now),
                    msg.getMessageID()
            );
            emitLog("[SMTP OK] Đã gửi thư tới " + recipient);
        }

        /** Nhận Email từ hộp thư INBOX qua giao thức POP3 (`Store`) và lưu vào SQLite (`INBOX`). */
        public List<MailDatabase.EmailRecord> fetchEmailsPop3(MailConfig cfg)
                throws MessagingException, IOException, SQLException {
            Properties props = new Properties();
            String proto = cfg.usePop3Ssl ? "pop3s" : "pop3";
            props.put("mail." + proto + ".host", cfg.pop3Host.trim());
            props.put("mail." + proto + ".port", String.valueOf(cfg.pop3Port));
            props.put("mail." + proto + ".connectiontimeout", "10000");
            props.put("mail." + proto + ".timeout", "10000");
            if (cfg.usePop3Ssl) {
                props.put("mail.pop3s.ssl.enable", "true");
            }

            Session session = Session.getInstance(props);
            session.setDebugOut(createDebugStream());
            session.setDebug(true);

            Store store = session.getStore(proto);
            Folder inbox = null;
            int added = 0;
            try {
                store.connect(cfg.pop3Host.trim(), cfg.pop3Port, cfg.email.trim(), cfg.password);
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);

                Message[] messages = inbox.getMessages();
                for (int i = 0; i < messages.length; i++) {
                    Message m = messages[i];
                    String sender = formatAddresses(m.getFrom());
                    String recips = formatAddresses(m.getRecipients(Message.RecipientType.TO));
                    if (recips.isEmpty()) recips = cfg.email.trim();
                    String subj = m.getSubject() != null ? m.getSubject() : "(Không tiêu đề)";
                    Date dt = m.getSentDate() != null ? m.getSentDate() : new Date();
                    String sentStr = MAIL_DT_FMT.format(dt);
                    String body = extractTextContent(m);

                    String uid = null;
                    if (m instanceof MimeMessage mm && mm.getMessageID() != null) {
                        uid = mm.getMessageID();
                    }
                    db.saveEmail(cfg.email.trim(), "INBOX", cfg.mode, sender, recips, subj, body, sentStr, uid);
                    added++;
                }
                emitLog("[POP3 OK] Đã kiểm tra hộp thư: tìm thấy " + added + " thư trên Server.");
            } finally {
                if (inbox != null && inbox.isOpen()) {
                    try { inbox.close(false); } catch (MessagingException ignored) {}
                }
                try { store.close(); } catch (MessagingException ignored) {}
            }
            return db.listEmails(cfg.email.trim(), "INBOX");
        }

        private static String formatAddresses(Address[] addrs) {
            if (addrs == null || addrs.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < addrs.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(addrs[i].toString());
            }
            return sb.toString();
        }

        /** Trích xuất nội dung văn bản từ MimeMessage đơn giản hoặc Multipart (Gmail). */
        public static String extractTextContent(Part part) throws MessagingException, IOException {
            if (part.isMimeType("text/plain")) {
                Object c = part.getContent();
                return c != null ? c.toString() : "";
            }
            if (part.isMimeType("text/html")) {
                Object c = part.getContent();
                return c != null ? c.toString() : "";
            }
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                String htmlFallback = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    if (bp.isMimeType("text/plain")) {
                        return extractTextContent(bp);
                    }
                    String candidate = extractTextContent(bp);
                    if (htmlFallback == null && !candidate.isBlank()) {
                        htmlFallback = candidate;
                    }
                }
                return htmlFallback != null ? htmlFallback : "";
            }
            Object content = part.getContent();
            return content != null ? content.toString() : "";
        }

        public static Properties loadConfigFile(Path propPath) throws IOException {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(propPath, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            return p;
        }
    }

    // =========================================================================
    // GIAO DIỆN JAVA SWING CHO MAIL CLIENT (LOCALHOST & GMAIL)
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
        Path dbPath = MailServerApp.resolveLabDir().resolve("data").resolve("mail_client_lab03.db");
        SwingUtilities.invokeLater(() -> launchGui(dbPath));
    }

    public static void launchGui(Path dbPath) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 03 — Java Mail Client (Jakarta Mail: SMTP & POP3)");
        frame.setSize(1160, 720);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        final MailClientCore core;
        try {
            core = new MailClientCore(dbPath);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(frame, "Không mở được SQLite Mail Client: " + e.getMessage());
            return;
        }

        // 1. THANH CẤU HÌNH TÀI KHOẢN & CHẾ ĐỘ (LOCALHOST HOẶC GMAIL)
        JPanel topConfig = new JPanel(new GridLayout(2, 1, 4, 4));
        topConfig.setBackground(Color.WHITE);
        topConfig.setBorder(new TitledBorder("Cấu hình kết nối SMTP / POP3 (Không lưu mật khẩu trong mã nguồn)"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.setBackground(Color.WHITE);
        JComboBox<String> cboMode = new JComboBox<>(new String[]{"LOCALHOST", "GMAIL"});
        JTextField txtEmail = new JTextField("a@ltm.local", 18);
        JPasswordField txtPass = new JPasswordField(14);
        JButton btnLoadFile = new JButton("Nạp mail.properties...");

        row1.add(new JLabel("Chế độ:")); row1.add(cboMode);
        row1.add(new JLabel("Email:")); row1.add(txtEmail);
        row1.add(new JLabel("Mật khẩu / App Password:")); row1.add(txtPass);
        row1.add(btnLoadFile);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.setBackground(Color.WHITE);
        JTextField txtSmtpHost = new JTextField("127.0.0.1", 14);
        JTextField txtSmtpPort = new JTextField(String.valueOf(MailServerApp.DEFAULT_SMTP_PORT), 5);
        JCheckBox chkStartTls = new JCheckBox("SMTP STARTTLS", false);
        chkStartTls.setBackground(Color.WHITE);
        JTextField txtPop3Host = new JTextField("127.0.0.1", 14);
        JTextField txtPop3Port = new JTextField(String.valueOf(MailServerApp.DEFAULT_POP3_PORT), 5);
        JCheckBox chkPopSsl = new JCheckBox("POP3 SSL", false);
        chkPopSsl.setBackground(Color.WHITE);

        row2.add(new JLabel("SMTP Host:")); row2.add(txtSmtpHost);
        row2.add(new JLabel("Port:")); row2.add(txtSmtpPort);
        row2.add(chkStartTls);
        row2.add(new JLabel(" | POP3 Host:")); row2.add(txtPop3Host);
        row2.add(new JLabel("Port:")); row2.add(txtPop3Port);
        row2.add(chkPopSsl);

        topConfig.add(row1);
        topConfig.add(row2);
        root.add(topConfig, BorderLayout.NORTH);

        cboMode.addActionListener(e -> {
            if ("GMAIL".equals(cboMode.getSelectedItem())) {
                txtSmtpHost.setText("smtp.gmail.com");
                txtSmtpPort.setText("587");
                chkStartTls.setSelected(true);
                txtPop3Host.setText("pop.gmail.com");
                txtPop3Port.setText("995");
                chkPopSsl.setSelected(true);
            } else {
                txtSmtpHost.setText("127.0.0.1");
                txtSmtpPort.setText(String.valueOf(MailServerApp.DEFAULT_SMTP_PORT));
                chkStartTls.setSelected(false);
                txtPop3Host.setText("127.0.0.1");
                txtPop3Port.setText(String.valueOf(MailServerApp.DEFAULT_POP3_PORT));
                chkPopSsl.setSelected(false);
            }
        });

        // 2. KHU VỰC GIỮA: TRÁI (INBOX / SENT + XEM NỘI DUNG), PHẢI (SOẠN THƯ SMTP)
        JPanel mailBoxPanel = new JPanel(new BorderLayout(6, 6));
        mailBoxPanel.setBackground(Color.WHITE);

        JPanel mailActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        mailActions.setBackground(new Color(248, 250, 252));
        JButton btnFetchPop3 = new JButton("Nhận Email qua POP3");
        styleBtn(btnFetchPop3, new Color(5, 150, 105));
        JComboBox<String> cboFolder = new JComboBox<>(new String[]{"INBOX (Đã nhận)", "SENT (Đã gửi)"});
        JButton btnLoadDb = new JButton("Tải từ SQLite");
        mailActions.add(btnFetchPop3);
        mailActions.add(cboFolder);
        mailActions.add(btnLoadDb);
        mailBoxPanel.add(mailActions, BorderLayout.NORTH);

        DefaultTableModel mailModel = new DefaultTableModel(
                new String[]{"Người gửi", "Người nhận", "Tiêu đề", "Thời gian"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblMail = new JTable(mailModel);
        tblMail.setRowHeight(24);
        JTextArea txtViewBody = new JTextArea();
        txtViewBody.setEditable(false);
        txtViewBody.setLineWrap(true);
        txtViewBody.setWrapStyleWord(true);
        txtViewBody.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane bodyScroll = new JScrollPane(txtViewBody);
        bodyScroll.setBorder(new TitledBorder("Nội dung chi tiết Email"));

        JSplitPane listAndBody = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tblMail), bodyScroll);
        listAndBody.setDividerLocation(210);
        mailBoxPanel.add(listAndBody, BorderLayout.CENTER);

        // BÊN PHẢI: FORM SOẠN VÀ GỬI EMAIL QUA SMTP
        JPanel composePanel = new JPanel(new BorderLayout(6, 6));
        composePanel.setPreferredSize(new Dimension(410, 0));
        composePanel.setBackground(Color.WHITE);
        composePanel.setBorder(new TitledBorder("Soạn & Gửi Email (Giao thức SMTP)"));

        JPanel fields = new JPanel(new GridLayout(2, 2, 6, 6));
        fields.setBorder(new EmptyBorder(6, 6, 6, 6));
        fields.setBackground(Color.WHITE);
        JTextField txtTo = new JTextField("b@ltm.local");
        JTextField txtSubject = new JTextField("Thử nghiệm Jakarta Mail Lab 03");
        fields.add(new JLabel("Người nhận (To):")); fields.add(txtTo);
        fields.add(new JLabel("Tiêu đề (Subject):")); fields.add(txtSubject);
        composePanel.add(fields, BorderLayout.NORTH);

        JTextArea txtComposeBody = new JTextArea("Xin chào!\nĐây là email gửi thực tế qua SMTP và nhận qua POP3 trong Lab 03.");
        txtComposeBody.setLineWrap(true);
        txtComposeBody.setWrapStyleWord(true);
        txtComposeBody.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        composePanel.add(new JScrollPane(txtComposeBody), BorderLayout.CENTER);

        JButton btnSendSmtp = new JButton("Gửi Email qua SMTP");
        styleBtn(btnSendSmtp, new Color(37, 99, 235));
        composePanel.add(btnSendSmtp, BorderLayout.SOUTH);

        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mailBoxPanel, composePanel);
        centerSplit.setDividerLocation(690);

        // 3. DƯỚI CÙNG: NHẬT KÝ SMTP / POP3
        JTextArea txtProtoLog = new JTextArea();
        txtProtoLog.setEditable(false);
        txtProtoLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtProtoLog.setBackground(new Color(15, 23, 42));
        txtProtoLog.setForeground(new Color(226, 232, 240));
        JScrollPane logScroll = new JScrollPane(txtProtoLog);
        logScroll.setPreferredSize(new Dimension(0, 155));
        logScroll.setBorder(new TitledBorder("Nhật ký giao thức SMTP / POP3 (Jakarta Mail Debug)"));

        JSplitPane vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centerSplit, logScroll);
        vertSplit.setDividerLocation(425);
        root.add(vertSplit, BorderLayout.CENTER);

        core.setProtocolLogger(line -> SwingUtilities.invokeLater(() -> {
            txtProtoLog.append(line + "\n");
            txtProtoLog.setCaretPosition(txtProtoLog.getDocument().getLength());
        }));

        final AtomicReference<List<MailDatabase.EmailRecord>> displayedRecords = new AtomicReference<>(new ArrayList<>());

        Runnable reloadTableFromDb = () -> {
            String dir = cboFolder.getSelectedIndex() == 0 ? "INBOX" : "SENT";
            String userEmail = txtEmail.getText().trim();
            try {
                List<MailDatabase.EmailRecord> recs = core.getDatabase().listEmails(userEmail, dir);
                displayedRecords.set(recs);
                mailModel.setRowCount(0);
                for (MailDatabase.EmailRecord r : recs) {
                    mailModel.addRow(new Object[]{r.sender, r.recipient, r.subject, r.sentAt});
                }
                txtViewBody.setText("");
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi đọc SQLite: " + ex.getMessage());
            }
        };

        tblMail.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = tblMail.getSelectedRow();
                List<MailDatabase.EmailRecord> list = displayedRecords.get();
                if (row >= 0 && row < list.size()) {
                    MailDatabase.EmailRecord r = list.get(row);
                    txtViewBody.setText("Từ: " + r.sender
                            + "\nTới: " + r.recipient
                            + "\nTiêu đề: " + r.subject
                            + "\nThời gian: " + r.sentAt
                            + "\nChế độ: " + r.mode
                            + "\n----------------------------------------\n" + r.body);
                }
            }
        });

        cboFolder.addActionListener(e -> reloadTableFromDb.run());
        btnLoadDb.addActionListener(e -> reloadTableFromDb.run());

        btnLoadFile.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(MailServerApp.resolveLabDir().toFile());
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                try {
                    Properties p = MailClientCore.loadConfigFile(fc.getSelectedFile().toPath());
                    String m = p.getProperty("mode", "GMAIL").toUpperCase();
                    cboMode.setSelectedItem(m);
                    txtEmail.setText(p.getProperty("email", txtEmail.getText()));
                    txtPass.setText(p.getProperty("password", ""));
                    txtSmtpHost.setText(p.getProperty("smtp.host", txtSmtpHost.getText()));
                    txtSmtpPort.setText(p.getProperty("smtp.port", txtSmtpPort.getText()));
                    txtPop3Host.setText(p.getProperty("pop3.host", txtPop3Host.getText()));
                    txtPop3Port.setText(p.getProperty("pop3.port", txtPop3Port.getText()));
                    if (p.containsKey("smtp.starttls")) chkStartTls.setSelected(Boolean.parseBoolean(p.getProperty("smtp.starttls")));
                    if (p.containsKey("pop3.ssl")) chkPopSsl.setSelected(Boolean.parseBoolean(p.getProperty("pop3.ssl")));
                    reloadTableFromDb.run();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(frame, "Không đọc được file cấu hình: " + ex.getMessage());
                }
            }
        });

        java.util.function.Supplier<MailConfig> readConfig = () -> {
            MailConfig cfg = new MailConfig();
            cfg.mode = String.valueOf(cboMode.getSelectedItem());
            cfg.email = txtEmail.getText().trim();
            cfg.password = new String(txtPass.getPassword());
            cfg.smtpHost = txtSmtpHost.getText().trim();
            cfg.smtpPort = Integer.parseInt(txtSmtpPort.getText().trim());
            cfg.pop3Host = txtPop3Host.getText().trim();
            cfg.pop3Port = Integer.parseInt(txtPop3Port.getText().trim());
            cfg.useStartTls = chkStartTls.isSelected();
            cfg.usePop3Ssl = chkPopSsl.isSelected();
            if (cfg.email.isEmpty() || cfg.password.isEmpty()) {
                throw new IllegalArgumentException("Vui lòng nhập đầy đủ Email và Mật khẩu / App Password.");
            }
            if (cfg.smtpHost.isEmpty() || cfg.pop3Host.isEmpty()) {
                throw new IllegalArgumentException("Host SMTP / POP3 không được để trống.");
            }
            if (cfg.smtpPort < 1 || cfg.smtpPort > 65535 || cfg.pop3Port < 1 || cfg.pop3Port > 65535) {
                throw new IllegalArgumentException("Cổng SMTP / POP3 phải nằm trong khoảng 1–65535.");
            }
            return cfg;
        };

        btnSendSmtp.addActionListener(e -> {
            MailConfig cfg;
            try {
                cfg = readConfig.get();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Cổng (Port) không hợp lệ.", "Thiếu / sai thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(frame, ex.getMessage(), "Thiếu / sai thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String toAddr = txtTo.getText().trim();
            if (toAddr.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Vui lòng nhập địa chỉ Email người nhận (To).", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            btnSendSmtp.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    core.sendEmail(cfg, toAddr, txtSubject.getText(), txtComposeBody.getText());
                    return null;
                }
                @Override protected void done() {
                    btnSendSmtp.setEnabled(true);
                    try {
                        get();
                        cboFolder.setSelectedIndex(1); // Chuyển sang tab Đã gửi
                        reloadTableFromDb.run();
                        JOptionPane.showMessageDialog(frame, "Gửi email thành công qua SMTP!");
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(frame, "Lỗi gửi SMTP: " + c.getMessage(), "Lỗi SMTP", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        btnFetchPop3.addActionListener(e -> {
            MailConfig cfg;
            try {
                cfg = readConfig.get();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Cổng (Port) không hợp lệ.", "Thiếu / sai thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(frame, ex.getMessage(), "Thiếu / sai thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }
            btnFetchPop3.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    core.fetchEmailsPop3(cfg);
                    return null;
                }
                @Override protected void done() {
                    btnFetchPop3.setEnabled(true);
                    try {
                        get();
                        cboFolder.setSelectedIndex(0);
                        reloadTableFromDb.run();
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        JOptionPane.showMessageDialog(frame, "Lỗi nhận POP3: " + c.getMessage(), "Lỗi POP3", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });

        reloadTableFromDb.run();
        frame.setVisible(true);
    }
}
