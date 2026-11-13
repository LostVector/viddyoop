package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import java.io.IOException;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class SharedInboxGuardTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private SharedInboxGuard guard(Path inbox, Path work, SubmissionCatalog catalog, String type, String epoch) throws Exception {
        return new SharedInboxGuard(inbox, work, catalog, path -> new SourceFilesystem.Stamp(type, epoch));
    }
    @Test public void remountPausesKnownSourcesWithoutTouchingThemThenResumesAfterQuarantine() throws Exception {
        Path root = temp.getRoot().toPath(), inbox = temp.newFolder("inbox").toPath(), work = temp.newFolder("work").toPath();
        var catalog = new SubmissionCatalog(root.resolve("catalog.db"));
        Assert.assertFalse(guard(inbox,work,catalog,"virtiofs","boot-A").check().paused());
        Path original = inbox.resolve("movie.mkv"); Files.writeString(original,"original");
        var input = catalog.register(original,"hash","profile","discovery",root.resolve("movie.mp4"),0);
        Assert.assertFalse(guard(inbox,work,catalog,"virtiofs","boot-A").check().paused());
        var restarted = guard(inbox,work,catalog,"virtiofs","boot-B");
        var status = restarted.check();
        Assert.assertTrue(status.paused()); Assert.assertEquals(java.util.List.of(original.toString()),status.sources());
        Assert.assertEquals("original",Files.readString(original));
        Assert.assertNotNull(catalog.find(input.submissionId)); Assert.assertEquals(1,catalog.pendingStarts().size());
        Assert.assertTrue(Files.isRegularFile(work.resolve(".ingest-reconcile.json")));
        Files.move(original, root.resolve("quarantined.mkv"));
        Assert.assertFalse(restarted.check().paused());
        Assert.assertFalse(Files.exists(work.resolve(".ingest-reconcile.json")));
        Assert.assertFalse(guard(inbox,work,catalog,"virtiofs","boot-B").check().paused());
    }
    @Test public void missingBaselineDoesNotAdoptRegisteredSharedInputs() throws Exception {
        Path root=temp.getRoot().toPath(),inbox=temp.newFolder("inbox").toPath(),work=temp.newFolder("work").toPath();
        var catalog=new SubmissionCatalog(root.resolve("catalog.db")); Path source=inbox.resolve("movie.mkv");Files.writeString(source,"source");
        catalog.register(source,"hash","profile","discovery",root.resolve("movie.mp4"),0);
        Assert.assertTrue(guard(inbox,work,catalog,"virtiofs","boot-A").check().paused());
        Assert.assertFalse(Files.exists(work.resolve(".ingest-epoch.json")));
        // Ordinary local filesystems retain the existing generation behavior.
        Assert.assertFalse(guard(inbox,work,catalog,"ext4","").check().paused());
    }
    @Test public void corruptMetadataAndChangedBindingFailClosed() throws Exception {
        Path root=temp.getRoot().toPath(),inbox=temp.newFolder("inbox").toPath(),work=temp.newFolder("work").toPath();
        var catalog=new SubmissionCatalog(root.resolve("catalog.db"));
        guard(inbox,work,catalog,"virtiofs","boot-A").check();
        Assert.assertThrows(IOException.class,()->guard(inbox,work,catalog,"ext4",""));
        Path other=temp.newFolder("other").toPath();
        Assert.assertThrows(IOException.class,()->guard(other,work,catalog,"virtiofs","boot-A"));
        Files.writeString(work.resolve(".ingest-epoch.json"),"broken");
        Assert.assertThrows(IOException.class,()->guard(inbox,work,catalog,"virtiofs","boot-A"));
    }
    @Test public void changedEpochWithoutRegisteredInboxFilesCanResumeAutomatically() throws Exception {
        Path root=temp.getRoot().toPath(),inbox=temp.newFolder("inbox").toPath(),work=temp.newFolder("work").toPath();
        var catalog=new SubmissionCatalog(root.resolve("catalog.db"));
        guard(inbox,work,catalog,"virtiofs","boot-A").check();
        Files.writeString(inbox.resolve("new.mkv"),"new unregistered arrival");
        Assert.assertFalse(guard(inbox,work,catalog,"virtiofs","boot-B").check().paused());
    }
    @Test public void failedEpochCommitCannotOpenAdmission() throws Exception {
        Path root=temp.getRoot().toPath(),inbox=temp.newFolder("inbox").toPath(),work=temp.newFolder("work").toPath();
        var catalog=new SubmissionCatalog(root.resolve("catalog.db"));
        guard(inbox,work,catalog,"virtiofs","boot-A").check();
        Path source=inbox.resolve("movie.mkv");
        catalog.register(source,"hash","profile","discovery",root.resolve("movie.mp4"),0);
        var restarted=guard(inbox,work,catalog,"virtiofs","boot-B");
        Path receipt=work.resolve(".ingest-epoch.json");Files.delete(receipt);Files.createDirectory(receipt);
        Assert.assertThrows(IOException.class,restarted::check);
        Files.writeString(source,"registered source appeared before epoch was committed");
        Assert.assertTrue(restarted.check().paused());
    }
}
