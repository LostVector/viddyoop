package com.rkuo.viddyoop.core.policy;

import java.nio.file.Path;
import org.junit.*;

public class LibraryNamesTest {
    @Test public void normalizesPresentationWithoutRenamingInboxFiles() {
        Assert.assertEquals("Movie.mp4", LibraryNames.outputName(Path.of("/inbox/Movie.mkv")));
        Assert.assertEquals("AAA-Movie-p1.mp4", LibraryNames.outputName(Path.of("/inbox/Movie-p1.r.mkv")));
        Assert.assertEquals("AAA-Movie-AC3-p1.mp4", LibraryNames.outputName(Path.of("Movie-p1-AC3.mkv")));
        Assert.assertEquals("AAA-Movie-p1.mp4", LibraryNames.outputName(Path.of("AAA-Movie-p1.mkv")));
    }
}
