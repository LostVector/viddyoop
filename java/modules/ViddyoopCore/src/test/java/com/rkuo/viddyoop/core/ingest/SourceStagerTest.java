package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SourceStagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void repeatedDiscoverySurvivesRestartAndReplacementCannotMutateSnapshot() throws Exception {
        Path root = temp.getRoot().toPath();
        Path source = root.resolve("movie.mkv");
        Path database = root.resolve("catalog.db");
        Files.writeString(source, "original");
        var catalog = new SubmissionCatalog(database);
        var coordinator = new IngestCoordinator(new SourceStager(root.resolve("work")), catalog);
        var first = coordinator.accept(source, "h264-v1", "discovery", root.resolve("movie.m4v"), 0);
        var restarted = new IngestCoordinator(new SourceStager(root.resolve("work")), new SubmissionCatalog(database));
        var repeat = restarted.accept(source, "h264-v1", "discovery", root.resolve("movie.m4v"), 0);
        Assert.assertEquals(first.submissionId, repeat.submissionId);
        Files.writeString(source, "replaced");
        Assert.assertEquals("original", Files.readString(Path.of(first.stagedSourcePath)));
        var replacement = restarted.accept(source, "h264-v1", "discovery", root.resolve("movie.m4v"), 0);
        Assert.assertNotEquals(first.submissionId, replacement.submissionId);
        Assert.assertEquals("replaced", Files.readString(Path.of(replacement.stagedSourcePath)));
        Assert.assertEquals(2, catalog.pendingStarts().size());
    }

    @Test public void orphanSnapshotCanBeRegisteredAfterCrashAndExplicitRequestGetsNewIdentity() throws Exception {
        Path root = temp.getRoot().toPath();
        Path source = root.resolve("movie.mkv");
        Files.writeString(source, "original");
        var stager = new SourceStager(root.resolve("work"));
        var snapshot = stager.stage(source); // crash before registration
        var catalog = new SubmissionCatalog(root.resolve("catalog.db"));
        var coordinator = new IngestCoordinator(stager, catalog);
        var recovered = coordinator.accept(source, "h264-v1", "discovery", root.resolve("movie.m4v"), 0);
        Assert.assertEquals(snapshot.staged().toString(), recovered.stagedSourcePath);
        var explicit = coordinator.accept(source, "h264-v1", "request-1", root.resolve("movie.m4v"), 0);
        Assert.assertNotEquals(recovered.submissionId, explicit.submissionId);
        Assert.assertEquals(explicit.submissionId,
                coordinator.accept(source, "h264-v1", "request-1", root.resolve("movie.m4v"), 0).submissionId);
    }

    @Test public void invalidMediaIsNotRegisteredOrDeleted() throws Exception {
        Path root = temp.getRoot().toPath();
        Path source = root.resolve("movie.mkv");
        Files.writeString(source, "not valid media");
        var catalog = new SubmissionCatalog(root.resolve("catalog.db"));
        var coordinator = new IngestCoordinator(new SourceStager(root.resolve("work")), catalog,
                snapshot -> { throw new java.io.IOException("no valid title"); });
        Assert.assertThrows(java.io.IOException.class,
                () -> coordinator.accept(source, "apple-tv-v1", "discovery", root.resolve("movie.mp4"), 0));
        Assert.assertTrue(catalog.pendingStarts().isEmpty());
        Assert.assertEquals("not valid media", Files.readString(source));
    }

    @Test public void rejectsSymlinksAndLeavesNoPartialSnapshot() throws Exception {
        Path root = temp.getRoot().toPath();
        Path source = root.resolve("movie.mkv");
        Files.writeString(source, "original");
        Path link = root.resolve("link.mkv");
        Files.createSymbolicLink(link, source);
        var stager = new SourceStager(root.resolve("work"));
        Assert.assertThrows(java.io.IOException.class, () -> stager.stage(link));
        try (var files = Files.list(root.resolve("work/ingest"))) {
            Assert.assertEquals(0, files.count());
        }
        Assert.assertEquals("original", Files.readString(source));
    }
}
