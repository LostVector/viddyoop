package com.rkuo.viddyoop.core.media;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class MediaProcessRunnerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void nonzeroExitPreservesDiagnostics() throws Exception {
        Path log = temp.getRoot().toPath().resolve("process.log");
        Assert.assertThrows(IOException.class, () -> new MediaProcessRunner().run(
                List.of("/bin/sh", "-c", "echo diagnostic; exit 7"), log, Duration.ofSeconds(10), () -> {}));
        Assert.assertTrue(Files.readString(log).contains("diagnostic"));
    }

    @Test public void quietProcessHeartbeatsAndCancellationKillsProcess() throws Exception {
        Path pid = temp.getRoot().toPath().resolve("pid");
        Path log = temp.getRoot().toPath().resolve("process.log");
        int[] beats = {0};
        Assert.assertThrows(IllegalStateException.class, () -> new MediaProcessRunner().run(
                List.of("/bin/sh", "-c", "echo $$ > \"$1\"; exec sleep 60", "runner-test", pid.toString()),
                log, Duration.ofSeconds(30), () -> {
                    if (++beats[0] >= 2) throw new IllegalStateException("cancelled");
                }));
        long processId = Long.parseLong(Files.readString(pid).trim());
        for (int i = 0; i < 100 && ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(10);
        }
        Assert.assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
        Assert.assertEquals(2, beats[0]);
    }

    @Test public void timeoutStopsQuietProcess() throws Exception {
        Path log = temp.getRoot().toPath().resolve("timeout.log");
        Assert.assertThrows(IOException.class, () -> new MediaProcessRunner().run(
                List.of("/bin/sleep", "60"), log, Duration.ofMillis(100), () -> {}));
    }
}
