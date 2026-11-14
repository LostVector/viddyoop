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

    @Test public void hevcProfileUsesMain10SlowSourceRangeAndKeepsLegacyProfileSeparate() throws Exception {
        var scan = new HandBrakeAdapter.Scan(1, 10000, 3840, 2160,
                List.of(new HandBrakeAdapter.Audio(1, "eac3", 6)));
        var adapter = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner());
        var args = adapter.encodeCommand(Path.of("input.mkv"), Path.of("output.mp4"), scan, false,
                List.of(), HandBrakeAdapter.HEVC_PROFILE);
        Assert.assertEquals("x265_10bit", option(args, "--encoder"));
        Assert.assertEquals("main10", option(args, "--encoder-profile"));
        Assert.assertEquals("5.0", option(args, "--encoder-level"));
        Assert.assertEquals("slow", option(args, "--encoder-preset"));
        Assert.assertEquals("auto", option(args, "--color-range"));
        Assert.assertEquals("3840", option(args, "--maxWidth"));
        Assert.assertEquals("2160", option(args, "--maxHeight"));
        Assert.assertEquals("22", option(args, "--quality"));
        Assert.assertTrue(option(args, "--encopts").endsWith("vbv-maxrate=60000:vbv-bufsize=60000"));
        Assert.assertTrue(option(args, "--encopts").contains("psy-rd=0.75:psy-rdoq=4.0"));
        Assert.assertTrue(option(args, "--encopts").contains("high-tier=1"));
        var legacy = adapter.encodeCommand(Path.of("input.mkv"), Path.of("output.mp4"), scan, false);
        Assert.assertEquals("x264", option(legacy, "--encoder"));
        Assert.assertEquals("1920", option(legacy, "--maxWidth"));
        Assert.assertFalse(legacy.contains("--color-range"));
        Assert.assertThrows(IllegalArgumentException.class, () -> adapter.encodeCommand(
                Path.of("input.mkv"), Path.of("output.mp4"), scan, false, List.of(), "unknown-profile"));
    }

    @Test public void hevcUsesLegacySelectionFromJsonBeforeCountingSubtitleSlots() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(Files.readString(Path.of(getClass().getResource("/handbrake-title-set.txt").toURI()))
                .replace("JSON Title Set: ", ""));
        var audio = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("TitleList").get(0).path("AudioList");
        audio.removeAll();
        String[] languages = {"English", "English", "日本語", "Français"};
        String[] codes = {"eng", "eng", "jpn", "fra"};
        String[] codecs = {"dts", "eac3", "ac3", "ac3"};
        for (int i = 0; i < languages.length; i++) {
            audio.addObject().put("TrackNumber", i + 1).put("Language", languages[i]).put("LanguageCode", codes[i])
                    .put("CodecName", codecs[i]).put("ChannelCount", 6).put("ChannelLayout", "5.1(side)");
        }
        Path log = temp.getRoot().toPath().resolve("languages.log");
        Files.writeString(log, "JSON Title Set: " + root);
        var scan = HandBrakeAdapter.parseScan(log);
        Assert.assertEquals(List.of(2, 3), HandBrakeAdapter.selectedAudio(scan).stream().map(HandBrakeAdapter.Audio::track).toList());
        var args = new HandBrakeAdapter("HandBrakeCLI", new MediaProcessRunner()).encodeCommand(
                Path.of("input.mp4"), Path.of("output.mp4"), scan, false, manySubtitles(), HandBrakeAdapter.HEVC_PROFILE);
        Assert.assertEquals("2,3,2,3", option(args, "--audio"));
        Assert.assertEquals("av_aac,av_aac,copy:eac3,copy:ac3", option(args, "--aencoder"));
        Assert.assertEquals(26, option(args, "--srt-file").split(",").length);
    }

    @Test public void selectionKeepsForeignDolbyPrecedenceDeduplicationAndFallback() {
        var englishStereo = new HandBrakeAdapter.Audio(1, "aac", 2, "English", "stereo");
        var japanese = new HandBrakeAdapter.Audio(2, "eac3", 6, "Japanese", "5.1");
        var scan = new HandBrakeAdapter.Scan(1, 1000, 320, 180, List.of(englishStereo, japanese));
        Assert.assertEquals(List.of(japanese), HandBrakeAdapter.selectedAudio(scan));
        var french = new HandBrakeAdapter.Audio(3, "aac", 2, "French", "stereo");
        Assert.assertEquals(List.of(french), HandBrakeAdapter.selectedAudio(
                new HandBrakeAdapter.Scan(1, 1000, 320, 180, List.of(french))));
        Assert.assertThrows(IllegalArgumentException.class, () -> HandBrakeAdapter.selectedAudio(
                new HandBrakeAdapter.Scan(1, 1000, 320, 180, List.of())));
    }

    @Test public void rejectsSilentHdrLossAndColorChangesBeforePublication() throws Exception {
        var hdr = new HandBrakeAdapter.Video(10, 1, 9, 16, 9, 8, true, true);
        var source = new HandBrakeAdapter.Scan(1, 1000, 3840, 2160, List.of(), hdr);
        HandBrakeAdapter.validateHevcOutput(source, source);
        for (var changed : List.of(
                new HandBrakeAdapter.Video(8, 1, 9, 16, 9, 8, true, true),
                new HandBrakeAdapter.Video(10, 2, 9, 16, 9, 8, true, true),
                new HandBrakeAdapter.Video(10, 1, 1, 1, 1, 8, true, true),
                new HandBrakeAdapter.Video(10, 1, 9, 16, 9, 0, true, true),
                new HandBrakeAdapter.Video(10, 1, 9, 16, 9, 8, false, true),
                new HandBrakeAdapter.Video(10, 1, 9, 16, 9, 8, true, false))) {
            Assert.assertThrows(java.io.IOException.class, () -> HandBrakeAdapter.validateHevcOutput(source,
                    new HandBrakeAdapter.Scan(1, 1000, 3840, 2160, List.of(), changed)));
        }
    }

    @Test public void permitsOmittedPartialMasteringButRejectsLostCompleteMetadata() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(Files.readString(Path.of(getClass().getResource("/handbrake-title-set.txt").toURI()))
                .replace("JSON Title Set: ", ""));
        var title = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("TitleList").get(0);
        var mastering = title.putObject("MasteringDisplayColorVolume");
        mastering.put("HasPrimaries", true).put("HasLuminance", false);
        Path log = temp.getRoot().toPath().resolve("mastering.log");
        Files.writeString(log, "JSON Title Set: " + root);
        var partial = HandBrakeAdapter.parseScan(log);
        var color = partial.video();
        var output = new HandBrakeAdapter.Scan(1, 2005, 320, 180, partial.audio(),
                new HandBrakeAdapter.Video(10, color.range(), color.primaries(), color.transfer(), color.matrix(), 0, false, false));
        HandBrakeAdapter.validateHevcOutput(partial, output);
        mastering.put("HasLuminance", true);
        Files.writeString(log, "JSON Title Set: " + root);
        var complete = HandBrakeAdapter.parseScan(log);
        Assert.assertThrows(java.io.IOException.class, () -> HandBrakeAdapter.validateHevcOutput(complete, output));
    }

    @Test public void realHevcMain10PreservesFullAndLimitedRange() throws Exception {
        String handbrake = System.getProperty("viddyoop.handbrake");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        Assume.assumeTrue("Set handbrake and ffmpeg for native HEVC/range verification", handbrake != null && ffmpeg != null);
        Path root = temp.getRoot().toPath();
        var runner = new MediaProcessRunner();
        var adapter = new HandBrakeAdapter(handbrake, runner);
        for (String range : List.of("full", "limited")) {
            String tag = range.equals("full") ? "pc" : "tv";
            Path source = root.resolve(range + ".mkv"), output = root.resolve(range + ".mp4");
            runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=white:size=160x96:rate=24",
                    "-f", "lavfi", "-i", "anullsrc=sample_rate=48000:channel_layout=stereo", "-t", "1",
                    "-vf", "scale=in_range=limited:out_range=" + range + ",format=yuv420p10le", "-color_range", tag,
                    "-c:v", "ffv1", "-c:a", "ac3", source.toString()), root.resolve(range + "-fixture.log"),
                    java.time.Duration.ofMinutes(1), () -> {});
            var scan = adapter.scan(source, root.resolve(range + "-scan.log"), () -> {});
            adapter.encode(source, output, scan, false, root.resolve(range + "-encode.log"), () -> {},
                    List.of(), HandBrakeAdapter.HEVC_PROFILE);
            Path probe = root.resolve(range + "-output.json");
            runner.run(List.of(Path.of(ffmpeg).resolveSibling("ffprobe").toString(), "-v", "error", "-select_streams", "v:0",
                    "-show_streams", "-show_data", "-of", "json", output.toString()), probe, java.time.Duration.ofMinutes(1), () -> {});
            var video = new com.fasterxml.jackson.databind.ObjectMapper().readTree(probe.toFile()).path("streams").get(0);
            Assert.assertEquals("hevc", video.path("codec_name").asText());
            Assert.assertEquals("Main 10", video.path("profile").asText());
            Assert.assertEquals("yuv420p10le", video.path("pix_fmt").asText());
            Assert.assertEquals("hvc1", video.path("codec_tag_string").asText());
            Assert.assertEquals(150, video.path("level").asInt());
            StringBuilder configHex = new StringBuilder();
            for (String line : video.path("extradata").asText().split("\n")) {
                int colon = line.indexOf(':');
                if (colon >= 0) configHex.append(line.substring(colon + 1).split("  ", 2)[0].replaceAll("\\s", ""));
            }
            byte[] config = java.util.HexFormat.of().parseHex(configHex.toString());
            Assert.assertTrue("Missing HEVC decoder configuration", config.length >= 13 && config[0] == 1);
            Assert.assertEquals(2, config[1] & 31); // Main 10
            Assert.assertEquals(32, config[1] & 32); // High Tier
            Assert.assertEquals(150, config[12] & 255); // Level 5.0
            Path bitstream = root.resolve(range + "-options.hevc");
            runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", output.toString(), "-map", "0:v:0",
                    "-frames:v", "1", "-c:v", "copy", "-bsf:v", "hevc_mp4toannexb", "-f", "hevc", bitstream.toString()),
                    root.resolve(range + "-options.log"), java.time.Duration.ofMinutes(1), () -> {});
            String sei = new String(Files.readAllBytes(bitstream), java.nio.charset.StandardCharsets.ISO_8859_1);
            // Each iteration launches a fresh encoder. Check actual settings to catch
            // accidental drift across jobs, rather than trusting the planned options.
            for (String key : List.of("vbv-maxrate", "vbv-bufsize")) {
                var settings = java.util.regex.Pattern.compile(key + "=([0-9]+)").matcher(sei);
                Assert.assertTrue("Missing encoder setting " + key, settings.find());
                do { Assert.assertEquals("60000", settings.group(1)); } while (settings.find());
            }
            Assert.assertEquals(tag, video.path("color_range").asText());
            Assert.assertEquals(meanLuma(runner, ffmpeg, source, root.resolve(range + "-source-luma.log")),
                    meanLuma(runner, ffmpeg, output, root.resolve(range + "-output-luma.log")), 3.0);
        }
    }

    private static double meanLuma(MediaProcessRunner runner, String ffmpeg, Path video, Path log) throws Exception {
        runner.run(List.of(ffmpeg, "-nostdin", "-v", "error", "-i", video.toString(), "-frames:v", "1",
                "-vf", "signalstats,metadata=print:file=-", "-f", "null", "-"), log, java.time.Duration.ofMinutes(1), () -> {});
        var match = java.util.regex.Pattern.compile("(?m)^lavfi.signalstats.YAVG=([0-9.]+)$").matcher(Files.readString(log));
        Assert.assertTrue("Luma statistic missing", match.find());
        return Double.parseDouble(match.group(1));
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
        Assert.assertTrue(args.contains("--srt-default=3"));
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
