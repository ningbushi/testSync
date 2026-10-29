package com.syncplugins.ui;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public final class AppIcon {
    private AppIcon() {
    }

    public static BufferedImage image(int size) {
        try (InputStream stream = AppIcon.class.getResourceAsStream("/icons/app-icon.png")) {
            if (stream == null) {
                return fallback(size);
            }
            BufferedImage source = ImageIO.read(stream);
            if (source == null) {
                return fallback(size);
            }
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
            graphics.dispose();
            return scaled;
        } catch (IOException exception) {
            return fallback(size);
        }
    }

    private static BufferedImage fallback(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(30, 91, 190));
        graphics.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.drawLine(size / 5, size / 3, size * 4 / 5, size / 3);
        graphics.drawLine(size / 5, size * 2 / 3, size * 4 / 5, size * 2 / 3);
        graphics.dispose();
        return image;
    }
}

