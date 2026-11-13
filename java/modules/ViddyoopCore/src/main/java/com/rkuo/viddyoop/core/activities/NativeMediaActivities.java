package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog.Artifact;
import com.rkuo.viddyoop.core.media.*;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.activity.Activity;
import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.UUID;

/** Real normalization, encode, and verified publication activities. */
public final class NativeMediaActivities implements PreprocessActivity, EncodeActivity, PublishActivity {
    private final Path work;
    private final HandBrakeAdapter adapter;
    private final ArtifactPublisher publisher;
    private final MediaNormalizer normalizer;
    private final SourceCleanup cleanup;
    private final SourceArchiver archiver;

    public NativeMediaActivities(Path work, HandBrakeAdapter adapter,
                                 ArtifactPublisher publisher) {
        this(work, adapter, publisher, new MediaNormalizer("/usr/bin/mkvmerge", "/usr/bin/mkvextract",
                "/usr/bin/ffmpeg", new MediaProcessRunner()));
    }

    public NativeMediaActivities(Path work, HandBrakeAdapter adapter, ArtifactPublisher publisher, MediaNormalizer normalizer) {
        this(work, adapter, publisher, normalizer, null);
    }

    public NativeMediaActivities(Path work, HandBrakeAdapter adapter, ArtifactPublisher publisher,
                                 MediaNormalizer normalizer, SourceCleanup cleanup) {
        this(work, adapter, publisher, normalizer, cleanup, null);
    }

    public NativeMediaActivities(Path work, HandBrakeAdapter adapter, ArtifactPublisher publisher,
                                 MediaNormalizer normalizer, SourceCleanup cleanup, SourceArchiver archiver) {
        this.archiver = archiver;
        this.cleanup = cleanup;
        this.normalizer = normalizer;
        this.work = work.toAbsolutePath().normalize();
        this.adapter = adapter;
        this.publisher = publisher;
    }

    private static void heartbeat() { Activity.getExecutionContext().heartbeat("media activity alive"); }

    private Path attempt(PipelineInput input, String stage) throws IOException {
        // Catalog-generated IDs only; no path fragments from workflow payloads.
        UUID.fromString(input.submissionId);
        Path directory = work.resolve(input.submissionId).resolve(stage + "-" + UUID.randomUUID());
        Files.createDirectories(directory);
        return directory;
    }

    private Path source(PipelineInput input) throws IOException {
        if (input.stagedSourcePath == null) throw new IOException("Missing owned source snapshot");
        Path path = Path.of(input.stagedSourcePath).toAbsolutePath().normalize();
        if (!path.getParent().toRealPath().equals(work.resolve("ingest").toRealPath())) {
            throw new IOException("Snapshot must be in owned ingest storage");
        }
        if (!MediaFiles.checksum(path, NativeMediaActivities::heartbeat).equals(input.sourceFingerprint)) {
            throw new IOException("Source snapshot fingerprint mismatch");
        }
        return path;
    }

    @Override public PreprocessResult preprocess(PipelineInput input) {
        try {
            Path source = source(input);
            Path attempt = attempt(input, "preprocess");
            var result = normalizer.normalize(source, input.sourcePath.toLowerCase(java.util.Locale.ROOT).endsWith(".mkv"),
                    attempt, NativeMediaActivities::heartbeat);
            var scan = adapter.scan(Path.of(result.stagedPath), attempt.resolve("scan.log"), NativeMediaActivities::heartbeat);
            result.durationMs = scan.durationMs(); result.width = scan.width(); result.height = scan.height();
            archive(input, scan.durationMs(), scan.width(), scan.height());
            return result;
        } catch (IOException e) { throw new IllegalStateException("Preprocess failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Preprocess interrupted", e); }
    }

