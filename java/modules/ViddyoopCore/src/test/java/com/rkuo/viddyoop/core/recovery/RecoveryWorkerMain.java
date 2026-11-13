package com.rkuo.viddyoop.core.recovery;

import com.rkuo.viddyoop.core.activities.*;
import com.rkuo.viddyoop.core.catalog.*;
import com.rkuo.viddyoop.core.media.*;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.*;
import io.temporal.common.RetryOptions;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Separate JVM using real activities. The optional last argument retains production timeout policies. */
public final class RecoveryWorkerMain {
    public static class FastRecoveryWorkflow extends VideoPipelineWorkflowImpl {
        private ActivityOptions fast(ActivityOptions options) {
            return options.toBuilder().setHeartbeatTimeout(Duration.ofSeconds(2))
                    .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumAttempts(3).build()).build();
        }
        @Override protected ActivityOptions encodeOptions(PipelineInput input) { return fast(super.encodeOptions(input)); }
        @Override protected ActivityOptions publishOptions(PipelineInput input) { return fast(super.publishOptions(input)); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        var catalog = new ArtifactCatalog(root.resolve("catalog.db"));
        var publisher = args[6].equals("publish") ? new ArtifactPublisher(catalog, root.resolve("library"), id -> {
            Files.writeString(root.resolve("boundary"), id);
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
        }) : new ArtifactPublisher(catalog, root.resolve("library"));
        var activities = new NativeMediaActivities(root.resolve("work"), new HandBrakeAdapter(args[2], new MediaProcessRunner()),
                publisher, new MediaNormalizer(args[3], args[4], args[5], new MediaProcessRunner()),
                new SourceCleanup(catalog, root.resolve("inbox")));
        var service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(args[0]).build());
        var factory = WorkerFactory.newInstance(WorkflowClient.newInstance(service));
        boolean productionTimeouts = args.length > 7 && Boolean.parseBoolean(args[7]);
        factory.newWorker("recovery").registerWorkflowImplementationTypes(
                productionTimeouts ? VideoPipelineWorkflowImpl.class : FastRecoveryWorkflow.class);
        factory.newWorker("recovery-preprocess").registerActivitiesImplementations(new PreprocessActivity() {
            public PreprocessResult preprocess(PipelineInput input) { return activities.preprocess(input); }
        });
        factory.newWorker("recovery-encode").registerActivitiesImplementations(new EncodeActivity() {
            public EncodeResult encode(PipelineInput input, PreprocessResult pre) { return activities.encode(input, pre); }
        });
        factory.newWorker("recovery-publish").registerActivitiesImplementations(new PublishActivity() {
            public void publish(PipelineInput input, EncodeResult enc) { activities.publish(input, enc); }
        });
        factory.start();
        new CountDownLatch(1).await();
    }
}
