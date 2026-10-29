package com.syncplugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class AppLogging {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private AppLogging() {
    }

    public static void configure(Path logFile) throws IOException {
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileHandler handler = new FileHandler(logFile.toString(), 2 * 1024 * 1024, 3, true);
        handler.setEncoding("UTF-8");
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public synchronized String format(java.util.logging.LogRecord record) {
                String thrown = record.getThrown() == null ? "" : "\n" + stack(record.getThrown());
                return TIME.format(Instant.ofEpochMilli(record.getMillis()))
                        + " " + record.getLevel().getName()
                        + " " + record.getLoggerName()
                        + " - " + record.getMessage() + thrown + System.lineSeparator();
            }
        });
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        root.setLevel(Level.INFO);
    }

    private static String stack(Throwable throwable) {
        java.io.StringWriter writer = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
