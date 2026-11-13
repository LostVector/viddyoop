package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.nio.file.*;
import java.util.UUID;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog.Outcome.*;

public class ItunesDeliveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path root, database, library, watched, staging, source;
    private String id;
    private ArtifactCatalog artifacts;
    private ItunesDeliveryCatalog receipts;
    private ArtifactCatalog.Artifact artifact;
    private ItunesDelivery service;
    @Before public void setup() throws Exception {
        root = temp.getRoot().toPath(); database = root.resolve("catalog.db");
        library = root.resolve("library"); watched = root.resolve("Automatically Add"); staging = root.resolve("staging");
        Files.createDirectories(library); Files.createDirectories(watched); source = library.resolve("movie.mp4"); Files.writeString(source, "verified encoded bytes");
        id = UUID.randomUUID().toString(); artifacts = new ArtifactCatalog(database); receipts = new ItunesDeliveryCatalog(database);
        artifact = new ArtifactCatalog.Artifact(id, "/inbox/movie.mkv", source.toString(), MediaFiles.checksum(source, () -> {}),
                2000, 320, 180, 100, "test", false);
        artifacts.reserve(artifact); artifacts.commit(id); artifact = artifacts.expected(id);
        service = new ItunesDelivery(artifacts, receipts, library, watched, staging);
    }
    private Path destination() { return watched.resolve("viddyoop-" + id + ".mp4"); }
    private Path staged() { return staging.resolve(id + ".mp4"); }

    @Test public void deliversCompleteCopyAndPreservesAuthoritativeLibraryOnRetries() throws Exception {
        var result = service.deliver(id, () -> {});
        Assert.assertEquals(DELIVERED, result.outcome()); Assert.assertNotNull(result.deliveredAt());
        Assert.assertEquals("verified encoded bytes", Files.readString(destination()));
        Assert.assertEquals("verified encoded bytes", Files.readString(source));
        Assert.assertFalse(Files.exists(staged()));
        var retry = new ItunesDelivery(artifacts, new ItunesDeliveryCatalog(database), library, watched, staging).deliver(id, () -> {});
        Assert.assertEquals(result.deliveredAt(), retry.deliveredAt());
        try (var names = Files.list(watched)) { Assert.assertEquals(1, names.count()); }
    }
    @Test public void disappearanceIsAmbiguousAndNeverCausesAutomaticRedelivery() throws Exception {
        service.deliver(id, () -> {}); Files.delete(destination());
        Assert.assertEquals(AMBIGUOUS, service.deliver(id, () -> {}).outcome());
        Assert.assertEquals(AMBIGUOUS, service.deliver(id, () -> {}).outcome());
        Assert.assertFalse(Files.exists(destination())); Assert.assertTrue(Files.exists(source));
        Assert.assertNotNull(receipts.find(id).observedAt());
    }
    @Test public void crashBeforePublishIntentCanRetryIncompleteStagingSafely() throws Exception {
        receipts.prepare(artifact, destination(), staged()); Files.writeString(staged(), "partial copy");
        Assert.assertEquals(DELIVERED, service.deliver(id, () -> {}).outcome());
        Assert.assertEquals(artifact.checksum(), MediaFiles.checksum(destination(), () -> {}));
    }
    @Test public void crashAfterIntentWithoutFinalNameRequiresReconciliation() throws Exception {
        receipts.prepare(artifact, destination(), staged()); receipts.publishIntent(id);
        Assert.assertEquals(AMBIGUOUS, service.deliver(id, () -> {}).outcome());
        Assert.assertFalse(Files.exists(destination()));
    }
    @Test public void crashAfterFinalNameCreationReconcilesExistingCopy() throws Exception {
        receipts.prepare(artifact, destination(), staged()); receipts.publishIntent(id); Files.copy(source, staged()); Files.createLink(destination(), staged());
        Assert.assertEquals(DELIVERED, service.deliver(id, () -> {}).outcome());
        Assert.assertNotNull(receipts.find(id).deliveredAt()); Assert.assertFalse(Files.exists(staged()));
    }
    @Test public void uncommittedCorruptAndConflictingFilesCannotBeDelivered() throws Exception {
        Files.writeString(source, "corrupt");
        Assert.assertThrows(java.io.IOException.class, () -> service.deliver(id, () -> {}));
        Assert.assertFalse(Files.exists(destination())); Assert.assertNull(receipts.find(id).publishIntentAt());
        Files.writeString(source, "verified encoded bytes"); Files.writeString(destination(), "unrelated");
        Assert.assertThrows(java.io.IOException.class, () -> service.deliver(id, () -> {}));
        Assert.assertEquals("unrelated", Files.readString(destination()));
        String uncommitted = UUID.randomUUID().toString();
        artifacts.reserve(new ArtifactCatalog.Artifact(uncommitted, "/other", library.resolve("other.mp4").toString(), "hash", 1, 1, 1, 1, "test", false));
        Assert.assertThrows(java.io.IOException.class, () -> service.deliver(uncommitted, () -> {}));
        Assert.assertNull(receipts.find(uncommitted));
    }
    @Test public void changedConfigurationAndStagingInsideWatchedFolderAreRejected() throws Exception {
        service.deliver(id, () -> {});
        Files.createDirectories(root.resolve("other-add"));
        var moved = new ItunesDelivery(artifacts, receipts, library, root.resolve("other-add"), staging);
        Assert.assertThrows(java.sql.SQLException.class, () -> moved.deliver(id, () -> {}));
        Assert.assertThrows(java.io.IOException.class, () -> new ItunesDelivery(artifacts, receipts, library, watched, watched.resolve("partial")));
    }
    @Test public void appMayModifyConsumedCopyWithoutChangingLibraryOrBlockingOwnedStagingCleanup() throws Exception {
        receipts.prepare(artifact, destination(), staged()); receipts.publishIntent(id);
        Files.copy(source, staged()); Files.createLink(destination(), staged()); receipts.delivered(id);
        Path imported = root.resolve("app-imported.mp4"); Files.move(destination(), imported);
        Files.writeString(imported, "iTunes metadata changed");
        Assert.assertEquals(AMBIGUOUS, service.deliver(id, () -> {}).outcome());
        Assert.assertFalse(Files.exists(staged()));
        Assert.assertEquals("iTunes metadata changed", Files.readString(imported));
        Assert.assertEquals("verified encoded bytes", Files.readString(source));
    }

    @Test public void terminalReceiptNeverRecreatesConsumedFiles() throws Exception {
        service.deliver(id, () -> {}); Files.delete(destination());
        receipts.observe(id, REJECTED, "Observed matching file in Not Added");
        Assert.assertEquals(REJECTED, service.deliver(id, () -> {}).outcome()); Assert.assertFalse(Files.exists(destination()));
    }
}
