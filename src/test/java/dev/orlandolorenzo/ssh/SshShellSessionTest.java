package dev.orlandolorenzo.ssh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SshShellSessionTest {

    private ExecutorService executor;
    private PipedOutputStream toSession;
    private PipedInputStream fromSession;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        // closing the write end signals EOF to the shell loop, letting it exit cleanly
        if (toSession != null) toSession.close();
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void start_sendsWelcomeBannerAndPrompt() throws Exception {
        SshShellSession session = buildSession(input -> "");
        startSession(session);

        String output = readUntil("$ ", 2000);
        assertTrue(output.contains("Welcome to SSH Shell"), "Expected welcome banner");
        assertTrue(output.contains("$ "), "Expected prompt");
    }

    @Test
    void command_dispatchedToHandler_responseWritten() throws Exception {
        SshShellSession session = buildSession(input -> "pong");
        startSession(session);

        readUntil("$ ", 2000); // consume banner + prompt
        sendLine("ping");

        String output = readUntil("$ ", 2000);
        assertTrue(output.contains("pong"), "Expected handler response");
    }

    @Test
    void emptyLine_showsPromptAgain() throws Exception {
        SshShellSession session = buildSession(input -> "should not be called");
        startSession(session);

        readUntil("$ ", 2000); // consume banner
        sendLine("");

        String output = readUntil("$ ", 2000);
        assertTrue(output.contains("$ "), "Expected prompt after empty line");
    }

    @Test
    void handlerException_writesErrorToShell() throws Exception {
        SshShellSession session = buildSession(input -> {
            throw new RuntimeException("boom");
        });
        startSession(session);

        readUntil("$ ", 2000);
        sendLine("fail");

        String output = readUntil("$ ", 2000);
        assertTrue(output.contains("Error:"), "Expected error message");
        assertTrue(output.contains("boom"), "Expected exception message");
    }

    @Test
    void backspace_removesLastCharacter() throws Exception {
        SshShellSession session = buildSession(input -> "echo:" + input);
        startSession(session);

        readUntil("$ ", 2000);

        // type "helo", backspace, then "lo" -> "hello"
        toSession.write("helo".getBytes());
        toSession.write(127); // DEL / backspace
        toSession.write("lo\r".getBytes());
        toSession.flush();

        String output = readUntil("$ ", 2000);
        assertTrue(output.contains("echo:hello"), "Expected corrected input dispatched");
    }

    @Test
    void exitCommand_closesSessionWithZeroCode() throws Exception {
        AtomicInteger exitCode = new AtomicInteger(-1);
        SshShellSession session = buildSession(input -> "");
        setExitCallback(session, code -> exitCode.set(code));
        startSession(session);

        readUntil("$ ", 2000);
        sendLine("exit");

        // wait for exit callback
        long deadline = System.currentTimeMillis() + 2000;
        while (exitCode.get() == -1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, exitCode.get(), "Expected exit code 0");
    }

    // --- helpers ---

    private SshShellSession buildSession(ShellCommandHandler handler) throws Exception {
        PipedOutputStream toSession = new PipedOutputStream();
        PipedInputStream sessionIn = new PipedInputStream(toSession);
        this.toSession = toSession;

        PipedOutputStream sessionOut = new PipedOutputStream();
        this.fromSession = new PipedInputStream(sessionOut);

        SshShellSession session = new SshShellSession(handler, executor, "Welcome to SSH Shell. Type 'exit' to quit.");
        session.setInputStream(sessionIn);
        session.setOutputStream(sessionOut);
        session.setExitCallback((code, message, immediate) -> {});
        return session;
    }

    private void setExitCallback(SshShellSession session, ExitCodeConsumer consumer) {
        session.setExitCallback((code, message, immediate) -> consumer.accept(code));
    }

    private void startSession(SshShellSession session) throws Exception {
        session.start(null, null);
    }

    private void sendLine(String line) throws Exception {
        toSession.write((line + "\r").getBytes());
        toSession.flush();
    }

    private String readUntil(String marker, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (fromSession.available() > 0) {
                sb.append((char) fromSession.read());
                if (sb.toString().contains(marker)) return sb.toString();
            } else {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Timeout waiting for \"" + marker + "\". Got: " + sb);
    }

    @FunctionalInterface
    interface ExitCodeConsumer {
        void accept(int code);
    }
}
