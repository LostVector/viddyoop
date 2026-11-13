package com.rkuo.viddyoop.core.itunes;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Durable observation timers; ambiguous external outcomes never cause a second transfer. */
public final class ItunesImportWorkflowImpl implements ItunesImportWorkflow {
    public ItunesDeliveryCatalog.Delivery run(String id, int checkSeconds) {
        if (checkSeconds < 1) throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure("Observation interval must be positive", "INVALID_CONFIG");
        var activity = Workflow.newActivityStub(ItunesImportActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofHours(2)).setHeartbeatTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(10)).setMaximumAttempts(3).build()).build());
        for (int observations=0; observations<100; observations++) {
            var result = activity.process(id);
            if (result.outcome() == ItunesDeliveryCatalog.Outcome.CONFIRMED || result.outcome() == ItunesDeliveryCatalog.Outcome.REJECTED) return result;
            Workflow.sleep(Duration.ofSeconds(checkSeconds));
        }
        Workflow.continueAsNew(id, checkSeconds);
        throw new IllegalStateException("continueAsNew returned unexpectedly");
    }
}
