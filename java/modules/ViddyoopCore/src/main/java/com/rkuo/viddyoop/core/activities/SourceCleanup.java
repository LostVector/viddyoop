package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.ingest.SourceStager;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.UUID;

/** Deletes only the isolated ingested generation, after verifying the committed library artifact. */
public final class SourceCleanup {
    private final ArtifactCatalog catalog;
    private final Path inbox;
    public SourceCleanup(ArtifactCatalog catalog, Path inbox) throws IOException {
        this.catalog = catalog; this.inbox = inbox.toRealPath();
    }

    public void cleanup(PipelineInput input, Runnable heartbeat) throws IOException, SQLException {
        UUID.fromString(input.submissionId);
        var artifact = catalog.expected(input.submissionId);
        if (artifact == null || !artifact.committed() || !artifact.source().equals(input.sourcePath)
                || !artifact.destination().equals(Path.of(input.finalPath).toAbsolutePath().normalize().toString())
                || !artifact.checksum().equals(MediaFiles.checksum(Path.of(artifact.destination()), heartbeat))) {
            throw new IOException("Cleanup requires a verified committed artifact");
        }
        // Old histories lack a source identity receipt. Preserve their originals.
        if (input.sourceGeneration == null) return;
        Path source = Path.of(input.sourcePath).toAbsolutePath().normalize();
        if (!source.getParent().toRealPath().equals(inbox)) throw new IOException("Cleanup source outside inbox");
        Path root = inbox.resolve(".viddyoop-cleanup");
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Cleanup directory cannot be a symlink");
        Path job = root.resolve(input.submissionId);
        Files.createDirectories(job);
        if (Files.isSymbolicLink(job)) throw new IOException("Cleanup job directory cannot be a symlink");
        MediaFiles.sync(root, true); MediaFiles.sync(inbox, true);
        try (FileChannel channel = FileChannel.open(job.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Cleanup already running");
            Path done = job.resolve("done");
            if (Files.exists(done, LinkOption.NOFOLLOW_LINKS)) return;
            Path held = job.resolve("source");
            String outcome;
            if (!Files.exists(held, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) outcome = "absent";
                else if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        || !SourceStager.matches(source, source, input.sourceFingerprint, input.sourceGeneration, heartbeat)) {
                    outcome = "replacement-preserved";
                } else {
                    Files.move(source, held, StandardCopyOption.ATOMIC_MOVE);
                    MediaFiles.sync(inbox, true); MediaFiles.sync(job, true);
                    outcome = finishClaim(input, source, held, heartbeat);
                }
            } else outcome = finishClaim(input, source, held, heartbeat);
            Path receipt = job.resolve("receipt.tmp");
            Files.writeString(receipt, outcome + "\n"); MediaFiles.sync(receipt, false);
            Files.move(receipt, done, StandardCopyOption.ATOMIC_MOVE);
            MediaFiles.sync(job, true);
        } catch (OverlappingFileLockException e) { throw new IOException("Cleanup already running", e); }
    }

    private String finishClaim(PipelineInput input, Path source, Path held, Runnable heartbeat) throws IOException {
        if (!Files.isRegularFile(held, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Nonregular claimed source retained for review: " + held);
        if (SourceStager.matches(held, source, input.sourceFingerprint, input.sourceGeneration, heartbeat)) {
            Files.delete(held); MediaFiles.sync(held.getParent(), true);
            return "deleted";
        }
        // A producer may have replaced the name between the precheck and rename.
        // Restore with create-if-absent; never overwrite a newer file at the inbox path.
        try { Files.createLink(source, held); }
        catch (FileAlreadyExistsException e) { throw new IOException("Replacement retained for review: " + held, e); }
        MediaFiles.sync(inbox, true);
        Files.delete(held); MediaFiles.sync(held.getParent(), true);
        return "replacement-restored";
    }
}
