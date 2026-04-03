package dev.orlandolorenzo.ssh;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

class SshShellSession implements Command {

    private static final Logger LOGGER = Logger.getLogger(SshShellSession.class.getName());

    private final ShellCommandHandler handler;
    private final ExecutorService executorService;
    private final String banner;

    private InputStream inputStream;
    private OutputStream outputStream;
    private ExitCallback exitCallback;
    private volatile boolean running = true;

    SshShellSession(ShellCommandHandler handler, ExecutorService executorService, String banner) {
        this.handler = handler;
        this.executorService = executorService;
        this.banner = banner;
    }

    @Override
    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void setErrorStream(OutputStream outputStream) {
    }

    @Override
    public void setExitCallback(ExitCallback exitCallback) {
        this.exitCallback = exitCallback;
    }

    @Override
    public void start(ChannelSession channelSession, Environment environment) throws IOException {
        executorService.submit(this::runShell);
    }

    @Override
    public void destroy(ChannelSession channelSession) throws Exception {
        running = false;
    }

    private void runShell() {
        PrintWriter writer = new PrintWriter(outputStream, true);

        writer.print(banner + "\r\n$ ");
        writer.flush();

        try {
            StringBuilder lineBuffer = new StringBuilder();
            int c;
            while (running && (c = inputStream.read()) != -1) {
                if (c == '\r' || c == '\n') {
                    String line = lineBuffer.toString().trim();
                    lineBuffer.setLength(0);

                    writer.print("\r\n");
                    writer.flush();

                    if (line.isEmpty()) {
                        writer.print("$ ");
                        writer.flush();
                        continue;
                    }

                    if (line.equals("exit")) {
                        writer.print("Bye.\r\n");
                        writer.flush();
                        break;
                    }

                    try {
                        String result = handler.handle(line);
                        writer.print(result + "\r\n$ ");
                    } catch (Exception e) {
                        LOGGER.severe(String.format("Error handling command: %s: %s", line, e));
                        writer.print("Error: " + e.getMessage() + "\r\n$ ");
                    }

                    writer.flush();

                } else if (c == 127 || c == 8) {
                    if (!lineBuffer.isEmpty()) {
                        lineBuffer.deleteCharAt(lineBuffer.length() - 1);
                        writer.print("\b \b");
                        writer.flush();
                    }
                } else {
                    lineBuffer.append((char) c);
                    writer.print((char) c);
                    writer.flush();
                }
            }
        } catch (IOException e) {
            if (running) {
                LOGGER.severe("Session IO error: " + e.getMessage());
            }
        } finally {
            exitCallback.onExit(0);
        }
    }
}
