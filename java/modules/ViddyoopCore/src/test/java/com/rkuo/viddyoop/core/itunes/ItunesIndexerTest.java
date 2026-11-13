package com.rkuo.viddyoop.core.itunes;

import java.nio.file.*;
import java.sql.*;
import javax.xml.stream.XMLStreamException;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ItunesIndexerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path database, xml;
    private ItunesIndexer indexer;
    @Before public void setup() {
        database = temp.getRoot().toPath().resolve("catalog.db"); xml = temp.getRoot().toPath().resolve("iTunes Library.xml");
        indexer = new ItunesIndexer(database);
    }
    private static String track(int id, String name) {
        return "<key>" + id + "</key><dict><key>Track ID</key><integer>" + id + "</integer>"
                + "<key>Name</key><string>" + name + "</string><key>Persistent ID</key><string>TRACK" + id + "</string>"
                + "<key>Kind</key><string>MPEG-4 video file</string><key>Total Time</key><integer>2000</integer>"
                + "<key>Location</key><string>file://localhost/Media/Movie%20One.mp4</string>"
                + "<key>Date Added</key><date>2026-11-12T00:00:00Z</date>"
                + "<key>Ignored</key><array><dict><key>Nested</key><true/></dict></array></dict>";
    }
    private void write(String tracks) throws Exception {
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict><key>Major Version</key><integer>1</integer>
            <key>Library Persistent ID</key><string>LIBRARY1</string><key>Tracks</key><dict>
            """ + tracks + "</dict><key>Playlists</key><array><dict><key>Name</key><string>Ignored</string></dict></array></dict></plist>");
    }
    private long count() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM itunes_items")) { r.next(); return r.getLong(1); }
    }
    @Test public void indexesStandardPlistWithoutFetchingDtdOrChangingExport() throws Exception {
        write(track(1, "Movie &amp; One")); byte[] before = Files.readAllBytes(xml);
        var result = indexer.index(xml, () -> {});
        Assert.assertEquals(1, result.items()); Assert.assertEquals("LIBRARY1", result.libraryId());
        var match = new ItunesCatalog(database).lookup("movie & one", 2100, 100);
        Assert.assertEquals("LIBRARY1", match.index().libraryId()); Assert.assertEquals(1, match.candidates().size());
        Assert.assertEquals(Long.valueOf(Files.getLastModifiedTime(xml).toMillis()), match.index().sourceModifiedAt());
        Assert.assertEquals("TRACK1", match.candidates().get(0).persistentId());
        Assert.assertEquals("file://localhost/Media/Movie%20One.mp4", match.candidates().get(0).location());
        Assert.assertArrayEquals(before, Files.readAllBytes(xml));
    }
    @Test public void refreshRemovesAbsentTracksAndValidEmptyExportClearsIndex() throws Exception {
        write(track(1, "One") + track(2, "Two")); indexer.index(xml, () -> {});
        write(track(2, "Two")); indexer.index(xml, () -> {}); Assert.assertEquals(1, count());
        Assert.assertTrue(new ItunesCatalog(database).lookup("One", 2000, 0).candidates().isEmpty());
        write(""); indexer.index(xml, () -> {}); Assert.assertEquals(0, count());
        Assert.assertNotNull(new ItunesCatalog(database).lookup("One", 2000, 0).index());
    }
    @Test public void malformedDuplicateAndIdentitylessExportsPreservePreviousIndex() throws Exception {
        write(track(1, "One")); indexer.index(xml, () -> {});
        Files.writeString(xml, "<plist><dict><key>Tracks</key><dict>");
        Assert.assertThrows(XMLStreamException.class, () -> indexer.index(xml, () -> {})); Assert.assertEquals(1, count());
        write(track(2, "Two") + track(2, "Duplicate"));
        Assert.assertThrows(SQLException.class, () -> indexer.index(xml, () -> {})); Assert.assertEquals(1, count());
        Files.writeString(xml, "<plist><dict><key>Tracks</key><dict/></dict></plist>");
        Assert.assertThrows(XMLStreamException.class, () -> indexer.index(xml, () -> {})); Assert.assertEquals(1, count());
        Assert.assertEquals(1, new ItunesCatalog(database).lookup("One", 2000, 0).candidates().size());
    }
    @Test public void upgradesOriginalCatalogSchemaWithoutDroppingOtherDomainData() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE itunes_items(id INTEGER PRIMARY KEY, location TEXT, name TEXT, kind TEXT, duration_ms INTEGER, date_added TEXT)");
            s.executeUpdate("CREATE TABLE domain_marker(value TEXT)"); s.executeUpdate("INSERT INTO domain_marker VALUES('keep')");
        }
        write(track(1, "One")); indexer.index(xml, () -> {});
        Assert.assertEquals("TRACK1", new ItunesCatalog(database).lookup("One", 2000, 0).candidates().get(0).persistentId());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT value FROM domain_marker")) { Assert.assertTrue(r.next()); Assert.assertEquals("keep", r.getString(1)); }
    }

    @Test public void catalogCannotAliasTheReadOnlyExport() throws Exception {
        write(track(1, "One")); byte[] before = Files.readAllBytes(xml);
        Assert.assertThrows(java.io.IOException.class, () -> new ItunesIndexer(xml).index(xml, () -> {}));
        Files.createLink(database, xml);
        Assert.assertThrows(java.io.IOException.class, () -> indexer.index(xml, () -> {}));
        Assert.assertArrayEquals(before, Files.readAllBytes(xml));
    }

    @Test public void readersSeePreviousSnapshotUntilRefreshCommits() throws Exception {
        write(track(1, "Old")); indexer.index(xml, () -> {});
        StringBuilder tracks = new StringBuilder(); for (int i=1; i<=100; i++) tracks.append(track(i, "New")); write(tracks.toString());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var refresh = executor.submit(() -> indexer.index(xml, () -> {
                entered.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Reader did not release refresh"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(1, new ItunesCatalog(database).lookup("Old", 2000, 0).candidates().size());
            Assert.assertTrue(new ItunesCatalog(database).lookup("New", 2000, 0).candidates().isEmpty());
            release.countDown(); Assert.assertEquals(100, refresh.get(5, java.util.concurrent.TimeUnit.SECONDS).items());
            Assert.assertTrue(new ItunesCatalog(database).lookup("Old", 2000, 0).candidates().isEmpty());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void externalEntitiesCannotReadHostFiles() throws Exception {
        Path secret = temp.getRoot().toPath().resolve("secret.txt"); Files.writeString(secret, "must not enter catalog");
        write(track(1, "One")); indexer.index(xml, () -> {});
        Files.writeString(xml, "<!DOCTYPE plist [<!ENTITY leak SYSTEM '" + secret.toUri() + "'>]><plist><dict>"
                + "<key>Library Persistent ID</key><string>LIBRARY1</string><key>Tracks</key><dict>" + track(2, "&leak;")
                + "</dict></dict></plist>");
        Assert.assertThrows(XMLStreamException.class, () -> indexer.index(xml, () -> {})); Assert.assertEquals(1, count());
    }
    @Test public void exportChangedDuringReadRollsBackAndUnknownIndexIsNotAnEmptySuccess() throws Exception {
        Assert.assertNull(new ItunesCatalog(database).lookup("One", 2000, 0).index()); Assert.assertFalse(Files.exists(database));
        write(track(1, "One")); indexer.index(xml, () -> {});
        StringBuilder tracks = new StringBuilder(); for (int i=1; i<=100; i++) tracks.append(track(i, "Changed")); write(tracks.toString());
        Assert.assertThrows(java.io.IOException.class, () -> indexer.index(xml, () -> {
            try { Files.setLastModifiedTime(xml, java.nio.file.attribute.FileTime.fromMillis(1)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }));
        Assert.assertEquals(1, count());
    }
}
