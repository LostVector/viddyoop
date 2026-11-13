package com.rkuo.viddyoop.core.itunes;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public final class ItunesIndexWorkflowImpl implements ItunesIndexWorkflow {
    public ItunesIndexer.Result run() {
        return Workflow.newActivityStub(ItunesIndexActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(15)).setHeartbeatTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(10))
                        .setMaximumAttempts(3).build()).build()).index();
    }
}
