package dev.orlandolorenzo.ssh;

/**
 * Authenticates SSH users by username and password.
 * Implement this to support multiple users, database-backed auth, etc.
 */
@FunctionalInterface
public interface SshAuthenticator {

    /**
     * Returns {@code true} if the credentials are accepted.
     * Throwing an exception is treated as a rejection.
     */
    boolean authenticate(String username, String password) throws Exception;
}
