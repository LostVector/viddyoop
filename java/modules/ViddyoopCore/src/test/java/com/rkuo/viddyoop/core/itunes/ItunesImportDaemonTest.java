package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Post-package opt-in: real CLI/Temporal, synthetic iTunes consumption and XML export. */
public class ItunesImportDaemonTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<end){if(condition.call())return;Thread.sleep(100);}
        Assert.fail("Import daemon did not reach expected state");
    }
    @Test public void packagedDaemonResumesAmbiguousImportWithoutResending() throws Exception {
        String jar=System.getProperty("viddyoop.jar"),target=System.getProperty("viddyoop.temporal");
        Assume.assumeTrue(jar!=null&&target!=null);
        Path root=temp.getRoot().toPath(),library=root.resolve("library"),watched=root.resolve("add"),stage=root.resolve("stage"),media=root.resolve("media");
        for(Path p:List.of(library,watched,media))Files.createDirectories(p);
        Path source=library.resolve("movie.mp4");Files.writeString(source,"verified encoded bytes");
        Path db=root.resolve("catalog.db"),xml=root.resolve("library.xml");var artifacts=new ArtifactCatalog(db);String id=UUID.randomUUID().toString();
        artifacts.reserve(new ArtifactCatalog.Artifact(id,"/inbox/movie.mkv",source.toString(),MediaFiles.checksum(source,()->{}),2000,320,180,100,"test",false));artifacts.commit(id);
        Files.writeString(xml,"<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict/></dict></plist>");
        new ItunesIndexer(db).index(xml,()->{});
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",jar,"itunes-import","--serve",
                "--database",db.toString(),"--library",library.toString(),"--add-folder",watched.toString(),"--staging",stage.toString(),
                "--host-media-root","/Host/Media","--media-root",media.toString(),"--temporal",target,"--queue","import-daemon-"+id,
                "--check-seconds","1","--scan-seconds","1"));
        Process daemon=launch(command,root.resolve("first.log"));
        var service=WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        try{
            Path finalCopy=watched.resolve("viddyoop-"+id+".mp4");var receipts=new ItunesDeliveryCatalog(db);
            Process initial=daemon;
            await(()->{if(!initial.isAlive())throw new AssertionError(Files.readString(root.resolve("first.log")));return Files.exists(finalCopy);});
            Files.move(finalCopy,media.resolve("imported.mp4"));
            await(()->receipts.find(id)!=null&&receipts.find(id).outcome()==ItunesDeliveryCatalog.Outcome.AMBIGUOUS);
            daemon.destroy();Assert.assertTrue(daemon.waitFor(10,TimeUnit.SECONDS));
            daemon=launch(command,root.resolve("restarted.log"));
            Files.writeString(xml,"<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict>"
                    +"<key>7</key><dict><key>Persistent ID</key><string>TRACK7</string><key>Name</key><string>Imported</string>"
                    +"<key>Total Time</key><integer>2000</integer><key>Location</key><string>file:///Host/Media/imported.mp4</string>"
                    +"</dict></dict></dict></plist>");new ItunesIndexer(db).index(xml,()->{});
            var client=WorkflowClient.newInstance(service);
            var result=client.newUntypedWorkflowStub("viddyoop-itunes-import-"+id).getResult(30,TimeUnit.SECONDS,ItunesDeliveryCatalog.Delivery.class);
            Assert.assertEquals(ItunesDeliveryCatalog.Outcome.CONFIRMED,result.outcome());
            Assert.assertFalse(Files.exists(finalCopy));Assert.assertEquals("verified encoded bytes",Files.readString(source));
            Assert.assertEquals("TRACK7",result.observedPersistentId());Assert.assertTrue(daemon.isAlive());
        }finally{daemon.destroy();if(!daemon.waitFor(10,TimeUnit.SECONDS))daemon.destroyForcibly();service.shutdownNow();}
    }
    private static Process launch(List<String> command,Path log)throws Exception{
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key->key.startsWith("VIDYOOP_"));
        return builder.start();
    }

}
