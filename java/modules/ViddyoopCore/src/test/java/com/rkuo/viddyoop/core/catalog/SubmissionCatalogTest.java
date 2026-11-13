package com.rkuo.viddyoop.core.catalog;

import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import com.rkuo.viddyoop.core.ingest.SubmissionDispatcher;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import io.temporal.testing.TestWorkflowEnvironment;
import java.nio.file.Path;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SubmissionCatalogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path database;
    private SubmissionCatalog catalog;

    @Before public void setup() throws Exception {
        database = temp.getRoot().toPath().resolve("catalog.db");
        catalog = new SubmissionCatalog(database);
    }

    private PipelineInput register(String fingerprint, String profile, String request) throws Exception {
        return catalog.register(Path.of("/inbox/movie.mkv"), fingerprint, profile,
                request, Path.of("/library/movie.m4v"), 0);
    }

    @Test public void registrationsSurviveRestartAndSeparateIdentities() throws Exception {
        PipelineInput original = register("sha256:first", "h264-v1", "discovery");
        catalog = new SubmissionCatalog(database);
        Assert.assertEquals(original.submissionId, register("sha256:first", "h264-v1", "discovery").submissionId);
        Assert.assertNotEquals(original.submissionId, register("sha256:replacement", "h264-v1", "discovery").submissionId);
        Assert.assertNotEquals(original.submissionId, register("sha256:first", "h264-v2", "discovery").submissionId);
        PipelineInput reencode = register("sha256:first", "h264-v1", "manual-request-1");
        Assert.assertNotEquals(original.submissionId, reencode.submissionId);
        Assert.assertEquals(reencode.submissionId, register("sha256:first", "h264-v1", "manual-request-1").submissionId);
        Assert.assertEquals(4, catalog.pendingStarts().size());
    }

    @Test public void conflictingRequestDoesNotSilentlyChangeDestination() throws Exception {
        register("hash", "profile", "discovery");
        Assert.assertThrows(IllegalArgumentException.class, () -> catalog.register(
                Path.of("/inbox/movie.mkv"), "hash", "profile", "discovery", Path.of("/other.m4v"), 0));
        Assert.assertEquals("/library/movie.m4v", catalog.pendingStarts().get(0).finalPath);
    }

    @Test public void failedStartRemainsPending() throws Exception {
        register("hash", "profile", "discovery");
        PipelineWorkflowStarter unavailable = new PipelineWorkflowStarter(null, "test") {
            @Override public boolean start(PipelineInput input) { throw new IllegalStateException("unavailable"); }
        };
        Assert.assertThrows(IllegalStateException.class,
                () -> new SubmissionDispatcher(catalog, unavailable).dispatchPending());
        Assert.assertEquals(1, new SubmissionCatalog(database).pendingStarts().size());
    }

    @Test public void startBeforeCrashReconcilesAgainstRealTemporalExecution() throws Exception {
        PipelineInput input = register("hash", "profile", "discovery");
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            env.start();
            PipelineWorkflowStarter starter = new PipelineWorkflowStarter(env.getWorkflowClient(), "test");
            Assert.assertTrue(starter.start(input));
            // Simulate crash after Temporal accepted the start but before catalog acknowledgement.
            catalog = new SubmissionCatalog(database);
            new SubmissionDispatcher(catalog, starter).dispatchPending();
            Assert.assertTrue(catalog.pendingStarts().isEmpty());
            Assert.assertFalse(starter.start(input));
            env.getWorkflowClient().newUntypedWorkflowStub(
                    PipelineWorkflowStarter.workflowIdFor(input.submissionId)).terminate("test terminal execution");
            // Reconciliation must not resurrect a terminal submission either.
            Assert.assertFalse(starter.start(input));
        }
    }
}
