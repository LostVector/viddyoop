package com.rkuo.viddyoop.core.worker;

import com.fasterxml.jackson.databind.*;
import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import com.rkuo.viddyoop.core.status.*;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Opt-in post-package integration: exercises the actual jar, watcher, status, and reencode CLI. */
public class RunnablePipelineTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @FunctionalInterface private interface Check { boolean ready() throws Exception; }
    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) { if (check.ready()) return; Thread.sleep(100); }
        Assert.fail("Runnable pipeline did not reach the expected state in 90 seconds");
    }
    @Test public void watcherReplacementReencodeAndRestartPreserveIdentityAndArtifacts() throws Exception {
        String jar = System.getProperty("viddyoop.jar"), target = System.getProperty("viddyoop.temporal");
        String fixture = System.getProperty("viddyoop.fixture"), hb = System.getProperty("viddyoop.handbrake");
        String merge = System.getProperty("viddyoop.mkvmerge"), extract = System.getProperty("viddyoop.mkvextract");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue(jar != null && target != null && fixture != null && hb != null && merge != null && extract != null && ffmpeg != null);
        Path root = temp.getRoot().toPath(), inbox = root.resolve("inbox"), library = root.resolve("library");
        Files.createDirectories(inbox); Files.createDirectories(library);
        Path source = inbox.resolve("movie.mkv"), database = root.resolve("catalog.db");
        Files.copy(Path.of(fixture), source);
        List<String> pipeline = List.of("pipeline", "--inbox", inbox.toString(), "--work", root.resolve("work").toString(),
                "--library", library.toString(), "--database", database.toString(), "--archive-policy", "none",
                "--temporal", target, "--queue", "runnable-" + UUID.randomUUID(), "--handbrake", hb, "--mkvmerge", merge,
                "--mkvextract", extract, "--ffmpeg", ffmpeg, "--settle-ms", "0", "--poll-ms", "100", "--min-free-bytes", "0");
        Process worker = launch(jar, pipeline, root.resolve("worker.log"), root.resolve("worker.stderr"));
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try {
            var client = WorkflowClient.newInstance(service);
            Process initialWorker = worker;
            await(() -> {
                if (!initialWorker.isAlive()) throw new AssertionError("Worker exited: " + Files.readString(root.resolve("worker.stderr")));
                return Files.exists(library.resolve("movie.mp4")) && !Files.exists(source);
            });
            var rows = new StatusCatalog().read(database, null, 100);
            Assert.assertEquals(1, rows.size());
            String originalId = rows.get(0).id();
            await(() -> new WorkflowStatus(client).inspect(new StatusCatalog().read(database, originalId, 1).get(0)).state().equals("COMPLETED"));
            String originalChecksum = rows.get(0).checksum();
            // Identical bytes at the old pathname are a different source generation.
            Files.copy(Path.of(fixture), source);
            await(() -> new StatusCatalog().read(database, null, 100).size() == 2);
            String replacementId = new StatusCatalog().read(database, null, 100).stream()
                    .filter(row -> !row.id().equals(originalId)).findFirst().orElseThrow().id();
            var args = List.of("reencode", "--database", database.toString(), "--library", library.toString(),
                    "--submission", originalId, "--request-key", "manual-request-1", "--output", "movie-v2.mp4");
            Path response = root.resolve("reencode.json");
            Assert.assertEquals(0, run(jar, args, response, root.resolve("reencode.stderr")));
            String reencodeId = new ObjectMapper().readTree(Files.readString(response)).path("submissionId").asText();
            Assert.assertNotEquals(originalId, reencodeId);
            await(() -> new WorkflowStatus(client).inspect(new StatusCatalog().read(database, reencodeId, 1).get(0)).state().equals("COMPLETED"));
            Assert.assertEquals(0, run(jar, args, response, root.resolve("reencode.stderr")));
            Assert.assertEquals(reencodeId, new ObjectMapper().readTree(Files.readString(response)).path("submissionId").asText());
            await(() -> new WorkflowStatus(client).inspect(new StatusCatalog().read(database, replacementId, 1).get(0)).state().equals("FAILED"));
            Assert.assertTrue(Files.exists(source));
            Assert.assertEquals(originalChecksum, com.rkuo.viddyoop.core.media.MediaFiles.checksum(library.resolve("movie.mp4"), () -> {}));
            Assert.assertTrue(Files.size(library.resolve("movie-v2.mp4")) > 0);
            worker.destroy(); Assert.assertTrue(worker.waitFor(10, TimeUnit.SECONDS));
            worker = launch(jar, pipeline, root.resolve("restarted.log"), root.resolve("restarted.stderr"));
            Thread.sleep(1500);
            Assert.assertTrue(worker.isAlive());
            Assert.assertEquals(3, new StatusCatalog().read(database, null, 100).size());
            Path status = root.resolve("status.json");
            Assert.assertEquals(0, run(jar, List.of("status", "--database", database.toString(), "--temporal", target, "--json"),
                    status, root.resolve("status.stderr")));
            JsonNode reported = new ObjectMapper().readTree(Files.readString(status));
            Assert.assertEquals(3, reported.size());
            long committed = 0;
            for (JsonNode row : reported) if (row.path("submission").path("artifactState").asText().equals("committed")) committed++;
            Assert.assertEquals(2, committed);
            Assert.assertEquals(0, run(jar, List.of("health", "--work", root.resolve("work").toString(), "--temporal", target),
                    root.resolve("health.out"), root.resolve("health.stderr")));
        } finally {
            worker.destroy();
            if (!worker.waitFor(10, TimeUnit.SECONDS)) worker.destroyForcibly();
            service.shutdownNow();
        }
    }
    private static Process launch(String jar, List<String> args, Path stdout, Path stderr) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-jar", jar));
        command.addAll(args);
        return new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
    }
    private static int run(String jar, List<String> args, Path stdout, Path stderr) throws Exception {
        Process process = launch(jar, args, stdout, stderr);
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); Assert.fail("CLI command timed out"); }
        return process.exitValue();
    }
}
