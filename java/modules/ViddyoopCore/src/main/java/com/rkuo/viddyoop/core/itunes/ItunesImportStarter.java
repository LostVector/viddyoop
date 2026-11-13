package com.rkuo.viddyoop.core.itunes;

import io.temporal.client.*;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;

public final class ItunesImportStarter {
    private final WorkflowClient client;
    private final String queue;
    public ItunesImportStarter(WorkflowClient client, String queue) { this.client = client; this.queue = queue; }
    public boolean start(String id, int checkSeconds) {
        java.util.UUID.fromString(id);
        if (checkSeconds < 1) throw new IllegalArgumentException("Observation interval must be positive");
        var stub = client.newWorkflowStub(ItunesImportWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(queue)
                .setWorkflowId("viddyoop-itunes-import-" + id)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
        try { WorkflowClient.start(stub::run, id, checkSeconds); return true; }
        catch (WorkflowExecutionAlreadyStarted e) { return false; }
    }
}