    @Override public EncodeResult encode(PipelineInput input, PreprocessResult pre) {
        try {
            if (!"apple-tv-v1".equals(input.encodeProfile)) throw new IOException("Unsupported encode profile: " + input.encodeProfile);
            Path source = source(input);
            Path attempt = attempt(input, "encode");
            // A completed Temporal activity does not restore deleted intermediate files.
            // Verify every artifact, and explicitly regenerate normalization when missing.
            if (!validPreprocess(input, pre)) pre = preprocess(input);
            source = Path.of(pre.stagedPath);
            var scan = adapter.scan(source, attempt.resolve("scan.log"), NativeMediaActivities::heartbeat);
            Path output = attempt.resolve("encoded.mp4");
            long start = System.nanoTime();
            adapter.encode(source, output, scan, input.sourcePath.toLowerCase(java.util.Locale.ROOT).endsWith(".ts"),
                    attempt.resolve("encode.log"), NativeMediaActivities::heartbeat, pre.subtitles);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            var verified = adapter.scan(output, attempt.resolve("verify.log"), NativeMediaActivities::heartbeat);
            if (Math.abs(verified.durationMs() - scan.durationMs()) > Math.max(1000, scan.durationMs() / 100)) {
                throw new IOException("Encoded duration differs from source");
            }
            if (!scan.audio().isEmpty() && verified.audio().isEmpty()) throw new IOException("Encoded audio missing");
            EncodeResult result = new EncodeResult();
            result.encodedPath = output.toString(); result.encodeDurationMs = elapsed;
            result.durationMs = verified.durationMs(); result.width = verified.width(); result.height = verified.height();
            result.sourceDurationMs = scan.durationMs(); result.sourceWidth = scan.width(); result.sourceHeight = scan.height();
            result.checksum = MediaFiles.checksum(output, NativeMediaActivities::heartbeat);
            result.machineName = java.net.InetAddress.getLocalHost().getHostName();
            return result;
        } catch (IOException e) { throw new IllegalStateException("Encode failed", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Encode interrupted", e); }
    }

    private boolean validPreprocess(PipelineInput input, PreprocessResult pre) throws IOException {
        if (pre == null || pre.stagedPath == null || pre.checksum == null || pre.subtitles == null) return false;
        Path owned = work.resolve(input.submissionId).toAbsolutePath().normalize();
        Path media = Path.of(pre.stagedPath).toAbsolutePath().normalize();
        Path snapshot = Path.of(input.stagedSourcePath).toAbsolutePath().normalize();
        if (!media.equals(snapshot) && !media.startsWith(owned)) throw new IOException("Intermediate outside job storage");
        if (!Files.isRegularFile(media, LinkOption.NOFOLLOW_LINKS)
                || !pre.checksum.equals(MediaFiles.checksum(media, NativeMediaActivities::heartbeat))) return false;
        for (var subtitle : pre.subtitles) {
            Path path = Path.of(subtitle.path).toAbsolutePath().normalize();
            if (!path.startsWith(owned)) throw new IOException("Subtitle outside job storage");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !subtitle.checksum.equals(MediaFiles.checksum(path, NativeMediaActivities::heartbeat))) return false;
        }
        return true;
    }

    private void archive(PipelineInput input, long duration, int width, int height) throws IOException {
        if (input.archivePolicy == null || input.archivePolicy.equals("none")) return;
        if (archiver == null) throw new IOException("Registered archive policy requires configured archiver");
        archiver.archive(input, duration, width, height, NativeMediaActivities::heartbeat);
    }

    @Override public void publish(PipelineInput input, EncodeResult enc) {
        try {
            // Recheck archival preservation before publication/cleanup, including on retries.
            archive(input, enc.sourceDurationMs, enc.sourceWidth, enc.sourceHeight);
            var artifact = new Artifact(input.submissionId, input.sourcePath,
                    Path.of(input.finalPath).toAbsolutePath().normalize().toString(), enc.checksum,
                    enc.durationMs, enc.width, enc.height, enc.encodeDurationMs, enc.machineName, false);
            publisher.publish(artifact, Path.of(enc.encodedPath), NativeMediaActivities::heartbeat);
            if (cleanup != null) cleanup.cleanup(input, NativeMediaActivities::heartbeat);
        } catch (IOException | SQLException e) { throw new IllegalStateException("Publish failed", e); }
    }
}
