package com.rkuo.viddyoop.core.recovery;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Opt-in cold backup drill. All deleted/restored data belongs to this test's temporary folder. */
public class ColdRestoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void restoredPersistenceResumesPublicationWithoutDuplicateArtifact() throws Exception {
        String cli = System.getProperty("viddyoop.temporal.cli"), hb = System.getProperty("viddyoop.handbrake");
        String fixture = System.getProperty("viddyoop.fixture"), merge = System.getProperty("viddyoop.mkvmerge");
        String extract = System.getProperty("viddyoop.mkvextract"), ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue(cli != null && hb != null && fixture != null && merge != null && extract != null && ffmpeg != null);
        Path root = temp.getRoot().toPath(), live = root.resolve("live"), pipeline = live.resolve("pipeline");
        Path persistence = live.resolve("temporal/temporal.db"), backup = root.resolve("backup");
        Files.createDirectories(persistence.getParent());
        Files.createDirectories(pipeline.resolve("inbox")); Files.createDirectories(pipeline.resolve("library"));
        Path original = pipeline.resolve("inbox/movie.mkv"); Files.copy(Path.of(fixture), original);
        var input = new IngestCoordinator(new SourceStager(pipeline.resolve("work")),
                new SubmissionCatalog(pipeline.resolve("catalog.db"))).accept(original, "apple-tv-v1", "discovery",
                pipeline.resolve("library/movie.mp4"), 0);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String target = "127.0.0.1:" + port;
        Process server = null, worker = null;
        WorkflowServiceStubs service = null;
        try {
            server = server(cli, port, persistence, root.resolve("first-server.log"));
            ready(server, target, root.resolve("first-server.log"));
            service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
            var client = WorkflowClient.newInstance(service);
            worker = worker(target, pipeline, hb, merge, extract, ffmpeg, "publish", root.resolve("first-worker.log"));
            Assert.assertTrue(new PipelineWorkflowStarter(client, "recovery").start(input));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!Files.exists(pipeline.resolve("boundary")) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            Assert.assertTrue("Publication boundary not reached: " + Files.readString(root.resolve("first-worker.log")),
                    Files.exists(pipeline.resolve("boundary")));
            Assert.assertTrue(Files.exists(Path.of(input.finalPath)));
            Assert.assertFalse(new ArtifactCatalog(pipeline.resolve("catalog.db")).expected(input.submissionId).committed());

            // Quiesce every writer before copying either database or media tree.
            stopWorker(worker); worker = null;
            service.shutdownNow(); service = null;
            stopServer(server); server = null;
            integrity(persistence); integrity(pipeline.resolve("catalog.db"));
            var manifest = checksums(live);
            copy(live, backup);
            Assert.assertEquals(manifest, checksums(backup));
            // Simulate losing all live persistence/media, then restore to the original absolute paths.
            try (var paths = Files.walk(live)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            copy(backup, live);
            Assert.assertEquals(manifest, checksums(live));
            integrity(persistence); integrity(pipeline.resolve("catalog.db"));

            server = server(cli, port, persistence, root.resolve("restored-server.log"));
            ready(server, target, root.resolve("restored-server.log"));
            service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
            client = WorkflowClient.newInstance(service);
            Assert.assertFalse("Restored history must retain the submission identity",
                    new PipelineWorkflowStarter(client, "recovery").start(input));
            worker = worker(target, pipeline, hb, merge, extract, ffmpeg, "normal", root.resolve("restored-worker.log"));
            PipelineResult result = client.newUntypedWorkflowStub(PipelineWorkflowStarter.workflowIdFor(input.submissionId))
                    .getResult(45, TimeUnit.SECONDS, PipelineResult.class);
            Assert.assertEquals(input.finalPath, result.finalPath);
            var artifact = new ArtifactCatalog(pipeline.resolve("catalog.db")).expected(input.submissionId);
            Assert.assertTrue(artifact.committed());
            Assert.assertEquals(artifact.checksum(), MediaFiles.checksum(Path.of(input.finalPath), () -> {}));
            try (var files = Files.list(pipeline.resolve("library"))) { Assert.assertEquals(1, files.count()); }
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + pipeline.resolve("catalog.db"));
                 var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM encode_stats")) {
                Assert.assertTrue(r.next()); Assert.assertEquals(1, r.getInt(1));
            }
            Assert.assertTrue(Files.exists(Path.of(input.stagedSourcePath)));
            // Restoring a copy changes inode/generation identity: cleanup safely preserves that original.
            Assert.assertTrue(Files.exists(original));
            Assert.assertEquals("replacement-preserved\n", Files.readString(pipeline.resolve(
                    "inbox/.viddyoop-cleanup/" + input.submissionId + "/done")));
        } finally {
            if (worker != null) stopWorker(worker);
            if (service != null) service.shutdownNow();
            // Preserve a startup failure and its log instead of masking it with an exit-code assertion.
            if (server != null && server.isAlive()) stopServer(server);
        }
    }

    private static Process server(String cli, int port, Path db, Path log) throws Exception {
        return new ProcessBuilder(cli, "server", "start-dev", "--headless", "--ip", "127.0.0.1", "--port",
                Integer.toString(port), "--db-filename", db.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void ready(Process server, String target, Path log) throws Exception {
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (server.isAlive() && System.nanoTime() < deadline) {
                try {
                    service.blockingStub().withDeadlineAfter(500, TimeUnit.MILLISECONDS).describeNamespace(
                            io.temporal.api.workflowservice.v1.DescribeNamespaceRequest.newBuilder().setNamespace("default").build());
                    return;
                } catch (io.grpc.StatusRuntimeException e) { Thread.sleep(100); }
            }
            throw new AssertionError("Temporal server unavailable: " + Files.readString(log));
        } finally { service.shutdownNow(); }
    }
    private static Process worker(String target, Path root, String hb, String merge, String extract, String ffmpeg,
                                  String mode, Path log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("java.class.path"), RecoveryWorkerMain.class.getName(), target, root.toString(),
                hb, merge, extract, ffmpeg, mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static void stopWorker(Process process) throws Exception {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly(); Assert.assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
    private static void stopServer(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            Assert.fail("Temporal did not stop cleanly; refusing cold backup");
        }
        Assert.assertEquals("Temporal stopped with an error", 0, process.exitValue());
    }
    private static void integrity(Path db) throws Exception {
        var config = new org.sqlite.SQLiteConfig(); config.setReadOnly(true);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db, config.toProperties());
             var s = c.createStatement(); var r = s.executeQuery("PRAGMA integrity_check")) {
            Assert.assertTrue(r.next()); Assert.assertEquals("ok", r.getString(1)); Assert.assertFalse(r.next());
        }
    }
    private static Map<String, String> checksums(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), MediaFiles.checksum(path, () -> {}));
        }
        return result;
    }
    private static void copy(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
