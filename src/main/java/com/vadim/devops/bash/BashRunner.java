package com.vadim.devops.bash;

import com.vadim.devops.config.DevopsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class BashRunner {

    private static final Logger log = LoggerFactory.getLogger(BashRunner.class);

    private final int timeoutSeconds;

    public BashRunner(DevopsProperties props) {
        this.timeoutSeconds = props.monitoring().bashTimeoutSeconds();
    }

    public BashResult run(String command) {
        return run(command, timeoutSeconds);
    }

    /** Run with explicit argument list — no shell interpolation, safe for untrusted input. */
    public BashResult runArgs(List<String> args) {
        return runArgs(args, timeoutSeconds);
    }

    public BashResult runArgs(List<String> args, int timeoutSeconds) {
        log.info("$ {}", String.join(" ", args));
        try {
            var process = new ProcessBuilder(args)
                    .redirectErrorStream(false)
                    .start();
            return collect(process, String.join(" ", args), timeoutSeconds);
        } catch (IOException e) {
            return new BashResult(-1, "", e.getMessage());
        }
    }

    public BashResult run(String command, int timeoutSeconds) {
        log.info("$ {}", command);
        try {
            var process = new ProcessBuilder("bash", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            return collect(process, command, timeoutSeconds);
        } catch (IOException e) {
            return new BashResult(-1, "", e.getMessage());
        }
    }

    private BashResult collect(Process process, String label, int timeoutSeconds) {
        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();

        var stdoutThread = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutBuf.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });

        var stderrThread = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrBuf.append(line).append("\n");
                }
            } catch (IOException ignored) {}
        });

        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Command timed out after {}s: {}", timeoutSeconds, label);
                process.destroyForcibly();
                stdoutThread.join(2000);
                stderrThread.join(2000);
                return new BashResult(-1, stdoutBuf.toString(), "timeout");
            }
            stdoutThread.join();
            stderrThread.join();
            int exitCode = process.exitValue();
            logResult(exitCode, stdoutBuf, stderrBuf);
            boolean killedBySignal = exitCode > 128;
            return new BashResult(exitCode, stdoutBuf.toString(), stderrBuf.toString(), killedBySignal);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new BashResult(-1, "", "interrupted", true);
        }
    }

    private void logResult(int exitCode, StringBuilder stdout, StringBuilder stderr) {
        if (!log.isDebugEnabled()) return;
        var sb = new StringBuilder("exit=").append(exitCode);
        var out = stdout.toString();
        var err = stderr.toString();
        if (!out.isBlank()) {
            sb.append("\n--- stdout ---\n");
            sb.append(out.length() > 3000 ? out.substring(0, 3000) + "\n…(truncated)" : out);
        }
        if (!err.isBlank()) {
            sb.append("\n--- stderr ---\n");
            sb.append(err.length() > 1000 ? err.substring(0, 1000) + "\n…(truncated)" : err);
        }
        log.debug("{}", sb);
    }
}
