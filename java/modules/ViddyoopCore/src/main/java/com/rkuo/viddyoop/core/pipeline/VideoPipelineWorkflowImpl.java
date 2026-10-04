package com.rkuo.viddyoop.core.pipeline;

import com.rkuo.viddyoop.core.activities.EncodeActivity;
import com.rkuo.viddyoop.core.activities.PreprocessActivity;
import com.rkuo.viddyoop.core.activities.PublishActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Orchestrates one video: preprocess -> encode -> publish.
 *
 * Workflow code must stay deterministic: all side effects (process
 * execution, file moves) live in the activities. Retry/backoff/lease
 * semantics are declared here and enforced by the Temporal server — this
 * replaces the hand-rolled queue, backoff, and stale-lease handling that a
 * custom job store would need.
 */
public class VideoPipelineWorkflowImpl implements VideoPipelineWorkflow {

    private static final RetryOptions STAGE_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofSeconds(10))
            .setBackoffCoefficient(2.0)
            .setMaximumInterval(Duration.ofMinutes(10))
            .setMaximumAttempts(3)
            .build();

    // Encodes can take hours. The generous start-to-close timeout plus the
    // heartbeat timeout means a dead worker is detected in ~10 minutes while
    // a healthy long encode is never killed (this replaces the old "ping YARN
    // every 10 minutes or the container dies" hack).
    private static final RetryOptions ENCODE_RETRY = RetryOptions.newBuilder()
            .setInitialInterval(Duration.ofMinutes(1))
            .setBackoffCoefficient(2.0)
            .setMaximumAttempts(2)
            .build();

    @Override
    public PipelineResult run(PipelineInput input) {
        PreprocessActivity preprocess = Workflow.newActivityStub(PreprocessActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(30))
                        .setRetryOptions(STAGE_RETRY)
                        .build());

        EncodeActivity encode = Workflow.newActivityStub(EncodeActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofHours(24))
                        .setHeartbeatTimeout(Duration.ofMinutes(10))
                        .setRetryOptions(ENCODE_RETRY)
                        .build());

        PublishActivity publish = Workflow.newActivityStub(PublishActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(10))
                        .setRetryOptions(STAGE_RETRY)
                        .build());

        PreprocessResult pre = preprocess.preprocess(input);
        EncodeResult enc = encode.encode(input, pre);
        publish.publish(input, enc);

        PipelineResult result = new PipelineResult();
        result.finalPath = input.finalPath;
        result.durationMs = pre.durationMs;
        result.width = pre.width;
        result.height = pre.height;
        result.encodeDurationMs = enc.encodeDurationMs;
        return result;
    }
}
