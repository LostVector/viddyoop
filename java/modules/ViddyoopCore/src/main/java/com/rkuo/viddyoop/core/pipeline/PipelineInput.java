package com.rkuo.viddyoop.core.pipeline;

/**
 * Input to a VideoPipelineWorkflow. Plain public fields are serialized to
 * JSON by the Temporal SDK across activity boundaries.
 */
public class PipelineInput {
    /** Durable catalog identity; independent of pathname and Temporal history. */
    public String submissionId;
    public String sourceFingerprint;
    public String sourceGeneration;
    /** Snapshot owned by ingest; preprocessing must never modify it in place. */
    public String stagedSourcePath;
    public String encodeProfile;
    public String archivePolicy;
    public String archiveRoot;
    /** Null preserves the skeleton single-queue execution for existing histories. */
    public String activityTaskQueuePrefix;

    /** Original inbox path for display and guarded cleanup; process stagedSourcePath. */
    public String sourcePath;
    /** Desired final library path for the finished encode. */
    public String finalPath;
    /**
     * Reserved priority metadata. Submission priority is deferred until pinned
     * server/SDK ordering semantics have been verified.
     */
    public int precedence;

    public PipelineInput() {
    }

    public PipelineInput(String sourcePath, String finalPath, int precedence) {
        this.sourcePath = sourcePath;
        this.finalPath = finalPath;
        this.precedence = precedence;
    }
}
