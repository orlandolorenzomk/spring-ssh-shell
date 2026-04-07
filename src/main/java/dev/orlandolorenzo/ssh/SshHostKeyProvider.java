package dev.orlandolorenzo.ssh;

import org.apache.sshd.common.keyprovider.AbstractKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Logger;

class SshHostKeyProvider extends AbstractKeyPairProvider {

    private static final Logger log = Logger.getLogger(SshHostKeyProvider.class.getName());

    private final Path keyFile;
    private KeyPair keyPair;

    SshHostKeyProvider(Path keyFile) {
        this.keyFile = keyFile;
    }

    SshHostKeyProvider() {
        this(null);
    }

    @Override
    public synchronized Iterable<KeyPair> loadKeys(SessionContext session) {
        if (keyPair == null) {
            keyPair = keyFile != null ? loadOrGenerate() : generate();
        }
        return Collections.singletonList(keyPair);
    }

    private KeyPair loadOrGenerate() {
        if (Files.exists(keyFile)) {
            try {
                return load();
            } catch (Exception e) {
                log.warning("Host key file is corrupted or unreadable, regenerating: " + keyFile +
                        " — existing SSH clients will see a host key warning: " + e.getMessage());
            }
        }
        KeyPair kp = generate();
        save(kp);
        return kp;
    }

    private KeyPair load() throws Exception {
        List<String> lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8);
        byte[] privateBytes = Base64.getDecoder().decode(lines.get(0).trim());
        byte[] publicBytes = Base64.getDecoder().decode(lines.get(1).trim());

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
        return new KeyPair(publicKey, privateKey);
    }

    private void save(KeyPair kp) {
        String privateKeyBase64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String publicKeyBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        try {
            if (keyFile.getParent() != null) {
                Files.createDirectories(keyFile.getParent());
            }
            Files.writeString(keyFile, privateKeyBase64 + "\n" + publicKeyBase64, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(keyFile,
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem (e.g. Windows) — skip permission hardening
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save host key to " + keyFile, e);
        }
    }

    private KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate host key", e);
        }
    }
}
