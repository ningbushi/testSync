package com.syncplugins.ui;

import com.syncplugins.config.AppConfig;

public record SetupResult(AppConfig config, char[] passphrase) {
}

