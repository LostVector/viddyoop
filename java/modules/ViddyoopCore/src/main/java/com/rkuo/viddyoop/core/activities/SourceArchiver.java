package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.io.*;
import java.nio.file.*;

/** Legacy movie threshold/buckets, with checksum-verified create-if-absent publication. */
public final class SourceArchiver {
    public static final long MOVIE_THRESHOLD_MS = 75L * 60 * 1000;
    private final Path allowedRoot;
    public SourceArchiver(Path root) { allowedRoot = root.toAbsolutePath().normalize(); }

    public Path archive(PipelineInput input, long durationMs, int width, int height, Runnable heartbeat) throws IOException {
        if (input.archivePolicy == null || input.archivePolicy.equals("none")) return null;
        if (!input.archivePolicy.equals("movies")) throw new IOException("Unsupported archive policy: " + input.archivePolicy);
        if (durationMs <= 0 || width <= 0 || height <= 0) throw new IOException("Missing source metadata for archive policy");
        if (durationMs <= MOVIE_THRESHOLD_MS) return null;
        if (input.archiveRoot == null || !allowedRoot.equals(Path.of(input.archiveRoot).toAbsolutePath().normalize())) {
            throw new IOException("Archive root differs from registered submission policy");
        }
        Files.createDirectories(allowedRoot);
        MediaFiles.sync(allowedRoot.getParent(), true);
        String bucket = width > 1280 || height > 720 ? "1080p" : "720p";
        Path directory = allowedRoot.resolve(bucket);
        Files.createDirectories(directory);
        if (!directory.toRealPath().getParent().equals(allowedRoot.toRealPath())) throw new IOException("Archive bucket escapes root");
        MediaFiles.sync(allowedRoot, true);
        Path destination = directory.resolve(Path.of(input.sourcePath).getFileName());
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Path temporary = Files.createTempFile(directory, ".archive-", ".tmp");
            try {
                try (InputStream in = Files.newInputStream(Path.of(input.stagedSourcePath), LinkOption.NOFOLLOW_LINKS);
                     OutputStream out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[1024 * 1024]; int count;
                    long last = System.nanoTime();
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        if (System.nanoTime() - last > 1_000_000_000L) { heartbeat.run(); last = System.nanoTime(); }
                    }
                }
                verify(temporary, input.sourceFingerprint, heartbeat);
                MediaFiles.sync(temporary, false);
                try { Files.createLink(destination, temporary); }
                catch (FileAlreadyExistsException e) { verify(destination, input.sourceFingerprint, heartbeat); }
                MediaFiles.sync(directory, true);
            } finally { Files.deleteIfExists(temporary); }
        }
        verify(destination, input.sourceFingerprint, heartbeat);
        MediaFiles.sync(directory, true);
        return destination;
    }

    private static void verify(Path path, String fingerprint, Runnable heartbeat) throws IOException {
        if (!fingerprint.equals(MediaFiles.checksum(path, heartbeat))) throw new IOException("Archive conflict or truncated copy: " + path);
    }
}
