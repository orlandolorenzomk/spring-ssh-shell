package dev.orlandolorenzo.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SshHostKeyProviderTest {

    @Test
    void loadKeys_returnsRsaKeyPair() {
        SshHostKeyProvider provider = new SshHostKeyProvider();
        List<KeyPair> keys = toList(provider.loadKeys(null));

        assertEquals(1, keys.size());
        assertEquals("RSA", keys.get(0).getPublic().getAlgorithm());
    }

    @Test
    void loadKeys_calledTwice_returnsSameInstance() {
        SshHostKeyProvider provider = new SshHostKeyProvider();
        List<KeyPair> first = toList(provider.loadKeys(null));
        List<KeyPair> second = toList(provider.loadKeys(null));

        assertSame(first.get(0), second.get(0), "Expected the same KeyPair instance on repeated calls");
    }

    @Test
    void loadKeys_withKeyFile_persistsAndReloadsKey(@TempDir Path dir) {
        Path keyFile = dir.resolve("host.key");

        SshHostKeyProvider first = new SshHostKeyProvider(keyFile);
        KeyPair firstKey = toList(first.loadKeys(null)).get(0);
        assertTrue(keyFile.toFile().exists(), "Key file should be created");

        SshHostKeyProvider second = new SshHostKeyProvider(keyFile);
        KeyPair secondKey = toList(second.loadKeys(null)).get(0);

        assertEquals(firstKey.getPublic(), secondKey.getPublic(), "Reloaded key should match persisted key");
        assertEquals(firstKey.getPrivate(), secondKey.getPrivate(), "Reloaded key should match persisted key");
    }

    private List<KeyPair> toList(Iterable<KeyPair> iterable) {
        List<KeyPair> list = new java.util.ArrayList<>();
        iterable.forEach(list::add);
        return list;
    }
}
