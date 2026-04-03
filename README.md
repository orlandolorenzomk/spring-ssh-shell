# spring-ssh-shell

An embeddable SSH shell server for Java applications. Add an interactive SSH interface to any JVM process in a few lines of code — no Spring required despite the name.

## Features

- Fluent builder API
- Single-user (username + password) or fully custom multi-user authentication
- Persistent host key across restarts (no more "host key changed" warnings)
- Parent directories for the host key file are created automatically
- Configurable welcome banner
- Idle session timeout
- Injectable `ExecutorService` for container-managed thread pools
- Zero dependencies beyond Apache MINA SSHD and JUL (no SLF4J in your code)

## Requirements

- Java 17+
- Maven or Gradle

## Installation

### Maven

```xml
<dependency>
    <groupId>dev.orlandolorenzo</groupId>
    <artifactId>spring-ssh-shell</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```groovy
implementation 'dev.orlandolorenzo:spring-ssh-shell:1.0-SNAPSHOT'
```

---

## Quick start

```java
SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .commandHandler(input -> "You said: " + input)
    .build();

server.start();
```

Connect with any SSH client:

```bash
ssh admin@localhost -p 2222
```

Call `server.stop()` to shut down gracefully.

---

## Builder reference

| Method                                | Default                     | Description                                            |
| ------------------------------------- | --------------------------- | ------------------------------------------------------ |
| `port(int)`                           | `2222`                      | Port to listen on                                      |
| `username(String)`                    | —                           | Single-user login (requires `password`)                |
| `password(String)`                    | —                           | Single-user password (requires `username`)             |
| `authenticator(SshAuthenticator)`     | —                           | Custom auth — takes precedence over username/password  |
| `commandHandler(ShellCommandHandler)` | **required**                | Handles every command the client sends                 |
| `banner(String)`                      | `"Welcome to SSH Shell..."` | Text shown to the client on connect                    |
| `idleTimeoutSeconds(int)`             | `0` (disabled)              | Disconnect idle clients after N seconds                |
| `hostKeyFile(Path)`                   | in-memory                   | Persist the server host key to disk                    |
| `executorService(ExecutorService)`    | internal cached pool        | Use your own thread pool                               |

---

## Integration examples

### Plain Java application

The simplest possible integration — start with the process, stop on shutdown.

```java
public class App {

    public static void main(String[] args) {
        SshShellServer shell = SshShellServer.builder()
            .port(2222)
            .username("admin")
            .password("secret")
            .hostKeyFile(Path.of("data/host.key"))
            .banner("MyApp Admin Shell — type 'help' for commands")
            .idleTimeoutSeconds(300)
            .commandHandler(new AppShellHandler())
            .build();

        shell.start();
        Runtime.getRuntime().addShutdownHook(new Thread(shell::stop));
    }
}
```

```java
public class AppShellHandler implements ShellCommandHandler {

    @Override
    public String handle(String input) {
        return switch (input.trim()) {
            case "help"    -> "Commands: status, version, help";
            case "status"  -> "Running since " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms";
            case "version" -> "1.0.0";
            default        -> "Unknown command: " + input;
        };
    }
}
```

---

### Spring Boot

The recommended pattern is to wrap `SshShellServer` in a `SmartLifecycle` bean. Spring will call `start()` and `stop()` automatically at the right phase — after all other beans are ready on startup, and before the context closes on shutdown.

#### SshShellLifecycle

Copy this class into your project once and reuse it across services:

```java
public class SshShellLifecycle implements SmartLifecycle {

    private final SshShellServer server;
    private volatile boolean running;

    public SshShellLifecycle(SshShellServer server) {
        this.server = server;
    }

    @Override
    public void start() {
        server.start();
        running = true;
    }

    @Override
    public void stop() {
        server.stop();
        running = false;
    }

    @Override public boolean isRunning()     { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase()          { return Integer.MAX_VALUE; }
}
```

#### Configuration

Externalize all SSH settings via environment variables so credentials never live in source code:

```java
@Configuration
public class SshShellConfig {

