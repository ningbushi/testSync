package com.syncplugins.ui;

import com.syncplugins.sync.MultiTaskController;
import com.syncplugins.workspace.BackupRecord;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class BackupDialog extends JDialog {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final MultiTaskController controller;
    private final String taskId;
    private final DefaultListModel<BackupRecord> model = new DefaultListModel<>();
    private final JList<BackupRecord> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");

    BackupDialog(java.awt.Frame owner, MultiTaskController controller, String taskId) {
        super(owner, "下载前备份", true);
        this.controller = controller;
        this.taskId = taskId;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 12));
        setMinimumSize(new Dimension(620, 380));
        setSize(680, 430);
        setLocationRelativeTo(owner);

        JLabel title = new JLabel("云端覆盖本地前自动保留最近 3 份备份");
        title.setBorder(BorderFactory.createEmptyBorder(18, 20, 0, 20));
        add(title, BorderLayout.NORTH);
        list.setCellRenderer(new BackupRenderer());
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 20, 16, 20));
        JButton restore = new JButton("恢复选中备份");
        JButton delete = new JButton("删除选中备份");
        JButton close = new JButton("关闭");
        restore.addActionListener(event -> restore());
        delete.addActionListener(event -> delete());
        close.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(status);
        buttons.add(restore);
        buttons.add(delete);
        buttons.add(close);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        reload();
    }

    private void reload() {
        model.clear();
        List<BackupRecord> records = controller.backups(taskId);
        records.forEach(model::addElement);
        status.setText(records.isEmpty() ? "暂无备份" : "共 " + records.size() + " 份备份");
        status.setForeground(records.isEmpty() ? new Color(110, 116, 126) : new Color(40, 100, 60));
    }

    private void restore() {
        BackupRecord record = list.getSelectedValue();
        if (record == null) {
            JOptionPane.showMessageDialog(this, "请先选择一份备份");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "恢复会覆盖当前工作区。当前内容会先重新进入自动备份队列。\n继续吗？",
                "恢复备份", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        status.setText("正在恢复...");
        CompletableFuture<Void> future = controller.restoreBackup(taskId, record);
        future.whenComplete((ignored, error) -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (error != null) {
                JOptionPane.showMessageDialog(this, error.getMessage(),
                        "恢复失败", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "备份已恢复");
            }
            reload();
        }));
    }

    private void delete() {
        BackupRecord record = list.getSelectedValue();
        if (record == null) {
            JOptionPane.showMessageDialog(this, "请先选择一份备份");
            return;
        }
        controller.deleteBackup(taskId, record);
        reload();
    }

    private static final class BackupRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof BackupRecord record) {
                setText(TIME.format(Instant.ofEpochMilli(record.createdAt()))
                        + "    " + record.localPath());
            }
            return this;
        }
    }
}
