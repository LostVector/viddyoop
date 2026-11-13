package com.rkuo.viddyoop.core.status;

import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import io.temporal.client.*;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkflowStatus {
    public record Execution(String state, String stage, String failure, String runId) {
        public Execution(String state, String stage, String failure) { this(state, stage, failure, ""); }
    }
    private final WorkflowClient client;
    public WorkflowStatus(WorkflowClient client) { this.client = client; }

    public Execution inspect(StatusCatalog.Submission submission) {
        try {
            var stub = client.newUntypedWorkflowStub(PipelineWorkflowStarter.workflowIdFor(submission.id()));
            var description = stub.describe();
            var state = description.getStatus();
            String stage = "";
            String failure = "";
            var pending = description.getRawDescription().getPendingActivitiesList();
            if (!pending.isEmpty()) {
                var activity = pending.get(0);
                stage = activity.getActivityType().getName();
                if (activity.hasLastFailure()) failure = failureMessages(activity.getLastFailure());
            }
            if (state == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED) {
                try { stub.getResult(1, TimeUnit.SECONDS, Object.class); }
                catch (WorkflowFailedException e) { failure = messages(e); }
                catch (TimeoutException e) { failure = "Closed workflow result unavailable"; }
            }
            return new Execution(state.name().replace("WORKFLOW_EXECUTION_STATUS_", ""), stage, failure,
                    description.getRawDescription().getWorkflowExecutionInfo().getExecution().getRunId());
        } catch (WorkflowNotFoundException e) {
            return new Execution(submission.startAcknowledged() ? "NOT_FOUND" : "NOT_STARTED", "",
                    submission.startAcknowledged() ? "Execution missing or outside Temporal history retention" : "");
        } catch (RuntimeException e) {
            return new Execution("UNAVAILABLE", "", messages(e));
        }
    }

    private static String failureMessages(io.temporal.api.failure.v1.Failure failure) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; depth < 8; depth++) {
            if (!result.isEmpty()) result.append("; ");
            result.append(failure.getMessage());
            if (!failure.hasCause()) break;
            failure = failure.getCause();
        }
        return result.toString();
    }

    private static String messages(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (!result.isEmpty()) result.append("; ");
            result.append(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return result.toString();
    }
}
