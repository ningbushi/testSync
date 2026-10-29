package com.syncplugins.ui;

import com.syncplugins.AppPaths;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.sync.SyncController;
import com.syncplugins.sync.SyncStatus;
import com.syncplugins.workspace.WorkspaceBootstrapService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JWindow;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
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
import java.util.function.Consumer;

public final class MainWindow {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("SyncPlugins");
    private final SyncController controller;
    private final Consumer<SetupResult> saveSettings;
    private final Runnable exitAction;
    private final AppPaths paths = AppPaths.system();
    private final InputMemoryStore memoryStore;
    private final SecretStore secretStore;
    private final WorkspaceBootstrapService workspaceService;
    private final JLabel stateLabel = new JLabel("正在启动");
    private final JLabel repositoryLabel = new JLabel(" ");
    private final JLabel branchLabel = new JLabel(" ");
    private final JLabel lastSyncLabel = new JLabel("尚未同步");
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel queueLabel = new JLabel(" ");
    private final JButton pauseButton = new JButton("暂停同步");
    private final JButton syncButton = new JButton("立即同步");
    private final JButton settingsButton = new JButton("设置");
    private final JButton cloudButton = new JButton("从云端下载");
    private final JButton backupButton = new JButton("备份");
    private final JProgressBar progressBar = new JProgressBar();
    private final JPopupMenu trayMenu = new JPopupMenu();
    private final JWindow trayPopupOwner = new JWindow();
    private final JMenuItem trayProgressItem = new JMenuItem("同步进度：等待同步");
    private final JTextArea logArea = new JTextArea();
    private TrayIcon trayIcon;
    private String lastLoggedError = "";
    private String lastLoggedMessage = "";
    private AppConfig settings;

