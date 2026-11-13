package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.activities.*;
import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.*;
import com.rkuo.viddyoop.core.status.StatusCatalog;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class FailedPipelineRetryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path database;
    private PipelineInput input;
    private final AtomicBoolean broken = new AtomicBoolean(true);
    private final AtomicInteger preprocess = new AtomicInteger(), encode = new AtomicInteger(), publish = new AtomicInteger();

    private void setup(TestWorkflowEnvironment env, String failedStage) throws Exception {
        broken.set(true); preprocess.set(0); encode.set(0); publish.set(0);
        Path root = temp.getRoot().toPath(); database = root.resolve("catalog.db");
        input = new SubmissionCatalog(database).register(root.resolve("movie.mkv"), "hash", "apple-tv-v1", "discovery",
                root.resolve("movie.mp4"), 0);
        var worker = env.newWorker("retry-test");
        worker.registerWorkflowImplementationTypes(VideoPipelineWorkflowImpl.class);
        worker.registerActivitiesImplementations(new PreprocessActivity() {
            public PreprocessResult preprocess(PipelineInput in) {
                preprocess.incrementAndGet();
                if (broken.get() && failedStage.equals("Preprocess")) throw new IllegalStateException("repair preprocessing");
                var result = new PreprocessResult(); result.durationMs = 2000; return result;
            }
        }, new EncodeActivity() {
            public EncodeResult encode(PipelineInput in, PreprocessResult pre) {
                encode.incrementAndGet();
                if (broken.get() && failedStage.equals("Encode")) throw new IllegalStateException("repair encoding");
                return new EncodeResult();
            }
        }, new PublishActivity() {
            public void publish(PipelineInput in, EncodeResult enc) {
                publish.incrementAndGet();
                if (broken.get() && failedStage.equals("Publish")) throw new IllegalStateException("repair publication");
            }
        });
        env.start();
    }
    private StatusCatalog.Submission record() throws Exception { return new StatusCatalog().read(database, input.submissionId, 1).get(0); }
    private String fail(TestWorkflowEnvironment env) {
        var stub = env.getWorkflowClient().newWorkflowStub(VideoPipelineWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue("retry-test").setWorkflowId(PipelineWorkflowStarter.workflowIdFor(input.submissionId)).build());
        Assert.assertThrows(WorkflowFailedException.class, () -> stub.run(input));
        return WorkflowStub.fromTyped(stub).getExecution().getRunId();
    }
    private void commit() throws Exception {
        Files.writeString(Path.of(input.finalPath), "committed encoded bytes");
        var artifacts = new ArtifactCatalog(database);
        artifacts.reserve(new ArtifactCatalog.Artifact(input.submissionId, input.sourcePath, input.finalPath,
                MediaFiles.checksum(Path.of(input.finalPath), () -> {}), 2000, 320, 180, 100, "test", false));
        artifacts.commit(input.submissionId);
    }
    @Test public void publicationPreviewRetainsCompletedEncodeAndRejectsStaleRun() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            setup(env, "Publish"); String run = fail(env);
            var retry = new FailedPipelineRetry(env.getWorkflowClient());
            byte[] before = Files.readAllBytes(database);
            var preview = retry.retry(record(), run, "repaired destination", true);
            Assert.assertNull(preview.newRunId()); Assert.assertEquals("Publish", preview.plan().activity());
            Assert.assertEquals(3, publish.get()); Assert.assertEquals(1, encode.get());
            Assert.assertArrayEquals(before, Files.readAllBytes(database));
            broken.set(false);
            // The Java service has no ResetWorkflowExecution RPC. Real reset execution is
            // covered by RetryCommandIntegrationTest; start another run here to check stale rejection.
            var next = env.getWorkflowClient().newWorkflowStub(VideoPipelineWorkflow.class, WorkflowOptions.newBuilder()
                    .setTaskQueue("retry-test").setWorkflowId(preview.plan().workflowId()).build());
            next.run(input);
            Assert.assertThrows(IllegalArgumentException.class, () -> retry.retry(record(), run, "old command retried", false));
            var current = env.getWorkflowClient().newUntypedWorkflowStub(preview.plan().workflowId()).describe();
            Assert.assertEquals(WorkflowStub.fromTyped(next).getExecution().getRunId(),
                    current.getRawDescription().getWorkflowExecutionInfo().getExecution().getRunId());
        }
    }
    @Test public void committedArtifactMustStillMatchBeforeCleanupPreview() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            setup(env, "Publish"); commit(); String run = fail(env);
            var retry = new FailedPipelineRetry(env.getWorkflowClient());
            Files.writeString(Path.of(input.finalPath), "unrelated replacement");
            Assert.assertThrows(IllegalArgumentException.class, () -> retry.retry(record(), run, "cleanup repaired", false));
            Files.writeString(Path.of(input.finalPath), "committed encoded bytes"); broken.set(false);
            var result = retry.retry(record(), run, "cleanup repaired", true);
            Assert.assertTrue(result.plan().artifactCommitted());
            Assert.assertNull(result.newRunId());
            Assert.assertEquals(1, encode.get());
        }
    }
    @Test public void committedArtifactCannotBeResetToEncoding() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            setup(env, "Encode"); commit(); String run = fail(env);
            Assert.assertThrows(IllegalArgumentException.class, () -> new FailedPipelineRetry(env.getWorkflowClient())
                    .retry(record(), run, "encoding repaired", false));
        }
    }
    @Test public void earlierStagePreviewsDoNotRunDownstreamActivities() throws Exception {
        for (String stage : java.util.List.of("Preprocess", "Encode")) {
            try (var env = TestWorkflowEnvironment.newInstance()) {
                setup(env, stage); String run = fail(env);
                var preview = new FailedPipelineRetry(env.getWorkflowClient()).retry(record(), run, "stage repaired", true);
                Assert.assertEquals(stage, preview.plan().activity());
                Assert.assertNull(preview.newRunId()); Assert.assertEquals(0, publish.get());
                Assert.assertEquals(stage.equals("Preprocess") ? 0 : 2, encode.get());
                Assert.assertEquals(stage.equals("Preprocess") ? 3 : 1, preprocess.get());
            }
        }
    }
    @Test public void refusesRunningWorkflowAndCatalogMismatch() throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            setup(env, "Publish"); String run = fail(env);
            var retry = new FailedPipelineRetry(env.getWorkflowClient());
            var row = record();
            var wrong = new StatusCatalog.Submission(row.id(), row.source(), row.destination() + ".other", row.profile(),
                    row.startAcknowledged(), row.artifactState(), row.checksum(), row.scratchReservedBytes(), row.delivery());
            Assert.assertThrows(IllegalArgumentException.class, () -> retry.retry(wrong, run, "wrong catalog", false));
            // A workflow with no polling worker stays running, even if its submission is registered.
            var another = new SubmissionCatalog(database).register(Path.of(input.sourcePath), "other", input.encodeProfile,
                    "new-request", Path.of(input.finalPath + ".other"), 0);
            var stub = env.getWorkflowClient().newUntypedWorkflowStub("VideoPipelineWorkflow", WorkflowOptions.newBuilder()
                    .setTaskQueue("no-worker").setWorkflowId(PipelineWorkflowStarter.workflowIdFor(another.submissionId)).build());
            String running = stub.start(another).getRunId();
            var runningRow = new StatusCatalog().read(database, another.submissionId, 1).get(0);
            Assert.assertThrows(IllegalArgumentException.class, () -> retry.retry(runningRow, running, "still running", false));
        }
    }
}
