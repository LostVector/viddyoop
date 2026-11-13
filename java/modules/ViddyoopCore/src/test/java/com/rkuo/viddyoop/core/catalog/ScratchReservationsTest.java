package com.rkuo.viddyoop.core.catalog;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ScratchReservationsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void survivesRestartAndCommitReleasesCapacityExactlyOnce() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var reservations = new ScratchReservations(database);
        Assert.assertTrue(reservations.reserve("job-1", 400, 1000, 100));
        Assert.assertEquals(500, new ScratchReservations(database).available(1000, 100));
        Assert.assertFalse(reservations.reserve("job-2", 600, 1000, 100));
        Assert.assertTrue(reservations.reserve("job-1", 400, 0, 100)); // no double reservation after restart
        var artifacts = new ArtifactCatalog(database);
        artifacts.reserve(new ArtifactCatalog.Artifact("job-1", "/source", "/final", "hash", 1000, 320, 180, 100, "test", false));
        Assert.assertEquals(500, reservations.available(1000, 100)); // reservation alone is not success
        artifacts.commit("job-1");
        artifacts.commit("job-1");
        Assert.assertEquals(900, reservations.available(1000, 100));
        Assert.assertTrue(reservations.reserve("job-2", 600, 1000, 100));
        // Retained files still reduce the filesystem's usable count independently.
        Assert.assertEquals(0, reservations.available(500, 100));
    }

    @Test public void failedStartKeepsReservationAndRestartAcknowledgesWithoutDoubleAllocation() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var catalog = new SubmissionCatalog(database);
        var input = catalog.register(Path.of("/inbox/movie.mkv"), "hash", "profile", "discovery", Path.of("/library/movie.mp4"), 0);
        var reservations = new ScratchReservations(database);
        var failedStart = new com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter(null, "queue") {
            @Override public boolean start(com.rkuo.viddyoop.core.pipeline.PipelineInput job) {
                throw new IllegalStateException("ambiguous transport failure");
            }
        };
        var dispatcher = new com.rkuo.viddyoop.core.ingest.SubmissionDispatcher(catalog, failedStart,
                job -> reservations.reserve(job.submissionId, 400, 1000, 100));
        Assert.assertThrows(IllegalStateException.class, dispatcher::dispatchPending);
        Assert.assertEquals(500, reservations.available(1000, 100));
        Assert.assertEquals(1, catalog.pendingStarts().size());
        var alreadyStarted = new com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter(null, "queue") {
            @Override public boolean start(com.rkuo.viddyoop.core.pipeline.PipelineInput job) { return false; }
        };
        new com.rkuo.viddyoop.core.ingest.SubmissionDispatcher(new SubmissionCatalog(database), alreadyStarted,
                job -> new ScratchReservations(database).reserve(job.submissionId, 400, 0, 100)).dispatchPending();
        Assert.assertTrue(catalog.pendingStarts().isEmpty());
        Assert.assertEquals(500, reservations.available(1000, 100));
        Assert.assertTrue(reservations.hasReservation(input.submissionId));
    }

    @Test public void capacityGateDefersQueuedJobUntilPublicationReleasesAllocation() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var catalog = new SubmissionCatalog(database);
        catalog.register(Path.of("/inbox/first.mkv"), "first", "profile", "discovery", Path.of("/library/first.mp4"), 0);
        catalog.register(Path.of("/inbox/second.mkv"), "second", "profile", "discovery", Path.of("/library/second.mp4"), 0);
        var reservations = new ScratchReservations(database);
        var started = new java.util.ArrayList<com.rkuo.viddyoop.core.pipeline.PipelineInput>();
        var starter = new com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter(null, "queue") {
            @Override public boolean start(com.rkuo.viddyoop.core.pipeline.PipelineInput job) { started.add(job); return true; }
        };
        var dispatcher = new com.rkuo.viddyoop.core.ingest.SubmissionDispatcher(catalog, starter,
                job -> reservations.reserve(job.submissionId, 600, 1000, 100));
        dispatcher.dispatchPending();
        Assert.assertEquals(1, started.size());
        Assert.assertEquals(1, catalog.pendingStarts().size());
        var artifacts = new ArtifactCatalog(database);
        var first = started.get(0);
        artifacts.reserve(new ArtifactCatalog.Artifact(first.submissionId, first.sourcePath, first.finalPath,
                "hash", 1000, 320, 180, 100, "test", false));
        artifacts.commit(first.submissionId);
        dispatcher.dispatchPending();
        Assert.assertEquals(2, started.size());
        Assert.assertTrue(catalog.pendingStarts().isEmpty());
    }

    @Test public void simultaneousAdmissionsCannotOversubscribe() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var first = new ScratchReservations(database);
        var second = new ScratchReservations(database);
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            Future<Boolean> a = executor.submit(() -> { ready.countDown(); go.await(); return first.reserve("a", 600, 1000, 100); });
            Future<Boolean> b = executor.submit(() -> { ready.countDown(); go.await(); return second.reserve("b", 600, 1000, 100); });
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            Assert.assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            Assert.assertEquals(300, first.available(1000, 100));
        } finally { go.countDown(); executor.shutdownNow(); }
    }

    @Test public void rejectsOverflowAndInvalidBudgetsWithoutLeakingCapacity() throws Exception {
        var reservations = new ScratchReservations(temp.getRoot().toPath().resolve("catalog.db"));
        Assert.assertThrows(ArithmeticException.class, () -> ScratchReservations.estimate(Long.MAX_VALUE, 4));
        Assert.assertThrows(IllegalArgumentException.class, () -> reservations.reserve("job", -1, 1000, 0));
        Assert.assertTrue(reservations.reserve("job", 100, 1000, 0));
        Assert.assertThrows(SQLException.class, () -> reservations.reserve("job", 200, 1000, 0));
        Assert.assertEquals(900, reservations.available(1000, 0));
    }
}
