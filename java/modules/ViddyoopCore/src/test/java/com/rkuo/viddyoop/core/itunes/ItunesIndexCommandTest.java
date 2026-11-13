package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.worker.Viddyoop;
import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import picocli.CommandLine;

public class ItunesIndexCommandTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void oneShotCommandReportsIndexAndPreservesSource() throws Exception {
        Path xml = temp.getRoot().toPath().resolve("iTunes Library.xml"), db = temp.getRoot().toPath().resolve("catalog.db");
        String text = "<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict/></dict></plist>";
        Files.writeString(xml, text);
        StringWriter output = new StringWriter();
        Assert.assertEquals(0, new CommandLine(new Viddyoop()).setOut(new PrintWriter(output)).execute(
                "itunes-index", "--xml", xml.toString(), "--database", db.toString()));
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toString());
        Assert.assertEquals("LIB1", result.path("libraryId").asText()); Assert.assertEquals(0, result.path("items").asInt());
        Assert.assertEquals(text, Files.readString(xml));
    }
    @Test public void missingExportDoesNotCreateCatalog() throws Exception {
        Path db = temp.getRoot().toPath().resolve("catalog.db");
        Assert.assertEquals(1, new CommandLine(new Viddyoop()).setErr(new PrintWriter(new StringWriter())).execute(
                "itunes-index", "--xml", temp.getRoot().toPath().resolve("missing.xml").toString(), "--database", db.toString()));
        Assert.assertFalse(Files.exists(db));
    }
}
