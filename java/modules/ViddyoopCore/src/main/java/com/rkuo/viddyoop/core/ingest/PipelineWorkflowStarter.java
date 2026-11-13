package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import com.rkuo.viddyoop.core.pipeline.VideoPipelineWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

/**
 * Starts one workflow per durable submission. A repeated start cannot restart
 * a completed or failed submission. Re-encodes use a new catalog identity;
 * an operator retry resets the existing failed execution at its failed activity.
 */
public class PipelineWorkflowStarter {

    private final WorkflowClient client;
    private final String taskQueue;

    public PipelineWorkflowStarter(WorkflowClient client, String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    public static String workflowIdFor(String submissionId) {
        if (submissionId == null || submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId is required");
        }
        return "viddyoop-" + submissionId;
    }

    /**
     * @return true if a workflow was started, false if one already exists
     */
    public boolean start(PipelineInput input) {
        input.activityTaskQueuePrefix = taskQueue;
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId(workflowIdFor(input.submissionId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();

        try {
            VideoPipelineWorkflow stub = client.newWorkflowStub(VideoPipelineWorkflow.class, options);
            WorkflowClient.start(stub::run, input);
            return true;
        }
        catch (WorkflowExecutionAlreadyStarted ex) {
            return false;
        }
    }
}
