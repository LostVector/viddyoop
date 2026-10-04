package com.rkuo.viddyoop.core.ingest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(JUnit4.class)
public class IngestWatcherTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path inbox;
    private IngestWatcher watcher;
    private Set<String> extensions;

    @Before
    public void setUp() throws Exception {
        inbox = tempFolder.newFolder("inbox").toPath();
        watcher = new IngestWatcher();
        extensions = new HashSet<String>(Arrays.asList(".mkv", ".avi"));
    }

    private void write(String name, String content) throws Exception {
        Files.write(inbox.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testFileBecomesReadyAfterStablePeriod() throws Exception {
        write("movie.mkv", "payload");

        // first scan: just recorded, not ready yet
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 0, 5000).isEmpty());

        // same size, but not stable long enough
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 4000, 5000).isEmpty());

        // same size, stable past the delay
        List<Path> ready = watcher.scanOnce(inbox, extensions, 5000, 5000);
        Assert.assertEquals(1, ready.size());
        Assert.assertEquals("movie.mkv", ready.get(0).getFileName().toString());
    }

    @Test
    public void testGrowingFileIsNotReady() throws Exception {
        write("downloading.mkv", "part1");
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 0, 1000).isEmpty());

        // file grew between scans: stability clock resets
        write("downloading.mkv", "part1-part2-longer");
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 500, 1000).isEmpty());

        // now stable from the new sighting
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 1400, 1000).isEmpty());
        List<Path> ready = watcher.scanOnce(inbox, extensions, 1600, 1000);
        Assert.assertEquals(1, ready.size());
    }

    @Test
    public void testIgnoresOtherExtensionsAndTmpFiles() throws Exception {
        write("movie.txt", "notes");
        write("partial.mkv.tmp", "still copying");
        write("real.mkv", "video");

        // first scan records sightings, second finds them stable (delay 0)
        watcher.scanOnce(inbox, extensions, 10000, 0);
        List<Path> ready = watcher.scanOnce(inbox, extensions, 10001, 0);
        Assert.assertEquals(1, ready.size());
        Assert.assertEquals("real.mkv", ready.get(0).getFileName().toString());
    }

    @Test
    public void testVanishedFileStopsBeingTracked() throws Exception {
        write("gone.mkv", "payload");
        watcher.scanOnce(inbox, extensions, 0, 1000);

        new File(inbox.resolve("gone.mkv").toString()).delete();
        // a scan observes the absence and stops tracking the file
        watcher.scanOnce(inbox, extensions, 2000, 1000);

        // re-created with identical size: must be re-seen, not instantly ready
        write("gone.mkv", "payload");
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 5000, 1000).isEmpty());
    }

    @Test
    public void testForgetStopsTracking() throws Exception {
        write("movie.mkv", "payload");
        watcher.scanOnce(inbox, extensions, 0, 1000);
        watcher.forget(inbox.resolve("movie.mkv"));

        // after forget, the file must be re-seen before it can become ready
        Assert.assertTrue(watcher.scanOnce(inbox, extensions, 9000, 1000).isEmpty());
    }
}
