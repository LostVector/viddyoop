package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SourceCleanupTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path inbox, source, output, database;
    private PipelineInput input;
    private ArtifactCatalog artifacts;
    private SourceCleanup cleanup;

    @Before public void setup() throws Exception {
        Path root = temp.getRoot().toPath(); inbox = root.resolve("inbox"); Files.createDirectories(inbox);
        source = inbox.resolve("movie.mkv"); Files.writeString(source, "original bytes");
        output = root.resolve("movie.mp4"); Files.writeString(output, "encoded bytes");
        database = root.resolve("catalog.db");
        input = new IngestCoordinator(new SourceStager(root.resolve("work")), new SubmissionCatalog(database))
                .accept(source, "apple-tv-v1", "discovery", output, 0);
        // Reopen registration to exercise persistence of the cleanup identity.
        input = new SubmissionCatalog(database).pendingStarts().get(0);
        artifacts = new ArtifactCatalog(database);
        artifacts.reserve(new ArtifactCatalog.Artifact(input.submissionId, input.sourcePath, input.finalPath,
                MediaFiles.checksum(output, () -> {}), 1000, 320, 180, 100, "test", false));
        cleanup = new SourceCleanup(artifacts, inbox);
    }

    private Path held() throws Exception {
        Path held = inbox.resolve(".viddyoop-cleanup").resolve(input.submissionId).resolve("source");
        Files.createDirectories(held.getParent()); return held;
    }

    @Test public void uncommittedOrDamagedPublicationNeverDeletesSource() throws Exception {
        Assert.assertThrows(java.io.IOException.class, () -> cleanup.cleanup(input, () -> {}));
        artifacts.commit(input.submissionId);
        Files.writeString(output, "truncated");
        Assert.assertThrows(java.io.IOException.class, () -> cleanup.cleanup(input, () -> {}));
        Assert.assertEquals("original bytes", Files.readString(source));
    }

    @Test public void deletesOnlyAfterPublicationAndReceiptProtectsLaterReplacement() throws Exception {
        artifacts.commit(input.submissionId);
        cleanup.cleanup(input, () -> {});
        Assert.assertFalse(Files.exists(source));
        Assert.assertTrue(Files.exists(Path.of(input.stagedSourcePath)));
        Files.writeString(source, "new arrival");
        new SourceCleanup(new ArtifactCatalog(database), inbox).cleanup(input, () -> {});
        Assert.assertEquals("new arrival", Files.readString(source));
    }

    @Test public void replacementWithSameBytesIsPreserved() throws Exception {
        artifacts.commit(input.submissionId);
        Files.move(source, inbox.resolve("previous.mkv"));
        Files.writeString(source, "original bytes");
        cleanup.cleanup(input, () -> {});
        Assert.assertEquals("original bytes", Files.readString(source));
        Assert.assertEquals("original bytes", Files.readString(inbox.resolve("previous.mkv")));
    }

    @Test public void crashAfterClaimResumesWithoutTouchingNewInboxFile() throws Exception {
        artifacts.commit(input.submissionId);
        Path claimed = held(); Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(source, "new arrival");
        cleanup.cleanup(input, () -> {});
        Assert.assertFalse(Files.exists(claimed));
        Assert.assertEquals("new arrival", Files.readString(source));
    }

    @Test public void racedReplacementIsRestoredInsteadOfDeleted() throws Exception {
        artifacts.commit(input.submissionId);
        Files.writeString(source, "replacement before claim");
        Path claimed = held(); Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
        cleanup.cleanup(input, () -> {});
        Assert.assertEquals("replacement before claim", Files.readString(source));
        Assert.assertFalse(Files.exists(claimed));
    }

    @Test public void conflictingRestorationRetainsBothFilesForReview() throws Exception {
        artifacts.commit(input.submissionId);
        Files.writeString(source, "raced replacement");
        Path claimed = held(); Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(source, "another arrival");
        Assert.assertThrows(java.io.IOException.class, () -> cleanup.cleanup(input, () -> {}));
        Assert.assertEquals("another arrival", Files.readString(source));
        Assert.assertEquals("raced replacement", Files.readString(claimed));
    }

    @Test public void symlinkReplacementIsPreservedWithoutTouchingItsTarget() throws Exception {
        artifacts.commit(input.submissionId);
        Files.delete(source); Files.createSymbolicLink(source, output);
        cleanup.cleanup(input, () -> {});
        Assert.assertTrue(Files.isSymbolicLink(source));
        Assert.assertEquals("encoded bytes", Files.readString(output));
    }

    @Test public void oldHistoryWithoutGenerationRetainsOriginal() throws Exception {
        artifacts.commit(input.submissionId);
        input.sourceGeneration = null;
        cleanup.cleanup(input, () -> {});
        Assert.assertTrue(Files.exists(source));
    }
}
