package com.syncplugins.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecretStoreTest {
    @TempDir
    Path temp;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void encryptsLoadsAndClearsPassphraseWithDpapi() throws Exception {
        SecretStore store = new SecretStore(temp.resolve("secret.bin"));
        store.savePassphrase("测试-passphrase".toCharArray());

        assertThat(store.loadPassphrase()).hasValueSatisfying(
                value -> assertThat(new String(value)).isEqualTo("测试-passphrase"));

        store.clear();
        assertThat(store.loadPassphrase()).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsPassphrasesSeparatedByPrivateKey() throws Exception {
        SecretStore store = new SecretStore(temp.resolve("secret-map.bin"));
        store.savePassphrase("W:\\keys\\one", "first".toCharArray());
        store.savePassphrase("W:\\keys\\two", "second".toCharArray());

        assertThat(store.loadPassphrase("W:\\keys\\one")).hasValueSatisfying(
                value -> assertThat(new String(value)).isEqualTo("first"));
        assertThat(store.loadPassphrase("W:\\keys\\two")).hasValueSatisfying(
                value -> assertThat(new String(value)).isEqualTo("second"));
        assertThat(store.loadPassphrase("W:\\keys\\missing")).isEmpty();
    }
}

