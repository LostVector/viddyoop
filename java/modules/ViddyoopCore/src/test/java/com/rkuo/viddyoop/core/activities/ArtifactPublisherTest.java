package com.rkuo.viddyoop.core.activities;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.catalog.ArtifactCatalog.Artifact;
import com.rkuo.viddyoop.core.media.MediaFiles;
import java.nio.file.*;
import java.sql.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ArtifactPublisherTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path database, library, encoded, destination;
    private ArtifactCatalog catalog;
    private ArtifactPublisher publisher;
    private Artifact artifact;

    @Before public void setup() throws Exception {
        Path root = temp.getRoot().toPath();
        database = root.resolve("catalog.db"); library = root.resolve("library");
        encoded = root.resolve("encoded.mp4"); destination = library.resolve("movie.mp4");
        Files.writeString(encoded, "verified encoded bytes");
        catalog = new ArtifactCatalog(database); publisher = new ArtifactPublisher(catalog, library);
        artifact = new Artifact("submission-1", "/inbox/movie.mkv", destination.toString(),
                MediaFiles.checksum(encoded, () -> {}), 2000, 320, 180, 100, "test", false);
    }

    @Test public void repeatedPublishCreatesOneArtifactAndStatsRow() throws Exception {
        publisher.publish(artifact, encoded, () -> {});
        Files.delete(encoded); // retry must reconcile even after attempt output disappears
        publisher.publish(artifact, encoded, () -> {});
        Assert.assertTrue(catalog.expected(artifact.submissionId()).committed());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM encode_stats")) {
            Assert.assertTrue(r.next()); Assert.assertEquals(1, r.getInt(1));
        }
    }

    @Test public void crashAfterFilesystemCommitReconcilesOnRestart() throws Exception {
        catalog.reserve(artifact);
        Files.copy(encoded, destination); // crash after file commit, before catalog commit
        Assert.assertFalse(catalog.expected(artifact.submissionId()).committed());
        catalog = new ArtifactCatalog(database);
        new ArtifactPublisher(catalog, library).publish(artifact, encoded, () -> {});
        Assert.assertTrue(catalog.expected(artifact.submissionId()).committed());
    }

    @Test public void conflictIsNeverOverwritten() throws Exception {
        Files.writeString(destination, "unrelated existing media");
        Assert.assertThrows(java.io.IOException.class, () -> publisher.publish(artifact, encoded, () -> {}));
        Assert.assertEquals("unrelated existing media", Files.readString(destination));
        Assert.assertFalse(catalog.expected(artifact.submissionId()).committed());
    }

    @Test public void damagedOutputLeavesNoFinalFileAndCanRetry() throws Exception {
        Files.writeString(encoded, "truncated");
        Assert.assertThrows(java.io.IOException.class, () -> publisher.publish(artifact, encoded, () -> {}));
        Assert.assertFalse(Files.exists(destination));
        Files.writeString(encoded, "verified encoded bytes");
        publisher.publish(artifact, encoded, () -> {});
        Assert.assertTrue(catalog.expected(artifact.submissionId()).committed());
    }

    @Test public void secondSubmissionCannotTakeReservedDestination() throws Exception {
        catalog.reserve(artifact);
        var other = new Artifact("submission-2", artifact.source(), artifact.destination(), artifact.checksum(),
                2000, 320, 180, 100, "test", false);
        Assert.assertThrows(SQLException.class, () -> publisher.publish(other, encoded, () -> {}));
        Assert.assertFalse(Files.exists(destination));
    }
}
