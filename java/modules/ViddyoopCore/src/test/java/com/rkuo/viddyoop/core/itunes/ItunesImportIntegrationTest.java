package com.rkuo.viddyoop.core.itunes;

import com.rkuo.viddyoop.core.catalog.ArtifactCatalog;
import com.rkuo.viddyoop.core.media.MediaFiles;
import io.temporal.activity.Activity;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ItunesImportIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void durableWorkflowDeliversThenConfirmsSyntheticAppMoveAndExport() throws Exception {
        Path root=temp.getRoot().toPath(),library=root.resolve("library"),watched=root.resolve("add"),stage=root.resolve("stage"),media=root.resolve("media");
        for(Path path:java.util.List.of(library,watched,media)) Files.createDirectories(path);
        Path source=library.resolve("movie.mp4"); Files.writeString(source,"verified encoded bytes");
        Path db=root.resolve("catalog.db"); var artifacts=new ArtifactCatalog(db); String id=UUID.randomUUID().toString();
        artifacts.reserve(new ArtifactCatalog.Artifact(id,"/inbox/movie.mkv",source.toString(),MediaFiles.checksum(source,()->{}),2000,320,180,100,"test",false));
        artifacts.commit(id); var receipts=new ItunesDeliveryCatalog(db);
        Assert.assertEquals(java.util.List.of(id),receipts.pendingArtifactIds());
        var reconciler=new ItunesReconciler(artifacts,receipts,new ItunesCatalog(db),Path.of("/Host/Media"),media,null);
        var processor=new ItunesImportProcessor(receipts,new ItunesDelivery(artifacts,receipts,library,watched,stage,reconciler),reconciler);
        try(var env=TestWorkflowEnvironment.newInstance()) {
            var worker=env.newWorker("native-import"); worker.registerWorkflowImplementationTypes(ItunesImportWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ItunesImportActivity() {
                public ItunesDeliveryCatalog.Delivery process(String submission) {
                    try{return processor.process(submission,()->Activity.getExecutionContext().heartbeat("observe"));}
                    catch(Exception e){throw new IllegalStateException(e);}
                }
            }); env.start();
            new ItunesImportStarter(env.getWorkflowClient(),"native-import").start(id,1);
            Path delivered=watched.resolve("viddyoop-"+id+".mp4");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.exists(delivered)&&System.nanoTime()<deadline) Thread.sleep(10);
            Assert.assertTrue(Files.exists(delivered));
            Files.move(delivered,media.resolve("renamed.mp4"));
            Path xml=root.resolve("library.xml");
            Files.writeString(xml,"<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict>"
                    +"<key>42</key><dict><key>Persistent ID</key><string>TRACK42</string><key>Name</key><string>Renamed</string>"
                    +"<key>Total Time</key><integer>2000</integer><key>Location</key><string>file:///Host/Media/renamed.mp4</string>"
                    +"</dict></dict></dict></plist>");
            new ItunesIndexer(db).index(xml,()->{});
            var result=env.getWorkflowClient().newUntypedWorkflowStub("viddyoop-itunes-import-"+id)
                    .getResult(30,TimeUnit.SECONDS,ItunesDeliveryCatalog.Delivery.class);
            Assert.assertEquals(ItunesDeliveryCatalog.Outcome.CONFIRMED,result.outcome());
            Assert.assertEquals("LIB1",result.observedLibraryId()); Assert.assertEquals("TRACK42",result.observedPersistentId());
        }
        Assert.assertEquals("verified encoded bytes",Files.readString(source));
        Assert.assertTrue(receipts.pendingArtifactIds().isEmpty());
        try(var files=Files.list(watched)){Assert.assertEquals(0,files.count());}
    }
}
