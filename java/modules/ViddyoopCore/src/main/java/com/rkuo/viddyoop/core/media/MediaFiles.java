package com.rkuo.viddyoop.core.media;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class MediaFiles {
    private MediaFiles() {}
    public static String checksum(Path path, Runnable heartbeat) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                long lastHeartbeat = System.nanoTime();
                while ((count = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    if (System.nanoTime() - lastHeartbeat > 1_000_000_000L) {
                        heartbeat.run(); lastHeartbeat = System.nanoTime();
                    }
                }
            }
            heartbeat.run();
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static void sync(Path path, boolean directory) throws IOException {
        try (FileChannel channel = FileChannel.open(path, directory ? StandardOpenOption.READ : StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
