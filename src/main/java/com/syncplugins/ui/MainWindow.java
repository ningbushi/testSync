package com.syncplugins.ui;

import com.syncplugins.AppPaths;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.config.TaskRepository;
import com.syncplugins.config.SyncMode;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.sync.MultiTaskController;
import com.syncplugins.sync.SyncStatus;
import com.syncplugins.sync.TaskSnapshot;
import com.syncplugins.workspace.WorkspaceBootstrapService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MainWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("SyncPlugins");
    private final MultiTaskController controller;
    private final TaskRepository taskRepository;
    private final Function<SetupResult, AppConfig> saveTask;
    private final Consumer<String> deleteTask;
    private final Runnable exitAction;
    private final AppPaths paths = AppPaths.system();
    private final InputMemoryStore memoryStore;
    private final SecretStore secretStore;
    private final WorkspaceBootstrapService workspaceService;
    private final JComboBox<TaskSnapshot> taskSelector = new JComboBox<>();
    private final JLabel stateLabel = new JLabel("正在启动");
    private final JLabel repositoryLabel = new JLabel(" ");
    private final JLabel branchLabel = new JLabel(" ");
    private final JLabel lastSyncLabel = new JLabel("尚未同步");
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel queueLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton pauseButton = new JButton("暂停选中任务");
    private final JButton syncButton = new JButton("同步选中任务");
    private final JButton settingsButton = new JButton("任务管理");
    private final JButton cloudButton = new JButton("下载选中任务");
    private final JButton backupButton = new JButton("备份");
    private final JTextArea logArea = new JTextArea();
    private final JPopupMenu trayMenu = new JPopupMenu();
    private final JWindow trayPopupOwner = new JWindow();
    private final JMenuItem trayProgressItem = new JMenuItem("同步进度：等待同步");
    private TrayIcon trayIcon;
    private List<TaskSnapshot> snapshots = List.of();
    private String selectedTaskId = "";
    private String lastLoggedError = "";
    private String lastLoggedMessage = "";
    private boolean updatingSelector;

    public MainWindow(MultiTaskController controller, List<AppConfig> tasks,
                      TaskRepository taskRepository,
                      Function<SetupResult, AppConfig> saveTask,
                      Consumer<String> deleteTask,
                      Runnable exitAction,
                      InputMemoryStore memoryStore, SecretStore secretStore,
                      WorkspaceBootstrapService workspaceService) {
        this.controller = controller;
        this.taskRepository = taskRepository;
        this.saveTask = saveTask;
        this.deleteTask = deleteTask;
        this.exitAction = exitAction;
        this.memoryStore = memoryStore;
        this.secretStore = secretStore;
        this.workspaceService = workspaceService;
        if (!tasks.isEmpty()) {
            selectedTaskId = tasks.get(0).taskId;
        }
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(AppIcon.image(64));
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setSize(980, 660);
        frame.setLocationByPlatform(true);
        frame.setLayout(new BorderLayout());
        frame.add(buildContent(), BorderLayout.CENTER);
        frame.add(buildFooter(), BorderLayout.SOUTH);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                hideToTray();
            }
        });
        taskSelector.addActionListener(event -> {
            if (!updatingSelector && taskSelector.getSelectedItem() instanceof TaskSnapshot snapshot) {
                selectedTaskId = snapshot.config().taskId;
                renderSelected(snapshot);
            }
        });
        controller.addListener(list -> SwingUtilities.invokeLater(() -> renderTasks(list)));
    }

    public void showWindow() {
        frame.setVisible(true);
        frame.toFront();
    }

    public void installTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        buildTrayMenu();
        trayPopupOwner.setAlwaysOnTop(true);
        trayPopupOwner.setFocusableWindowState(false);
        trayPopupOwner.setBackground(new Color(0, 0, 0, 0));
        trayPopupOwner.setSize(1, 1);
        trayPopupOwner.setLocation(-10_000, -10_000);
        trayMenu.setLightWeightPopupEnabled(false);
        trayMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                trayPopupOwner.setVisible(false);
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
                trayPopupOwner.setVisible(false);
            }
        });
        trayIcon = new TrayIcon(AppIcon.image(32), "SyncPlugins");
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger()) {
                    showSwingTrayMenu();
                } else if (event.getClickCount() == 2) {
                    showWindow();
                }
            }
        });
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception exception) {
            trayIcon = null;
        }
    }

    public void removeTray() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    public void dispose() {
        removeTray();
        frame.dispose();
    }

    public JFrame frame() {
        return frame;
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 12, 24));
        JPanel top = new JPanel(new BorderLayout(12, 0));
        JLabel title = new JLabel("SyncPlugins");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 15f));
        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.add(title, BorderLayout.WEST);
        taskSelector.setPreferredSize(new Dimension(280, 30));
        taskSelector.setRenderer(new TaskSnapshotRenderer());
        left.add(taskSelector, BorderLayout.CENTER);
        top.add(left, BorderLayout.WEST);
        top.add(stateLabel, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 223, 228)),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        summary.add(repositoryLabel);
        summary.add(Box.createVerticalStrut(8));
        summary.add(branchLabel);
        summary.add(Box.createVerticalStrut(8));
        summary.add(lastSyncLabel);
        summary.add(Box.createVerticalStrut(8));
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressBar.setMaximum(1);
        summary.add(progressBar);
        summary.add(Box.createVerticalStrut(8));
        summary.add(queueLabel);
        summary.add(Box.createVerticalStrut(8));
        errorLabel.setForeground(new Color(180, 55, 45));
        summary.add(errorLabel);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.add(summary, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setRows(10);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("选中任务操作记录"));
        body.add(scroll, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 24, 20, 24));
        settingsButton.addActionListener(event -> openTaskManager());
        JButton open = new JButton("打开选中目录");
        open.addActionListener(event -> openRepository());
        JButton syncAll = new JButton("全部同步");
        syncAll.addActionListener(event -> controller.syncAll());
        JButton pauseAll = new JButton("全部暂停");
        pauseAll.addActionListener(event -> controller.pauseAll(true));
        syncButton.addActionListener(event -> selectedControllerSync());
        pauseButton.addActionListener(event -> toggleSelectedPause());
        cloudButton.addActionListener(event -> downloadSelected());
        backupButton.addActionListener(event -> showBackups());
        JPanel actions = new JPanel(new GridLayout(1, 8, 7, 0));
        actions.add(settingsButton);
        actions.add(open);
        actions.add(syncButton);
        actions.add(syncAll);
        actions.add(pauseButton);
        actions.add(pauseAll);
        actions.add(cloudButton);
        actions.add(backupButton);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private void renderTasks(List<TaskSnapshot> list) {
        snapshots = list;
        TaskSnapshot selected = selectedSnapshot();
        if (selected == null && !list.isEmpty()) {
            selected = list.get(0);
            selectedTaskId = selected.config().taskId;
        }
        updatingSelector = true;
        taskSelector.setModel(new DefaultComboBoxModel<>(list.toArray(TaskSnapshot[]::new)));
        if (selected != null) {
            for (TaskSnapshot snapshot : list) {
                if (snapshot.config().taskId.equals(selectedTaskId)) {
                    taskSelector.setSelectedItem(snapshot);
                    break;
                }
            }
        }
        updatingSelector = false;
        renderAggregate(list);
        if (taskSelector.getSelectedItem() instanceof TaskSnapshot snapshot) {
            renderSelected(snapshot);
        }
    }

    private void renderAggregate(List<TaskSnapshot> list) {
        int active = (int) list.stream().filter(snapshot -> snapshot.status().syncing()).count();
        int errors = (int) list.stream().filter(snapshot ->
                snapshot.status().halted() || snapshot.status().failure() != null).count();
        int queued = list.stream().mapToInt(snapshot -> snapshot.status().queueSize()).sum();
        String state;
        Color color;
        if (active > 0) {
            state = "多任务同步中 " + active + "/" + list.size();
            color = new Color(40, 95, 165);
        } else if (errors > 0) {
            state = list.size() + " 个任务，需处理 " + errors;
            color = new Color(180, 55, 45);
        } else if (list.isEmpty()) {
            state = "尚未配置任务";
            color = new Color(110, 116, 126);
        } else {
            boolean realtime = list.stream().anyMatch(snapshot ->
                    snapshot.config().syncMode == SyncMode.REALTIME);
            boolean scheduled = list.stream().anyMatch(snapshot ->
                    snapshot.config().syncMode == SyncMode.SCHEDULED);
            String mode = realtime && scheduled ? "实时/定时模式"
                    : realtime ? "实时监控中" : "定时检查中";
            state = list.size() + " 个任务，" + mode;
            color = new Color(25, 125, 65);
        }
        stateLabel.setText(state);
        stateLabel.setForeground(color);
        trayProgressItem.setText("同步进度：" + state + (queued > 0 ? "，队列 " + queued : ""));
        if (trayIcon != null) {
            trayIcon.setToolTip("SyncPlugins - " + trayProgressItem.getText().substring(5));
        }
    }

    private void renderSelected(TaskSnapshot snapshot) {
        SyncStatus status = snapshot.status();
        AppConfig config = snapshot.config();
        repositoryLabel.setText("任务：" + config.taskName + "    目录：" + config.repositoryPath);
        branchLabel.setText("仓库：" + config.remoteUrl + "    分支：" + config.branch);
        lastSyncLabel.setText(status.lastSuccessAt() == 0
                ? "尚未成功推送"
                : "最后成功：" + TIME.format(Instant.ofEpochMilli(status.lastSuccessAt())));
        errorLabel.setText(status.lastError().isBlank() ? " " : "错误：" + status.lastError());
        queueLabel.setText("队列：" + status.queueSize()
                + (status.failedCount() > 0 ? "，失败 " + status.failedCount() : "")
                + (status.currentFile().isBlank() ? "" : "，当前 " + status.currentFile()));
        pauseButton.setText(status.halted() ? "处理后继续" : status.paused() ? "恢复选中任务" : "暂停选中任务");
        syncButton.setEnabled(!status.syncing());
        settingsButton.setEnabled(true);
        cloudButton.setEnabled(!status.syncing());
        backupButton.setEnabled(!status.syncing());
        renderProgress(status);
        appendLog(status);
        if (status.halted() && !status.lastError().isBlank()
                && !status.lastError().equals(lastLoggedError) && trayIcon != null) {
            trayIcon.displayMessage("SyncPlugins 任务已暂停",
                    config.taskName + "：" + status.lastError(), TrayIcon.MessageType.ERROR);
        }
    }

    private void appendLog(SyncStatus status) {
        String key = selectedTaskId + "|" + status.message();
        if (!key.equals(lastLoggedMessage)) {
            lastLoggedMessage = key;
            logArea.append(TIME.format(Instant.now()) + "  " + status.message() + "\n");
        }
        String errorKey = selectedTaskId + "|" + status.lastError();
        if (!status.lastError().isBlank() && !errorKey.equals(lastLoggedError)) {
            lastLoggedError = errorKey;
            logArea.append(TIME.format(Instant.now()) + "  错误：" + status.lastError() + "\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void renderProgress(SyncStatus status) {
        boolean active = status.syncing() || status.downloading() || status.restoring();
        boolean hasCounts = status.progressTotal() > 0;
        if (active && hasCounts) {
            progressBar.setVisible(true);
            progressBar.setIndeterminate(false);
            progressBar.setMaximum(status.progressTotal());
            progressBar.setValue(Math.min(status.progressDone(), status.progressTotal()));
            progressBar.setString(status.progressDone() + " / " + status.progressTotal());
        } else if (active) {
            progressBar.setVisible(true);
            progressBar.setIndeterminate(true);
            progressBar.setString(status.downloading() ? "正在从云端下载..."
                    : status.restoring() ? "正在恢复备份..." : "正在扫描待同步文件...");
        } else if (status.queueSize() > 0 && hasCounts) {
            progressBar.setVisible(true);
            progressBar.setIndeterminate(false);
            progressBar.setMaximum(status.progressTotal());
            progressBar.setValue(Math.min(status.progressDone(), status.progressTotal()));
            progressBar.setString("已完成 " + status.progressDone() + " / " + status.progressTotal());
        } else {
            progressBar.setVisible(false);
        }
    }

    private TaskSnapshot selectedSnapshot() {
        return snapshots.stream()
                .filter(snapshot -> snapshot.config().taskId.equals(selectedTaskId))
                .findFirst().orElse(null);
    }

    private void selectedControllerSync() {
        if (selectedSnapshot() != null) {
            // Manager's individual controller is exposed through this helper.
            controller.taskController(selectedTaskId).syncNow();
        }
    }

    private void toggleSelectedPause() {
        TaskSnapshot snapshot = selectedSnapshot();
        if (snapshot == null) {
            return;
        }
        if (snapshot.status().halted()) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    "确认已经在外部 Git 工具中处理完问题？",
                    "继续同步", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                controller.taskController(selectedTaskId).resumeAfterFailure();
            }
        } else {
            controller.taskController(selectedTaskId).setPaused(!snapshot.status().paused());
        }
    }

    private void downloadSelected() {
        if (selectedSnapshot() == null) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame,
                "从 GitHub 下载并覆盖选中任务的本地内容吗？\n"
                        + "本地有改动时会先自动备份。",
                "从云端下载", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        controller.downloadFromCloud(selectedTaskId).whenComplete((result, error) ->
                SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        JOptionPane.showMessageDialog(frame, error.getMessage(),
                                "云端下载失败", JOptionPane.ERROR_MESSAGE);
                    }
                }));
    }

    private void showBackups() {
        if (selectedSnapshot() != null) {
            new BackupDialog(frame, controller, selectedTaskId).setVisible(true);
        }
    }

    private void openTaskManager() {
        new TaskManagerDialog(frame, snapshots.stream().map(TaskSnapshot::config).toList(),
                memoryStore, secretStore, new KnownHostsStore(paths.knownHostsFile()),
                workspaceService, saveTask, deleteTask).setVisible(true);
    }

    private void openRepository() {
        TaskSnapshot snapshot = selectedSnapshot();
        if (snapshot == null) {
            return;
        }
        try {
            Desktop.getDesktop().open(Path.of(snapshot.config().repositoryPath).toFile());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(frame, "无法打开目录：" + exception.getMessage(),
                    "SyncPlugins", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void hideToTray() {
        if (trayIcon != null) {
            frame.setVisible(false);
        } else {
            exitAction.run();
        }
    }

    private void buildTrayMenu() {
        trayMenu.removeAll();
        trayProgressItem.setFont(UiFonts.uiFont(13));
        trayProgressItem.setEnabled(false);
        trayMenu.add(trayProgressItem);
        trayMenu.addSeparator();
        trayMenu.add(trayMenuItem("打开 SyncPlugins", this::showWindow));
        trayMenu.add(trayMenuItem("同步全部任务", controller::syncAll));
        trayMenu.add(trayMenuItem("暂停全部任务", () -> controller.pauseAll(true)));
        trayMenu.add(trayMenuItem("下载选中任务", this::downloadSelected));
        trayMenu.add(trayMenuItem("任务管理", this::openTaskManager));
        trayMenu.add(trayMenuItem("备份管理", this::showBackups));
        trayMenu.addSeparator();
        trayMenu.add(trayMenuItem("退出", () -> exitAction.run()));
    }

    private JMenuItem trayMenuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(UiFonts.uiFont(13));
        item.addActionListener(event -> action.run());
        return item;
    }

    private void showSwingTrayMenu() {
        Point pointer = MouseInfo.getPointerInfo() == null
                ? new Point(0, 0) : MouseInfo.getPointerInfo().getLocation();
        trayPopupOwner.setLocation(pointer);
        trayPopupOwner.setVisible(true);
        trayMenu.show(trayPopupOwner, 0, 0);
    }

    private static final class TaskSnapshotRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof TaskSnapshot snapshot) {
                String state = snapshot.status().syncing() ? "同步中"
                        : snapshot.status().halted() ? "需处理"
                        : snapshot.status().paused() ? "已暂停" : "定时检查";
                setText(snapshot.config().taskName + "  ·  " + state
                        + (snapshot.config().syncMode == SyncMode.REALTIME ? " · 实时" : " · 定时"));
            }
            return this;
        }
    }
}
