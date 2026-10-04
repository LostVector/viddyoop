package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import com.rkuo.viddyoop.core.pipeline.VideoPipelineWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.Priority;

/**
 * Starts one VideoPipelineWorkflow per ingested file.
 *
 * The workflow id is derived from the source path, which makes ingest
 * idempotent: re-scanning the same file cannot start a second concurrent
 * pipeline (the duplicate start is swallowed). This replaces the old
 * "watched directory + rename" dedup.
 */
public class PipelineWorkflowStarter {

    private final WorkflowClient client;
    private final String taskQueue;

    public PipelineWorkflowStarter(WorkflowClient client, String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    public static String workflowIdFor(String sourcePath) {
        return "viddyoop-" + sourcePath;
    }

    /**
     * @return true if a workflow was started, false if one already exists
     */
    public boolean start(PipelineInput input) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId(workflowIdFor(input.sourcePath))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                .setPriority(Priority.newBuilder().setPriorityKey(input.precedence).build())
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
