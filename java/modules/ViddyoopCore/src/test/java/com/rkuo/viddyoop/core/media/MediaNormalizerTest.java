package com.rkuo.viddyoop.core.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class MediaNormalizerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void realRemuxExtractRepairAssConversionAndSubtitleEncode() throws Exception {
        String merge = System.getProperty("viddyoop.mkvmerge");
        String extract = System.getProperty("viddyoop.mkvextract");
        String ffmpeg = System.getProperty("viddyoop.ffmpeg");
        String handbrake = System.getProperty("viddyoop.handbrake");
        String fixture = System.getProperty("viddyoop.fixture");
        Assume.assumeTrue(merge != null && extract != null && ffmpeg != null && handbrake != null && fixture != null);
        Path root = temp.getRoot().toPath();
        Path srt = root.resolve("input.srt");
        Files.writeString(srt, "1\n00:00:00,100 --> 00:00:01,500\nHello from SRT\n\n");
        Path ass = root.resolve("input.ass");
        Files.writeString(ass, """
            [Script Info]
            ScriptType: v4.00+
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.10,0:00:01.50,Default,,0,0,0,,Hello from ASS
            """);
        var runner = new MediaProcessRunner();
        Path source = root.resolve("source.mkv");
        runner.run(List.of(merge, "--output", source.toString(), fixture, "--language", "0:eng",
                "--default-track-flag", "0:yes", srt.toString(), "--language", "0:fra",
                "--default-track-flag", "0:no", ass.toString()), root.resolve("fixture.log"), Duration.ofMinutes(1), () -> {});
        String original = MediaFiles.checksum(source, () -> {});
        Path work = root.resolve("normalize"); Files.createDirectories(work);
        var normalized = new MediaNormalizer(merge, extract, ffmpeg, runner).normalize(source, true, work, () -> {});
        Assert.assertEquals(original, MediaFiles.checksum(source, () -> {}));
        Assert.assertNotEquals(source.toString(), normalized.stagedPath);
        Assert.assertEquals(2, normalized.subtitles.size());
        Assert.assertEquals("eng", normalized.subtitles.get(0).language);
        Assert.assertTrue(normalized.subtitles.get(0).defaultTrack);
        Assert.assertTrue(Files.readString(Path.of(normalized.subtitles.get(0).path)).contains("Hello from SRT"));
        Assert.assertTrue(Files.readString(Path.of(normalized.subtitles.get(1).path)).contains("Hello from ASS"));
        var adapter = new HandBrakeAdapter(handbrake, runner);
        var scan = adapter.scan(Path.of(normalized.stagedPath), root.resolve("scan.log"), () -> {});
        Path output = root.resolve("encoded.mp4");
        adapter.encode(Path.of(normalized.stagedPath), output, scan, false, root.resolve("encode.log"), () -> {}, normalized.subtitles);
        Path verify = root.resolve("verify.log");
        adapter.scan(output, verify, () -> {});
        String text = Files.readString(verify);
        var json = new ObjectMapper().readTree(text.substring(text.indexOf('{', text.indexOf("JSON Title Set:"))));
        Assert.assertEquals(2, json.path("TitleList").get(0).path("SubtitleList").size());
    }
}
