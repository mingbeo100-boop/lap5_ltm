package com.ltm.lab05;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab 05 — Java RMI Client:
 * - Kết nối linh hoạt tới RMI Registry của Server A (port 1099) hoặc Server B (port 1100) hay IP LAN khác.
 * - Thực hiện Thêm, Sửa, Xóa, Xem danh sách dữ liệu trực tiếp qua Remote Interface {@link SyncService}.
 */
public class RmiClientApp {

    static {
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "3000");
        System.setProperty("sun.rmi.transport.connectionTimeout", "3000");
    }

    private static void styleBtn(JButton b, Color bg) {
        b.setUI(new BasicButtonUI());
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(6, 12, 6, 12));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(RmiClientApp::launchGui);
    }

    public static void launchGui() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        JFrame frame = new JFrame("Lab 05 — Java RMI Client (Thêm / Sửa / Xóa & Kiểm tra Đồng bộ 2 Server)");
        frame.setSize(1020, 660);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setBackground(new Color(241, 245, 249));
        frame.setContentPane(root);

        // 1. THANH CHỌN SERVER ĐÍCH (SERVER A HOẶC SERVER B)
        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        connPanel.setBackground(Color.WHITE);
        connPanel.setBorder(new TitledBorder("Kết nối tới Java RMI Registry"));

        JComboBox<String> cboTarget = new JComboBox<>(new String[]{
                "Server A (127.0.0.1:1099)",
                "Server B (127.0.0.1:1100)",
                "Tùy chỉnh Host/Port..."
        });
        JTextField txtHost = new JTextField("127.0.0.1", 12);
        JTextField txtPort = new JTextField("1099", 5);
        JButton btnConnect = new JButton("Kết nối & Tải dữ liệu");
        styleBtn(btnConnect, new Color(37, 99, 235));
        JLabel lblConn = new JLabel("● Chưa kết nối");
        lblConn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblConn.setForeground(new Color(220, 38, 38));

        connPanel.add(new JLabel("Server đích:"));
        connPanel.add(cboTarget);
        connPanel.add(new JLabel("Host:"));
        connPanel.add(txtHost);
        connPanel.add(new JLabel("Port:"));
        connPanel.add(txtPort);
        connPanel.add(btnConnect);
        connPanel.add(lblConn);
        root.add(connPanel, BorderLayout.NORTH);

        // 2. BẢNG DỮ LIỆU + FORM THAO TÁC CRUD
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"UUID", "Mã SP", "Tên mặt hàng", "Số lượng", "Ghi chú", "Version", "Server tạo"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tbl = new JTable(model);
        tbl.setRowHeight(22);
        JScrollPane spTbl = new JScrollPane(tbl);
        spTbl.setBorder(new TitledBorder("Danh sách dữ liệu đọc qua RMI (getAllActiveItems)"));

        JPanel formPanel = new JPanel(new BorderLayout(8, 8));
        formPanel.setBackground(Color.WHITE);
        formPanel.setBorder(new TitledBorder("Thao tác Thêm / Sửa / Xóa qua RMI"));

        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        fields.setBackground(Color.WHITE);
        JTextField txtCode = new JTextField("SP01", 8);
        JTextField txtName = new JTextField("Router WiFi 6", 16);
        JTextField txtQty = new JTextField("10", 5);
        JTextField txtNote = new JTextField("Kho Lab 05", 16);
        fields.add(new JLabel("Mã SP:")); fields.add(txtCode);
        fields.add(new JLabel("Tên:")); fields.add(txtName);
        fields.add(new JLabel("Số lượng:")); fields.add(txtQty);
        fields.add(new JLabel("Ghi chú:")); fields.add(txtNote);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        btns.setBackground(Color.WHITE);
        JButton btnAdd = new JButton("+ Thêm mới");
        JButton btnUpdate = new JButton("✎ Sửa dòng đang chọn");
        JButton btnDelete = new JButton("🗑 Xóa dòng đang chọn");
        JButton btnRefresh = new JButton("↻ Làm mới bảng");
        styleBtn(btnAdd, new Color(5, 150, 105));
        styleBtn(btnUpdate, new Color(217, 119, 6));
        styleBtn(btnDelete, new Color(220, 38, 38));
        styleBtn(btnRefresh, new Color(15, 118, 110));
        btns.add(btnAdd);
        btns.add(btnUpdate);
        btns.add(btnDelete);
        btns.add(btnRefresh);

        formPanel.add(fields, BorderLayout.NORTH);
        formPanel.add(btns, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(6, 6));
        center.add(spTbl, BorderLayout.CENTER);
        center.add(formPanel, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        final SyncService[] stubRef = {null};
        final String[] connectedEndpoint = {""};
        final List<DataItem> currentItems = new ArrayList<>();

        java.util.function.Supplier<SyncService> getActiveOrFreshStub = () -> {
            try {
                String h = txtHost.getText().trim();
                int p = Integer.parseInt(txtPort.getText().trim());
                if (h.isEmpty() || p < 1 || p > 65535) {
                    throw new IllegalArgumentException("Host hoặc Port RMI không hợp lệ.");
                }
                String endpoint = h + ":" + p;
                if (stubRef[0] != null && endpoint.equals(connectedEndpoint[0])) {
                    try {
                        stubRef[0].ping();
                        return stubRef[0];
                    } catch (RemoteException stale) {
                        stubRef[0] = null;
                    }
                }
                Registry reg = LocateRegistry.getRegistry(h, p);
                SyncService fresh = (SyncService) reg.lookup(SyncServerApp.SERVICE_NAME);
                fresh.ping();
                stubRef[0] = fresh;
                connectedEndpoint[0] = endpoint;
                return fresh;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        };

        Runnable reloadItems = () -> new SwingWorker<List<DataItem>, Void>() {
            String serverPing;
            @Override protected List<DataItem> doInBackground() throws Exception {
                String h = txtHost.getText().trim();
                int p = Integer.parseInt(txtPort.getText().trim());
                if (h.isEmpty() || p < 1 || p > 65535) {
                    throw new IllegalArgumentException("Host hoặc Port RMI không hợp lệ (1–65535).");
                }
                Registry reg = LocateRegistry.getRegistry(h, p);
                SyncService stub = (SyncService) reg.lookup(SyncServerApp.SERVICE_NAME);
                serverPing = stub.ping();
                stubRef[0] = stub;
                connectedEndpoint[0] = h + ":" + p;
                return stub.getAllActiveItems();
            }

            @Override protected void done() {
                try {
                    List<DataItem> list = get();
                    currentItems.clear();
                    currentItems.addAll(list);
                    model.setRowCount(0);
                    for (DataItem it : list) {
                        model.addRow(new Object[]{it.id, it.code, it.name, it.quantity, it.note, it.version, it.originServer});
                    }
                    lblConn.setText("● Đang nối: " + serverPing + " (" + txtHost.getText().trim() + ":" + txtPort.getText().trim() + ")");
                    lblConn.setForeground(new Color(5, 150, 105));
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    stubRef[0] = null;
                    connectedEndpoint[0] = "";
                    lblConn.setText("● Mất kết nối (" + c.getMessage() + ")");
                    lblConn.setForeground(new Color(220, 38, 38));
                }
            }
        }.execute();

        cboTarget.addActionListener(e -> {
            int idx = cboTarget.getSelectedIndex();
            if (idx == 0) {
                txtHost.setText("127.0.0.1");
                txtPort.setText("1099");
                reloadItems.run();
            } else if (idx == 1) {
                txtHost.setText("127.0.0.1");
                txtPort.setText("1100");
                reloadItems.run();
            }
        });

        tbl.getSelectionModel().addListSelectionListener(e -> {
            int row = tbl.getSelectedRow();
            if (!e.getValueIsAdjusting() && row >= 0 && row < currentItems.size()) {
                DataItem it = currentItems.get(row);
                txtCode.setText(it.code);
                txtName.setText(it.name);
                txtQty.setText(String.valueOf(it.quantity));
                txtNote.setText(it.note);
            }
        });

        btnConnect.addActionListener(e -> reloadItems.run());
        btnRefresh.addActionListener(e -> reloadItems.run());

        btnAdd.addActionListener(e -> {
            String code = txtCode.getText().trim();
            String name = txtName.getText().trim();
            if (code.isEmpty() || name.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Mã SP và Tên mặt hàng không được để trống."); return;
            }
            int qty;
            try { qty = Integer.parseInt(txtQty.getText().trim()); } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Số lượng phải là số nguyên."); return;
            }
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    SyncService s = getActiveOrFreshStub.get();
                    s.createItem(code, name, qty, txtNote.getText());
                    return null;
                }
                @Override protected void done() {
                    try { get(); reloadItems.run(); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        if (c.getCause() != null) c = c.getCause();
                        JOptionPane.showMessageDialog(frame, "Lỗi Thêm RMI: " + c.getMessage());
                    }
                }
            }.execute();
        });

        btnUpdate.addActionListener(e -> {
            int row = tbl.getSelectedRow();
            if (row < 0 || row >= currentItems.size()) {
                JOptionPane.showMessageDialog(frame, "Chọn 1 dòng trong bảng để sửa."); return;
            }
            String code = txtCode.getText().trim();
            String name = txtName.getText().trim();
            if (code.isEmpty() || name.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Mã SP và Tên mặt hàng không được để trống."); return;
            }
            String id = currentItems.get(row).id;
            int qty;
            try { qty = Integer.parseInt(txtQty.getText().trim()); } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Số lượng phải là số nguyên."); return;
            }
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    SyncService s = getActiveOrFreshStub.get();
                    s.updateItem(id, code, name, qty, txtNote.getText());
                    return null;
                }
                @Override protected void done() {
                    try { get(); reloadItems.run(); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        if (c.getCause() != null) c = c.getCause();
                        JOptionPane.showMessageDialog(frame, "Lỗi Sửa RMI: " + c.getMessage());
                    }
                }
            }.execute();
        });

        btnDelete.addActionListener(e -> {
            int row = tbl.getSelectedRow();
            if (row < 0 || row >= currentItems.size()) {
                JOptionPane.showMessageDialog(frame, "Chọn 1 dòng trong bảng để xóa."); return;
            }
            String id = currentItems.get(row).id;
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception {
                    SyncService s = getActiveOrFreshStub.get();
                    s.deleteItem(id);
                    return null;
                }
                @Override protected void done() {
                    try { get(); reloadItems.run(); }
                    catch (Exception ex) {
                        Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        if (c.getCause() != null) c = c.getCause();
                        JOptionPane.showMessageDialog(frame, "Lỗi Xóa RMI: " + c.getMessage());
                    }
                }
            }.execute();
        });

        frame.setVisible(true);
    }
}

