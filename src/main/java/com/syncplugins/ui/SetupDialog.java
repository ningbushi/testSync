package com.syncplugins.ui;

import com.syncplugins.AppPaths;
import com.syncplugins.config.AppConfig;
import com.syncplugins.config.InputMemory;
import com.syncplugins.config.InputMemoryStore;
import com.syncplugins.config.SetupDraft;
import com.syncplugins.config.SyncMode;
import com.syncplugins.git.SyncException;
import com.syncplugins.security.KnownHostKey;
import com.syncplugins.security.KnownHostsStore;
import com.syncplugins.security.SecretStore;
import com.syncplugins.security.UnknownHostKeyException;
import com.syncplugins.workspace.ConflictResolution;
import com.syncplugins.workspace.WorkspaceBootstrapService;
import com.syncplugins.workspace.WorkspaceInspection;
import com.syncplugins.workspace.WorkspaceMode;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public final class SetupDialog extends JDialog {
    private final InputMemoryStore memoryStore;
    private final SecretStore secretStore;
    private final WorkspaceBootstrapService workspaceService;
    private final KnownHostsStore knownHosts;
    private final InputMemory memory;
    private final JComboBox<String> repositoryField = editableCombo();
    private final javax.swing.JTextField taskNameField = new javax.swing.JTextField();
    private final JComboBox<String> remoteField = editableCombo();
    private final JComboBox<String> branchField = editableCombo();
    private final JComboBox<String> keyField = editableCombo();
    private final JPasswordField passphraseField = new JPasswordField();
    private final JComboBox<String> authorNameField = editableCombo();
    private final JComboBox<String> authorEmailField = editableCombo();
    private final JComboBox<SyncMode> syncModeField = new JComboBox<>(new DefaultComboBoxModel<>(new SyncMode[]{
            SyncMode.SCHEDULED, SyncMode.REALTIME
    }));
    private final JSpinner pollIntervalSpinner = new JSpinner(
            new SpinnerNumberModel(30, 1, 3_600, 1));
    private final JCheckBox autoStartBox = new JCheckBox("登录 Windows 后自动启动", true);
    private final JCheckBox minimizedBox = new JCheckBox("启动后缩到系统托盘", true);
    private final JLabel feedbackLabel = new JLabel(" ");
    private final JButton testButton = new JButton("检测并连接");
    private final JButton saveButton = new JButton("保存并开始同步");
    private final JLabel detectedModeLabel = new JLabel("尚未检测工作区");
    private final Timer draftTimer;
    private boolean loading = true;
    private SetupResult result;
    private String originalTaskId = "";

    public SetupDialog(java.awt.Window owner, AppConfig existing, InputMemoryStore memoryStore,
                       SecretStore secretStore, KnownHostsStore knownHosts,
                       WorkspaceBootstrapService workspaceService) {
        super(owner, existing != null && existing.isComplete() ? "SyncPlugins 设置" : "欢迎使用 SyncPlugins",
                ModalityType.APPLICATION_MODAL);
        this.memoryStore = memoryStore;
        this.secretStore = secretStore;
        this.workspaceService = workspaceService;
        this.knownHosts = knownHosts;
        this.memory = memoryStore.load();
        this.draftTimer = new Timer(500, event -> persistMemoryQuietly());
        draftTimer.setRepeats(false);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                persistMemoryQuietly();
            }
        });
        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        add(buildForm(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        populate(existing, memory);
        installDraftListeners();
        loading = false;
        loadPassphraseForKey();
        setMinimumSize(new Dimension(800, 650));
        setSize(840, 690);
        setLocationRelativeTo(owner);
    }

    public SetupResult showDialog() {
        setVisible(true);
        return result;
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));
        JLabel title = new JLabel("自动上传、手动下载 GitHub 文件");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel subtitle = new JLabel(
                "可选择已有仓库、普通资料目录或尚不存在的下载目录；应用会自动建仓或克隆。");
        subtitle.setForeground(new Color(90, 96, 105));
        panel.add(title, BorderLayout.NORTH);
        panel.add(subtitle, BorderLayout.CENTER);
        return panel;
    }

    private JScrollPane buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 24, 10, 24));
        int row = 0;
        row = addSection(form, row, "工作区");
        row = addTextRow(form, row, "任务名称", taskNameField,
                "用于区分多个本地文件夹");
        row = addComboRow(form, row, "本地目录 / 下载位置", repositoryField,
                "不存在的路径会自动创建", this::chooseRepository);
        row = addComboRow(form, row, "GitHub SSH 地址", remoteField,
                "例如 git@github.com:owner/repository.git", null);
        row = addComboRow(form, row, "分支", branchField,
                "检测后会填充远端分支", null);
        row = addTextRow(form, row, "检测结果", detectedModeLabel);
        row = addTextRow(form, row, "同步模式", syncModeField,
                "定时扫描资源占用较低；实时监控响应更快但会常驻文件监听器");
        syncModeField.setRenderer(new SyncModeRenderer());
        row = addSection(form, row, "SSH 身份");
        row = addComboRow(form, row, "私钥文件", keyField,
                "不同私钥的口令会分别加密保存", this::choosePrivateKey);
        row = addTextRow(form, row, "私钥口令", passphraseField,
                "没有口令请留空");
        row = addSection(form, row, "提交身份");
        row = addComboRow(form, row, "姓名", authorNameField,
                "历史输入可从下拉列表选择", null);
        row = addComboRow(form, row, "邮箱", authorEmailField,
                "历史输入可从下拉列表选择", null);
        row = addSection(form, row, "启动选项");
        row = addTextRow(form, row, "检查间隔（秒）", pollIntervalSpinner,
                "定时模式的扫描周期；实时模式也按此间隔检查云端和补偿事件");
        GridBagConstraints optionConstraints = constraints(1, row++, 2, 1);
        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setOpaque(false);
        options.add(autoStartBox);
        options.add(Box.createVerticalStrut(4));
        options.add(minimizedBox);
        form.add(options, optionConstraints);
        GridBagConstraints filler = constraints(1, row, 2, 1);
        filler.weighty = 1;
        form.add(Box.createVerticalGlue(), filler);
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel buildButtons() {
        JPanel wrapper = new JPanel(new BorderLayout(10, 0));
        wrapper.setBorder(BorderFactory.createEmptyBorder(10, 24, 20, 24));
        JButton clear = new JButton("清除历史与凭据");
        clear.addActionListener(event -> clearHistory());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(event -> {
            persistMemoryQuietly();
            dispose();
        });
        wrapper.add(feedbackLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(clear);
        buttons.add(testButton);
        buttons.add(cancel);
        buttons.add(saveButton);
        wrapper.add(buttons, BorderLayout.EAST);
        testButton.addActionListener(event -> inspectOnly());
        saveButton.addActionListener(event -> inspectAndSave());
        return wrapper;
    }

    private void populate(AppConfig existing, InputMemory memory) {
        SetupDraft draft = draftForSetup(existing, memory);
        originalTaskId = draft.taskId == null ? "" : draft.taskId;
        taskNameField.setText(draft.taskName == null ? "" : draft.taskName);
        setValues(repositoryField, memory.repositoryPaths, draft.repositoryPath);
        setValues(remoteField, memory.remoteUrls, draft.remoteUrl);
        setValues(branchField, memory.branches, draft.branch);
        setValues(keyField, memory.privateKeyPaths, draft.privateKeyPath);
        setValues(authorNameField, memory.authorNames, draft.authorName);
        setValues(authorEmailField, memory.authorEmails, draft.authorEmail);
        autoStartBox.setSelected(draft.autoStart);
        minimizedBox.setSelected(draft.startMinimized);
        pollIntervalSpinner.setValue(Math.max(1, Math.min(3_600, draft.pollIntervalSeconds)));
        syncModeField.setSelectedItem(draft.syncMode == null ? SyncMode.SCHEDULED : draft.syncMode);
    }

    static SetupDraft draftForSetup(AppConfig existing, InputMemory memory) {
        boolean editingExistingTask = existing != null && existing.isComplete()
                && existing.taskId != null && !existing.taskId.isBlank();
        SetupDraft draft = editingExistingTask
                ? SetupDraft.from(existing)
                : memory.draftPending
                ? memory.draft
                : existing == null || !existing.isComplete()
                ? mergeDraft(SetupDraft.from(existing), memory.draft)
                : SetupDraft.from(existing);
        if (!editingExistingTask) {
            draft.taskId = "";
            draft.taskName = "";
        }
        return draft;
    }

    private static SetupDraft mergeDraft(SetupDraft existing, SetupDraft remembered) {
        if (remembered == null) {
            return existing;
        }
        if (existing.repositoryPath.isBlank()) existing.repositoryPath = remembered.repositoryPath;
        if (existing.taskId.isBlank()) existing.taskId = remembered.taskId;
        if (existing.taskName.isBlank()) existing.taskName = remembered.taskName;
        if (existing.remoteUrl.isBlank()) existing.remoteUrl = remembered.remoteUrl;
        if (existing.branch.isBlank()) existing.branch = remembered.branch;
        if (existing.privateKeyPath.isBlank()) existing.privateKeyPath = remembered.privateKeyPath;
        if (existing.authorName.isBlank()) existing.authorName = remembered.authorName;
        if (existing.authorEmail.isBlank()) existing.authorEmail = remembered.authorEmail;
        return existing;
    }

    private void installDraftListeners() {
        for (JComboBox<String> combo : List.of(repositoryField, remoteField, branchField,
                keyField, authorNameField, authorEmailField)) {
            combo.addActionListener(event -> {
                scheduleDraftSave();
                if (combo == keyField) {
                    loadPassphraseForKey();
                }
            });
            if (combo.getEditor().getEditorComponent() instanceof javax.swing.JTextField text) {
                text.getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent event) {
                        scheduleDraftSave();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent event) {
                        scheduleDraftSave();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent event) {
                        scheduleDraftSave();
                    }
                });
            }
        }
        pollIntervalSpinner.addChangeListener(event -> scheduleDraftSave());
        syncModeField.addActionListener(event -> scheduleDraftSave());
    }

    private void inspectOnly() {
        final AppConfig candidate;
        try {
            candidate = buildConfig();
        } catch (Exception exception) {
            showError(exception.getMessage());
            return;
        }
        setBusy(true, "正在检测远端和工作区...");
        runInspection(candidate, inspection -> {
            setBusy(false, "检测完成");
            showInspection(inspection);
        });
    }

    private void inspectAndSave() {
        final AppConfig candidate;
        try {
            candidate = buildConfig();
        } catch (Exception exception) {
            showError(exception.getMessage());
            return;
        }
        setBusy(true, "正在检测远端和工作区...");
        runInspection(candidate, inspection -> {
            selectedInspection = inspection;
            if (inspection.mode() == WorkspaceMode.CONFLICT) {
                ConflictResolution resolution = ConflictResolutionDialog.choose(this, inspection);
                if (resolution == null) {
                    setBusy(false, "已取消初始化");
                    return;
                }
                prepareWorkspace(inspection, resolution);
            } else {
                prepareWorkspace(inspection, null);
            }
        });
    }

    private volatile WorkspaceInspection selectedInspection;

    private void runInspection(AppConfig candidate, java.util.function.Consumer<WorkspaceInspection> action) {
        char[] passphrase = passphraseField.getPassword();
        new SwingWorker<WorkspaceInspection, Void>() {
            @Override
            protected WorkspaceInspection doInBackground() throws Exception {
                try {
                    return workspaceService.inspect(candidate.repositoryPath, candidate.remoteUrl,
                            candidate.branch, candidate.privateKeyPath, passphrase);
                } finally {
                    Arrays.fill(passphrase, '\0');
                }
            }

            @Override
            protected void done() {
                try {
                    WorkspaceInspection inspection = get();
                    updateBranchesAndBranch(inspection);
                    persistPassphrase(candidate.privateKeyPath);
                    action.accept(inspection);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    setBusy(false, "检测被中断");
                } catch (ExecutionException exception) {
                    handleWorkerFailure(exception.getCause(), () -> runInspection(candidate, action));
                }
            }
        }.execute();
    }

    private void prepareWorkspace(WorkspaceInspection inspection, ConflictResolution resolution) {
        AppConfig candidate;
        try {
            candidate = buildConfig();
            candidate.branch = inspection.branch();
        } catch (Exception exception) {
            setBusy(false, "配置不完整");
            showError(exception.getMessage());
            return;
        }
        setBusy(true, "正在初始化工作区...");
        char[] passphrase = passphraseField.getPassword();
        new SwingWorker<WorkspaceInspection, Void>() {
            @Override
            protected WorkspaceInspection doInBackground() throws Exception {
                try {
                    return workspaceService.prepare(candidate.repositoryPath, candidate.remoteUrl,
                            inspection.branch(), candidate.privateKeyPath, passphrase,
                            resolution, candidate.backupLimit);
                } finally {
                    Arrays.fill(passphrase, '\0');
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    finishSave(candidate);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    setBusy(false, "初始化被中断");
                } catch (ExecutionException exception) {
                    handleWorkerFailure(exception.getCause(),
                            () -> prepareWorkspace(inspection, resolution));
                }
            }
        }.execute();
    }

    private void handleWorkerFailure(Throwable cause, Runnable retry) {
        if (cause instanceof UnknownHostKeyException unknown) {
            promptUnknownHost(unknown.hostKey(), retry);
        } else if (cause instanceof SyncException syncException) {
            setBusy(false, "操作失败");
            showError(syncException.getMessage());
        } else {
            setBusy(false, "操作失败");
            showError(cause == null ? "未知错误" : cause.getMessage());
        }
    }

    private void promptUnknownHost(KnownHostKey key, Runnable retry) {
        String message = "<html>首次连接到 <b>" + escapeHtml(key.host()) + "</b>。<br><br>"
                + "主机密钥指纹：<br><code>" + escapeHtml(key.fingerprint()) + "</code><br><br>"
                + "请与 GitHub 官方公布的指纹核验。确认信任并继续？</html>";
        if (JOptionPane.showConfirmDialog(this, message, "确认 GitHub 主机密钥",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            try {
                knownHosts.accept(key);
                setBusy(true, "正在重新连接...");
                retry.run();
            } catch (IOException exception) {
                setBusy(false, "无法保存主机密钥");
                showError(exception.getMessage());
            }
        } else {
            setBusy(false, "未确认主机密钥");
        }
    }

    private void showInspection(WorkspaceInspection inspection) {
        String mode = switch (inspection.mode()) {
            case EXISTING_REPO -> "已有 Git 仓库，将直接使用";
            case INIT_LOCAL -> "将初始化普通目录并逐文件上传";
            case CLONE_REMOTE -> "将从 GitHub 克隆到本地目录";
            case CONFLICT -> "本地和云端都有内容，保存时需选择处理方式";
        };
        if (inspection.createsBranch()) {
            mode += "；将创建新分支 " + inspection.branch()
                    + (inspection.baseBranch() == null ? "" : "，基于 " + inspection.baseBranch());
        }
        detectedModeLabel.setText(mode);
        feedbackLabel.setForeground(new Color(20, 125, 60));
        feedbackLabel.setText("连接正常，当前分支：" + inspection.branch());
    }

    private void updateBranchesAndBranch(WorkspaceInspection inspection) {
        if (!inspection.remoteBranches().isEmpty()) {
            setValues(branchField, inspection.remoteBranches(), inspection.branch());
        } else if (value(branchField).isBlank()) {
            setSelected(branchField, inspection.branch());
        }
    }

    private void finishSave(AppConfig candidate) {
        String keyPath = candidate.privateKeyPath;
        Path key = Path.of(keyPath).toAbsolutePath().normalize();
        Path repository = Path.of(candidate.repositoryPath).toAbsolutePath().normalize();
        if (key.startsWith(repository)) {
            setBusy(false, "私钥位置无效");
            showError("私钥不能放在被同步的仓库目录中");
            return;
        }
        result = new SetupResult(candidate, passphraseField.getPassword());
        SetupDraft draft = toDraft(candidate);
        memoryStore.remember(memory, draft);
        memory.draft = draft;
        memory.draftPending = false;
        memory.draftUpdatedAt = System.currentTimeMillis();
        try {
            memoryStore.save(memory);
        } catch (IOException exception) {
            showError("无法保存输入历史：" + exception.getMessage());
        }
        setBusy(false, "即将开始同步");
        dispose();
    }

    private AppConfig buildConfig() {
        String repository = value(repositoryField).trim();
        if (repository.isBlank()) {
            throw new IllegalArgumentException("请填写本地目录或下载位置");
        }
        Path local = Path.of(repository).toAbsolutePath().normalize();
        if (Files.exists(local) && !Files.isDirectory(local)) {
            throw new IllegalArgumentException("本地路径不是文件夹");
        }
        String remote = value(remoteField).trim();
        String lower = remote.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("ssh://") || lower.startsWith("git@"))
                || !lower.contains("github.com")) {
            throw new IllegalArgumentException("第一版仅支持 github.com 的 SSH 地址");
        }
        String keyPath = value(keyField).trim();
        if (!Files.isRegularFile(Path.of(keyPath))) {
            throw new IllegalArgumentException("SSH 私钥文件不存在");
        }
        if (value(authorNameField).isBlank() || value(authorEmailField).isBlank()) {
            throw new IllegalArgumentException("请填写 Git 提交姓名和邮箱");
        }
        if (!value(authorEmailField).contains("@")) {
            throw new IllegalArgumentException("提交邮箱格式不正确");
        }
        AppConfig config = new AppConfig();
        config.taskId = originalTaskId;
        config.taskName = taskNameField.getText().trim().isBlank()
                ? defaultTaskName(local) : taskNameField.getText().trim();
        config.repositoryPath = local.toString();
        config.remoteUrl = remote;
        config.branch = value(branchField).isBlank() ? "main" : value(branchField).trim();
        config.privateKeyPath = Path.of(keyPath).toAbsolutePath().normalize().toString();
        config.authorName = value(authorNameField).trim();
        config.authorEmail = value(authorEmailField).trim();
        config.autoStart = autoStartBox.isSelected();
        config.startMinimized = minimizedBox.isSelected();
        config.pollIntervalSeconds = ((Number) pollIntervalSpinner.getValue()).longValue();
        config.syncMode = (SyncMode) syncModeField.getSelectedItem();
        config.paused = false;
        config.validate();
        return config;
    }

    private SetupDraft toDraft(AppConfig config) {
        SetupDraft draft = SetupDraft.from(config);
        draft.repositoryPath = config.repositoryPath;
        draft.remoteUrl = config.remoteUrl;
        draft.branch = value(branchField).isBlank() ? config.branch : value(branchField).trim();
        draft.privateKeyPath = config.privateKeyPath;
        draft.authorName = config.authorName;
        draft.authorEmail = config.authorEmail;
        draft.autoStart = config.autoStart;
        draft.startMinimized = config.startMinimized;
        draft.pollIntervalSeconds = config.pollIntervalSeconds;
        draft.syncMode = config.syncMode;
        return draft;
    }

    private void scheduleDraftSave() {
        if (loading) {
            return;
        }
        draftTimer.restart();
    }

    private void persistMemoryQuietly() {
        if (loading) {
            return;
        }
        SetupDraft draft = draftFromFields();
        memoryStore.remember(memory, draft);
        memory.draft = draft;
        memory.draftPending = true;
        memory.draftUpdatedAt = System.currentTimeMillis();
        try {
            memoryStore.save(memory);
        } catch (IOException ignored) {
            // The active configuration remains valid even if draft persistence fails.
        }
    }

    private SetupDraft draftFromFields() {
        SetupDraft draft = new SetupDraft();
        draft.taskId = originalTaskId;
        draft.taskName = taskNameField.getText().trim();
        draft.repositoryPath = value(repositoryField).trim();
        draft.remoteUrl = value(remoteField).trim();
        draft.branch = value(branchField).trim();
        draft.privateKeyPath = value(keyField).trim();
        draft.authorName = value(authorNameField).trim();
        draft.authorEmail = value(authorEmailField).trim();
        draft.autoStart = autoStartBox.isSelected();
        draft.startMinimized = minimizedBox.isSelected();
        draft.pollIntervalSeconds = ((Number) pollIntervalSpinner.getValue()).longValue();
        draft.syncMode = (SyncMode) syncModeField.getSelectedItem();
        return draft;
    }

    private void clearHistory() {
        if (JOptionPane.showConfirmDialog(this, "清除最近输入和所有已保存的私钥口令？",
                "清除历史", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        memoryStore.clear(memory);
        try {
            memoryStore.save(memory);
            secretStore.clear();
            for (JComboBox<String> combo : List.of(repositoryField, remoteField, branchField,
                    keyField, authorNameField, authorEmailField)) {
                combo.setModel(new DefaultComboBoxModel<>());
                setSelected(combo, "");
            }
            passphraseField.setText("");
            feedbackLabel.setText("历史与凭据已清除");
        } catch (IOException exception) {
            showError(exception.getMessage());
        }
    }

    private void loadPassphraseForKey() {
        if (loading) {
            return;
        }
        String key = value(keyField).trim();
        if (key.isBlank()) {
            passphraseField.setText("");
            return;
        }
        try {
            char[] value = secretStore.loadPassphrase(key).orElseGet(() -> new char[0]);
            passphraseField.setText(value.length == 0 ? "" : new String(value));
            Arrays.fill(value, '\0');
        } catch (IOException ignored) {
            passphraseField.setText("");
        }
    }

    private void persistPassphrase(String privateKeyPath) {
        try {
            secretStore.savePassphrase(privateKeyPath, passphraseField.getPassword());
        } catch (IOException exception) {
            feedbackLabel.setText("口令未能保存：" + exception.getMessage());
        }
    }

    private void chooseRepository() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择本地目录或已有仓库");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setSelected(repositoryField, chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void choosePrivateKey() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 OpenSSH 私钥");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        Path sshDirectory = Path.of(System.getProperty("user.home"), ".ssh");
        if (Files.isDirectory(sshDirectory)) {
            chooser.setCurrentDirectory(sshDirectory.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setSelected(keyField, chooser.getSelectedFile().getAbsolutePath());
            loadPassphraseForKey();
        }
    }

    private void setBusy(boolean busy, String text) {
        testButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        feedbackLabel.setForeground(new Color(70, 76, 84));
        feedbackLabel.setText(text);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message == null ? "操作失败" : message,
                "SyncPlugins", JOptionPane.ERROR_MESSAGE);
    }

    private int addSection(JPanel form, int row, String title) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        GridBagConstraints constraints = constraints(0, row++, 3, 1);
        constraints.insets = new Insets(row == 1 ? 0 : 18, 0, 8, 0);
        form.add(label, constraints);
        return row;
    }

    private int addComboRow(JPanel form, int row, String label, JComboBox<String> field,
                            String help, Runnable browseAction) {
        GridBagConstraints labelConstraints = constraints(0, row, 1, 1);
        labelConstraints.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), labelConstraints);
        GridBagConstraints fieldConstraints = constraints(1, row, 1, 1);
        field.setPreferredSize(new Dimension(390, 30));
        form.add(field, fieldConstraints);
        if (browseAction != null) {
            JButton browse = new JButton("选择...");
            browse.addActionListener(event -> browseAction.run());
            form.add(browse, constraints(2, row, 1, 1));
        } else {
            JLabel hint = new JLabel(help);
            hint.setForeground(new Color(110, 116, 126));
            hint.setFont(hint.getFont().deriveFont(11f));
            form.add(hint, constraints(2, row, 1, 1));
        }
        row++;
        if (help != null && browseAction != null) {
            GridBagConstraints hintConstraints = constraints(1, row++, 2, 1);
            JLabel hint = new JLabel(help);
            hint.setForeground(new Color(110, 116, 126));
            hint.setFont(hint.getFont().deriveFont(11f));
            form.add(hint, hintConstraints);
        }
        return row;
    }

    private int addTextRow(JPanel form, int row, String label, javax.swing.JComponent field,
                           String help) {
        GridBagConstraints labelConstraints = constraints(0, row, 1, 1);
        labelConstraints.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), labelConstraints);
        GridBagConstraints fieldConstraints = constraints(1, row, 2, 1);
        field.setPreferredSize(new Dimension(430, 28));
        form.add(field, fieldConstraints);
        row++;
        if (help != null) {
            GridBagConstraints hintConstraints = constraints(1, row++, 2, 1);
            JLabel hint = new JLabel(help);
            hint.setForeground(new Color(110, 116, 126));
            hint.setFont(hint.getFont().deriveFont(11f));
            form.add(hint, hintConstraints);
        }
        return row;
    }

    private int addTextRow(JPanel form, int row, String label, JLabel value) {
        GridBagConstraints labelConstraints = constraints(0, row, 1, 1);
        form.add(new JLabel(label), labelConstraints);
        form.add(value, constraints(1, row, 2, 1));
        return row + 1;
    }

    private static GridBagConstraints constraints(int x, int y, int width, int height) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.gridwidth = width;
        constraints.gridheight = height;
        constraints.weightx = x == 1 ? 1 : 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 5, 4, 5);
        return constraints;
    }

    private static JComboBox<String> editableCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        return combo;
    }

    private static String value(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private static void setSelected(JComboBox<String> combo, String value) {
        combo.getEditor().setItem(value == null ? "" : value);
    }

    private static void setValues(JComboBox<String> combo, List<String> values, String selected) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (selected != null && !selected.isBlank()) {
            unique.add(selected);
        }
        if (values != null) {
            unique.addAll(values);
        }
        combo.setModel(new DefaultComboBoxModel<>(new ArrayList<>(unique).toArray(String[]::new)));
        setSelected(combo, selected == null ? "" : selected);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String defaultTaskName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "同步任务" : fileName.toString();
    }

    private static final class SyncModeRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                                                              Object value, int index,
                                                              boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value == SyncMode.REALTIME) {
                setText("实时监控（低延迟，资源较高）");
            } else if (value == SyncMode.SCHEDULED) {
                setText("定时扫描（低资源占用）");
            }
            return this;
        }
    }
}
