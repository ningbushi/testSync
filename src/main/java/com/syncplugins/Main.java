package com.syncplugins;

import com.formdev.flatlaf.FlatLightLaf;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemory;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.config.SetupDraft;
import com.syncplugins.config.TaskRepository;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.sync.MultiTaskController;
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
import java.util.ArrayList;
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
            Files.createDirectories(paths.tasksDirectory());
            Files.createDirectories(paths.statesDirectory());
            AppLogging.configure(paths.logFile());
        } catch (IOException exception) {
            System.err.println("无法初始化运行目录：" + exception.getMessage());
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

        TaskRepository taskRepository = new TaskRepository(
                paths.tasksDirectory(), paths.configFile());
        InputMemoryStore memoryStore = new InputMemoryStore(paths.uiStateFile());
        SecretStore secretStore = new SecretStore(paths.secretFile());
        KnownHostsStore knownHosts = new KnownHostsStore(paths.knownHostsFile());
        WorkspaceBootstrapService workspaceService = new WorkspaceBootstrapService(
                knownHosts, new BackupManager(paths.backupsDirectory()));
        InputMemory inputMemory;
        List<AppConfig> tasks;
        try {
            inputMemory = memoryStore.load();
            tasks = new ArrayList<>(taskRepository.loadAll());
            taskRepository.saveAll(tasks);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "加载任务失败", exception);
            JOptionPane.showMessageDialog(null, exception.getMessage(),
                    "无法加载 SyncPlugins", JOptionPane.ERROR_MESSAGE);
            closeQuietly(instanceLock);
            return;
        }

        if (tasks.isEmpty()) {
            SetupDialog setup = new SetupDialog(null, new AppConfig(), memoryStore,
                    secretStore, knownHosts, workspaceService);
            SetupResult result = setup.showDialog();
            if (result == null) {
                closeQuietly(instanceLock);
                return;
            }
            try {
                AppConfig saved = taskRepository.saveNew(result.config());
                secretStore.savePassphrase(saved.privateKeyPath, result.passphrase());
                tasks.add(saved);
            } catch (Exception exception) {
                JOptionPane.showMessageDialog(null, "无法保存任务：" + exception.getMessage(),
                        "SyncPlugins", JOptionPane.ERROR_MESSAGE);
                closeQuietly(instanceLock);
                return;
            } finally {
                Arrays.fill(result.passphrase(), '\0');
            }
        }

        if (!inputMemory.draftPending && !tasks.isEmpty()) {
            memoryStore.remember(inputMemory, SetupDraft.from(tasks.get(0)));
            try {
                memoryStore.save(inputMemory);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "无法保存最近输入", exception);
            }
        }
        applyAutoStart(tasks.stream().anyMatch(task -> task.autoStart));

        MultiTaskController controller = new MultiTaskController(
                paths.tasksDirectory(), paths.statesDirectory(), paths.secretFile(),
                paths.knownHostsFile(), paths.backupsDirectory());
        controller.start(tasks);

        java.util.function.Function<SetupResult, AppConfig> saveTask = result -> {
            try {
                AppConfig saved = result.config().taskId == null || result.config().taskId.isBlank()
                        ? taskRepository.saveNew(result.config())
                        : taskRepository.save(result.config());
                secretStore.savePassphrase(saved.privateKeyPath, result.passphrase());
                controller.addOrUpdate(saved, true);
                List<AppConfig> current = controller.snapshots().stream()
                        .map(snapshot -> snapshot.config()).toList();
                applyAutoStart(current.stream().anyMatch(task -> task.autoStart));
                return saved;
            } catch (Exception exception) {
                throw new IllegalStateException("无法保存任务：" + exception.getMessage(), exception);
            }
        };
        java.util.function.Consumer<String> deleteTask = taskId -> {
            try {
                controller.removeTask(taskId);
                taskRepository.delete(taskId);
            } catch (IOException exception) {
                throw new IllegalStateException("无法删除任务：" + exception.getMessage(), exception);
            }
        };

        AtomicReference<MainWindow> windowReference = new AtomicReference<>();
        AtomicBoolean exiting = new AtomicBoolean();
        Runnable exitAction = () -> {
            if (exiting.compareAndSet(false, true)) {
                performExit(controller, windowReference.get(), instanceLock);
            }
        };
        MainWindow window = new MainWindow(controller, tasks, taskRepository,
                saveTask, deleteTask, exitAction, memoryStore, secretStore, workspaceService);
        windowReference.set(window);
        window.installTray();

        boolean background = Arrays.asList(args).contains("--background");
        if (!background || !java.awt.SystemTray.isSupported()) {
            window.showWindow();
        }
    }

    private static void applyAutoStart(boolean enabled) {
        try {
            new AutoStartManager().setEnabled(enabled);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "无法更新开机启动设置", exception);
        }
    }

    private static void performExit(MultiTaskController controller, MainWindow window,
                                    SingleInstanceLock lock) {
        JDialog progress = new JDialog(window == null ? null : window.frame(),
                "正在退出 SyncPlugins", true);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24));
        panel.add(new JLabel("正在尝试完成全部任务的最后一次同步..."), BorderLayout.NORTH);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
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
        if (Files.isDirectory(legacyBackups) && !Files.exists(paths.backupsDirectory())) {
            copyTree(legacyBackups, paths.backupsDirectory());
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

    private static void closeQuietly(SingleInstanceLock lock) {
        try {
            lock.close();
        } catch (IOException ignored) {
            // Process exit also releases the lock.
        }
    }
}
