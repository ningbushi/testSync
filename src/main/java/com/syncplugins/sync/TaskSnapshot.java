package com.syncplugins.sync;

import com.syncplugins.config.AppConfig;

public record TaskSnapshot(AppConfig config, SyncStatus status) {
}

