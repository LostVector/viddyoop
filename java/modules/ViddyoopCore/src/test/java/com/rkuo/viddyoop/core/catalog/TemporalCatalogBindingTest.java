package com.rkuo.viddyoop.core.catalog;

import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class TemporalCatalogBindingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path database() { return temp.getRoot().toPath().resolve("catalog.db"); }
    @Test public void restartRetainsBindingAndRejectsRecreatedNamespace() throws Exception {
        Path db=database(); new SubmissionCatalog(db);
        TemporalCatalogBinding.bind(db,"default","original-id");
        new SubmissionCatalog(db).register(Path.of("/inbox/movie.mkv"),"hash","profile","discovery",Path.of("/library/movie.mp4"),0);
        TemporalCatalogBinding.bind(db,"default","original-id");
        Assert.assertThrows(IllegalStateException.class,()->TemporalCatalogBinding.bind(db,"default","recreated-id"));
        Assert.assertThrows(IllegalStateException.class,()->TemporalCatalogBinding.bind(db,"other","original-id"));
        TemporalCatalogBinding.bind(db,"default","original-id");
    }
    @Test public void unboundPendingAndAcknowledgedRegistrationsBothRequireReconciliation() throws Exception {
        Path db=database(); var catalog=new SubmissionCatalog(db);
        var input=catalog.register(Path.of("/inbox/movie.mkv"),"hash","profile","discovery",Path.of("/library/movie.mp4"),0);
        Assert.assertThrows(IllegalStateException.class,()->TemporalCatalogBinding.bind(db,"default","new-id"));
        catalog.acknowledgeStart(input.submissionId);
        Assert.assertThrows(IllegalStateException.class,()->TemporalCatalogBinding.bind(db,"default","new-id"));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM temporal_binding")) {
            Assert.assertTrue(r.next()); Assert.assertEquals(0,r.getInt(1));
        }
    }
    @Test public void concurrentDifferentNamespacesCannotBothClaimCatalog() throws Exception {
        Path db=database();new SubmissionCatalog(db);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();try{TemporalCatalogBinding.bind(db,"default","id-a");return true;}catch(IllegalStateException expected){return false;}});
            var b=pool.submit(()->{start.await();try{TemporalCatalogBinding.bind(db,"default","id-b");return true;}catch(IllegalStateException expected){return false;}});
            start.countDown();Assert.assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test public void missingNamespaceIdentityCannotCreateBinding() throws Exception {
        Assert.assertThrows(IllegalArgumentException.class,()->TemporalCatalogBinding.bind(database(),"default",""));
        Assert.assertThrows(IllegalArgumentException.class,()->TemporalCatalogBinding.bind(database(),"","id"));
        Assert.assertThrows(IllegalArgumentException.class,()->TemporalCatalogBinding.bind(database(),"default","valid-id"));
        Assert.assertFalse(java.nio.file.Files.exists(database()));
    }
    @Test public void readsActualNamespaceUuidFromTemporal() throws Exception {
        new SubmissionCatalog(database());
        try(var env=TestWorkflowEnvironment.newInstance()) {
            env.start();
            TemporalCatalogBinding.verify(database(),env.getWorkflowServiceStubs(),"default");
            TemporalCatalogBinding.verify(database(),env.getWorkflowServiceStubs(),"default");
            Assert.assertThrows(IllegalStateException.class,()->TemporalCatalogBinding.bind(database(),"default","different-id"));
        }
    }
}
