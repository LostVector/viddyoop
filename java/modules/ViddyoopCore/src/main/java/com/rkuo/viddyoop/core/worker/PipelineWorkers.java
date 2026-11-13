package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.activities.*;
import com.rkuo.viddyoop.core.pipeline.*;
import io.temporal.worker.*;

/** Each expensive stage has its own activity pool, independent of workflow polling. */
public final class PipelineWorkers {
    private PipelineWorkers() {}
    public static void register(WorkerFactory factory, String queue, NativeMediaActivities activities,
                                int preprocessWorkers, int encodeWorkers, int publishWorkers) {
        if (preprocessWorkers < 1 || encodeWorkers < 1 || publishWorkers < 1) {
            throw new IllegalArgumentException("Stage worker counts must be positive");
        }
        factory.newWorker(queue).registerWorkflowImplementationTypes(VideoPipelineWorkflowImpl.class);
        factory.newWorker(queue + "-preprocess", options(preprocessWorkers)).registerActivitiesImplementations(
                new PreprocessActivity() {
                    public PreprocessResult preprocess(PipelineInput input) { return activities.preprocess(input); }
                });
        factory.newWorker(queue + "-encode", options(encodeWorkers)).registerActivitiesImplementations(
                new EncodeActivity() {
                    public EncodeResult encode(PipelineInput input, PreprocessResult pre) { return activities.encode(input, pre); }
                });
        factory.newWorker(queue + "-publish", options(publishWorkers)).registerActivitiesImplementations(
                new PublishActivity() {
                    public void publish(PipelineInput input, EncodeResult enc) { activities.publish(input, enc); }
                });
    }
    private static WorkerOptions options(int concurrency) {
        return WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(concurrency).build();
    }
}
