package com.rkuo.viddyoop.core.media;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.rkuo.viddyoop.core.pipeline.PreprocessResult;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class HandBrakeAdapterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void parsesCapturedRealScanAndRejectsInvalidMedia() throws Exception {
        Path log = Path.of(getClass().getResource("/handbrake-title-set.txt").toURI());
        var scan = HandBrakeAdapter.parseScan(log);
        Assert.assertEquals(320, scan.width());
        Assert.assertEquals(180, scan.height());
        Assert.assertEquals(2005, scan.durationMs());
        Assert.assertEquals("ac3", scan.audio().get(0).codec());
        Path invalid = temp.getRoot().toPath().resolve("invalid.log");
        Files.writeString(invalid, "JSON Title Set: {\"TitleList\":[]}");
        Assert.assertThrows(java.io.IOException.class, () -> HandBrakeAdapter.parseScan(invalid));
    }

    @Test public void surroundTracksFollowAacTracksWithAc3ConversionForDts() {
        var scan = new HandBrakeAdapter.Scan(1, 10000, 1920, 1080, java.util.List.of(
                new HandBrakeAdapter.Audio(1, "dts", 6), new HandBrakeAdapter.Audio(2, "ac3", 6)));
        var args = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner())
                .encodeCommand(Path.of("input with spaces.mkv"), Path.of("output.mp4"), scan, true);
        Assert.assertEquals("1,2,1,2", args.get(args.indexOf("--audio") + 1));
        Assert.assertEquals("av_aac,av_aac,ac3,copy:ac3", args.get(args.indexOf("--aencoder") + 1));
        Assert.assertEquals("22", args.get(args.indexOf("--quality") + 1));
        Assert.assertTrue(args.contains("--decomb"));
        Assert.assertTrue(args.contains("--detelecine"));
    }

    @Test public void dolbyPassthroughKeepsSourceSettingsAndAacCompatibilityTrack() {
        var scan = new HandBrakeAdapter.Scan(1, 10000, 1920, 1080, List.of(
                new HandBrakeAdapter.Audio(1, "EAC3", 6), new HandBrakeAdapter.Audio(2, "ac3", 6)));
        var args = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner())
                .encodeCommand(Path.of("input.mkv"), Path.of("output.mp4"), scan, false);
        Assert.assertEquals("av_aac,av_aac,copy:eac3,copy:ac3", option(args, "--aencoder"));
        Assert.assertEquals("dpl2,dpl2,auto,auto", option(args, "--mixdown"));
        Assert.assertEquals("224,224,auto,auto", option(args, "--ab"));
        Assert.assertEquals("48,48,auto,auto", option(args, "--arate"));
        Assert.assertEquals("2.0,2.0,0.0,0.0", option(args, "--drc"));
    }

    @Test public void prioritizesSubtitlesBeforeBudgetAndUpdatesDefaultIndex() throws Exception {
        var scan = new HandBrakeAdapter.Scan(1, 10000, 1920, 1080,
                List.of(new HandBrakeAdapter.Audio(1, "eac3", 6)));
        var subtitles = manySubtitles();
        subtitles.get(27).defaultTrack = true; // French sorts after English and Spanish.
        var args = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner())
                .encodeCommand(Path.of("input.mkv"), Path.of("output.mp4"), scan, false, subtitles);
        var paths = option(args, "--srt-file").split(",");
        Assert.assertEquals(28, paths.length); // One input surround track becomes two output audio tracks.
        Assert.assertEquals(Arrays.asList("english.srt", "spanish.srt", "french.srt"), Arrays.asList(paths).subList(0, 3));
        Assert.assertEquals("other-24.srt", paths[27]);
        Assert.assertTrue(option(args, "--srt-lang").startsWith("eng,spa,fra,"));
        Assert.assertEquals("3", option(args, "--srt-default"));
        Assert.assertEquals(30, subtitles.size());
    }

    @Test public void subtitleBudgetCountsAllAudioOutputsAndDoesNotReplaceOmittedDefault() throws Exception {
        var scan = new HandBrakeAdapter.Scan(1, 10000, 1920, 1080, List.of(
                new HandBrakeAdapter.Audio(1, "ac3", 6), new HandBrakeAdapter.Audio(2, "eac3", 6)));
        var subtitles = manySubtitles();
        subtitles.get(26).defaultTrack = true;
        var args = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner())
                .encodeCommand(Path.of("input.mkv"), Path.of("output.mp4"), scan, false, subtitles);
        Assert.assertEquals(26, option(args, "--srt-file").split(",").length);
        Assert.assertFalse(args.contains("--srt-default"));
    }

    private static List<PreprocessResult.Subtitle> manySubtitles() {
        var subtitles = new ArrayList<PreprocessResult.Subtitle>();
        for (int i = 0; i < 27; i++) subtitles.add(subtitle("other-" + i + ".srt", "jpn"));
        subtitles.add(subtitle("french.srt", "fre"));
        subtitles.add(subtitle("spanish.srt", "spa"));
        subtitles.add(subtitle("english.srt", "eng"));
        return subtitles;
    }

    private static PreprocessResult.Subtitle subtitle(String path, String language) {
        var subtitle = new PreprocessResult.Subtitle();
        subtitle.path = path;
        subtitle.language = language;
        return subtitle;
    }

    private static String option(List<String> args, String option) {
        return args.get(args.indexOf(option) + 1);
    }

    @Test public void realEac3PassthroughPreservesSurroundBytes() throws Exception {
        String handbrake = System.getProperty("viddyoop.handbrake");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue("Set handbrake and ffmpeg paths for native passthrough verification",
                handbrake != null && ffmpeg != null);
        Path root = temp.getRoot().toPath(), source = root.resolve("eac3.mkv"), output = root.resolve("encoded.mp4");
        var runner = new MediaProcessRunner();
        runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24",
                "-f", "lavfi", "-i", "anullsrc=channel_layout=5.1:sample_rate=48000", "-t", "2",
                "-c:v", "mpeg4", "-c:a", "eac3", "-b:a", "768k", source.toString()),
                root.resolve("fixture.log"), java.time.Duration.ofMinutes(1), () -> {});
        var adapter = new HandBrakeAdapter(handbrake, runner);
        var scan = adapter.scan(source, root.resolve("scan.log"), () -> {});
        Assert.assertEquals("eac3", scan.audio().get(0).codec());
        adapter.encode(source, output, scan, false, root.resolve("encode.log"), () -> {});
        var encoded = adapter.scan(output, root.resolve("output-scan.log"), () -> {});
        Assert.assertEquals(2, encoded.audio().size());
        Assert.assertEquals("aac", encoded.audio().get(0).codec());
        Assert.assertEquals(2, encoded.audio().get(0).channels());
        Assert.assertEquals("eac3", encoded.audio().get(1).codec());
        Assert.assertEquals(6, encoded.audio().get(1).channels());
        Path audioProbe = root.resolve("audio.json");
        runner.run(List.of(Path.of(ffmpeg).resolveSibling("ffprobe").toString(), "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=codec_name,channels:stream_disposition=default", "-of", "json", output.toString()),
                audioProbe, java.time.Duration.ofMinutes(1), () -> {});
        var streams = new com.fasterxml.jackson.databind.ObjectMapper().readTree(audioProbe.toFile()).path("streams");
        Assert.assertEquals(1, streams.get(0).path("disposition").path("default").asInt());
        Assert.assertEquals(0, streams.get(1).path("disposition").path("default").asInt());
        Path sourceHash = root.resolve("source-hash.log"), outputHash = root.resolve("output-hash.log");
        runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", source.toString(), "-map", "0:a:0",
                "-c", "copy", "-f", "hash", "-hash", "sha256", "-"), sourceHash, java.time.Duration.ofMinutes(1), () -> {});
        runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", output.toString(), "-map", "0:a:1",
                "-c", "copy", "-f", "hash", "-hash", "sha256", "-"), outputHash, java.time.Duration.ofMinutes(1), () -> {});
        Assert.assertEquals(Files.readString(sourceHash).trim(), Files.readString(outputHash).trim());
    }

    @Test public void realScanEncodeAndRescan() throws Exception {
        String executable = System.getProperty("viddyoop.handbrake");
        String fixture = System.getProperty("viddyoop.fixture");
        Assume.assumeTrue("Set viddyoop.handbrake and viddyoop.fixture for real media smoke test",
                executable != null && fixture != null);
        Path root = temp.getRoot().toPath();
        var adapter = new HandBrakeAdapter(executable, new MediaProcessRunner());
        int[] heartbeats = {0};
        var scan = adapter.scan(Path.of(fixture), root.resolve("scan.log"), () -> heartbeats[0]++);
        Path encoded = root.resolve("encoded.mp4");
        adapter.encode(Path.of(fixture), encoded, scan, false, root.resolve("encode.log"), () -> heartbeats[0]++);
        var output = adapter.scan(encoded, root.resolve("verify.log"), () -> heartbeats[0]++);
        Assert.assertEquals(scan.width(), output.width());
        Assert.assertEquals(scan.height(), output.height());
        Assert.assertTrue(Math.abs(scan.durationMs() - output.durationMs()) < 500);
        Assert.assertFalse(output.audio().isEmpty());
        Assert.assertTrue(heartbeats[0] >= 3);
    }
}
