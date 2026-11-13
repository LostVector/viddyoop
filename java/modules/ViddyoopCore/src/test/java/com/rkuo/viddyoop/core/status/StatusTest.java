package com.rkuo.viddyoop.core.status;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import com.rkuo.viddyoop.core.worker.Viddyoop;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.*;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

public class StatusTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @WorkflowInterface public interface FailingWorkflow { @WorkflowMethod void run(); }
    public static class FailingWorkflowImpl implements FailingWorkflow {
        public void run() { throw ApplicationFailure.newNonRetryableFailure("bad input", "INVALID_MEDIA"); }
    }

    @Test public void offlineJsonReportsCatalogWithoutChangingIt() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var catalog = new SubmissionCatalog(database);
        var input = catalog.register(Path.of("/inbox/movie\tname.mkv"), "hash", "apple-tv-v1", "discovery",
                Path.of("/library/movie.mp4"), 0);
        var reservations = new ScratchReservations(database);
        Assert.assertTrue(reservations.reserve(input.submissionId, 400, 1000, 100));
        Assert.assertEquals(400, new StatusCatalog().read(database, null, 10).get(0).scratchReservedBytes());
        var artifacts = new ArtifactCatalog(database);
        artifacts.reserve(new ArtifactCatalog.Artifact(input.submissionId, input.sourcePath, input.finalPath,
                "output-hash", 2000, 320, 180, 100, "test", false));
        artifacts.commit(input.submissionId);
        var add=temp.newFolder("add").toPath(); var stage=temp.newFolder("stage").toPath();
        new com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog(database).prepare(artifacts.expected(input.submissionId),
                add.resolve("copy.mp4"),stage.resolve("copy.mp4"));
        byte[] before = Files.readAllBytes(database);
        StringWriter output = new StringWriter();
        int exit = new CommandLine(new Viddyoop()).setOut(new PrintWriter(output)).execute(
                "status", "--database", database.toString(), "--offline", "--json");
        Assert.assertEquals(0, exit);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString());
        Assert.assertEquals(0, json.get(0).path("submission").path("scratchReservedBytes").asLong());
        Assert.assertEquals("PREPARED",json.get(0).path("submission").path("delivery").path("outcome").asText());
        Assert.assertEquals("committed", json.get(0).path("submission").path("artifactState").asText());
        Assert.assertEquals(input.sourcePath, json.get(0).path("submission").path("source").asText());
        Assert.assertEquals("OFFLINE", json.get(0).path("execution").path("state").asText());
        Assert.assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test public void missingDatabaseIsNotCreatedAndEarlyCatalogCanBeRead() throws Exception {
        Path database = temp.getRoot().toPath().resolve("missing.db");
        Assert.assertThrows(IOException.class, () -> new StatusCatalog().read(database, null, 10));
        Assert.assertFalse(Files.exists(database));
        new SubmissionCatalog(database).register(Path.of("/inbox/movie.mkv"), "hash", "profile", "request",
                Path.of("/library/movie.mp4"), 0);
        Assert.assertEquals("unregistered", new StatusCatalog().read(database, null, 10).get(0).artifactState());
    }

    @Test public void unavailableTemporalProducesPartialJsonAndNonzeroExit() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        new SubmissionCatalog(database).register(Path.of("/inbox/movie.mkv"), "hash", "profile", "request",
                Path.of("/library/movie.mp4"), 0);
        StringWriter output = new StringWriter();
        int exit = new CommandLine(new Viddyoop()).setOut(new PrintWriter(output)).execute(
                "status", "--database", database.toString(), "--temporal", "127.0.0.1:1", "--json");
        Assert.assertEquals(2, exit);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString());
        Assert.assertEquals("UNAVAILABLE", json.get(0).path("execution").path("state").asText());
        Assert.assertEquals("unregistered", json.get(0).path("submission").path("artifactState").asText());
    }

    @Test public void liveProbeDistinguishesNotStartedRunningTerminalAndFailed() throws Exception {
        Path database = temp.getRoot().toPath().resolve("catalog.db");
        var catalog = new SubmissionCatalog(database);
        var input = catalog.register(Path.of("/inbox/movie.mkv"), "hash", "profile", "request",
                Path.of("/library/movie.mp4"), 0);
        var record = new StatusCatalog().read(database, null, 10).get(0);
        try (var env = TestWorkflowEnvironment.newInstance()) {
            env.newWorker("status-test").registerWorkflowImplementationTypes(FailingWorkflowImpl.class,
                    com.rkuo.viddyoop.core.pipeline.VideoPipelineWorkflowImpl.class);
            env.start();
            var client = env.getWorkflowClient();
            var probe = new WorkflowStatus(client);
            Assert.assertEquals("NOT_STARTED", probe.inspect(record).state());
            var unprocessed = client.newUntypedWorkflowStub("unregistered-type", WorkflowOptions.newBuilder()
                    .setTaskQueue("no-worker").setWorkflowId(PipelineWorkflowStarter.workflowIdFor(input.submissionId)).build());
            unprocessed.start();
            Assert.assertEquals("RUNNING", probe.inspect(record).state());
            unprocessed.terminate("test termination");
            Assert.assertEquals("TERMINATED", probe.inspect(record).state());
            var waiting = catalog.register(Path.of("/inbox/waiting.mkv"), "waiting", "profile", "request",
                    Path.of("/library/waiting.mp4"), 0);
            new PipelineWorkflowStarter(client, "status-test").start(waiting);
            var waitingRecord = new StatusCatalog().read(database, waiting.submissionId, 1).get(0);
            WorkflowStatus.Execution pending = null;
            for (int i = 0; i < 100; i++) {
                pending = probe.inspect(waitingRecord);
                if (!pending.stage().isEmpty()) break;
                Thread.sleep(10);
            }
            Assert.assertNotNull(pending);
            Assert.assertEquals("RUNNING", pending.state());
            Assert.assertEquals("Preprocess", pending.stage());
            var failed = catalog.register(Path.of("/inbox/invalid.mkv"), "other", "profile", "request",
                    Path.of("/library/invalid.mp4"), 0);
            var stub = client.newWorkflowStub(FailingWorkflow.class, WorkflowOptions.newBuilder()
                    .setTaskQueue("status-test").setWorkflowId(PipelineWorkflowStarter.workflowIdFor(failed.submissionId)).build());
            Assert.assertThrows(WorkflowFailedException.class, stub::run);
            var status = probe.inspect(new StatusCatalog().read(database, failed.submissionId, 1).get(0));
            Assert.assertEquals("FAILED", status.state());
            Assert.assertEquals(WorkflowStub.fromTyped(stub).getExecution().getRunId(), status.runId());
            Assert.assertTrue(status.failure(), status.failure().contains("bad input"));
        }
    }
}
