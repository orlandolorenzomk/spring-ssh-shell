package dev.orlandolorenzo.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SshShellServerIntegrationTest {

    private static final int PORT = 2299;
    private static final String USERNAME = "testuser";
    private static final String PASSWORD = "testpass";

    private SshShellServer server;

    @BeforeEach
    void startServer() {
        server = SshShellServer.builder()
                .port(PORT)
                .username(USERNAME)
                .password(PASSWORD)
                .commandHandler(input -> "echo:" + input)
                .build();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void connect_sendCommand_receivesResponse() throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();

        try (ClientSession session = client.connect(USERNAME, "localhost", PORT)
                .verify(5, TimeUnit.SECONDS)
                .getSession()) {

            session.addPasswordIdentity(PASSWORD);
            session.auth().verify(5, TimeUnit.SECONDS);

            PipedOutputStream toShell = new PipedOutputStream();
            PipedInputStream fromShell = new PipedInputStream();
            PipedOutputStream shellOut = new PipedOutputStream(fromShell);

            try (ChannelShell channel = session.createShellChannel()) {
                channel.setIn(new PipedInputStream(toShell));
                channel.setOut(shellOut);
                channel.open().verify(5, TimeUnit.SECONDS);

                readUntil(fromShell, "$ ", 3000); // consume banner

                toShell.write("hello\r".getBytes());
                toShell.flush();

                String response = readUntil(fromShell, "$ ", 3000);
                assertTrue(response.contains("echo:hello"), "Expected echoed response, got: " + response);
            }
        } finally {
            client.stop();
        }
    }

    @Test
    void connect_wrongPassword_authenticationFails() throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();

        try (ClientSession session = client.connect(USERNAME, "localhost", PORT)
                .verify(5, TimeUnit.SECONDS)
                .getSession()) {

            session.addPasswordIdentity("wrongpassword");
            assertThrowsAuth(session);
        } finally {
            client.stop();
        }
    }

    // --- helpers ---

    private String readUntil(PipedInputStream in, String marker, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                sb.append((char) in.read());
                if (sb.toString().contains(marker)) return sb.toString();
            } else {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Timeout waiting for \"" + marker + "\". Got: " + sb);
    }

    private void assertThrowsAuth(ClientSession session) {
        try {
            session.auth().verify(3, TimeUnit.SECONDS);
            throw new AssertionError("Expected authentication to fail");
        } catch (Exception e) {
            // expected
        }
    }
}
