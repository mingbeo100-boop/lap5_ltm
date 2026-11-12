package com.ltm.lab03;

import com.icegreen.greenmail.mail.MailAddress;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;

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
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lab 03 — Localhost / LAN Mail Server thực thụ (SMTP + POP3) dựa trên GreenMail + SQLite:
 * - Lắng nghe SMTP thực tế để nhận thư gửi đi từ Mail Client.
 * - Lắng nghe POP3 thực tế để Mail Client kết nối tải thư về hộp thư cá nhân.
 * - Chặn MessageDeliveryHandler để đồng thời lưu bản sao email vào bảng `emails` của SQLite.
 */
public class MailServerApp {

    public static final int DEFAULT_SMTP_PORT = 3025;
    public static final int DEFAULT_POP3_PORT = 3110;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final SimpleDateFormat MAIL_DT_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static String getLocalLanIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Lõi Localhost Mail Server tích hợp SMTP + POP3 + SQLite. */
    public static class LocalMailServerCore {
        private final String bindHost;
        private int smtpPort;
        private int pop3Port;
        private final MailDatabase db;
        private GreenMail greenMail;
        private volatile boolean running = false;
        private final Set<String> activeSessionAccounts = ConcurrentHashMap.newKeySet();

        private Consumer<String> logListener;
        private Runnable dataChangeListener;

        public LocalMailServerCore(String bindHost, int smtpPort, int pop3Port, Path dbPath) throws SQLException {
            this.bindHost = bindHost;
            this.smtpPort = smtpPort;
            this.pop3Port = pop3Port;
            this.db = new MailDatabase(dbPath);
        }

        public void setLogListener(Consumer<String> logListener) {
            this.logListener = logListener;
        }

        public void setDataChangeListener(Runnable dataChangeListener) {
            this.dataChangeListener = dataChangeListener;
        }

        public MailDatabase getDatabase() {
            return db;
        }

        public int getSmtpPort() {
            return greenMail != null ? greenMail.getSmtp().getPort() : smtpPort;
        }

        public int getPop3Port() {
            return greenMail != null ? greenMail.getPop3().getPort() : pop3Port;
        }

        public boolean isRunning() {
            return running;
        }

        private void log(String msg) {
            String line = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
            if (logListener != null) logListener.accept(line);
            else System.out.println(line);
        }

        public synchronized void start() {
            if (running) return;
            ServerSetup smtp = new ServerSetup(smtpPort, bindHost, ServerSetup.PROTOCOL_SMTP);
            ServerSetup pop3 = new ServerSetup(pop3Port, bindHost, ServerSetup.PROTOCOL_POP3);
            smtp.setServerStartupTimeout(5000);
            pop3.setServerStartupTimeout(5000);

            greenMail = new GreenMail(new ServerSetup[]{smtp, pop3});
            greenMail.getUserManager().setAuthRequired(true);

            // Ghi nhận mỗi bức thư đi qua SMTP Server trước khi giao vào hộp thư POP3 của GreenMail
            MessageDeliveryHandler defaultHandler = greenMail.getUserManager().getMessageDeliveryHandler();
            greenMail.getUserManager().setMessageDeliveryHandler((MovingMessage msg, MailAddress targetAddr) -> {
                GreenMailUser user = defaultHandler != null ? defaultHandler.handle(msg, targetAddr) : null;
                persistSmtpMailToDb(msg, targetAddr);
                return user;
            });

            greenMail.start();
            this.smtpPort = greenMail.getSmtp().getPort();
            this.pop3Port = greenMail.getPop3().getPort();
            this.running = true;
            log("Mail Server đã khởi động! SMTP=" + bindHost + ":" + this.smtpPort
                    + " | POP3=" + bindHost + ":" + this.pop3Port + " (IP LAN: " + getLocalLanIp() + ")");
        }

