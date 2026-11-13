package com.rkuo.viddyoop.core.itunes;

import io.temporal.workflow.*;

@WorkflowInterface
public interface ItunesImportWorkflow {
    @WorkflowMethod ItunesDeliveryCatalog.Delivery run(String submissionId, int checkSeconds);
}
