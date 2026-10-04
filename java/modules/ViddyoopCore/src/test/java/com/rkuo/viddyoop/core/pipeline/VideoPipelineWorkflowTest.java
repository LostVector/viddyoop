package com.rkuo.viddyoop.core.pipeline;

import com.rkuo.viddyoop.core.activities.EncodeActivity;
import com.rkuo.viddyoop.core.activities.PreprocessActivity;
import com.rkuo.viddyoop.core.activities.PublishActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VideoPipelineWorkflowTest {

    private static final String QUEUE = "viddyoop-test";

    private TestWorkflowEnvironment env;

    @Before
    public void setUp() {
        env = TestWorkflowEnvironment.newInstance();
    }

    @After
    public void tearDown() {
        env.close();
    }

    // ---- fakes ---------------------------------------------------------

    static class FakePreprocess implements PreprocessActivity {
        int calls = 0;
        int failuresBeforeSuccess = 0;
        boolean alwaysFail = false;

        @Override
        public PreprocessResult preprocess(PipelineInput input) {
            calls++;
            if (alwaysFail || (failuresBeforeSuccess > 0 && calls <= failuresBeforeSuccess)) {
                throw new RuntimeException("preprocess boom " + calls);
            }
            PreprocessResult result = new PreprocessResult();
            result.stagedPath = input.sourcePath + ".r.mkv";
            result.durationMs = 5400000;
            result.width = 1920;
            result.height = 1080;
            return result;
        }
    }

    static class FakeEncode implements EncodeActivity {
        int calls = 0;

        @Override
        public EncodeResult encode(PipelineInput input, PreprocessResult pre) {
            calls++;
            EncodeResult result = new EncodeResult();
            result.encodedPath = pre.stagedPath + ".m4v";
            result.encodeDurationMs = 3600000;
            result.machineName = "testbox";
            return result;
        }
    }

    static class FakePublish implements PublishActivity {
        int calls = 0;
        String lastEncodedPath;

        @Override
        public void publish(PipelineInput input, EncodeResult enc) {
            calls++;
            lastEncodedPath = enc.encodedPath;
        }
    }

    // ---- helpers -------------------------------------------------------

    private Worker register(FakePreprocess pre, FakeEncode enc, FakePublish pub) {
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(VideoPipelineWorkflowImpl.class);
        worker.registerActivitiesImplementations(pre, enc, pub);
        env.start();
        return worker;
    }

    private PipelineInput input() {
        return new PipelineInput("/inbox/movie.mkv", "/library/movie.m4v", 0);
    }

    // ---- tests ---------------------------------------------------------

    @Test
    public void testHappyPath() {
        FakePreprocess pre = new FakePreprocess();
        FakeEncode enc = new FakeEncode();
        FakePublish pub = new FakePublish();
        register(pre, enc, pub);

        VideoPipelineWorkflow stub = env.getWorkflowClient().newWorkflowStub(
                VideoPipelineWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());

        // synchronous invocation: starts the workflow and blocks until it completes
        PipelineResult result = stub.run(input());

        Assert.assertEquals("/library/movie.m4v", result.finalPath);
        Assert.assertEquals(5400000, result.durationMs);
        Assert.assertEquals(1920, result.width);
        Assert.assertEquals(1080, result.height);
        Assert.assertEquals(3600000, result.encodeDurationMs);

        Assert.assertEquals(1, pre.calls);
        Assert.assertEquals(1, enc.calls);
        Assert.assertEquals(1, pub.calls);
        Assert.assertEquals("/inbox/movie.mkv.r.mkv.m4v", pub.lastEncodedPath);
    }

    @Test
    public void testTransientStageFailureIsRetried() {
        FakePreprocess pre = new FakePreprocess();
        pre.failuresBeforeSuccess = 1; // first attempt fails, second succeeds
        FakeEncode enc = new FakeEncode();
        FakePublish pub = new FakePublish();
        register(pre, enc, pub);

        VideoPipelineWorkflow stub = env.getWorkflowClient().newWorkflowStub(
                VideoPipelineWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());

        PipelineResult result = stub.run(input());

        Assert.assertEquals("/library/movie.m4v", result.finalPath);
        Assert.assertEquals(2, pre.calls); // the retry happened automatically
        Assert.assertEquals(1, enc.calls);
        Assert.assertEquals(1, pub.calls);
    }

    @Test
    public void testPermanentStageFailureDeadLettersWorkflow() {
        FakePreprocess pre = new FakePreprocess();
        pre.alwaysFail = true;
        FakeEncode enc = new FakeEncode();
        FakePublish pub = new FakePublish();
        register(pre, enc, pub);

        VideoPipelineWorkflow stub = env.getWorkflowClient().newWorkflowStub(
                VideoPipelineWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());

        try {
            stub.run(input());
            Assert.fail("expected WorkflowFailedException");
        }
        catch (WorkflowFailedException expected) {
            // stage retry policy allowed 3 attempts, then the workflow failed
            Assert.assertEquals(3, pre.calls);
            // encode/publish never ran: the pipeline stops at the failed stage
            Assert.assertEquals(0, enc.calls);
            Assert.assertEquals(0, pub.calls);
        }
    }
}
