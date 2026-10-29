package com.syncplugins.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class UiFonts {
    private static final String[] PREFERRED = {
            "Microsoft YaHei UI", "Microsoft YaHei", "SimSun", Font.DIALOG
    };

    private UiFonts() {
    }

    public static Font uiFont(int size) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames(java.util.Locale.CHINA)));
        for (String family : PREFERRED) {
            if (available.contains(family)) {
                return new Font(family, Font.PLAIN, size);
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }

    public static void applyTrayFont(PopupMenu menu) {
        Font font = uiFont(12);
        menu.setFont(font);
        for (int index = 0; index < menu.getItemCount(); index++) {
            MenuItem item = menu.getItem(index);
            if (item != null) {
                item.setFont(font);
            }
        }
    }
}

