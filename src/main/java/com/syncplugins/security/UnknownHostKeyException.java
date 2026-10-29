package com.syncplugins.security;

public final class UnknownHostKeyException extends Exception {
    private final KnownHostKey hostKey;

    public UnknownHostKeyException(KnownHostKey hostKey) {
        super("首次连接到 " + hostKey.host() + "，需要确认主机密钥 " + hostKey.fingerprint());
        this.hostKey = hostKey;
    }

    public KnownHostKey hostKey() {
        return hostKey;
    }
}