    @Value("${ssh.shell.port:2222}")
    private int port;

    @Value("${ssh.shell.username}")
    private String username;

    @Value("${ssh.shell.password}")
    private String password;

    @Value("${ssh.shell.host-key-file:data/ssh_host_rsa_key}")
    private String hostKeyFile;

    @Value("${ssh.shell.idle-timeout-seconds:600}")
    private int idleTimeoutSeconds;

    @Bean
    public SshShellLifecycle sshShell(AppShellHandler handler) {
        SshShellServer server = SshShellServer.builder()
            .port(port)
            .username(username)
            .password(password)
            .hostKeyFile(Path.of(hostKeyFile))
            .idleTimeoutSeconds(idleTimeoutSeconds)
            .banner("""
                    ╔══════════════════════════════════╗
                    ║        MyApp Admin Shell         ║
                    ╚══════════════════════════════════╝
                    Type 'help' for available commands.
                    """)
            .commandHandler(handler)
            .build();

        return new SshShellLifecycle(server);
    }
}
```

`application.yml`:

```yaml
ssh:
  shell:
    port: ${SSH_SHELL_PORT:2222}
    username: ${SSH_SHELL_USERNAME:admin}
    password: ${SSH_SHELL_PASSWORD}
    host-key-file: ${SSH_SHELL_HOST_KEY_FILE:data/ssh_host_rsa_key}
    idle-timeout-seconds: ${SSH_SHELL_IDLE_TIMEOUT:600}
```

`SSH_SHELL_PASSWORD` has no default — the application will refuse to start if it is not set.

#### Command handler

Implement `ShellCommandHandler` as a Spring `@Component` so you can inject any service you need:

```java
@Component
@RequiredArgsConstructor
public class AppShellHandler implements ShellCommandHandler {

    private final AuthenticationService authenticationService;

