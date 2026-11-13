package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.worker.Viddyoop;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

public class ItunesImportCommandTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void oneShotImportAndReconciliationNeverResendConsumedCopy() throws Exception {
        Path root = temp.getRoot().toPath(), library = root.resolve("library"), watched = root.resolve("add"), staging = root.resolve("stage");
        Files.createDirectories(library); Files.createDirectories(watched);
        Path source = library.resolve("movie.mp4"); Files.writeString(source, "verified media");
        Path db = root.resolve("catalog.db"); var artifacts = new ArtifactCatalog(db); String id = UUID.randomUUID().toString();
        artifacts.reserve(new ArtifactCatalog.Artifact(id,"/inbox/movie.mkv",source.toString(),MediaFiles.checksum(source,()->{}),2000,320,180,100,"test",false));
        artifacts.commit(id);
        String[] args = {"itunes-import","--database",db.toString(),"--library",library.toString(),"--add-folder",watched.toString(),
                "--staging",staging.toString(),"--submission",id};
        var output = new StringWriter(); var command = new CommandLine(new Viddyoop()).setOut(new PrintWriter(output));
        Assert.assertEquals(0, command.execute(args));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString());
        Assert.assertEquals("DELIVERED",json.path("outcome").asText());
        Path finalCopy = Path.of(json.path("watchedPath").asText()); Files.delete(finalCopy);
        output.getBuffer().setLength(0); Assert.assertEquals(0,command.execute(args));
        Assert.assertEquals("AMBIGUOUS",new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString()).path("outcome").asText());
        Assert.assertFalse(Files.exists(finalCopy)); Assert.assertEquals("verified media",Files.readString(source));
    }
    @Test public void automaticModeRequiresMediaMappingAndModesAreExclusive() {
        var command = new CommandLine(new Viddyoop()).setErr(new PrintWriter(new StringWriter()));
        Assert.assertEquals(1, command.execute("itunes-import","--serve","--submission",UUID.randomUUID().toString()));
        Assert.assertEquals(1, command.execute("itunes-import","--serve","--database",temp.getRoot().toPath().resolve("missing.db").toString()));
    }
    @Test public void refusesUnrelatedDatabaseWithoutInitializingIt() throws Exception {
        Path db = temp.getRoot().toPath().resolve("unrelated.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("CREATE TABLE unrelated(value TEXT)");
        }
        byte[] before = Files.readAllBytes(db);
        var command = new CommandLine(new Viddyoop()).setErr(new PrintWriter(new StringWriter()));
        Assert.assertEquals(1, command.execute("itunes-import", "--database", db.toString(), "--submission", UUID.randomUUID().toString()));
        Assert.assertArrayEquals(before, Files.readAllBytes(db));
    }
    @Test public void reconcileOnlyCanConfirmAnUnrecordedTransferWithoutSendingAnotherCopy() throws Exception {
        Path root = temp.getRoot().toPath(), library = root.resolve("library"), watched = root.resolve("add"), media = root.resolve("media");
        for (Path p : java.util.List.of(library, watched, media)) Files.createDirectories(p);
        Path source = library.resolve("movie.mp4"); Files.writeString(source, "encoded bytes");
        Path db = root.resolve("catalog.db"); String id = UUID.randomUUID().toString();
        var artifacts = new ArtifactCatalog(db);
        artifacts.reserve(new ArtifactCatalog.Artifact(id,"/inbox/movie.mkv",source.toString(),MediaFiles.checksum(source,()->{}),2000,320,180,100,"test",false));
        artifacts.commit(id);
        String[] args = {"itunes-import", "--reconcile-only", "--database", db.toString(), "--library", library.toString(),
                "--add-folder", watched.toString(), "--staging", root.resolve("stage").toString(), "--submission", id,
                "--host-media-root", "/Host/Media", "--media-root", media.toString()};
        var output = new StringWriter(); var command = new CommandLine(new Viddyoop()).setOut(new PrintWriter(output));
        Assert.assertEquals(0, command.execute(args));
        var receipts = new ItunesDeliveryCatalog(db);
        Assert.assertEquals(ItunesDeliveryCatalog.Outcome.PREPARED, receipts.find(id).outcome());
        Assert.assertNull(receipts.find(id).publishIntentAt());
        try (var files = Files.list(watched)) { Assert.assertEquals(0, files.count()); }
        Files.copy(source, media.resolve("imported.mp4"));
        Path xml = root.resolve("library.xml");
        Files.writeString(xml,"<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict>"
                +"<key>7</key><dict><key>Persistent ID</key><string>TRACK7</string><key>Total Time</key><integer>2000</integer>"
                +"<key>Location</key><string>file:///Host/Media/imported.mp4</string></dict></dict></dict></plist>");
        new ItunesIndexer(db).index(xml, () -> {});
        Assert.assertEquals(0, command.execute(args));
        Assert.assertEquals(ItunesDeliveryCatalog.Outcome.CONFIRMED, receipts.find(id).outcome());
        Assert.assertNull(receipts.find(id).publishIntentAt());
        try (var files = Files.list(watched)) { Assert.assertEquals(0, files.count()); }
    }
}
