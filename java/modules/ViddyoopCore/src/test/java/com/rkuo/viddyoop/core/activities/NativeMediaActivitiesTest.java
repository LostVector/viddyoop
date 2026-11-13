package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.*;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class NativeMediaActivitiesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void missingNormalizedIntermediateIsRegeneratedDuringEncode() throws Exception {
        String executable = System.getProperty("viddyoop.handbrake");
        String fixture = System.getProperty("viddyoop.fixture");
        Assume.assumeTrue(executable != null && fixture != null && System.getProperty("viddyoop.mkvmerge") != null);
        Path root = temp.getRoot().toPath();
        Path work = root.resolve("work"), library = root.resolve("library");
        Files.createDirectories(library);
        Path source = root.resolve("movie.mkv"); Files.copy(Path.of(fixture), source);
        Path database = root.resolve("catalog.db");
        var input = new IngestCoordinator(new SourceStager(work), new SubmissionCatalog(database)).accept(
                source, "apple-tv-v1", "discovery", library.resolve("movie.mp4"), 0);
        var activities = new NativeMediaActivities(work, new HandBrakeAdapter(executable, new MediaProcessRunner()),
                new ArtifactPublisher(new ArtifactCatalog(database), library),
                new MediaNormalizer(System.getProperty("viddyoop.mkvmerge"), System.getProperty("viddyoop.mkvextract"),
                        System.getProperty("viddyoop.ffmpeg"), new MediaProcessRunner()));
        var env = io.temporal.testing.TestActivityEnvironment.newInstance();
        try {
            env.registerActivitiesImplementations(activities);
            var pre = env.newActivityStub(PreprocessActivity.class).preprocess(input);
            Assert.assertNotEquals(input.stagedSourcePath, pre.stagedPath);
            Files.delete(Path.of(pre.stagedPath));
            var encoded = env.newActivityStub(EncodeActivity.class).encode(input, pre);
            Assert.assertTrue(Files.size(Path.of(encoded.encodedPath)) > 0);
            Assert.assertEquals(encoded.checksum, MediaFiles.checksum(Path.of(encoded.encodedPath), () -> {}));
            Assert.assertTrue(Files.exists(Path.of(input.stagedSourcePath)));
        } finally { env.close(); }
    }

    @Test public void realWorkflowPublishesVerifiedMediaThenCleansOriginalAndPreservesSnapshot() throws Exception {
        String executable = System.getProperty("viddyoop.handbrake");
        String fixture = System.getProperty("viddyoop.fixture");
        Assume.assumeTrue(executable != null && fixture != null && System.getProperty("viddyoop.mkvmerge") != null);
        Path root = temp.getRoot().toPath();
        Path work = root.resolve("work");
        Path library = root.resolve("library");
        Files.createDirectories(library);
        Path source = root.resolve("movie.mkv");
        Files.copy(Path.of(fixture), source);
        Path database = root.resolve("catalog.db");
        var submissions = new SubmissionCatalog(database);
        var artifacts = new ArtifactCatalog(database);
        var input = new IngestCoordinator(new SourceStager(work), submissions).accept(
                source, "apple-tv-v1", "discovery", library.resolve("movie.mp4"), 0, "movies", root.resolve("archive"));
        try (var env = TestWorkflowEnvironment.newInstance()) {
            input.activityTaskQueuePrefix = "real-media-test";
            com.rkuo.viddyoop.core.worker.PipelineWorkers.register(env.getWorkerFactory(), "real-media-test",
                    new NativeMediaActivities(work, new HandBrakeAdapter(executable, new MediaProcessRunner()),
                            new ArtifactPublisher(artifacts, library), new MediaNormalizer(System.getProperty("viddyoop.mkvmerge"),
                                    System.getProperty("viddyoop.mkvextract"), System.getProperty("viddyoop.ffmpeg"),
                                    new MediaProcessRunner()), new SourceCleanup(artifacts, root), new SourceArchiver(root.resolve("archive"))), 1, 1, 1);
            env.start();
            var stub = env.getWorkflowClient().newWorkflowStub(VideoPipelineWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("real-media-test").build());
            var result = stub.run(input);
            Assert.assertEquals(input.finalPath, result.finalPath);
        }
        Assert.assertTrue(Files.size(Path.of(input.finalPath)) > 0);
        Assert.assertTrue(artifacts.expected(input.submissionId).committed());
        Assert.assertEquals(artifacts.expected(input.submissionId).checksum(),
                MediaFiles.checksum(Path.of(input.finalPath), () -> {}));
        Assert.assertFalse(Files.exists(source));
        Assert.assertTrue(Files.exists(Path.of(input.stagedSourcePath)));
    }
}