    public MainWindow(SyncController controller, AppConfig initialSettings,
                      Consumer<SetupResult> saveSettings, Runnable exitAction,
                      InputMemoryStore memoryStore, SecretStore secretStore,
                      WorkspaceBootstrapService workspaceService) {
        this.controller = controller;
        this.settings = initialSettings;
        this.saveSettings = saveSettings;
        this.exitAction = exitAction;
        this.memoryStore = memoryStore;
        this.secretStore = secretStore;
        this.workspaceService = workspaceService;
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 540));
        frame.setSize(820, 590);
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
        controller.addListener(status -> SwingUtilities.invokeLater(() -> render(status)));
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
                // The owner stays visible until Swing closes the popup.
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
        trayIcon = new TrayIcon(createTrayImage(), "SyncPlugins");
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

    public void updateSettings(AppConfig config) {
        this.settings = config;
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 12, 24));
        JPanel top = new JPanel(new BorderLayout());
        JLabel title = new JLabel("SyncPlugins");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD, 15f));
        top.add(title, BorderLayout.WEST);
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
        progressBar.setString("等待同步");
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
        scroll.setBorder(BorderFactory.createTitledBorder("操作记录"));
        body.add(scroll, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(0, 24, 20, 24));
        settingsButton.addActionListener(event -> openSettings());
        JButton openButton = new JButton("打开仓库目录");
        openButton.addActionListener(event -> openRepository());
        syncButton.addActionListener(event -> controller.syncNow());
        pauseButton.addActionListener(event -> togglePause());
        cloudButton.addActionListener(event -> downloadFromCloud());
        backupButton.addActionListener(event -> showBackups());
        JPanel actions = new JPanel(new GridLayout(1, 6, 8, 0));
        actions.add(settingsButton);
        actions.add(openButton);
        actions.add(cloudButton);
        actions.add(backupButton);
        actions.add(syncButton);
        actions.add(pauseButton);
        footer.add(actions, BorderLayout.EAST);
        return footer;
    }

    private void render(SyncStatus status) {
        String state;
        Color color;
        if (status.halted()) {
            state = status.lastError().isBlank() ? "需要处理" : "已暂停";
            color = new Color(180, 55, 45);
        } else if (status.paused()) {
            state = "已暂停";
            color = new Color(150, 105, 20);
        } else if (status.failure() == com.syncplugins.git.SyncFailure.NETWORK) {
            state = "网络重试中";
            color = new Color(150, 105, 20);
        } else if (status.syncing()) {
            state = "正在同步";
            color = new Color(40, 95, 165);
        } else if (status.remoteUpdateAvailable() && !status.syncing()) {
            state = "云端有更新";
            color = new Color(40, 95, 165);
        } else if (status.watching()) {
            state = status.pendingPush() ? "等待推送" : "监听中";
            color = status.pendingPush() ? new Color(150, 105, 20) : new Color(25, 125, 65);
        } else {
            state = "未监听";
            color = new Color(100, 105, 112);
        }
        stateLabel.setText(state);
        stateLabel.setForeground(color);
        repositoryLabel.setText("仓库：" + status.repositoryPath());
        branchLabel.setText("分支：" + status.branch());
        lastSyncLabel.setText(status.lastSuccessAt() == 0
                ? "尚未成功推送"
                : "最后成功：" + TIME.format(Instant.ofEpochMilli(status.lastSuccessAt())));
        errorLabel.setText(status.lastError().isBlank() ? " " : "错误：" + status.lastError());
        queueLabel.setText("队列：" + status.queueSize()
                + (status.failedCount() > 0 ? "，失败 " + status.failedCount() : "")
                + (status.currentFile().isBlank() ? "" : "，当前 " + status.currentFile()));
        renderProgress(status);
        updateTrayProgress(status);
        pauseButton.setText(status.halted() ? "处理后继续" : status.paused() ? "恢复同步" : "暂停同步");
        syncButton.setEnabled(!status.syncing());
        settingsButton.setEnabled(!status.syncing());
        cloudButton.setEnabled(!status.syncing());
        backupButton.setEnabled(!status.syncing());
        appendLog(status);
        if (status.halted() && !status.lastError().isBlank()
                && !status.lastError().equals(lastLoggedError) && trayIcon != null) {
            trayIcon.displayMessage("SyncPlugins 已暂停", status.lastError(), TrayIcon.MessageType.ERROR);
        }
    }

    private void appendLog(SyncStatus status) {
        if (!status.message().equals(lastLoggedMessage)) {
            lastLoggedMessage = status.message();
            logArea.append(TIME.format(Instant.now()) + "  " + status.message() + "\n");
        }
        if (!status.lastError().isBlank() && !status.lastError().equals(lastLoggedError)) {
            lastLoggedError = status.lastError();
            logArea.append(TIME.format(Instant.now()) + "  错误：" + status.lastError() + "\n");
        }
        if (status.lastError().isBlank()) {
            lastLoggedError = "";
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void togglePause() {
        SyncStatus status = controller.status();
        if (status.halted()) {
            int choice = JOptionPane.showConfirmDialog(frame,
                    "确认已经在外部 Git 工具中处理完远端冲突或工作区问题？\n"
                            + "SyncPlugins 会重新检查本地与远端关系，不会自动合并或覆盖远端。",
                    "继续自动同步", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                controller.resumeAfterFailure();
            }
        } else {
            controller.setPaused(!status.paused());
        }
    }

    private void openSettings() {
        boolean wasPaused = controller.status().paused();
        controller.setPaused(true);
        SetupDialog dialog = new SetupDialog(frame, settings, memoryStore, secretStore,
                new com.syncplugins.security.KnownHostsStore(paths.knownHostsFile()),
                workspaceService);
        SetupResult result = dialog.showDialog();
        if (result == null) {
            if (!wasPaused) {
                controller.setPaused(false);
            }
            return;
        }
        try {
            saveSettings.accept(result);
            updateSettings(result.config());
            controller.updateConfig(result.config());
        } catch (IllegalStateException exception) {
            JOptionPane.showMessageDialog(frame, exception.getMessage(),
                    "SyncPlugins", JOptionPane.ERROR_MESSAGE);
        } finally {
            Arrays.fill(result.passphrase(), '\0');
        }
    }

    private void downloadFromCloud() {
        int choice = JOptionPane.showConfirmDialog(frame,
                "从 GitHub 下载并覆盖本地吗？\n"
                        + "本地干净时可安全更新；有本地改动时会先自动备份再覆盖。",
                "从云端下载", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        controller.downloadFromCloud().whenComplete((result, error) ->
                SwingUtilities.invokeLater(() -> {
                    if (error != null) {
                        JOptionPane.showMessageDialog(frame, error.getMessage(),
                                "云端下载失败", JOptionPane.ERROR_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(frame, result.message(),
                                "SyncPlugins", JOptionPane.INFORMATION_MESSAGE);
                    }
                }));
    }

    private void showBackups() {
        new BackupDialog(frame, controller).setVisible(true);
    }

    private void openRepository() {
        try {
            Desktop.getDesktop().open(Path.of(settings.repositoryPath).toFile());
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(frame, "无法打开仓库目录：" + exception.getMessage(),
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

    private static BufferedImage createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(39, 111, 185));
        graphics.fillRoundRect(1, 1, 30, 30, 9, 9);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 19));
        graphics.drawString("S", 9, 23);
        graphics.dispose();
        return image;
    }

    private void buildTrayMenu() {
        trayMenu.removeAll();
        trayProgressItem.setFont(UiFonts.uiFont(13));
        trayProgressItem.setEnabled(false);
        trayMenu.add(trayProgressItem);
        trayMenu.addSeparator();
        trayMenu.add(trayMenuItem("打开 SyncPlugins", this::showWindow));
        trayMenu.add(trayMenuItem("立即同步", () -> controller.syncNow()));
        trayMenu.add(trayMenuItem("暂停 / 恢复", this::togglePause));
        trayMenu.add(trayMenuItem("从云端下载", this::downloadFromCloud));
        trayMenu.add(trayMenuItem("备份管理", this::showBackups));
        trayMenu.add(trayMenuItem("打开仓库目录", this::openRepository));
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
        if (trayMenu.isVisible()) {
            trayMenu.setVisible(false);
            return;
        }
        Point pointer = MouseInfo.getPointerInfo() == null
                ? new Point(0, 0) : MouseInfo.getPointerInfo().getLocation();
        trayPopupOwner.setLocation(pointer);
        trayPopupOwner.setVisible(true);
        trayMenu.show(trayPopupOwner, 0, 0);
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

    private void updateTrayProgress(SyncStatus status) {
        String text;
        if (status.downloading()) {
            text = "正在从云端下载...";
        } else if (status.restoring()) {
            text = "正在恢复备份...";
        } else if (status.syncing() && status.progressTotal() > 0) {
            text = "同步中 " + Math.min(status.progressDone(), status.progressTotal())
                    + "/" + status.progressTotal()
                    + (status.currentFile().isBlank() ? "" : "：" + status.currentFile());
        } else if (status.syncing()) {
            text = "正在扫描待同步文件...";
        } else if (status.queueSize() > 0) {
            text = "队列剩余 " + status.queueSize()
                    + (status.failedCount() > 0 ? "，失败 " + status.failedCount() : "");
        } else {
            text = "等待同步";
        }
        trayProgressItem.setText("同步进度：" + text);
        if (trayIcon != null) {
            trayIcon.setToolTip("SyncPlugins - " + text);
        }
    }
}

