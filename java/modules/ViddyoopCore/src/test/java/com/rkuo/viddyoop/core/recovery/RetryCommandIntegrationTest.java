package com.rkuo.viddyoop.core.recovery;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Opt-in packaged CLI/real-server test. Uses production activities with shortened retry timing. */
public class RetryCommandIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void publicationConflictRetryRetainsCompletedEncode() throws Exception { exercise(false); }
    @Test public void cleanupRetryNeedsNoEncodedIntermediateAndKeepsOneStat() throws Exception { exercise(true); }

    private void exercise(boolean cleanupFailure) throws Exception {
        String target = System.getProperty("viddyoop.temporal"), jar = System.getProperty("viddyoop.jar");
        String hb = System.getProperty("viddyoop.handbrake"), fixture = System.getProperty("viddyoop.fixture");
        String merge = System.getProperty("viddyoop.mkvmerge"), extract = System.getProperty("viddyoop.mkvextract"), ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue(target != null && jar != null && hb != null && fixture != null && merge != null && extract != null && ffmpeg != null);
        Path root = temp.getRoot().toPath(); Files.createDirectories(root.resolve("inbox")); Files.createDirectories(root.resolve("library"));
        Path original = root.resolve("inbox/movie.mkv"), destination = root.resolve("library/movie.mp4"), db = root.resolve("catalog.db");
        Files.copy(Path.of(fixture), original);
        Path blocker = cleanupFailure ? root.resolve("inbox/.viddyoop-cleanup") : destination;
        Files.writeString(blocker, "fixture conflict");
        var input = new IngestCoordinator(new SourceStager(root.resolve("work")), new SubmissionCatalog(db))
                .accept(original, "apple-tv-v1", "discovery", destination, 0);
        Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), RecoveryWorkerMain.class.getName(), target, root.toString(),
                hb, merge, extract, ffmpeg, "normal").redirectErrorStream(true).redirectOutput(root.resolve("worker.log").toFile()).start();
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try {
            var client = WorkflowClient.newInstance(service);
            Assert.assertTrue(new PipelineWorkflowStarter(client, "recovery").start(input));
            String id = PipelineWorkflowStarter.workflowIdFor(input.submissionId);
            var failed = client.newUntypedWorkflowStub(id);
            Assert.assertThrows(WorkflowFailedException.class, () -> failed.getResult(45, TimeUnit.SECONDS, PipelineResult.class));
            String failedRun = failed.describe().getRawDescription().getWorkflowExecutionInfo().getExecution().getRunId();
            Assert.assertTrue(Files.exists(original));
            var catalog = new ArtifactCatalog(db);
            Assert.assertEquals(cleanupFailure, catalog.expected(input.submissionId).committed());
            List<Path> encoded;
            try (var files = Files.walk(root.resolve("work"))) {
                encoded = files.filter(path -> path.getFileName().toString().equals("encoded.mp4")).toList();
            }
            Assert.assertEquals(1, encoded.size());
            if (cleanupFailure) Files.delete(encoded.get(0));
            Files.delete(blocker); // Remove only this test's injected destination/cleanup conflict.
            byte[] before = Files.readAllBytes(db);
            var preview = invoke(jar, target, db, input.submissionId, failedRun, true, root, "preview", 0);
            Assert.assertEquals("Publish", preview.path("plan").path("activity").asText());
            Assert.assertTrue(preview.path("newRunId").isNull());
            Assert.assertArrayEquals(before, Files.readAllBytes(db));
            var retried = invoke(jar, target, db, input.submissionId, failedRun, false, root, "retry", 0);
            Assert.assertNotEquals(failedRun, retried.path("newRunId").asText());
            var result = client.newUntypedWorkflowStub(id).getResult(30, TimeUnit.SECONDS, PipelineResult.class);
            Assert.assertEquals(input.finalPath, result.finalPath);
            var artifact = catalog.expected(input.submissionId);
            Assert.assertTrue(artifact.committed());
            Assert.assertEquals(artifact.checksum(), MediaFiles.checksum(destination, () -> {}));
            Assert.assertFalse(Files.exists(original));
            // Resetting publication must not produce another encode attempt, even if its file is absent.
            try (var files = Files.walk(root.resolve("work"))) {
                Assert.assertEquals(1, files.filter(path -> path.getFileName().toString().equals("encode.log")).count());
            }
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
                 var r = s.executeQuery("SELECT count(*) FROM encode_stats")) {
                Assert.assertTrue(r.next()); Assert.assertEquals(1, r.getInt(1));
            }
            invoke(jar, target, db, input.submissionId, failedRun, false, root, "stale", 1);
            Assert.assertEquals(retried.path("newRunId").asText(), client.newUntypedWorkflowStub(id).describe()
                    .getRawDescription().getWorkflowExecutionInfo().getExecution().getRunId());
        } finally {
            worker.descendants().forEach(ProcessHandle::destroyForcibly); worker.destroyForcibly();
            worker.waitFor(10, TimeUnit.SECONDS); service.shutdownNow();
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode invoke(String jar, String target, Path db, String id, String run,
            boolean preview, Path root, String label, int expected) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar", jar,
                "retry", "--database", db.toString(), "--temporal", target, "--submission", id, "--run-id", run,
                "--reason", "Removed fixture conflict"));
        if (preview) command.add("--dry-run");
        Path out = root.resolve(label + ".json"), err = root.resolve(label + ".log");
        var builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("VIDYOOP_"));
        var process = builder.start();
        try {
            Assert.assertTrue("CLI retry timed out", process.waitFor(20, TimeUnit.SECONDS));
            Assert.assertEquals(Files.readString(err), expected, process.exitValue());
            return expected == 0 ? new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(out)) : null;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
