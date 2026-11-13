package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.catalog.ArtifactCatalog.Artifact;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;

/** Reconciles retries against a durable manifest before acknowledging publication. */
public final class ArtifactPublisher {
    @FunctionalInterface public interface CatalogCommit {
        void commit(String submissionId) throws IOException, SQLException;
    }
    private final CatalogCommit commit;
    private final ArtifactCatalog catalog;
    private final Path library;

    public ArtifactPublisher(ArtifactCatalog catalog, Path library) throws IOException {
        this(catalog, library, catalog::commit);
    }

    public ArtifactPublisher(ArtifactCatalog catalog, Path library, CatalogCommit commit) throws IOException {
        this.commit = commit;
        this.catalog = catalog;
        Files.createDirectories(library);
        this.library = library.toRealPath();
    }

    public void publish(Artifact artifact, Path encoded, Runnable heartbeat) throws IOException, SQLException {
        Path destination = Path.of(artifact.destination()).toAbsolutePath().normalize();
        // Initial layout is flat. Resolve the parent to prevent symlink escapes.
        if (!destination.getParent().toRealPath().equals(library)) {
            throw new IOException("Publication destination must be directly inside library: " + destination);
        }
        catalog.reserve(artifact); // Persist identity before touching the destination.
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            verify(destination, artifact.checksum(), heartbeat);
        } else {
            Path temporary = Files.createTempFile(library, ".publish-", ".tmp");
            try {
                try (InputStream in = Files.newInputStream(encoded, LinkOption.NOFOLLOW_LINKS);
                     OutputStream out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    long lastHeartbeat = System.nanoTime();
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        if (System.nanoTime() - lastHeartbeat > 1_000_000_000L) {
                            heartbeat.run(); lastHeartbeat = System.nanoTime();
                        }
                    }
                }
                verify(temporary, artifact.checksum(), heartbeat);
                MediaFiles.sync(temporary, false);
                try {
                    // Atomic create-if-absent on the library filesystem: no overwrite race.
                    Files.createLink(destination, temporary);
                } catch (FileAlreadyExistsException e) {
                    verify(destination, artifact.checksum(), heartbeat);
                }
                MediaFiles.sync(library, true);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        // Also flush when reconciling an earlier attempt that died before directory sync.
        MediaFiles.sync(library, true);
        commit.commit(artifact.submissionId());
    }

    private static void verify(Path file, String expected, Runnable heartbeat) throws IOException {
        if (!expected.equals(MediaFiles.checksum(file, heartbeat))) throw new IOException("Artifact conflict: " + file);
    }
}
