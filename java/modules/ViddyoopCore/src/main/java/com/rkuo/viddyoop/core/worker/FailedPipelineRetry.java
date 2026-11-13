package com.rkuo.viddyoop.core.worker;

import com.rkuo.viddyoop.core.ingest.PipelineWorkflowStarter;
import com.rkuo.viddyoop.core.media.MediaFiles;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import com.rkuo.viddyoop.core.status.StatusCatalog;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest;
import io.temporal.client.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Resets the task which scheduled the failed activity, retaining completed upstream results. */
public final class FailedPipelineRetry {
    public record Plan(String workflowId, String failedRunId, String activity, long scheduledEventId,
                       long workflowTaskEventId, boolean artifactCommitted) {}
    public record Result(Plan plan, String newRunId) {}
    private final WorkflowClient client;
    public FailedPipelineRetry(WorkflowClient client) { this.client = client; }

    public Result retry(StatusCatalog.Submission submission, String failedRunId, String reason, boolean dryRun) throws Exception {
        UUID.fromString(failedRunId);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A repair reason is required");
        String workflowId = PipelineWorkflowStarter.workflowIdFor(submission.id());
        requireFailedRun(client.newUntypedWorkflowStub(workflowId).describe(), failedRunId);
        var history = client.fetchHistory(workflowId, failedRunId).getHistory().getEventsList();
        if (history.isEmpty()) throw new IllegalArgumentException("Workflow history is unavailable");
        var start = history.get(0).getWorkflowExecutionStartedEventAttributes();
        if (!start.getWorkflowType().getName().equals("VideoPipelineWorkflow")) {
            throw new IllegalArgumentException("Execution is not a video pipeline workflow");
        }
        PipelineInput input = client.getOptions().getDataConverter().fromPayloads(0, Optional.of(start.getInput()),
                PipelineInput.class, PipelineInput.class);
        if (!submission.id().equals(input.submissionId) || !submission.destination().equals(input.finalPath)
                || !submission.source().equals(input.sourcePath) || !submission.profile().equals(input.encodeProfile)) {
            throw new IllegalArgumentException("Workflow input does not match the catalog submission");
        }
        HistoryEvent last = history.get(history.size() - 1);
        if (!last.hasWorkflowExecutionFailedEventAttributes()) throw new IllegalArgumentException("History does not end in failure");
        var failure = last.getWorkflowExecutionFailedEventAttributes().getFailure();
        long scheduled = 0;
        for (int depth = 0; depth < 32; depth++) {
            if (failure.hasActivityFailureInfo()) { scheduled = failure.getActivityFailureInfo().getScheduledEventId(); break; }
            if (!failure.hasCause()) break;
            failure = failure.getCause();
        }
        if (scheduled == 0) throw new IllegalArgumentException("Failure is not attributable to a scheduled activity");
        Map<Long, HistoryEvent> events = new HashMap<>();
        for (var event : history) events.put(event.getEventId(), event);
        var event = events.get(scheduled);
        if (event == null || !event.hasActivityTaskScheduledEventAttributes()) throw new IllegalArgumentException("Failed activity schedule is missing");
        var activity = event.getActivityTaskScheduledEventAttributes();
        String stage = activity.getActivityType().getName();
        if (!Set.of("Preprocess", "Encode", "Publish").contains(stage)) throw new IllegalArgumentException("Unknown pipeline activity: " + stage);
        // This workflow emits one activity command per task. Require the actual adjacent
        // completion event: accepting an older task could repeat a completed upstream stage.
        long boundary = scheduled - 1;
        var task = events.get(boundary);
        if (task == null || !task.hasWorkflowTaskCompletedEventAttributes() || boundary >= scheduled) {
            throw new IllegalArgumentException("Failed activity has no valid reset boundary: scheduled=" + scheduled
                    + ", boundary=" + boundary + ", event=" + (task == null ? "missing" : task.getEventType()));
        }
        boolean committed = submission.artifactState().equals("committed");
        if (committed) {
            if (!stage.equals("Publish")) throw new IllegalArgumentException("Refusing to re-encode a committed artifact");
            if (!Objects.equals(submission.checksum(), MediaFiles.checksum(Path.of(submission.destination()), () -> {}))) {
                throw new IllegalArgumentException("Committed library artifact is missing or changed; repair it before retrying cleanup");
            }
        }
        var plan = new Plan(workflowId, failedRunId, stage, scheduled, boundary, committed);
        if (dryRun) return new Result(plan, null);
        // Reset APIs can terminate a newer current run. Recheck under the CLI's catalog lock.
        // External Temporal operators must not reset the same execution concurrently.
        requireFailedRun(client.newUntypedWorkflowStub(workflowId).describe(), failedRunId);
        String requestId = UUID.nameUUIDFromBytes(("viddyoop-retry:" + workflowId + ":" + failedRunId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        var request = ResetWorkflowExecutionRequest.newBuilder().setNamespace(client.getOptions().getNamespace())
                .setWorkflowExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(failedRunId))
                .setWorkflowTaskFinishEventId(boundary).setRequestId(requestId).setReason(reason)
                .setIdentity(client.getOptions().getIdentity())
                .addResetReapplyExcludeTypes(ResetReapplyExcludeType.RESET_REAPPLY_EXCLUDE_TYPE_SIGNAL)
                .addResetReapplyExcludeTypes(ResetReapplyExcludeType.RESET_REAPPLY_EXCLUDE_TYPE_UPDATE)
                .addResetReapplyExcludeTypes(ResetReapplyExcludeType.RESET_REAPPLY_EXCLUDE_TYPE_NEXUS)
                .addResetReapplyExcludeTypes(ResetReapplyExcludeType.RESET_REAPPLY_EXCLUDE_TYPE_CANCEL_REQUEST).build();
        String run = client.getWorkflowServiceStubs().blockingStub().withDeadlineAfter(10, TimeUnit.SECONDS)
                .resetWorkflowExecution(request).getRunId();
        return new Result(plan, run);
    }

    private static void requireFailedRun(WorkflowExecutionDescription description, String expectedRun) {
        var info = description.getRawDescription().getWorkflowExecutionInfo();
        if (!info.getExecution().getRunId().equals(expectedRun)) throw new IllegalArgumentException("Current run changed; inspect status before retrying");
        if (info.getStatus() != WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED) {
            throw new IllegalArgumentException("Only a failed workflow run can be retried");
        }
    }
}
