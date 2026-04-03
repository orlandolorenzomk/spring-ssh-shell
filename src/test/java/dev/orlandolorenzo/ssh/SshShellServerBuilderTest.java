package dev.orlandolorenzo.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SshShellServerBuilderTest {

    private static final ShellCommandHandler NOOP_HANDLER = input -> "ok";

    @Test
    void build_withoutUsername_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                SshShellServer.builder()
                        .password("pass")
                        .commandHandler(NOOP_HANDLER)
                        .build());
    }

    @Test
    void build_withoutPassword_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                SshShellServer.builder()
                        .username("user")
                        .commandHandler(NOOP_HANDLER)
                        .build());
    }

    @Test
    void build_withoutHandler_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                SshShellServer.builder()
                        .username("user")
                        .password("pass")
                        .build());
    }

    @Test
    void build_withAllRequired_succeeds() {
        SshShellServer server = SshShellServer.builder()
                .username("user")
                .password("pass")
                .commandHandler(NOOP_HANDLER)
                .build();
        assertNotNull(server);
    }

    @Test
    void build_withCustomAuthenticator_succeeds() {
        SshShellServer server = SshShellServer.builder()
                .authenticator((u, p) -> "admin".equals(u))
                .commandHandler(NOOP_HANDLER)
                .build();
        assertNotNull(server);
    }

    @Test
    void build_withoutAuthenticatorOrCredentials_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                SshShellServer.builder()
                        .commandHandler(NOOP_HANDLER)
                        .build());
    }
}
