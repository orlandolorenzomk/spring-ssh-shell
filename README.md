# spring-ssh-shell

[![Maven Central](https://img.shields.io/maven-central/v/io.github.orlandolorenzomk/spring-ssh-shell)](https://central.sonatype.com/artifact/io.github.orlandolorenzomk/spring-ssh-shell)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/java-17%2B-orange)](https://adoptium.net)

Embed a fully working SSH shell into any Java application in a few lines of code.

No Spring required. No agents. No sidecar processes. Just a dependency and a builder.

```java
SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .commandHandler(input -> "You said: " + input)
    .build();

server.start();
```

```bash
ssh admin@localhost -p 2222
```

---

## Why

Most Java applications have no runtime introspection beyond HTTP endpoints and log files. Attaching a debugger to production is risky, adding custom HTTP endpoints for internal tooling is noisy, and redeploying just to run a one-off query is wasteful.

spring-ssh-shell gives you a proper interactive shell — accessible from anywhere via standard SSH — wired directly into your running application context. Query internal state, trigger operations, inspect live data, all without touching your public API surface.

---

## Installation

### Maven

```xml
<dependency>
    <groupId>io.github.orlandolorenzomk</groupId>
    <artifactId>spring-ssh-shell</artifactId>
    <version>1.1.1</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.orlandolorenzomk:spring-ssh-shell:1.1.1'
```

**Requirements:** Java 17+

---

## Quick start

```java
SshShellServer server = SshShellServer.builder()
    .port(2222)
    .username("admin")
    .password("secret")
    .hostKeyFile(Path.of("data/host.key"))
    .idleTimeoutSeconds(300)
    .banner("MyApp Admin Shell — type 'help' for commands")
    .commandHandler(new AppShellHandler())
    .build();

server.start();
Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
```

```java
public class AppShellHandler implements ShellCommandHandler {

    @Override
    public String handle(String input) {
        return switch (input.trim()) {
            case "help"    -> "Commands: status, version, help";
            case "status"  -> "Running since " + ManagementFactory.getRuntimeMXBean().getUptime() + "ms";
            case "version" -> "1.1.1";
            default        -> "Unknown command: " + input;
        };
    }
}
```

---

## Builder reference

| Method                                | Default                     | Description                                           |
|---------------------------------------|-----------------------------|-------------------------------------------------------|
| `port(int)`                           | `2222`                      | Port to listen on                                     |
| `username(String)`                    | —                           | Single-user login (requires `password`)               |
| `password(String)`                    | —                           | Single-user password (requires `username`)            |
| `authenticator(SshAuthenticator)`     | —                           | Custom auth — takes precedence over username/password |
| `commandHandler(ShellCommandHandler)` | **required**                | Handles every command the client sends                |
| `banner(String)`                      | `"Welcome to SSH Shell..."` | Text shown to the client on connect                   |
| `idleTimeoutSeconds(int)`             | `0` (disabled)              | Disconnect idle clients after N seconds               |
| `hostKeyFile(Path)`                   | in-memory                   | Persist the server host key to disk                   |
| `executorService(ExecutorService)`    | internal cached pool        | Use your own thread pool                              |

---

## Integration examples

### Spring Boot

Wrap `SshShellServer` in a `SmartLifecycle` bean. Spring will call `start()` and `stop()` automatically — after all other beans are ready on startup, and before the context closes on shutdown.

```java
public class SshShellLifecycle implements SmartLifecycle {

    private final SshShellServer server;
    private volatile boolean running;

    public SshShellLifecycle(SshShellServer server) {
        this.server = server;
    }

    @Override public void start()        { server.start(); running = true; }
    @Override public void stop()         { server.stop();  running = false; }
    @Override public boolean isRunning()     { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase()          { return Integer.MAX_VALUE; }
}
```

Externalize all SSH settings via environment variables so credentials never live in source code:

```java
@Configuration
public class SshShellConfig {

    @Value("${ssh.shell.port:2222}")         private int port;
    @Value("${ssh.shell.username}")          private String username;
    @Value("${ssh.shell.password}")          private String password;
    @Value("${ssh.shell.host-key-file:data/ssh_host_rsa_key}") private String hostKeyFile;
    @Value("${ssh.shell.idle-timeout-seconds:600}") private int idleTimeoutSeconds;

    @Bean
    public SshShellLifecycle sshShell(AppShellHandler handler) {
        SshShellServer server = SshShellServer.builder()
            .port(port)
            .username(username)
            .password(password)
            .hostKeyFile(Path.of(hostKeyFile))
            .idleTimeoutSeconds(idleTimeoutSeconds)
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

Implement `ShellCommandHandler` as a `@Component` so you can inject any service:

```java
@Component
@RequiredArgsConstructor
public class AppShellHandler implements ShellCommandHandler {

    private final AuthenticationService authenticationService;

    @Override
    public String handle(String input) {
        String[] parts = input.trim().split("\\s+");
        return switch (parts[0]) {
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
            default     -> "Unknown command: " + parts[0];
        };
    }
}
```

The client session:

```
MyApp Admin Shell — type 'help' for commands
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
SshShellServer server = SshShellServer.builder()
    .port(2222)
    .authenticator((username, password) -> {
        String expected = users.get(username);
        return expected != null && expected.equals(password);
    })
    .commandHandler(input -> "Hello, " + username)
    .build();
```

#### Database-backed authentication

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

---

### Persistent host key

Without a host key file, a new RSA key is generated on every restart and SSH clients will warn:

```
WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!
```

Point `hostKeyFile` to a path on disk. The file and any missing parent directories are created automatically on first start and reused on every subsequent one. The file is written with `600` permissions on POSIX systems.

```java
SshShellServer server = SshShellServer.builder()
    .username("admin")
    .password("secret")
    .hostKeyFile(Path.of("data/ssh_host_rsa_key"))
    .commandHandler(input -> "ok")
    .build();
```

Keep the file out of version control:

```gitignore
**/data/ssh_host_rsa_key
```

---

### Error handling

`ShellCommandHandler.handle()` declares `throws Exception`. Any uncaught exception is logged at `SEVERE` and its message is written back to the client as `Error: <message>` — no try/catch required in every branch.

```java
.commandHandler(input -> {
    String[] parts = input.trim().split("\\s+");
    return switch (parts[0]) {
        case "validate-key" -> {
            if (parts.length < 2) throw new IllegalArgumentException("Usage: validate-key <uuid>");
            yield String.valueOf(authService.validate(UUID.fromString(parts[1])));
        }
        default -> "Unknown command: " + parts[0];
    };
})
```

```
$ validate-key
Error: Usage: validate-key <uuid>
$
```

---

### Shared thread pool

If you manage a shared `ExecutorService` across services, pass it in. The server uses it but will **not** shut it down when `stop()` is called — lifecycle ownership stays with you.

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

Only three types are part of the public contract:

| Type                  | Description                                                                        |
|-----------------------|------------------------------------------------------------------------------------|
| `SshShellServer`      | The server. Build with `SshShellServer.builder()`, then call `start()` / `stop()` |
| `ShellCommandHandler` | Functional interface — implement to handle commands                                |
| `SshAuthenticator`    | Functional interface — implement for custom authentication                         |

All other classes are package-private implementation details.

---

## Logging

The library uses `java.util.logging` (JUL). Log names follow the class hierarchy under `dev.orlandolorenzo.ssh`.

Plain Java:

```java
Logger.getLogger("dev.orlandolorenzo.ssh").setLevel(Level.WARNING);
```

Spring Boot bridges JUL to SLF4J automatically. No additional configuration needed.

---

## Releases

See the [releases](releases/) folder for a full changelog.

---

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
