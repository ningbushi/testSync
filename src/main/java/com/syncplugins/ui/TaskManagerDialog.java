package com.syncplugins.ui;

import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.workspace.WorkspaceBootstrapService;

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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

final class TaskManagerDialog extends JDialog {
    private final DefaultListModel<AppConfig> model = new DefaultListModel<>();
    private final JList<AppConfig> list = new JList<>(model);
    private final InputMemoryStore memoryStore;
    private final SecretStore secretStore;
    private final KnownHostsStore knownHosts;
    private final WorkspaceBootstrapService workspaceService;
    private final Function<SetupResult, AppConfig> saveTask;
    private final Consumer<String> deleteTask;

    TaskManagerDialog(java.awt.Frame owner, List<AppConfig> tasks,
                      InputMemoryStore memoryStore, SecretStore secretStore,
                      KnownHostsStore knownHosts, WorkspaceBootstrapService workspaceService,
                      Function<SetupResult, AppConfig> saveTask,
                      Consumer<String> deleteTask) {
        super(owner, "同步任务", true);
        this.memoryStore = memoryStore;
        this.secretStore = secretStore;
        this.knownHosts = knownHosts;
        this.workspaceService = workspaceService;
        this.saveTask = saveTask;
        this.deleteTask = deleteTask;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 12));
        setMinimumSize(new Dimension(700, 430));
        setSize(760, 480);
        setLocationRelativeTo(owner);

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(18, 20, 0, 20));
        JLabel title = new JLabel("每个任务对应一个本地目录、一个仓库和一个分支");
        header.add(title, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        list.setCellRenderer(new TaskRenderer());
        add(new JScrollPane(list), BorderLayout.CENTER);

        JButton add = new JButton("新增任务");
        JButton edit = new JButton("编辑任务");
        JButton delete = new JButton("删除任务");
        JButton close = new JButton("关闭");
        add.addActionListener(event -> edit(new AppConfig()));
        edit.addActionListener(event -> {
            AppConfig selected = list.getSelectedValue();
            if (selected == null) {
                JOptionPane.showMessageDialog(this, "请先选择任务");
            } else {
                edit(selected);
            }
        });
        delete.addActionListener(event -> delete());
        close.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(add);
        buttons.add(edit);
        buttons.add(delete);
        buttons.add(close);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 20, 16, 20));
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        tasks.stream().sorted(java.util.Comparator.comparing(
                        task -> task.taskName, String.CASE_INSENSITIVE_ORDER))
                .forEach(model::addElement);
    }

    private void edit(AppConfig source) {
        SetupDialog dialog = new SetupDialog(this, source, memoryStore, secretStore,
                knownHosts, workspaceService);
        SetupResult result = dialog.showDialog();
        if (result == null) {
            return;
        }
        try {
            AppConfig saved = saveTask.apply(result);
            replace(saved);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "保存任务失败", JOptionPane.ERROR_MESSAGE);
        } finally {
            java.util.Arrays.fill(result.passphrase(), '\0');
        }
    }

    private void replace(AppConfig saved) {
        for (int index = 0; index < model.size(); index++) {
            if (saved.taskId.equals(model.get(index).taskId)) {
                model.set(index, saved);
                list.setSelectedIndex(index);
                return;
            }
        }
        model.addElement(saved);
        list.setSelectedValue(saved, true);
    }

    private void delete() {
        AppConfig selected = list.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "请先选择任务");
            return;
        }
        if (JOptionPane.showConfirmDialog(this,
                "停止并删除任务“" + selected.taskName + "”？\n不会删除本地文件或 GitHub 仓库。",
                "删除任务", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) {
            return;
        }
        deleteTask.accept(selected.taskId);
        model.removeElement(selected);
    }

    private static final class TaskRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof AppConfig task) {
                setText(task.taskName + "    " + task.repositoryPath
                        + "    [" + task.branch + "]");
            }
            return this;
        }
    }
}
