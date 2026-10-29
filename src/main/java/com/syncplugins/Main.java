package com.syncplugins;

import com.formdev.flatlaf.FlatLightLaf;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.AppState;
import com.syncplugins.config.ConfigStore;
import com.syncplugins.config.InputMemory;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.config.StateStore;
import com.syncplugins.config.SetupDraft;
import com.syncplugins.security.SecretStore;
import com.syncplugins.sync.SyncController;
import com.syncplugins.system.AutoStartManager;
import com.syncplugins.system.SingleInstanceLock;
import com.syncplugins.ui.MainWindow;
import com.syncplugins.ui.SetupDialog;
import com.syncplugins.ui.SetupResult;
import com.syncplugins.workspace.BackupManager;
import com.syncplugins.workspace.WorkspaceBootstrapService;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SyncPlugins 需要图形桌面环境");
            System.exit(1);
        }
        AppPaths paths = AppPaths.system();
        try {
            migrateLocalData(paths);
            Files.createDirectories(paths.configDirectory());
            Files.createDirectories(paths.localDataDirectory());
            AppLogging.configure(paths.logFile());
        } catch (IOException exception) {
            System.err.println("无法初始化日志：" + exception.getMessage());
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            LOGGER.log(Level.SEVERE, "未捕获异常", throwable);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "SyncPlugins 遇到未预期错误：" + throwable.getMessage()
                            + "\n日志：" + paths.logFile(),
                    "SyncPlugins", JOptionPane.ERROR_MESSAGE));
        });

        SwingUtilities.invokeLater(() -> startApplication(paths, args));
    }

    private static void startApplication(AppPaths paths, String[] args) {
        FlatLightLaf.setup();
        UIManager.put("defaultFont", com.syncplugins.ui.UiFonts.uiFont(13));
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);

        SingleInstanceLock instanceLock;
        try {
            instanceLock = SingleInstanceLock.tryAcquire(paths.instanceLockFile());
        } catch (SingleInstanceLock.AlreadyRunningException exception) {
            JOptionPane.showMessageDialog(null, "SyncPlugins 已经在运行，请查看系统托盘。",
                    "SyncPlugins", JOptionPane.INFORMATION_MESSAGE);
            return;
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(null, "无法创建单实例锁：" + exception.getMessage(),
                    "SyncPlugins", JOptionPane.ERROR_MESSAGE);
            return;
        }

        ConfigStore configStore = new ConfigStore(paths.configFile());
        StateStore stateStore = new StateStore(paths.stateFile());
        SecretStore secretStore = new SecretStore(paths.secretFile());
        InputMemoryStore memoryStore = new InputMemoryStore(paths.uiStateFile());
        InputMemory inputMemory;
        AppConfig config;
        AppState state;
        try {
            config = configStore.load();
            state = stateStore.load();
            inputMemory = memoryStore.load();
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "加载配置失败", exception);
            JOptionPane.showMessageDialog(null,
                    exception.getMessage() + "\n\n配置文件：" + paths.configFile(),
                    "无法加载 SyncPlugins", JOptionPane.ERROR_MESSAGE);
            closeQuietly(instanceLock);
            return;
        }

        if (!config.isComplete()) {
            WorkspaceBootstrapService workspaceService = new WorkspaceBootstrapService(
                    new com.syncplugins.security.KnownHostsStore(paths.knownHostsFile()),
                    new BackupManager(paths.backupsDirectory()));
            SetupDialog setup = new SetupDialog(null, config, memoryStore, secretStore,
                    new com.syncplugins.security.KnownHostsStore(paths.knownHostsFile()),
                    workspaceService);
            SetupResult result = setup.showDialog();
            if (result == null) {
                closeQuietly(instanceLock);
                return;
            }
            try {
                persistSettings(secretStore, result.config(), result.passphrase());
                configStore.save(result.config());
                config = result.config();
            } catch (Exception exception) {
                JOptionPane.showMessageDialog(null, "无法保存设置：" + exception.getMessage(),
                        "SyncPlugins", JOptionPane.ERROR_MESSAGE);
                closeQuietly(instanceLock);
                return;
            } finally {
                Arrays.fill(result.passphrase(), '\0');
            }
        }

        if (config.isComplete()) {
            if (!inputMemory.draftPending) {
                memoryStore.remember(inputMemory, SetupDraft.from(config));
                try {
                    memoryStore.save(inputMemory);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "无法保存最近输入", exception);
                }
            }
        }

        SyncController controller = new SyncController(
                paths.configFile(), paths.stateFile(), paths.secretFile(), paths.knownHostsFile(),
                paths.backupsDirectory());
        controller.start(config, state);

        AtomicReference<MainWindow> windowReference = new AtomicReference<>();
        AtomicBoolean exiting = new AtomicBoolean();
        Runnable exitAction = () -> {
            if (exiting.compareAndSet(false, true)) {
                performExit(controller, windowReference.get(), instanceLock);
            }
        };
        java.util.function.Consumer<SetupResult> saveSettings = result -> {
            try {
                persistSettings(secretStore, result.config(), result.passphrase());
                configStore.save(result.config());
            } catch (Exception exception) {
                throw new IllegalStateException("无法保存设置：" + exception.getMessage(), exception);
            }
        };
        WorkspaceBootstrapService workspaceService = new WorkspaceBootstrapService(
                new com.syncplugins.security.KnownHostsStore(paths.knownHostsFile()),
                new BackupManager(paths.backupsDirectory()));
        MainWindow window = new MainWindow(controller, config, saveSettings, exitAction,
                memoryStore, secretStore, workspaceService);
        windowReference.set(window);
        window.installTray();

        boolean background = Arrays.asList(args).contains("--background");
        if (!background || !java.awt.SystemTray.isSupported()) {
            window.showWindow();
        }
    }

    private static void persistSettings(SecretStore secretStore, AppConfig config, char[] passphrase)
            throws IOException {
        secretStore.savePassphrase(config.privateKeyPath, passphrase);
        if (config.autoStart) {
            AutoStartManager manager = new AutoStartManager();
            if (!manager.setEnabled(true)) {
                LOGGER.warning("当前运行方式不支持开机自启；安装版会启用该功能");
            }
        } else {
            new AutoStartManager().setEnabled(false);
        }
    }

    private static void performExit(SyncController controller, MainWindow window,
                                    SingleInstanceLock lock) {
        JDialog progress = new JDialog(window == null ? null : window.frame(),
                "正在退出 SyncPlugins", true);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel text = new JLabel("正在尝试完成最后一次同步...");
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        panel.add(text, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        progress.add(panel);
        progress.pack();
        progress.setLocationRelativeTo(window == null ? null : window.frame());
        if (window != null) {
            window.frame().setVisible(false);
        }
        Thread.ofPlatform().name("syncplugins-exit").start(() -> {
            try {
                controller.shutdownGracefully();
            } finally {
                closeQuietly(lock);
            }
            SwingUtilities.invokeLater(() -> {
                progress.dispose();
                if (window != null) {
                    window.dispose();
                }
                System.exit(0);
            });
        });
        progress.setVisible(true);
    }

    private static void closeQuietly(SingleInstanceLock lock) {
        try {
            lock.close();
        } catch (IOException ignored) {
            // Process exit also releases the lock.
        }
    }

    private static void migrateLocalData(AppPaths paths) throws IOException {
        Path legacy = paths.legacyLocalDataDirectory();
        if (legacy == null || legacy.equals(paths.localDataDirectory()) || !Files.isDirectory(legacy)) {
            return;
        }
        Files.createDirectories(paths.localDataDirectory());
        for (String name : List.of("state.json", "syncplugins.log", "syncplugins.log.0",
                "syncplugins.log.1", "syncplugins.log.2")) {
            Path source = legacy.resolve(name);
            Path target = paths.localDataDirectory().resolve(name);
            if (Files.isRegularFile(source) && !Files.exists(target)) {
                Files.copy(source, target);
            }
        }
        Path legacyBackups = legacy.resolve("backups");
        Path targetBackups = paths.backupsDirectory();
        if (Files.isDirectory(legacyBackups) && !Files.exists(targetBackups)) {
            copyTree(legacyBackups, targetBackups);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    Path directory, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attributes)
                    throws IOException {
                Path destination = target.resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