        private void persistSmtpMailToDb(MovingMessage msg, MailAddress targetAddr) {
            try {
                MimeMessage mime = msg.getMessage();
                String from = mime.getFrom() != null && mime.getFrom().length > 0
                        ? mime.getFrom()[0].toString()
                        : (msg.getReturnPath() != null ? msg.getReturnPath().getEmail() : "unknown");
                String to = targetAddr != null ? targetAddr.getEmail() : formatAddresses(mime.getAllRecipients());
                String subject = mime.getSubject() != null ? mime.getSubject() : "(Không tiêu đề)";
                String body = MailClientApp.MailClientCore.extractTextContent(mime);
                Date sent = mime.getSentDate() != null ? mime.getSentDate() : new Date();
                String sentAt = MAIL_DT_FMT.format(sent);
                String msgId = mime.getMessageID();

                db.saveEmail(to, "SERVER_INBOX", "LOCALHOST", from, to, subject, body, sentAt, msgId);
                log("[SMTP Nhận] " + from + " -> " + to + " | Tiêu đề: " + subject);
                if (dataChangeListener != null) dataChangeListener.run();
            } catch (Exception e) {
                log("[Lỗi lưu email vào SQLite] " + e.getMessage());
            }
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

        public synchronized void stop() {
            running = false;
            activeSessionAccounts.clear();
            if (greenMail != null) {
                greenMail.stop();
                greenMail = null;
                log("Mail Server đã dừng.");
            }
        }

        public boolean isAccountActiveInSession(String email) {
            return email != null && running && activeSessionAccounts.contains(email.trim().toLowerCase(Locale.ROOT));
        }

        public synchronized void createLocalAccount(String email, String password) throws SQLException {
            String cleanEmail = email == null ? "" : email.trim();
            if (cleanEmail.isEmpty() || password == null || password.isEmpty()) {
                throw new IllegalArgumentException("Email và mật khẩu không được rỗng.");
            }
            if (greenMail == null || !running) {
                throw new IllegalStateException("Vui lòng khởi chạy Mail Server trước khi tạo tài khoản.");
            }
            greenMail.setUser(cleanEmail, cleanEmail, password);
            activeSessionAccounts.add(cleanEmail.toLowerCase(Locale.ROOT));
            db.saveAccount(cleanEmail, password, "LOCALHOST");
            log("[Account] Đã kích hoạt hộp thư Localhost: " + cleanEmail);
            if (dataChangeListener != null) dataChangeListener.run();
        }
    }

    // =========================================================================
    // GIAO DIỆN SWING QUẢN TRỊ LOCALHOST MAIL SERVER
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
            var cs = MailServerApp.class.getProtectionDomain().getCodeSource();
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
        Path nested = cwd.resolve("LTM_5_LABS").resolve("Lab03_Mail");
        if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        Path sibling = cwd.resolve("Lab03_Mail");
        if (Files.isRegularFile(sibling.resolve("pom.xml"))) return sibling;
        return cwd;
    }

    public static void main(String[] args) {
        Path dbPath = resolveLabDir().resolve("data").resolve("mail_server_lab03.db");
        SwingUtilities.invokeLater(() -> launchGui(DEFAULT_SMTP_PORT, DEFAULT_POP3_PORT, dbPath));
    }

