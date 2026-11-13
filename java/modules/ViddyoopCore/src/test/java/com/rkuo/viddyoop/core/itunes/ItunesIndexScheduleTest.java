package com.rkuo.viddyoop.core.itunes;

import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class ItunesIndexScheduleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) { if (condition.call()) return; Thread.sleep(100); }
        Assert.fail("Scheduled iTunes index did not complete");
    }
    @Test public void scheduledIndexRefreshesAndRestartPreservesOperatorPause() throws Exception {
        String target = System.getProperty("viddyoop.temporal"); Assume.assumeTrue(target != null);
        Path xml = temp.getRoot().toPath().resolve("library.xml"), database = temp.getRoot().toPath().resolve("catalog.db");
        Files.writeString(xml, export("Original"));
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        var factory = WorkerFactory.newInstance(WorkflowClient.newInstance(service));
        var schedules = ScheduleClient.newInstance(service);
        String id = "viddyoop-index-test-" + UUID.randomUUID();
        var handle = schedules.getHandle(id);
        try {
            var worker = factory.newWorker(id);
            worker.registerWorkflowImplementationTypes(ItunesIndexWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ItunesIndexActivity() {
                public ItunesIndexer.Result index() {
                    try { return new ItunesIndexer(database).index(xml, () -> Activity.getExecutionContext().heartbeat("index")); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }
            });
            factory.start();
            ItunesIndexSchedule.ensure(schedules, id, id, Duration.ofHours(1));
            await(() -> new ItunesCatalog(database).lookup("Original", 2000, 0).candidates().size() == 1);
            await(() -> handle.describe().getInfo().getRunningActions().isEmpty());
            handle.pause("operator maintenance");
            ItunesIndexSchedule.ensure(schedules, id, id, Duration.ofHours(2));
            var description = handle.describe();
            Assert.assertTrue(description.getSchedule().getState().isPaused());
            Assert.assertEquals("operator maintenance", description.getSchedule().getState().getNote());
            Assert.assertEquals(Duration.ofHours(2), description.getSchedule().getSpec().getIntervals().get(0).getEvery());
            Files.writeString(xml, export("Refreshed"));
            handle.trigger();
            await(() -> new ItunesCatalog(database).lookup("Refreshed", 2000, 0).candidates().size() == 1);
            Assert.assertTrue(new ItunesCatalog(database).lookup("Original", 2000, 0).candidates().isEmpty());
            Assert.assertEquals(2, handle.describe().getInfo().getNumActions());
        } finally {
            try { handle.delete(); } finally { factory.shutdownNow(); service.shutdownNow(); }
        }
    }
    private static String export(String name) {
        return "<plist><dict><key>Library Persistent ID</key><string>LIB1</string><key>Tracks</key><dict>"
                + "<key>1</key><dict><key>Name</key><string>" + name + "</string>"
                + "<key>Total Time</key><integer>2000</integer></dict></dict></dict></plist>";
    }
}
