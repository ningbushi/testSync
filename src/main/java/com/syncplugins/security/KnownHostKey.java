package com.syncplugins.security;

import org.apache.sshd.common.config.keys.PublicKeyEntry;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;

public record KnownHostKey(String host, String fingerprint, String encodedKey) {
    public static KnownHostKey from(String host, PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            String fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
            return new KnownHostKey(host, fingerprint, PublicKeyEntry.toString(key));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("系统不支持 SHA-256", exception);
        }
    }
}

