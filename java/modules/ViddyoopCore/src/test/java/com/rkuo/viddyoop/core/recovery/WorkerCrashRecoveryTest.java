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

public class WorkerCrashRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void killedEncoderWorkerIsReplacedWithoutDuplicatePublication() throws Exception { exercise("encode"); }
    @Test public void killedPublisherAfterFileCommitReconcilesCatalogAndCleanup() throws Exception { exercise("publish"); }

    private void exercise(String boundary) throws Exception {
        String hb = System.getProperty("viddyoop.handbrake"), fixture = System.getProperty("viddyoop.fixture");
        String merge = System.getProperty("viddyoop.mkvmerge"), extract = System.getProperty("viddyoop.mkvextract");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        String target = System.getProperty("viddyoop.temporal");
        boolean productionTimeouts = Boolean.getBoolean("viddyoop.productionTimeouts");
        Assume.assumeTrue(target != null && hb != null && fixture != null && merge != null && extract != null && ffmpeg != null);
        Path root = temp.getRoot().toPath();
        Files.createDirectories(root.resolve("inbox")); Files.createDirectories(root.resolve("library"));
        Path original = root.resolve("inbox/movie.mkv"); Files.copy(Path.of(fixture), original);
        var submissions = new SubmissionCatalog(root.resolve("catalog.db"));
        var input = new IngestCoordinator(new SourceStager(root.resolve("work")), submissions).accept(
                original, "apple-tv-v1", "discovery", root.resolve("library/movie.mp4"), 0);
        input.activityTaskQueuePrefix = "recovery";
        Path executable = root.resolve("gated-handbrake");
        // The first worker runs real HandBrake, suspends it once encoding reports progress,
        // then exposes a marker so the parent can abruptly kill the entire process tree.
        Files.writeString(executable, """
                #!/bin/sh
                case " $* " in
                  *" --output "*)
                    "$REAL_HANDBRAKE" "$@" --encoder-preset placebo > "$RECOVERY_ROOT/progress" 2>&1 &
                    child=$!
                    while kill -0 "$child" 2>/dev/null; do
                      if /usr/bin/grep -q 'Encoding:' "$RECOVERY_ROOT/progress"; then
                        kill -STOP "$child" || exit 99
                        echo "$child" > "$RECOVERY_ROOT/boundary"
                        wait "$child"
                        exit $?
                      fi
                      sleep 0.01
                    done
                    wait "$child"; exit $?
                    ;;
                  *) exec "$REAL_HANDBRAKE" "$@" ;;
                esac
                """);
        Assert.assertTrue(executable.toFile().setExecutable(true));
        Process first = null, replacement = null;
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try {
            var client = WorkflowClient.newInstance(service);
            first = worker(target, root, boundary.equals("encode") ? executable.toString() : hb, merge, extract, ffmpeg,
                    boundary.equals("publish") ? "publish" : "normal", hb, "first.log");
            var stub = client.newWorkflowStub(VideoPipelineWorkflow.class, WorkflowOptions.newBuilder()
                    .setTaskQueue("recovery").setWorkflowId(PipelineWorkflowStarter.workflowIdFor(input.submissionId)).build());
            WorkflowClient.start(stub::run, input);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!Files.exists(root.resolve("boundary")) && System.nanoTime() < deadline && first.isAlive()) Thread.sleep(20);
            Assert.assertTrue("Worker never reached " + boundary + ": " + Files.readString(root.resolve("first.log")),
                    Files.exists(root.resolve("boundary")));
            Assert.assertTrue(Files.exists(original));
            if (productionTimeouts) {
                var scheduled = client.fetchHistory(PipelineWorkflowStarter.workflowIdFor(input.submissionId)).getHistory().getEventsList().stream()
                        .filter(event -> event.hasActivityTaskScheduledEventAttributes())
                        .map(event -> event.getActivityTaskScheduledEventAttributes())
                        .filter(activity -> activity.getActivityType().getName().equals(boundary.equals("encode") ? "Encode" : "Publish"))
                        .findFirst().orElseThrow();
                Assert.assertEquals(boundary.equals("encode") ? 600 : 120, scheduled.getHeartbeatTimeout().getSeconds());
                Assert.assertEquals(boundary.equals("encode") ? 60 : 10, scheduled.getRetryPolicy().getInitialInterval().getSeconds());
            }
            if (boundary.equals("publish")) {
                Assert.assertTrue(Files.exists(Path.of(input.finalPath)));
                Assert.assertFalse(new ArtifactCatalog(root.resolve("catalog.db")).expected(input.submissionId).committed());
            }
            if (boundary.equals("encode")) {
                long encoder = Long.parseLong(Files.readString(root.resolve("boundary")).trim());
                Assert.assertTrue("Expected a live suspended HandBrake process", ProcessHandle.of(encoder).map(ProcessHandle::isAlive).orElse(false));
            }
            killTree(first); Assert.assertTrue(first.waitFor(5, TimeUnit.SECONDS));
            replacement = worker(target, root, hb, merge, extract, ffmpeg, "normal", hb, "replacement.log");
            PipelineResult result;
            try { result = WorkflowStub.fromTyped(stub).getResult(productionTimeouts ? 900 : 20, TimeUnit.SECONDS, PipelineResult.class); }
            catch (java.util.concurrent.TimeoutException timeout) {
                throw new AssertionError("Recovery stalled: " + WorkflowStub.fromTyped(stub).describe().getRawDescription()
                        + "\nhistory:\n" + client.fetchHistory(PipelineWorkflowStarter.workflowIdFor(input.submissionId)).getHistory()
                        + "\nreplacement worker:\n" + Files.readString(root.resolve("replacement.log")), timeout);
            }
            Assert.assertEquals(input.finalPath, result.finalPath);
            var artifact = new ArtifactCatalog(root.resolve("catalog.db")).expected(input.submissionId);
            Assert.assertTrue(artifact.committed());
            Assert.assertEquals(artifact.checksum(), MediaFiles.checksum(Path.of(input.finalPath), () -> {}));
            Assert.assertFalse(Files.exists(original));
            Assert.assertTrue(Files.exists(Path.of(input.stagedSourcePath)));
            try (var files = Files.list(root.resolve("library"))) {
                Assert.assertEquals(1, files.filter(Files::isRegularFile).count());
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("catalog.db"));
                 Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM encode_stats")) {
                Assert.assertTrue(r.next()); Assert.assertEquals(1, r.getInt(1));
            }
        } finally { if (first != null) killTree(first); if (replacement != null) killTree(replacement); service.shutdownNow(); }
    }

    private Process worker(String target, Path root, String hb, String merge, String extract, String ffmpeg,
                           String mode, String realHandBrake, String log) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), RecoveryWorkerMain.class.getName(),
                target, root.toString(), hb, merge, extract, ffmpeg, mode, Boolean.toString(Boolean.getBoolean("viddyoop.productionTimeouts"))));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve(log).toFile());
        builder.environment().put("REAL_HANDBRAKE", realHandBrake);
        builder.environment().put("RECOVERY_ROOT", root.toString());
        return builder.start();
    }
    private static void killTree(Process worker) throws Exception {
        var children = worker.descendants().toList();
        for (int i = children.size() - 1; i >= 0; i--) children.get(i).destroyForcibly();
        if (worker.isAlive()) worker.destroyForcibly();
        worker.waitFor(5, TimeUnit.SECONDS);
    }
}
