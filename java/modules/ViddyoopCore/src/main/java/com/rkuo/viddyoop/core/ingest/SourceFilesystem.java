package com.rkuo.viddyoop.core.ingest;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** Guest sharing filesystems can recycle their synthetic file keys on a VM restart. */
public final class SourceFilesystem {
    public record Stamp(String type, String epoch) {}
    @FunctionalInterface interface Reader { Stamp read(Path path) throws IOException; }
    private SourceFilesystem() {}
    static boolean shared(String type) { return type.equals("virtiofs") || type.equals("9p") || type.startsWith("fuse"); }
    public static Stamp inspect(Path path) throws IOException {
        String type = Files.getFileStore(path).type().toLowerCase(Locale.ROOT);
        if (!shared(type)) return new Stamp(type, "");
        Path boot = Path.of("/proc/sys/kernel/random/boot_id");
        if (!Files.isRegularFile(boot)) throw new IOException("Shared source identities require a readable Linux boot ID");
        String epoch = Files.readString(boot).trim();
        if (epoch.isEmpty()) throw new IOException("Shared source boot ID is empty");
        return new Stamp(type, type + ":" + epoch);
    }
}
