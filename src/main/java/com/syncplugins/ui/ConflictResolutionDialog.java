package com.syncplugins.ui;

import com.syncplugins.workspace.ConflictResolution;
import com.syncplugins.workspace.WorkspaceInspection;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;

final class ConflictResolutionDialog {
    private ConflictResolutionDialog() {
    }

    static ConflictResolution choose(java.awt.Window owner, WorkspaceInspection inspection) {
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 12, 20));
        JLabel title = new JLabel("本地和 GitHub 都已有文件");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        content.add(title, BorderLayout.NORTH);

        JTextArea details = new JTextArea(buildDetails(inspection));
        details.setEditable(false);
        details.setRows(14);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(details);
        scroll.setPreferredSize(new Dimension(620, 260));
        content.add(scroll, BorderLayout.CENTER);

        JRadioButton cloud = new JRadioButton("云端为准：备份本地后，以云端工作区为准");
        JRadioButton local = new JRadioButton("本地为准：保留云端历史，以本地文件覆盖同名并删除云端独有文件");
        JRadioButton both = new JRadioButton("独立保留：保留双方独有文件，冲突文件将本地版本改名");
        cloud.setSelected(true);
        ButtonGroup group = new ButtonGroup();
        group.add(cloud);
        group.add(local);
        group.add(both);
        JPanel choices = new JPanel(new GridLayout(3, 1, 0, 6));
        choices.add(cloud);
        choices.add(local);
        choices.add(both);
        content.add(choices, BorderLayout.SOUTH);

        Object[] options = {"继续初始化", "取消"};
        int choice = JOptionPane.showOptionDialog(owner, content, "选择初始化方式",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (choice != 0) {
            return null;
        }
        if (local.isSelected()) {
            return ConflictResolution.LOCAL_WINS;
        }
        if (both.isSelected()) {
            return ConflictResolution.KEEP_BOTH;
        }
        return ConflictResolution.CLOUD_WINS;
    }

    private static String buildDetails(WorkspaceInspection inspection) {
        StringBuilder text = new StringBuilder();
        text.append("本地目录：").append(inspection.localPath())
                .append("\nGitHub 分支：").append(inspection.branch())
                .append("\n\n初始化前会自动备份本地内容。\n\n");
        append(text, "本地独有文件", inspection.localOnlyFiles());
        append(text, "云端独有文件", inspection.remoteOnlyFiles());
        append(text, "同名但内容不同的文件", inspection.conflictingFiles());
        return text.toString();
    }

    private static void append(StringBuilder text, String title, List<String> paths) {
        text.append(title).append("（").append(paths.size()).append("）\n");
        int limit = Math.min(paths.size(), 40);
        for (int index = 0; index < limit; index++) {
            text.append("  ").append(paths.get(index)).append('\n');
        }
        if (paths.size() > limit) {
            text.append("  ... 还有 ").append(paths.size() - limit).append(" 个\n");
        }
        text.append('\n');
    }
}

