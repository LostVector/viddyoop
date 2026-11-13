package com.rkuo.viddyoop.core.itunes;

import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import static com.rkuo.viddyoop.core.itunes.ItunesDeliveryCatalog.Outcome.*;

public class ItunesImportWorkflowTest {
    private static ItunesDeliveryCatalog.Delivery receipt(String id, ItunesDeliveryCatalog.Outcome outcome) {
        return new ItunesDeliveryCatalog.Delivery(id,"artifact","hash","watched","staged",0,1L,2L,3L,outcome,"note",null,null,null);
    }
    @Test public void ambiguousOutcomeContinuesObservingUntilConfirmed() {
        var calls = new AtomicInteger();
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var worker = env.newWorker("import-test"); worker.registerWorkflowImplementationTypes(ItunesImportWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ItunesImportActivity() {
                public ItunesDeliveryCatalog.Delivery process(String id) {
                    int call = calls.incrementAndGet(); return receipt(id, call == 1 ? DELIVERED : call == 2 ? AMBIGUOUS : CONFIRMED);
                }
            }); env.start();
            var stub = env.getWorkflowClient().newWorkflowStub(ItunesImportWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("import-test").build());
            Assert.assertEquals(CONFIRMED, stub.run(UUID.randomUUID().toString(), 1).outcome()); Assert.assertEquals(3, calls.get());
        }
    }
    @Test public void observationHistoryContinuesAsNewAndRejectionEndsWorkflow() {
        var calls = new AtomicInteger();
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var worker = env.newWorker("rollover-test"); worker.registerWorkflowImplementationTypes(ItunesImportWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ItunesImportActivity() {
                public ItunesDeliveryCatalog.Delivery process(String id) { return receipt(id, calls.incrementAndGet() <= 100 ? AMBIGUOUS : REJECTED); }
            }); env.start();
            var stub = env.getWorkflowClient().newWorkflowStub(ItunesImportWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("rollover-test").build());
            Assert.assertEquals(REJECTED, stub.run(UUID.randomUUID().toString(), 1).outcome()); Assert.assertEquals(101, calls.get());
        }
    }
    @Test public void invalidObservationIntervalFailsInsteadOfRetryingWorkflowTasksForever() {
        try(var env=TestWorkflowEnvironment.newInstance()) {
            env.newWorker("invalid-import").registerWorkflowImplementationTypes(ItunesImportWorkflowImpl.class); env.start();
            var stub=env.getWorkflowClient().newWorkflowStub(ItunesImportWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("invalid-import").build());
            Assert.assertThrows(WorkflowFailedException.class,()->stub.run(UUID.randomUUID().toString(),0));
        }
    }

    @Test public void workflowStarterDoesNotDuplicateRunningOrTerminalExecutions() {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            env.start(); String id = UUID.randomUUID().toString();
            var starter = new ItunesImportStarter(env.getWorkflowClient(), "no-worker");
            Assert.assertTrue(starter.start(id, 1)); Assert.assertFalse(starter.start(id, 1));
            env.getWorkflowClient().newUntypedWorkflowStub("viddyoop-itunes-import-" + id).terminate("test");
            Assert.assertFalse(starter.start(id, 1));
        }
    }
}
