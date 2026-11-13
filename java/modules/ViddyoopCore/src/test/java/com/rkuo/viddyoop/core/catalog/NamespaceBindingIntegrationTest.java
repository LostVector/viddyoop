package com.rkuo.viddyoop.core.catalog;

import io.temporal.serviceclient.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Opt-in packaged CLI check: a restored catalog must not attach to a new namespace UUID. */
public class NamespaceBindingIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void allPackagedWorkersRefuseMismatchedNamespaceBeforeStartingWork() throws Exception {
        String jar=System.getProperty("viddyoop.jar"),target=System.getProperty("viddyoop.temporal");
        Assume.assumeTrue(jar!=null&&target!=null);
        Path root=temp.getRoot().toPath(),db=root.resolve("catalog.db"),inbox=root.resolve("inbox"),work=root.resolve("work"),library=root.resolve("library");
        for(Path directory:List.of(inbox,work,library,root.resolve("add"),root.resolve("media")))Files.createDirectories(directory);
        var catalog=new SubmissionCatalog(db);new ArtifactCatalog(db);
        var service=WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try { TemporalCatalogBinding.verify(db,service,"default"); } finally {service.shutdownNow();}
        // Preserve an earlier namespace UUID while the configured server exposes a new one.
        String previousId=UUID.randomUUID().toString();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.prepareStatement("UPDATE temporal_binding SET namespace_id=?")) {
            s.setString(1,previousId);Assert.assertEquals(1,s.executeUpdate());
        }
        Path original=inbox.resolve("movie.mkv");Files.writeString(original,"input retained before media tools run");
        var input=catalog.register(original,"hash","apple-tv-v1","discovery",library.resolve("movie.mp4"),0);
        List<List<String>> commands=List.of(
                List.of("pipeline","--inbox",inbox.toString(),"--work",work.toString(),"--library",library.toString(),"--archive-policy","none"),
                List.of("itunes-index","--serve","--xml",root.resolve("export.xml").toString()),
                List.of("itunes-import","--serve","--library",library.toString(),"--add-folder",root.resolve("add").toString(),
                        "--staging",root.resolve("stage").toString(),"--host-media-root","/Host/Media","--media-root",root.resolve("media").toString()));
        for(List<String> args:commands) {
            Path log=root.resolve(args.get(0)+".log");
            var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",jar));
            command.addAll(args);command.addAll(List.of("--database",db.toString(),"--temporal",target));
            var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("VIDYOOP_"));
            Process process=builder.start();
            try {
                Assert.assertTrue("Worker stayed alive: "+Files.readString(log),process.waitFor(20,TimeUnit.SECONDS));
                Assert.assertEquals(Files.readString(log),1,process.exitValue());
                Assert.assertTrue(Files.readString(log),Files.readString(log).contains("namespace identity differs"));
            } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
        }
        Assert.assertEquals(input.submissionId,catalog.pendingStarts().get(0).submissionId);
        Assert.assertEquals("input retained before media tools run",Files.readString(original));
        Assert.assertFalse(Files.exists(library.resolve("movie.mp4")));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT namespace_id FROM temporal_binding")) {
            Assert.assertTrue(r.next());Assert.assertEquals(previousId,r.getString(1));
        }
    }
}
