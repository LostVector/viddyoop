package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.UUID;
import static com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog.Outcome.*;

/** Copies into a watched folder at most once unless positive filesystem evidence permits reconciliation. */
public final class ItunesDelivery {
    private final ItunesReconciler reconciler;
    private final ArtifactCatalog artifacts;
    private final ItunesDeliveryCatalog receipts;
    private final Path library, watched, staging;
    public ItunesDelivery(ArtifactCatalog artifacts, ItunesDeliveryCatalog receipts, Path library, Path watched, Path staging) throws IOException {
        this(artifacts, receipts, library, watched, staging, null);
    }
    public ItunesDelivery(ArtifactCatalog artifacts, ItunesDeliveryCatalog receipts, Path library, Path watched, Path staging,
                          ItunesReconciler reconciler) throws IOException {
        this.reconciler = reconciler;
        this.artifacts = artifacts; this.receipts = receipts; this.library = library.toRealPath();
        Path rawWatched = watched.toAbsolutePath().normalize(), rawStaging = staging.toAbsolutePath().normalize();
        if (rawStaging.startsWith(rawWatched) || rawWatched.startsWith(rawStaging)
                || rawStaging.startsWith(this.library) || this.library.startsWith(rawStaging)) {
            throw new IOException("Staging must be outside the watched folder and library");
        }
        this.watched = watched.toRealPath();
        if (!Files.isDirectory(this.watched) || !Files.isWritable(this.watched)) throw new IOException("Watched folder must exist and be writable");
        Files.createDirectories(staging); this.staging = staging.toRealPath();
        if (this.staging.startsWith(this.watched) || this.watched.startsWith(this.staging)
                || this.watched.startsWith(this.library) || this.library.startsWith(this.watched)
                || this.staging.startsWith(this.library) || this.library.startsWith(this.staging)) {
            throw new IOException("Library, watched folder, and staging must be separate directory trees");
        }
        if (!Files.getFileStore(this.watched).equals(Files.getFileStore(this.staging))) {
            throw new IOException("Staging and watched folder must share a filesystem/mount");
        }
    }
    public ItunesDeliveryCatalog.Delivery deliver(String id, Runnable heartbeat) throws IOException, SQLException {
        return deliver(id, heartbeat, true);
    }
    /** Observation-only operation can repair receipts but never exposes a new watched copy. */
    public ItunesDeliveryCatalog.Delivery deliver(String id, Runnable heartbeat, boolean allowNewCopy) throws IOException, SQLException {
        UUID.fromString(id);
        var artifact = artifacts.expected(id);
        if (artifact == null || !artifact.committed()) throw new IOException("No committed library artifact: " + id);
        Path source = Path.of(artifact.destination()).toAbsolutePath().normalize();
        if (!source.getParent().toRealPath().equals(library)) throw new IOException("Artifact outside configured library");
        Path destination = watched.resolve("viddyoop-" + id + ".mp4"), staged = staging.resolve(id + ".mp4");
        try (FileChannel channel = FileChannel.open(staging.resolve(id + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Delivery already running");
            var receipt = receipts.prepare(artifact, destination, staged);
            if (reconciler != null) receipt = reconciler.reconcile(id, heartbeat);
            if (receipt.outcome() == CONFIRMED || receipt.outcome() == REJECTED) {
                cleanupStage(staged, artifact.checksum(), heartbeat); return receipt;
            }
            if (receipt.publishIntentAt() != null || receipt.deliveredAt() != null) {
                // A crash may have happened just before or after the external application
                // consumed the final name. Absence cannot establish that no copy was sent.
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    verify(destination, artifact.checksum(), heartbeat); receipts.delivered(id);
                } else receipts.observe(id, AMBIGUOUS, "Watched name absent after publish intent; reconcile before any redelivery");
                var observed = receipts.find(id);
                if (observed.deliveredAt() != null) cleanupStage(staged, artifact.checksum(), heartbeat);
                return observed;
            }
            if (!allowNewCopy) return receipt;
            verify(source, artifact.checksum(), heartbeat);
            copy(source, staged, artifact.checksum(), heartbeat);
            receipts.publishIntent(id); // durable before exposing the complete name to iTunes
            try { Files.createLink(destination, staged); }
            catch (FileAlreadyExistsException e) { verify(destination, artifact.checksum(), heartbeat); }
            MediaFiles.sync(watched, true);
            receipts.delivered(id);
            cleanupStage(staged, artifact.checksum(), heartbeat);
            return receipts.find(id);
        } catch (OverlappingFileLockException e) { throw new IOException("Delivery already running", e); }
    }
    private void cleanupStage(Path staged, String checksum, Runnable heartbeat) throws IOException {
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
            // iTunes may modify the shared inode after consuming its watched name.
            // This is our private staging name; unlinking it preserves the app's name
            // and the independent authoritative library file.
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected staging entry: " + staged);
            heartbeat.run(); Files.delete(staged); MediaFiles.sync(staging, true);
        }
    }

    private void copy(Path source, Path destination, String checksum, Runnable heartbeat) throws IOException {
        Path temporary = Files.createTempFile(staging, ".copy-", ".tmp");
        try {
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS); OutputStream out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024]; int count; long last = System.nanoTime();
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (System.nanoTime() - last > 1_000_000_000L) { heartbeat.run(); last = System.nanoTime(); }
                }
            }
            verify(temporary, checksum, heartbeat); MediaFiles.sync(temporary, false);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            MediaFiles.sync(staging, true);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void verify(Path path, String checksum, Runnable heartbeat) throws IOException {
        if (!checksum.equals(MediaFiles.checksum(path, heartbeat))) throw new IOException("Delivery file conflict: " + path);
    }
}
