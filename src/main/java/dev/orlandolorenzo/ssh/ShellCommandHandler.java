package dev.orlandolorenzo.ssh;

/**
 * Handles a single command received from an SSH shell session.
 * The returned string is written back to the client as the command output.
 * Throwing any exception causes an error message to be shown to the client.
 */
@FunctionalInterface
public interface ShellCommandHandler {

    String handle(String input) throws Exception;
}
