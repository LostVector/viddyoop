package com.rkuo.viddyoop.core.ingest;

import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SourceEpochTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void recycledFileKeyCannotAuthorizeCleanupInAnotherGuestBoot() throws Exception {
        Path root=temp.getRoot().toPath(), source=root.resolve("movie.mkv"); Files.writeString(source,"source bytes");
        SourceFilesystem.Reader first=path->new SourceFilesystem.Stamp("virtiofs","boot-A");
        SourceFilesystem.Reader second=path->new SourceFilesystem.Stamp("virtiofs","boot-B");
        var snapshot=new SourceStager(root.resolve("work"),first).stage(source);
        Assert.assertTrue(SourceStager.matches(source,source,snapshot.fingerprint(),snapshot.generation(),()->{},first));
        // Same bytes, timestamps and inode, but a different boot must preserve the original.
        Assert.assertFalse(SourceStager.matches(source,source,snapshot.fingerprint(),snapshot.generation(),()->{},second));
        var next=new SourceStager(root.resolve("work"),second).stage(source);
        Assert.assertNotEquals(snapshot.generation(),next.generation());
        Assert.assertEquals("source bytes",Files.readString(source));
    }
}
