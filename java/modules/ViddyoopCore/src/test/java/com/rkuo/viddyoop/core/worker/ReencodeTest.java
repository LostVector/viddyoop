package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.ingest.*;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

public class ReencodeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void retriesReuseIdentityAndReencodeNeverClaimsOriginalForCleanup() throws Exception {
        Path root = temp.getRoot().toPath(), source = root.resolve("movie.mkv"); Files.writeString(source, "original");
        Path database = root.resolve("catalog.db"), library = root.resolve("library");
        var catalog = new SubmissionCatalog(database);
        var original = new IngestCoordinator(new SourceStager(root.resolve("work")), catalog).accept(
                source, "apple-tv-v1", "discovery", library.resolve("movie.mp4"), 0, "movies", root.resolve("archive"));
        String[] args = {"reencode", "--database", database.toString(), "--library", library.toString(), "--submission",
                original.submissionId, "--request-key", "manual-1", "--output", "movie-v2.mp4"};
        Files.delete(source);
        StringWriter output = new StringWriter();
        var command = new CommandLine(new Viddyoop()).setOut(new PrintWriter(output));
        Assert.assertEquals(0, command.execute(args));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString());
        String id = json.path("submissionId").asText();
        Assert.assertNotEquals(original.submissionId, id);
        Assert.assertNull(catalog.find(id).sourceGeneration);
        Assert.assertEquals(original.stagedSourcePath, catalog.find(id).stagedSourcePath);
        Assert.assertEquals(original.archiveRoot, catalog.find(id).archiveRoot);
        output.getBuffer().setLength(0);
        Assert.assertEquals(0, command.execute(args));
        Assert.assertEquals(id, new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString()).path("submissionId").asText());
        Assert.assertEquals(2, catalog.pendingStarts().size());
    }
    @Test public void corruptSnapshotAndUnsafeOutputDoNotRegisterWork() throws Exception {
        Path root = temp.getRoot().toPath(), source = root.resolve("movie.mkv"); Files.writeString(source, "original");
        Path database = root.resolve("catalog.db"), library = root.resolve("library");
        var catalog = new SubmissionCatalog(database);
        var original = new IngestCoordinator(new SourceStager(root.resolve("work")), catalog).accept(
                source, "apple-tv-v1", "discovery", library.resolve("movie.mp4"), 0);
        var command = new CommandLine(new Viddyoop()).setErr(new PrintWriter(new StringWriter()));
        String[] base = {"reencode", "--database", database.toString(), "--library", library.toString(),
                "--submission", original.submissionId, "--request-key", "manual", "--output", "../outside.mp4"};
        Assert.assertEquals(1, command.execute(base));
        base[base.length - 1] = "movie.mp4";
        Assert.assertEquals(1, command.execute(base));
        Files.writeString(Path.of(original.stagedSourcePath), "damaged");
        base[base.length - 1] = "new.mp4";
        Assert.assertEquals(1, command.execute(base));
        Assert.assertEquals(1, catalog.pendingStarts().size());
        Assert.assertEquals("original", Files.readString(source));
    }

    @Test public void rejectsMissingDatabaseWithoutCreatingIt() throws Exception {
        Path missing = temp.getRoot().toPath().resolve("missing.db");
        var command = new CommandLine(new Viddyoop()).setErr(new PrintWriter(new StringWriter()));
        Assert.assertEquals(1, command.execute("reencode", "--database", missing.toString(), "--submission", "none",
                "--request-key", "request", "--output", "new.mp4"));
        Assert.assertFalse(Files.exists(missing));
    }
}
