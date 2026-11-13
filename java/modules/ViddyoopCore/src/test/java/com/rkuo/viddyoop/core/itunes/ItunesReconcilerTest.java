package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.nio.file.*;
import java.util.UUID;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog.Outcome.*;

public class ItunesReconcilerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root, db, library, watched, staging, media, rejected, xml;
    private String id;
    private ArtifactCatalog artifacts;
    private ItunesDeliveryCatalog receipts;
    private ItunesReconciler reconciler;
    private ItunesDelivery delivery;
    @Before public void setup() throws Exception {
        root = temp.getRoot().toPath(); db = root.resolve("catalog.db");
        library = root.resolve("library"); watched = root.resolve("add"); staging = root.resolve("stage");
        media = root.resolve("media"); rejected = root.resolve("rejected"); xml = root.resolve("library.xml");
        for (Path path : java.util.List.of(library, watched, staging, media, rejected)) Files.createDirectories(path);
        Path source = library.resolve("movie.mp4"); Files.writeString(source, "verified video bytes");
        id = UUID.randomUUID().toString(); artifacts = new ArtifactCatalog(db); receipts = new ItunesDeliveryCatalog(db);
        artifacts.reserve(new ArtifactCatalog.Artifact(id, "/inbox/movie.mkv", source.toString(), MediaFiles.checksum(source, () -> {}),
                2000, 320, 180, 100, "test", false)); artifacts.commit(id);
        reconciler = new ItunesReconciler(artifacts, receipts, new ItunesCatalog(db), Path.of("/Host/Media"), media, rejected);
        delivery = new ItunesDelivery(artifacts, receipts, library, watched, staging, reconciler);
    }
    private Path watchedFile() { return watched.resolve("viddyoop-" + id + ".mp4"); }
    private void index(String location) throws Exception {
        Files.writeString(xml, "<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict>"
                + "<key>12</key><dict><key>Persistent ID</key><string>TRACK12</string><key>Name</key><string>Renamed by app</string>"
                + "<key>Total Time</key><integer>2000</integer><key>Location</key><string>" + location
                + "</string></dict></dict></dict></plist>");
        new ItunesIndexer(db).index(xml, () -> {});
    }
    @Test public void consumedRenamedCopyIsConfirmedByExactContentAndLibraryIdentity() throws Exception {
        delivery.deliver(id, () -> {});
        Files.move(watchedFile(), media.resolve("Renamed Movie.mp4"));
        index("file://localhost/Host/Media/Renamed%20Movie.mp4");
        var result = reconciler.reconcile(id, () -> {});
        Assert.assertEquals(CONFIRMED, result.outcome());
        Assert.assertEquals("LIB1", result.observedLibraryId()); Assert.assertEquals(Long.valueOf(12), result.observedTrackId());
        Assert.assertEquals("TRACK12", result.observedPersistentId());
        Assert.assertFalse(Files.exists(watchedFile())); Assert.assertTrue(Files.exists(library.resolve("movie.mp4")));
    }
    @Test public void preflightSkipsDeliveryWhenExactContentAlreadyExistsInIndexedMedia() throws Exception {
        Files.copy(library.resolve("movie.mp4"), media.resolve("existing.mp4")); index("file:///Host/Media/existing.mp4");
        var result = delivery.deliver(id, () -> {});
        Assert.assertEquals(CONFIRMED, result.outcome()); Assert.assertNull(result.publishIntentAt());
        Assert.assertFalse(Files.exists(watchedFile()));
    }
    @Test public void durationNameOrDisappearanceAloneCannotConfirmOrResend() throws Exception {
        delivery.deliver(id, () -> {}); Files.delete(watchedFile());
        Files.writeString(media.resolve("wrong.mp4"), "different video"); index("file:///Host/Media/wrong.mp4");
        Assert.assertEquals(AMBIGUOUS, reconciler.reconcile(id, () -> {}).outcome());
        Assert.assertEquals(AMBIGUOUS, delivery.deliver(id, () -> {}).outcome()); Assert.assertFalse(Files.exists(watchedFile()));
        Files.delete(xml); // Missing source export cannot turn a previous read into positive evidence.
        Assert.assertEquals(AMBIGUOUS, reconciler.reconcile(id, () -> {}).outcome());
    }
    @Test public void matchingRejectionCopyIsObservedWithoutDeletingIt() throws Exception {
        delivery.deliver(id, () -> {});
        Path dated = rejected.resolve("2026-11-12"); Files.createDirectories(dated);
        Path failed = dated.resolve(watchedFile().getFileName()); Files.move(watchedFile(), failed);
        var result = reconciler.reconcile(id, () -> {});
        Assert.assertEquals(REJECTED, result.outcome()); Assert.assertTrue(Files.exists(failed));
        Assert.assertEquals(REJECTED, delivery.deliver(id, () -> {}).outcome()); Assert.assertFalse(Files.exists(watchedFile()));
    }
    @Test public void readingAnOldExportAgainDoesNotConfirmARecentHandoff() throws Exception {
        delivery.deliver(id, () -> {}); Files.move(watchedFile(), media.resolve("movie.mp4"));
        index("file:///Host/Media/movie.mp4");
        long intent = receipts.find(id).publishIntentAt();
        Files.setLastModifiedTime(xml, java.nio.file.attribute.FileTime.fromMillis(intent - 10000));
        new ItunesIndexer(db).index(xml, () -> {});
        Assert.assertEquals(AMBIGUOUS, reconciler.reconcile(id, () -> {}).outcome());
        Assert.assertFalse(Files.exists(watchedFile()));
    }

    @Test public void changedRejectionFileIsNotPositiveRejectionEvidence() throws Exception {
        delivery.deliver(id, () -> {}); Files.delete(watchedFile());
        Files.writeString(rejected.resolve(watchedFile().getFileName()), "unrelated bytes");
        Assert.assertEquals(AMBIGUOUS, reconciler.reconcile(id, () -> {}).outcome());
    }
    @Test public void outOfRootRemoteAndSymlinkLocationsCannotConfirm() throws Exception {
        delivery.deliver(id, () -> {}); Files.delete(watchedFile());
        Path outside = root.resolve("outside.mp4"); Files.copy(library.resolve("movie.mp4"), outside);
        Files.createSymbolicLink(media.resolve("link.mp4"), outside);
        for (String location : java.util.List.of("file:///Host/Media/link.mp4", "file:///Host/Media/../outside.mp4", "file:///Host/Media/%2e%2e/outside.mp4",
                "file://other-host/Host/Media/link.mp4", "https://example.com/movie.mp4")) {
            index(location); Assert.assertEquals(AMBIGUOUS, reconciler.reconcile(id, () -> {}).outcome());
        }
    }
    @Test public void indexedPrivateStagingFileIsNotImportConfirmation() throws Exception {
        Path staged = staging.resolve(id + ".mp4");
        receipts.prepare(artifacts.expected(id), watchedFile(), staged);
        Files.copy(library.resolve("movie.mp4"), staged);
        index("file:///Host/Media/stage/" + id + ".mp4");
        var broadMapping = new ItunesReconciler(artifacts, receipts, new ItunesCatalog(db), Path.of("/Host/Media"), root, rejected);
        Assert.assertEquals(PREPARED, broadMapping.reconcile(id, () -> {}).outcome());
        Assert.assertFalse(Files.exists(watchedFile()));
    }

    @Test public void genericObservationCannotAssertConfirmationWithoutIndexEvidence() throws Exception {
        delivery.deliver(id, () -> {});
        Assert.assertThrows(IllegalArgumentException.class, () -> receipts.observe(id, CONFIRMED, "unverified"));
        Assert.assertEquals(DELIVERED, receipts.find(id).outcome());
    }

    @Test public void waitingWatchedFileIsPendingRatherThanConfirmed() throws Exception {
        var result = delivery.deliver(id, () -> {});
        Assert.assertEquals(DELIVERED, result.outcome());
        Assert.assertEquals(DELIVERED, reconciler.reconcile(id, () -> {}).outcome()); Assert.assertTrue(Files.exists(watchedFile()));
    }
}
