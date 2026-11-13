package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import com.rkuo.viddyoop.core.media.*;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Opt-in: point viddyoop.fullfs at a disposable mounted filesystem of at most 64 MiB. */
public class DiskFullRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path constrained;
    @Before public void prepareSmallFilesystem() throws Exception {
        String fullfs = System.getProperty("viddyoop.fullfs");
        Assume.assumeTrue("Requires a disposable small filesystem", fullfs != null);
        Path mount = Path.of(fullfs).toRealPath();
        long capacity = Files.getFileStore(mount).getTotalSpace();
        Assert.assertTrue("Refusing to fill a filesystem larger than 64 MiB", capacity > 0 && capacity <= 64L * 1024 * 1024);
        constrained = Files.createTempDirectory(mount, "viddyoop-full-");
    }
    @After public void removeTestFiles() throws Exception {
        if (constrained != null) {
            try (var paths = Files.walk(constrained)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private Path exhaust() throws Exception {
        Path filler = constrained.resolve("filler");
        boolean full = false;
        try (OutputStream out = Files.newOutputStream(filler)) {
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 65; i++) out.write(block);
        } catch (IOException e) { full = true; }
        // Large writes and delayed-allocation filesystems can leave a small usable
        // remainder after ENOSPC. Close/flush, then consume individual blocks too.
        for (int pass = 0; pass < 8; pass++) {
            try (var channel = java.nio.channels.FileChannel.open(filler, StandardOpenOption.WRITE)) { channel.force(true); }
            catch (IOException e) { full = true; }
            if (Files.getFileStore(constrained).getUsableSpace() < 4096) break;
            try (OutputStream out = Files.newOutputStream(filler, StandardOpenOption.APPEND)) {
                byte[] block = new byte[4096];
                for (int i = 0; i < 16385; i++) out.write(block);
            } catch (IOException e) { full = true; }
        }
        Assert.assertTrue("Expected actual filesystem exhaustion", full);
        Assert.assertTrue("Filesystem still has space for a small remux", Files.getFileStore(constrained).getUsableSpace() < 4096);
        return filler;
    }

    private void assertNoSpace(Throwable error) throws IOException {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append("; ");
        if (messages.toString().toLowerCase(java.util.Locale.ROOT).contains("space")) return;
        // Subprocesses can only report an exit code when their diagnostic volume
        // is full too. Verify ENOSPC independently on that same filesystem.
        Path probe = constrained.resolve("enospc-probe");
        try {
            IOException full = Assert.assertThrows(IOException.class, () -> Files.write(probe, new byte[2 * 1024 * 1024]));
            Assert.assertTrue(full.toString(), full.toString().toLowerCase(java.util.Locale.ROOT).contains("space"));
        } finally { Files.deleteIfExists(probe); }
    }

    private void releaseSpace(Path filler) throws IOException {
        // VirtioFS may retain a host descriptor after the guest unlinks a file.
        // Truncate explicitly so freeing this fixture's blocks does not depend
        // on the sharing daemon's descriptor-cache lifetime.
        try (var channel = java.nio.channels.FileChannel.open(filler, StandardOpenOption.WRITE)) {
            channel.truncate(0); channel.force(true);
        }
        Files.delete(filler);
    }

    @Test public void fullLibraryDoesNotCommitOrLoseInputAndPublicationRetriesAfterSpaceReturns() throws Exception {
        Path root = temp.getRoot().toPath();
        Path source = root.resolve("source.mkv"); Files.writeString(source, "original input");
        Path encoded = root.resolve("encoded.mp4"); Files.write(encoded, new byte[2 * 1024 * 1024]);
        var catalog = new ArtifactCatalog(root.resolve("catalog.db"));
        Path library = constrained.resolve("library");
        var publisher = new ArtifactPublisher(catalog, library);
        Path destination = library.resolve("movie.mp4");
        var artifact = new ArtifactCatalog.Artifact("disk-full", source.toString(), destination.toString(),
                MediaFiles.checksum(encoded, () -> {}), 2000, 320, 180, 100, "test", false);
        Path filler = exhaust();
        assertNoSpace(Assert.assertThrows(IOException.class, () -> publisher.publish(artifact, encoded, () -> {})));
        Assert.assertFalse(Files.exists(destination));
        Assert.assertFalse(catalog.expected("disk-full").committed());
        Assert.assertEquals("original input", Files.readString(source));
        releaseSpace(filler);
        publisher.publish(artifact, encoded, () -> {});
        Assert.assertTrue(catalog.expected("disk-full").committed());
        Assert.assertEquals(artifact.checksum(), MediaFiles.checksum(destination, () -> {}));
        Assert.assertTrue(Files.exists(source));
    }

    @Test public void fullWorkVolumeFailsPreprocessingWithoutLosingOriginalOrSnapshot() throws Exception {
        String executable = System.getProperty("viddyoop.handbrake"), fixture = System.getProperty("viddyoop.fixture");
        String merge = System.getProperty("viddyoop.mkvmerge"), extract = System.getProperty("viddyoop.mkvextract");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue(executable != null && fixture != null && merge != null && extract != null && ffmpeg != null);
        Path root = temp.getRoot().toPath(), source = root.resolve("source.mkv");
        Files.copy(Path.of(fixture), source);
        Path database = root.resolve("catalog.db"), work = constrained.resolve("work");
        var input = new IngestCoordinator(new SourceStager(work), new SubmissionCatalog(database)).accept(
                source, "apple-tv-v1", "discovery", root.resolve("library/movie.mp4"), 0);
        var runner = new MediaProcessRunner();
        var activities = new NativeMediaActivities(work, new HandBrakeAdapter(executable, runner),
                new ArtifactPublisher(new ArtifactCatalog(database), root.resolve("library")),
                new MediaNormalizer(merge, extract, ffmpeg, runner));
        var env = io.temporal.testing.TestActivityEnvironment.newInstance();
        try {
            env.registerActivitiesImplementations(activities);
            var preprocess = env.newActivityStub(PreprocessActivity.class);
            Path filler = exhaust();
            assertNoSpace(Assert.assertThrows(RuntimeException.class, () -> preprocess.preprocess(input)));
            Assert.assertEquals(input.sourceFingerprint, MediaFiles.checksum(source, () -> {}));
            Assert.assertEquals(input.sourceFingerprint, MediaFiles.checksum(Path.of(input.stagedSourcePath), () -> {}));
            Assert.assertFalse(Files.exists(Path.of(input.finalPath)));
            releaseSpace(filler);
            var recovered = preprocess.preprocess(input);
            Assert.assertTrue(recovered.durationMs > 0);
            Assert.assertTrue(Files.size(Path.of(recovered.stagedPath)) > 0);
        } finally { env.close(); }
    }
}
