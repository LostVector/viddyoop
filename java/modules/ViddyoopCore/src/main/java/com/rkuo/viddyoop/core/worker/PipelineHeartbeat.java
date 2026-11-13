package com.rkuo.viddyoop.core.worker;

import java.io.IOException;
import java.nio.file.*;

/** Ephemeral service readiness signal; never workflow/domain state. */
public final class PipelineHeartbeat {
    private PipelineHeartbeat() {}
    private static final String FILE = ".pipeline-heartbeat";
    public static void write(Path work) throws IOException {
        Path temporary = work.resolve(FILE + ".tmp");
        Files.writeString(temporary, Long.toString(System.currentTimeMillis()));
        Files.move(temporary, work.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static void clear(Path work) throws IOException { Files.deleteIfExists(work.resolve(FILE)); }
    public static boolean fresh(Path work, long now) {
        try {
            long timestamp = Long.parseLong(Files.readString(work.resolve(FILE)).trim());
            return timestamp >= 0 && timestamp <= now && now - timestamp <= 30000;
        } catch (IOException | NumberFormatException e) { return false; }
    }
}