    @Override
    public String handle(String input) {
        String[] parts = input.trim().split("\\s+");
        String command = parts[0];

        return switch (command) {
            case "list-keys"    -> authenticationService.findAll().toString();
            case "generate-key" -> parts.length < 2
                    ? "Usage: generate-key <name>"
                    : authenticationService.generateKey(parts[1]);
            case "validate-key" -> parts.length < 2
                    ? "Usage: validate-key <uuid>"
                    : String.valueOf(authenticationService.validate(UUID.fromString(parts[1])));
            case "delete-key"   -> {
                if (parts.length < 2) yield "Usage: delete-key <uuid>";
                authenticationService.delete(UUID.fromString(parts[1]));
                yield "Deleted.";
            }
            case "help" -> "Commands: list-keys, generate-key <name>, validate-key <uuid>, delete-key <uuid>";
            default     -> "Unknown command: " + command;
        };
    }
}
```

The client session looks like this:

```
╔══════════════════════════════════╗
║        MyApp Admin Shell         ║
╚══════════════════════════════════╝
Type 'help' for available commands.
$ help
Commands: list-keys, generate-key <name>, validate-key <uuid>, delete-key <uuid>
$ generate-key device-001
a3f1c2d4-...
$ list-keys
[Authentication{name='device-001', ...}]
$ exit
Bye.
```

---

### Multi-user authentication

Use `SshAuthenticator` instead of `username`/`password` when you need more than one user or want to validate credentials against an external source.

```java
Map<String, String> users = Map.of(
    "alice", "pass1",
    "bob",   "pass2"
);

SshShellServer server = SshShellServer.builder()
    .port(2222)
    .authenticator((username, password) -> {
        String expected = users.get(username);
        return expected != null && expected.equals(password);
    })
    .commandHandler(input -> "Hello, " + input)
    .build();
```

#### Database-backed authentication (Spring example)

```java
@Component
@RequiredArgsConstructor
public class DbSshAuthenticator implements SshAuthenticator {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Override
    public boolean authenticate(String username, String password) {
        return users.findByUsername(username)
            .filter(u -> u.hasRole("SSH_ACCESS"))
            .map(u -> encoder.matches(password, u.getPasswordHash()))
            .orElse(false);
    }
}
```

```java
@Bean
public SshShellLifecycle sshShell(DbSshAuthenticator auth, AppShellHandler handler) {
    SshShellServer server = SshShellServer.builder()
        .port(2222)
        .authenticator(auth)
        .commandHandler(handler)
        .build();
    return new SshShellLifecycle(server);
}
```

---

### Persistent host key

Without a host key file, a new RSA key is generated on every restart and clients will see:

```
WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!
```

Point `hostKeyFile` to a path on disk. The file — and any missing parent directories — are created automatically on first start and reused on every subsequent start.

```java
SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .hostKeyFile(Path.of("data/ssh_host_rsa_key"))
    .commandHandler(input -> "ok")
    .build();
```

Keep this file out of version control:

```
# .gitignore
**/data/ssh_host_rsa_key
```

And restrict its permissions:

```bash
chmod 600 data/ssh_host_rsa_key
```

---

### Idle timeout

Disconnect clients that have not sent a command within a time window. Useful in production to free resources from forgotten sessions.

```java
SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .idleTimeoutSeconds(300)   // 5 minutes
    .commandHandler(input -> "ok")
    .build();
```

---

### Custom banner

The banner is printed immediately when the client connects, before the first prompt.

```java
String banner = """
    ╔══════════════════════════════════╗
    ║   fleet-locate / gps-service     ║
    ╚══════════════════════════════════╝
    Type 'help' for available commands.
    """;

SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .banner(banner)
    .commandHandler(input -> "ok")
    .build();
```

---

### Error handling in commands

`ShellCommandHandler.handle()` declares `throws Exception`. Any thrown exception is caught by the shell, logged at `SEVERE`, and its message is written back to the client as `Error: <message>`. You do not need to wrap every handler branch in try/catch.

```java
.commandHandler(input -> {
    String[] parts = input.trim().split("\\s+");
    return switch (parts[0]) {
        case "validate-key" -> {
            if (parts.length < 2) throw new IllegalArgumentException("Usage: validate-key <uuid>");
            yield String.valueOf(authenticationService.validate(UUID.fromString(parts[1])));
        }
        default -> "Unknown command: " + parts[0];
    };
})
```

The client sees:

```
$ validate-key
Error: Usage: validate-key <uuid>
$
```

---

### Sharing a thread pool

If you run multiple services and want to manage a single thread pool, pass your own `ExecutorService`. The server will use it but will **not** shut it down when `stop()` is called — lifecycle is yours.

```java
ExecutorService sharedPool = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors()
);

SshShellServer server = SshShellServer.builder()
    .port(2222)
    .username("ops")
    .password("secret")
    .executorService(sharedPool)
    .commandHandler(input -> "ok")
    .build();
```

---

## Public API

Only three types are part of the public API:

| Type                  | Description                                                                       |
| --------------------- | --------------------------------------------------------------------------------- |
| `SshShellServer`      | The server. Build with `SshShellServer.builder()`, then call `start()` / `stop()` |
| `ShellCommandHandler` | Functional interface — implement to handle commands                               |
| `SshAuthenticator`    | Functional interface — implement for custom authentication                        |

All other classes (`SshShellSession`, `SshHostKeyProvider`) are package-private implementation details and are not part of the API.

---

## Logging

The library uses `java.util.logging` (JUL). Log names follow the class hierarchy under `dev.orlandolorenzo.ssh`.

To configure in a plain Java app:

```java
Logger.getLogger("dev.orlandolorenzo.ssh").setLevel(Level.WARNING);
```

In a Spring Boot app, bridge JUL to your logging framework by adding `jul-to-slf4j` and calling `SLF4JBridgeHandler.install()` in your configuration, or let Spring Boot handle it automatically (it does this by default).

---

## License

Apache License 2.0
