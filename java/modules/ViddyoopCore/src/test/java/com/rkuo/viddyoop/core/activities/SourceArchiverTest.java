package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SourceArchiverTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root, archive, source;
    private PipelineInput input;
    private SourceArchiver archiver;
    @Before public void setup() throws Exception {
        root = temp.getRoot().toPath(); archive = root.resolve("archive");
        source = root.resolve("movie.mkv"); Files.writeString(source, "original source");
        var catalog = new SubmissionCatalog(root.resolve("catalog.db"));
        input = new IngestCoordinator(new SourceStager(root.resolve("work")), catalog).accept(
                source, "apple-tv-v1", "discovery", root.resolve("movie.mp4"), 0, "movies", archive);
        input = new SubmissionCatalog(root.resolve("catalog.db")).pendingStarts().get(0);
        archiver = new SourceArchiver(archive);
    }
    @Test public void durationThresholdAndResolutionBucketsMatchLegacyRules() throws Exception {
        Assert.assertNull(archiver.archive(input, SourceArchiver.MOVIE_THRESHOLD_MS, 1280, 720, () -> {}));
        Assert.assertFalse(Files.exists(archive));
        Path archived = archiver.archive(input, SourceArchiver.MOVIE_THRESHOLD_MS + 1, 1280, 720, () -> {});
        Assert.assertEquals(archive.resolve("720p/movie.mkv"), archived);
        Assert.assertEquals("original source", Files.readString(archived));
        Path fullHd = archiver.archive(input, SourceArchiver.MOVIE_THRESHOLD_MS + 1, 1281, 720, () -> {});
        Assert.assertEquals(archive.resolve("1080p/movie.mkv"), fullHd);
    }
    @Test public void retryReconcilesArchiveEvenIfSnapshotIsMissing() throws Exception {
        Path archived = archiver.archive(input, 5000000, 1920, 1080, () -> {});
        Files.delete(Path.of(input.stagedSourcePath));
        Assert.assertEquals(archived, new SourceArchiver(archive).archive(input, 5000000, 1920, 1080, () -> {}));
        Assert.assertTrue(Files.exists(source));
    }
    @Test public void conflictAndCorruptSnapshotDoNotOverwriteArchiveOrSource() throws Exception {
        Files.writeString(Path.of(input.stagedSourcePath), "truncated");
        Assert.assertThrows(java.io.IOException.class, () -> archiver.archive(input, 5000000, 1920, 1080, () -> {}));
        Assert.assertFalse(Files.exists(archive.resolve("1080p/movie.mkv")));
        Files.writeString(archive.resolve("1080p/movie.mkv"), "unrelated archive");
        Assert.assertThrows(java.io.IOException.class, () -> archiver.archive(input, 5000000, 1920, 1080, () -> {}));
        Assert.assertEquals("unrelated archive", Files.readString(archive.resolve("1080p/movie.mkv")));
        Assert.assertEquals("original source", Files.readString(source));
    }
    @Test public void failedArchiveBlocksPublicationAndCleanupUntilRetrySucceeds() throws Exception {
        var artifacts = new com.rkuo.viddyoop.core.catalog.ArtifactCatalog(root.resolve("catalog.db"));
        Path encoded = root.resolve("encoded.mp4"); Files.writeString(encoded, "verified encoded bytes");
        var result = new com.rkuo.viddyoop.core.pipeline.EncodeResult();
        result.encodedPath = encoded.toString();
        result.checksum = com.rkuo.viddyoop.core.media.MediaFiles.checksum(encoded, () -> {});
        result.durationMs = 5000000; result.width = 1920; result.height = 1080; result.machineName = "test";
        result.sourceDurationMs = 5000000; result.sourceWidth = 1920; result.sourceHeight = 1080;
        var runner = new com.rkuo.viddyoop.core.media.MediaProcessRunner();
        var activities = new NativeMediaActivities(root.resolve("work"),
                new com.rkuo.viddyoop.core.media.HandBrakeAdapter("unused", runner), new ArtifactPublisher(artifacts, root),
                new com.rkuo.viddyoop.core.media.MediaNormalizer("unused", "unused", "unused", runner),
                new SourceCleanup(artifacts, root), archiver);
        var env = io.temporal.testing.TestActivityEnvironment.newInstance();
        try {
            env.registerActivitiesImplementations(activities);
            var publish = env.newActivityStub(PublishActivity.class);
            Files.writeString(Path.of(input.stagedSourcePath), "damaged snapshot");
            Assert.assertThrows(RuntimeException.class, () -> publish.publish(input, result));
            Assert.assertNull(artifacts.expected(input.submissionId));
            Assert.assertTrue(Files.exists(source));
            Files.writeString(Path.of(input.stagedSourcePath), "original source");
            publish.publish(input, result);
            Assert.assertTrue(artifacts.expected(input.submissionId).committed());
            Assert.assertFalse(Files.exists(source));
            Assert.assertEquals("original source", Files.readString(archive.resolve("1080p/movie.mkv")));
        } finally { env.close(); }
    }

    @Test public void rediscoveryCannotSilentlyChangeRegisteredArchivePolicy() throws Exception {
        var coordinator = new IngestCoordinator(new SourceStager(root.resolve("work")),
                new SubmissionCatalog(root.resolve("catalog.db")));
        Assert.assertThrows(IllegalArgumentException.class, () -> coordinator.accept(source, "apple-tv-v1",
                "discovery", root.resolve("movie.mp4"), 0, "none", null));
    }

    @Test public void rejectsChangedRootAndSupportsExplicitOptOut() throws Exception {
        Assert.assertThrows(java.io.IOException.class, () -> new SourceArchiver(root.resolve("other")).archive(
                input, 5000000, 1920, 1080, () -> {}));
        input.archivePolicy = "none";
        Assert.assertNull(archiver.archive(input, 5000000, 1920, 1080, () -> {}));
    }
}
