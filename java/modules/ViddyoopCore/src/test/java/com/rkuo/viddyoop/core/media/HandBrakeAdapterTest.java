package com.rkuo.viddyoop.core.media;

import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class HandBrakeAdapterTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void parsesCapturedRealScanAndRejectsInvalidMedia() throws Exception {
        Path log = Path.of(getClass().getResource("/handbrake-title-set.log").toURI());
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
