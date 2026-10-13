package com.acme.broker.envadmin.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Key and trust stores for mTLS tests, made with the JDK's keytool: a server certificate for localhost and client
 * certificates, each self-signed. Stores are PKCS12 with password {@value #PASSWORD}.
 */
public final class TestCertificates {

    public static final String PASSWORD = "changeit";

    private final Path directory;

    private TestCertificates(Path directory) {
        this.directory = directory;
    }

    public static TestCertificates create() {
        try {
            return new TestCertificates(Files.createTempDirectory("broker-mtls"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A key store holding one self-signed key pair; returns its path. */
    public Path keyPair(String name, String dname, String san) {
        Path store = directory.resolve(name + ".p12");
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", name, "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", dname, "-validity", "2", "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD));
        if (san != null) {
            args.addAll(List.of("-ext", "SAN=" + san));
        }
        keytool(args);
        return store;
    }

    /** A trust store holding the certificates of the given key stores. */
    public Path trustStore(String name, Path... keyStores) {
        Path store = directory.resolve(name + ".p12");
        for (Path keyStore : keyStores) {
            String alias = keyStore.getFileName().toString().replace(".p12", "");
            Path certificate = directory.resolve(alias + ".crt");
            keytool(List.of("-exportcert", "-alias", alias, "-keystore", keyStore.toString(), "-storetype",
                    "PKCS12", "-storepass", PASSWORD, "-rfc", "-file", certificate.toString()));
            keytool(List.of("-importcert", "-noprompt", "-alias", alias, "-file", certificate.toString(),
                    "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD));
        }
        return store;
    }

    public static SSLContext context(Path keyStore, Path trustStore) {
        try {
            KeyManagerFactory keys = null;
            if (keyStore != null) {
                keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(keyStore), PASSWORD.toCharArray());
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load(trustStore));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    private static void keytool(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool başarısız: " + output);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
