package com.rkuo.viddyoop.core.media;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** File-backed diagnostics avoid pipe deadlocks; polling also heartbeats quiet processes. */
public final class MediaProcessRunner {
    public void run(List<String> command, Path log, Duration timeout, Runnable heartbeat)
            throws IOException, InterruptedException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        Path errorLog = log.resolveSibling(log.getFileName() + ".stderr");
        Process process = new ProcessBuilder(command).redirectError(errorLog.toFile())
                .redirectOutput(log.toFile()).start();
        long started = System.nanoTime();
        Thread shutdown = new Thread(() -> terminate(process), "media-process-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            heartbeat.run();
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                heartbeat.run(); // cancellation exceptions escape through finally
                if (System.nanoTime() - started > timeout.toNanos()) {
                    throw new IOException("Media process timed out; diagnostics: " + log);
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("Media process exited " + process.exitValue() + "; diagnostics: " + log + " and " + errorLog);
            }
        } finally {
            terminate(process);
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown already owns cleanup */ }
        }
    }

    private static void terminate(Process process) {
        // Snapshot descendants before terminating their parent, which may reparent them.
        var children = process.descendants().toList();
        for (int i = children.size() - 1; i >= 0; i--) children.get(i).destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
    }
}
