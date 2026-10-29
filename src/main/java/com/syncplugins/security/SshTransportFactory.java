package com.syncplugins.security;

import org.eclipse.jgit.internal.transport.sshd.OpenSshServerKeyDatabase;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.KeyCache;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class SshTransportFactory implements AutoCloseable {
    private final SshdSessionFactory sessionFactory;
    private final PendingApprovalServerKeyDatabase serverKeyDatabase;

    public SshTransportFactory(Path privateKey, char[] passphrase, Path knownHosts) {
        Map<Path, java.security.KeyPair> cache = new ConcurrentHashMap<>();
        KeyCache keyCache = new KeyCache() {
            @Override
            public java.security.KeyPair get(Path path, Function<? super Path, ? extends java.security.KeyPair> loader) {
                return cache.computeIfAbsent(path, loader);
            }

            @Override
            public void close() {
                cache.clear();
            }
        };

        this.serverKeyDatabase = new PendingApprovalServerKeyDatabase(knownHosts);
        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
                .setHomeDirectory(new File(System.getProperty("user.home")))
                .setSshDirectory(knownHosts.getParent().toFile())
                .setPreferredAuthentications("publickey")
                .setDefaultIdentities(ignored -> List.of(privateKey))
                .setDefaultKnownHostsFiles(ignored -> List.of(knownHosts))
                .setKeyPasswordProvider(ignored -> new OneShotPasswordProvider(passphrase))
                .setServerKeyDatabase((home, ssh) -> serverKeyDatabase);

        this.sessionFactory = builder.build(keyCache);
    }

    public SshdSessionFactory sessionFactory() {
        return sessionFactory;
    }

    public KnownHostKey rejectedHostKey() {
        return serverKeyDatabase.rejected;
    }

    @Override
    public void close() {
        sessionFactory.close();
    }

    private static final class OneShotPasswordProvider implements KeyPasswordProvider {
        private final char[] passphrase;
        private boolean used;
        private int attempts;

        private OneShotPasswordProvider(char[] passphrase) {
            this.passphrase = passphrase == null ? new char[0] : passphrase.clone();
        }

        @Override
        public char[] getPassphrase(URIish uri, int numberOfAttempts) {
            if (used) {
                return null;
            }
            used = true;
            return passphrase.length == 0 ? null : passphrase.clone();
        }

        @Override
        public void setAttempts(int attempts) {
            this.attempts = attempts;
        }

        @Override
        public int getAttempts() {
            return attempts;
        }

        @Override
        public boolean keyLoaded(URIish uri, int attempt, Exception error) {
            return error == null;
        }
    }

    private static final class PendingApprovalServerKeyDatabase implements ServerKeyDatabase {
        private final OpenSshServerKeyDatabase delegate;
        private volatile KnownHostKey rejected;

        private PendingApprovalServerKeyDatabase(Path knownHosts) {
            this.delegate = new OpenSshServerKeyDatabase(false, List.of(knownHosts));
        }

        @Override
        public List<PublicKey> lookup(String host, InetSocketAddress remoteAddress, Configuration config) {
            return delegate.lookup(host, remoteAddress, config);
        }

        @Override
        public boolean accept(String host, InetSocketAddress remoteAddress, PublicKey key,
                              Configuration config, CredentialsProvider credentialsProvider) {
            if (delegate.accept(host, remoteAddress, key, config, credentialsProvider)) {
                return true;
            }
            rejected = KnownHostKey.from(host, key);
            return false;
        }
    }
}
