package com.rkuo.viddyoop.core.ingest;

import com.rkuo.viddyoop.core.catalog.SubmissionCatalog;
import com.rkuo.viddyoop.core.pipeline.PipelineInput;
import java.sql.SQLException;

/** Reconciles the non-atomic catalog/Temporal boundary on startup and each ingest poll. */
public final class SubmissionDispatcher {
    @FunctionalInterface public interface Admission {
        boolean admit(PipelineInput input) throws SQLException, java.io.IOException;
    }
    private final Admission admission;
    private final SubmissionCatalog catalog;
    private final PipelineWorkflowStarter starter;

    public SubmissionDispatcher(SubmissionCatalog catalog, PipelineWorkflowStarter starter) {
        this(catalog, starter, input -> true);
    }

    public SubmissionDispatcher(SubmissionCatalog catalog, PipelineWorkflowStarter starter, Admission admission) {
        this.admission = admission;
        this.catalog = catalog;
        this.starter = starter;
    }

    public void dispatchPending() throws SQLException, java.io.IOException {
        for (PipelineInput input : catalog.pendingStarts()) {
            if (!admission.admit(input)) continue;
            // Both started and already-existing executions acknowledge this registration.
            // Any transport failure leaves it pending for reconciliation on the next poll.
            starter.start(input);
            catalog.acknowledgeStart(input.submissionId);
        }
    }
}
