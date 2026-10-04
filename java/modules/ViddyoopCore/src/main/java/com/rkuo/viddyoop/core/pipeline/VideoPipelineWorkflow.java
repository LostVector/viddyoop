package com.rkuo.viddyoop.core.pipeline;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One video through the whole pipeline. Durable: state survives worker
 * restarts, stage failures retry per policy, and the full event history is
 * visible in the Temporal UI.
 */
@WorkflowInterface
public interface VideoPipelineWorkflow {
    @WorkflowMethod
    PipelineResult run(PipelineInput input);
}
