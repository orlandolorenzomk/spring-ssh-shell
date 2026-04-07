package dev.orlandolorenzo.ssh;

import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * An embeddable SSH shell server.
 *
 * <pre>{@code
 * SshShellServer server = SshShellServer.builder()
 *     .username("admin")
 *     .password("secret")
 *     .commandHandler(input -> "You said: " + input)
 *     .build();
 * server.start();
 * }</pre>
 *
 * For multi-user or custom authentication use {@link Builder#authenticator(SshAuthenticator)}.
 */
public class SshShellServer {

    private static final Logger log = Logger.getLogger(SshShellServer.class.getName());

    private static final String DEFAULT_BANNER = "Welcome to SSH Shell. Type 'exit' to quit.";

    private final SshServer sshServer;
    private final ExecutorService executorService;
    private final boolean managedExecutor;

    private SshShellServer(Builder builder) {
        if (builder.executorService != null) {
            this.executorService = builder.executorService;
            this.managedExecutor = false;
        } else {
            this.executorService = Executors.newCachedThreadPool();
            this.managedExecutor = true;
        }

        String banner = builder.banner != null ? builder.banner : DEFAULT_BANNER;

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(builder.port);
        sshServer.setKeyPairProvider(new SshHostKeyProvider(builder.hostKeyFile));
        sshServer.setPasswordAuthenticator((username, password, session) -> {
            try {
                return builder.authenticator.authenticate(username, password);
            } catch (Exception e) {
                log.warning("Authentication error for user " + username + ": " + e.getMessage());
                return false;
            }
        });
        sshServer.setShellFactory(channel ->
                new SshShellSession(builder.handler, executorService, banner));

        if (builder.idleTimeoutSeconds > 0) {
            CoreModuleProperties.IDLE_TIMEOUT.set(sshServer, Duration.ofSeconds(builder.idleTimeoutSeconds));
        }
    }

    public void start() {
        try {
            sshServer.start();
            log.info("SSH shell started on port " + sshServer.getPort());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start SSH shell server", e);
        }
    }

    public void stop() {
        try {
            sshServer.stop();
            log.info("SSH shell stopped");
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop SSH shell server", e);
        } finally {
            if (managedExecutor) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public boolean isRunning() {
        return sshServer.isStarted();
    }

    public int getPort() {
        return sshServer.getPort();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 2222;
        private String username;
        private String password;
        private SshAuthenticator authenticator;
        private ShellCommandHandler handler;
        private ExecutorService executorService;
        private String banner;
        private int idleTimeoutSeconds = 0;
        private Path hostKeyFile;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Single-user shorthand. For multiple users use {@link #authenticator(SshAuthenticator)}. */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /** Single-user shorthand. For multiple users use {@link #authenticator(SshAuthenticator)}. */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /** Custom authenticator. Takes precedence over {@link #username}/{@link #password} if both are set. */
        public Builder authenticator(SshAuthenticator authenticator) {
            this.authenticator = authenticator;
            return this;
        }

        public Builder commandHandler(ShellCommandHandler handler) {
            this.handler = handler;
            return this;
        }

        /** Replaces the default executor. The provided executor will NOT be shut down by {@link SshShellServer#stop()}. */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /** Overrides the default welcome banner shown to clients on connect. */
        public Builder banner(String banner) {
            this.banner = banner;
            return this;
        }

        /** Disconnects idle clients after the given number of seconds. 0 means no timeout (default). */
        public Builder idleTimeoutSeconds(int seconds) {
            this.idleTimeoutSeconds = seconds;
            return this;
        }

        /**
         * Path to persist the server host key across restarts.
         * If the file does not exist it will be created on first start.
         * Without this, a new key is generated on every start and clients will see host key warnings.
         */
        public Builder hostKeyFile(Path hostKeyFile) {
            this.hostKeyFile = hostKeyFile;
            return this;
        }

        public SshShellServer build() {
            if (authenticator == null) {
                if (username == null || password == null) {
                    throw new IllegalStateException("Either authenticator or username and password are required");
                }
                final String u = username, p = password;
                authenticator = (user, pass) -> {
                    // Use non-short-circuit & so both comparisons always run (prevents timing side-channel)
                    return MessageDigest.isEqual(u.getBytes(StandardCharsets.UTF_8), user.getBytes(StandardCharsets.UTF_8))
                         & MessageDigest.isEqual(p.getBytes(StandardCharsets.UTF_8), pass.getBytes(StandardCharsets.UTF_8));
                };
            }
            if (handler == null) {
                throw new IllegalStateException("commandHandler is required");
            }
            return new SshShellServer(this);
        }
    }
}