    public static void launchGui(int defaultSmtp, int defaultPop3, Path dbPath) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 03 — Localhost Mail Server (SMTP + POP3 + SQLite)");
        frame.setSize(1080, 670);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        // 1. THANH ĐIỀU KHIỂN SERVER
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));
        JLabel lblIp = new JLabel("IP LAN: " + getLocalLanIp());
        lblIp.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblIp.setForeground(new Color(15, 118, 110));

        JTextField txtSmtpPort = new JTextField(String.valueOf(defaultSmtp), 5);
        JTextField txtPop3Port = new JTextField(String.valueOf(defaultPop3), 5);
        JButton btnToggle = new JButton("Khởi chạy Mail Server");
        styleBtn(btnToggle, new Color(37, 99, 235));
        JLabel lblStatus = new JLabel("● Đã dừng");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblStatus.setForeground(new Color(220, 38, 38));

        topBar.add(lblIp);
        topBar.add(new JLabel("SMTP Port:")); topBar.add(txtSmtpPort);
        topBar.add(new JLabel("POP3 Port:")); topBar.add(txtPop3Port);
        topBar.add(btnToggle);
        topBar.add(lblStatus);
        root.add(topBar, BorderLayout.NORTH);

        // 2. BÊN TRÁI: TẠO TÀI KHOẢN LOCALHOST
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setPreferredSize(new Dimension(320, 0));
        leftPanel.setBackground(Color.WHITE);
        leftPanel.setBorder(new TitledBorder("Tài khoản Localhost (SMTP AUTH & POP3)"));

        DefaultTableModel accModel = new DefaultTableModel(new String[]{"Email", "Trạng thái", "Chế độ", "Ngày tạo"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblAcc = new JTable(accModel);
        leftPanel.add(new JScrollPane(tblAcc), BorderLayout.CENTER);

        JPanel formAcc = new JPanel(new GridLayout(3, 2, 6, 6));
        formAcc.setBorder(new EmptyBorder(6, 6, 6, 6));
        formAcc.setBackground(Color.WHITE);
        JTextField txtEmail = new JTextField("a@ltm.local");
        JPasswordField txtPass = new JPasswordField();
        JButton btnAddAcc = new JButton("Tạo / Kích hoạt");
        styleBtn(btnAddAcc, new Color(5, 150, 105));
        formAcc.add(new JLabel("Email nội bộ:")); formAcc.add(txtEmail);
        formAcc.add(new JLabel("Mật khẩu (nhập tay):")); formAcc.add(txtPass);
        formAcc.add(new JLabel("")); formAcc.add(btnAddAcc);
        leftPanel.add(formAcc, BorderLayout.SOUTH);

        // 3. BÊN PHẢI: BẢNG EMAIL LƯU TRÊN SERVER + LOG
        DefaultTableModel mailModel = new DefaultTableModel(
                new String[]{"ID", "Hộp thư", "Từ (Sender)", "Tới (Recipient)", "Tiêu đề", "Thời gian"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tblMail = new JTable(mailModel);
        JScrollPane mailScroll = new JScrollPane(tblMail);
        mailScroll.setBorder(new TitledBorder("Bảng SQLite Server: emails (Tất cả thư đi qua SMTP)"));

        JTextArea txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(226, 232, 240));
        JScrollPane logScroll = new JScrollPane(txtLog);
        logScroll.setBorder(new TitledBorder("Nhật ký SMTP & POP3 Server"));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mailScroll, logScroll);
        rightSplit.setDividerLocation(310);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit);
        mainSplit.setDividerLocation(335);
        root.add(mainSplit, BorderLayout.CENTER);

        final LocalMailServerCore[] srvRef = {null};

        Consumer<String> appendLog = line -> SwingUtilities.invokeLater(() -> {
            txtLog.append(line + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });

        Runnable refreshUi = () -> SwingUtilities.invokeLater(() -> {
            LocalMailServerCore s = srvRef[0];
            if (s == null) return;
            try {
                accModel.setRowCount(0);
                for (MailDatabase.AccountRecord a : s.getDatabase().listAccounts()) {
                    String activeTxt = s.isAccountActiveInSession(a.email)
                            ? "● Hoạt động"
                            : "○ Cần kích hoạt lại";
                    accModel.addRow(new Object[]{a.email, activeTxt, a.mode, a.createdAt});
                }
                mailModel.setRowCount(0);
                for (MailDatabase.EmailRecord m : s.getDatabase().listEmails(null, "SERVER_INBOX")) {
                    mailModel.addRow(new Object[]{m.id, m.mailboxEmail, m.sender, m.recipient, m.subject, m.sentAt});
                }
            } catch (SQLException ex) {
                appendLog.accept("[Lỗi đọc DB] " + ex.getMessage());
            }
        });

        tblAcc.getSelectionModel().addListSelectionListener(e -> {
            int row = tblAcc.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0) {
                txtEmail.setText(String.valueOf(accModel.getValueAt(row, 0)));
            }
        });

        Runnable startAct = () -> {
            try {
                int sp = Integer.parseInt(txtSmtpPort.getText().trim());
                int pp = Integer.parseInt(txtPop3Port.getText().trim());
                if (sp < 1 || sp > 65535 || pp < 1 || pp > 65535) {
                    throw new IllegalArgumentException("Cổng SMTP/POP3 phải nằm trong khoảng 1–65535.");
                }
                LocalMailServerCore core = new LocalMailServerCore("0.0.0.0", sp, pp, dbPath);
                core.setLogListener(appendLog);
                core.setDataChangeListener(refreshUi);
                core.start();
                srvRef[0] = core;

                btnToggle.setText("Dừng Mail Server");
                btnToggle.setBackground(new Color(220, 38, 38));
                lblStatus.setText("● Đang chạy (SMTP:" + core.getSmtpPort() + " | POP3:" + core.getPop3Port() + ")");
                lblStatus.setForeground(new Color(5, 150, 105));
                txtSmtpPort.setEnabled(false);
                txtPop3Port.setEnabled(false);
                if (!core.getDatabase().listAccounts().isEmpty()) {
                    appendLog.accept("Lưu ý: GreenMail lưu tài khoản trên RAM mỗi phiên — bấm vào tài khoản 'Cần kích hoạt lại', nhập mật khẩu rồi bấm 'Tạo / Kích hoạt'.");
                } else {
                    appendLog.accept("Gợi ý: Hãy tạo tài khoản thử nghiệm (vd: a@ltm.local, b@ltm.local) bằng form bên trái.");
                }
                refreshUi.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi khởi động Mail Server: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnToggle.addActionListener(e -> {
            if (srvRef[0] == null || !srvRef[0].isRunning()) {
                startAct.run();
            } else {
                srvRef[0].stop();
                btnToggle.setText("Khởi chạy Mail Server");
                btnToggle.setBackground(new Color(37, 99, 235));
                lblStatus.setText("● Đã dừng");
                lblStatus.setForeground(new Color(220, 38, 38));
                txtSmtpPort.setEnabled(true);
                txtPop3Port.setEnabled(true);
                refreshUi.run();
            }
        });

        btnAddAcc.addActionListener(e -> {
            LocalMailServerCore s = srvRef[0];
            if (s == null || !s.isRunning()) {
                JOptionPane.showMessageDialog(frame, "Khởi chạy Mail Server trước.");
                return;
            }
            try {
                s.createLocalAccount(txtEmail.getText(), new String(txtPass.getPassword()));
                txtPass.setText("");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Lỗi tạo tài khoản: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (srvRef[0] != null) srvRef[0].stop();
            }
        });
        frame.setVisible(true);
        startAct.run();
    }
}

