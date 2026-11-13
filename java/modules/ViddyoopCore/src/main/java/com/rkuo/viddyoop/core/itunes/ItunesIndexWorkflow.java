package com.rkuo.viddyoop.core.itunes;

import io.temporal.workflow.*;

@WorkflowInterface
public interface ItunesIndexWorkflow {
    @WorkflowMethod ItunesIndexer.Result run();
}
